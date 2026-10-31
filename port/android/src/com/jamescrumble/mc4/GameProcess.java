package com.jamescrumble.mc4;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Owns everything outside the UI: unpacking the sysroot, linking the player's
 * game files, running qemu-arm and talking to the port through the control
 * directory (commands in, frame.bin and status.json out, audio over a FIFO).
 */
final class GameProcess {
    interface Listener {
        void onStatus(String text);
        void onExit(int code);
    }

    private static final String NATIVE_LIB = "lib/armeabi-v7a/libModernCombat4.so";
    private static final String GAME_PACKAGE = "com.gameloft.android.ANMP.GloftM4HM";
    private static final String MAIN_OBB = "main.1100." + GAME_PACKAGE + ".obb";
    private static final int SAMPLE_RATE = 44100;

    private static final class Cmd {
        String line;
        final String key;
        Cmd(String line, String key) { this.line = line; this.key = key; }
    }

    /*
     * Presets. Each pair is {KEY, VALUE} applied by withPresets() only when the user
     * has not set that key manually in settings.txt. Every value here maps to a knob
     * that either GameProcess, GlBridge, PerfHint or gl_bridge.cpp actually reads
     * (see gl_bridge.cpp: port_getenv("GL_INFLIGHT"), port_getenv_long("READPIXELS_EVERY"),
     *  port_getenv_long("GL_PIPE_KB"); GlBridge.setFrameLimit; PerfHint.start).
     * NONE of these change the engine's quality profile (that lives inside
     * libModernCombat4.so and is chosen by GL_RENDERER, exposed separately in the UI).
     */
    private static final String[][] PRESET_SHARP = {
        { "RENDER_HEIGHT", "1440" },
    };
    private static final String[][] PRESET_SMOOTH = {
        { "MC4_GL_INFLIGHT",      "3" },
        { "MC4_GL_PIPE_KB",       "2048" },
        { "PERF_HINT",            "1" },
    };
    private static final String[][] PRESET_EXTREME = {
        { "RENDER_HEIGHT",        "1440" },
        { "MC4_GL_INFLIGHT",      "3" },
        { "MC4_GL_PIPE_KB",       "2048" },
        { "PERF_HINT",            "1" },
        { "MC4_READPIXELS_EVERY", "1" },
        { "FPS_LIMIT",            "60" },
    };

    private static void applyDefaults(Map<String, String> m, String[][] defaults) {
        for (String[] kv : defaults) if (!m.containsKey(kv[0])) m.put(kv[0], kv[1]);
    }

    /**
     * Returns a copy of the raw settings with the active presets filled in. Preset
     * values never override a key that settings.txt already contains. The three
     * presets are cumulative (Extremo = Nítido + Fluido + readpixels/60fps) and
     * only apply their own defaults.
     */
    private static Map<String, String> withPresets(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>(raw);
        if ("1".equals(out.get("MC4_PRESET_SHARP")))   applyDefaults(out, PRESET_SHARP);
        if ("1".equals(out.get("MC4_PRESET_SMOOTH")))  applyDefaults(out, PRESET_SMOOTH);
        if ("1".equals(out.get("MC4_PRESET_EXTREME"))) applyDefaults(out, PRESET_EXTREME);
        return out;
    }

    /** Keys that only exist to talk to the wrapper, never to the engine. */
    private static boolean isWrapperKey(String k) {
        switch (k) {
            case "RENDER_HEIGHT":
            case "CPU_PRIME":
            case "AUDIO_LATENCY_MS":
            case "PERF_HINT":
            case "MC4_PRESET_SHARP":
            case "MC4_PRESET_SMOOTH":
            case "MC4_PRESET_EXTREME":
            case "MC4_POST_PROCESS":
            case "MC4_POST_STRENGTH":
            case "MC4_COLOR_PROFILE":
                return true;
            default:
                return false;
        }
    }

    private final Context ctx;
    private volatile Listener listener;
    private final boolean touchMode;
    private final File root;
    private final File control;
    private final File commandsFile;
    private final File statusFile;
    private final File fifo;
    private volatile File logFile;
    private final LinkedList<Cmd> queue = new LinkedList<>();
    private volatile boolean running;
    private volatile boolean muted = true;
    private Process process;
    private final GlBridge gl;
    private final PerfHint perf;
    private int renderW = 1280, renderH = 590;
    private String saveFolder = "saves";

    GameProcess(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        root = ctx.getFilesDir();
        control = new File(root, "ctl");
        commandsFile = new File(control, "commands");
        statusFile = new File(control, "status.json");
        fifo = new File(root, "audio.pcm");
        logFile = new File(Storage.mainDir(ctx), "mc4.log");
        touchMode = !new File(logFile.getParentFile(), "pad_overlay.flag").exists();
        perf = new PerfHint(ctx, msg -> appendLog("[perf] " + msg));
        gl = new GlBridge(new File(root, "gl_cmd.pipe").getPath(), new File(root, "gl_reply.pipe").getPath(),
                msg -> appendLog("[gl] " + msg), perf::frame, perf::cpuLine);
        chooseRenderSize();
    }

    /**
     * The engine renders at whatever size it is told, so use the phone's own aspect ratio and let the
     * compositor scale the surface to the panel. resolution.txt ("1280x590") next to the log overrides it.
     * Presets that set RENDER_HEIGHT are honoured here, so the constructor already picks the preset size.
     */
    private void chooseRenderSize() {
        android.util.DisplayMetrics m = ctx.getResources().getDisplayMetrics();
        int longSide = Math.max(m.widthPixels, m.heightPixels), shortSide = Math.min(m.widthPixels, m.heightPixels);
        int h = 720;
        Map<String, String> s = withPresets(SettingsActivity.load(ctx));
        try { h = Integer.parseInt(s.getOrDefault("RENDER_HEIGHT", "720")); } catch (NumberFormatException ignored) { }
        h = Math.max(240, Math.min(h, 2160)) & ~1;
        int w = Math.round(h * (float) longSide / shortSide) & ~1;
        String over = readText(Storage.find(ctx, "resolution.txt")).trim();
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("(\\d+)x(\\d+)").matcher(over);
        if (mt.matches()) {
            w = Integer.parseInt(mt.group(1));
            h = Integer.parseInt(mt.group(2));
        }
        renderW = Math.max(320, Math.min(w, 4096));
        renderH = Math.max(240, Math.min(h, 4096));
    }

    int renderWidth() { return renderW; }
    int renderHeight() { return renderH; }
    GlBridge gl() { return gl; }
    File logFile() { return logFile; }
    boolean isRunning() { return running; }
    boolean touchMode() { return touchMode; }
    void setListener(Listener l) { listener = l; }
    void setMuted(boolean m) { muted = m; }

    void start() throws Exception {
        stopStale();
        int moved = Storage.migrate(ctx, listener::onStatus);
        logFile = new File(Storage.mainDir(ctx), logFile.getName());
        if (moved > 0) appendLog("[wrapper] moved " + moved + " files to " + Storage.mainDir(ctx));
        prepare();
        launch();
    }

    /** Returns {frame, menu}, or null before the port has written a status. */
    int[] readStatus() {
        String s = readText(statusFile);
        if (s.isEmpty()) return null;
        return new int[] { intField(s, "\"frame\":"), intField(s, "\"menu\":") };
    }

    void command(String line, String key) {
        synchronized (queue) {
            if (!running) return;
            if (key != null) {
                ListIterator<Cmd> it = queue.listIterator(queue.size());
                while (it.hasPrevious()) {
                    Cmd c = it.previous();
                    if (c.key == null) break;
                    if (c.key.equals(key)) { c.line = line; return; }
                }
            }
            queue.add(new Cmd(line, key));
            queue.notifyAll();
        }
    }

    void stop() {
        boolean wasRunning = running;
        running = false;
        gl.stop();
        synchronized (queue) { queue.notifyAll(); }
        if (!wasRunning) return;
        appendLine("quit");
        final Process p = process;
        if (p != null) {
            new Thread(() -> {
                try {
                    if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy();
                } catch (InterruptedException ignored) {
                    p.destroy();
                }
            }, "mc4-stop").start();
        }
    }

    private void stopStale() throws InterruptedException {
        if (!statusFile.exists() || System.currentTimeMillis() - statusFile.lastModified() > 5000) return;
        appendLine("quit");
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end && !readText(statusFile).contains("\"stopped\"")) {
            Thread.sleep(100);
        }
    }

    private void prepare() throws Exception {
        listener.onStatus("Preparando arquivos…");
        File bin = new File(root, "bin");
        File sysroot = new File(root, "sysroot");

        String stamp = String.valueOf(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime);
        File stampFile = new File(root, "install.stamp");
        if (!stamp.equals(readText(stampFile).trim())) {
            deleteTree(bin);
            deleteTree(sysroot);
            stampFile.delete();
        }

        File binary = new File(bin, "mc4");
        if (!binary.exists()) {
            bin.mkdirs();
            try (InputStream in = new BufferedInputStream(ctx.getAssets().open("mc4"))) {
                copy(in, new FileOutputStream(binary));
            }
        }

        File done = new File(sysroot, ".done");
        if (!done.exists()) {
            listener.onStatus("Extraindo bibliotecas do sistema…\nIsso ocorre apenas na primeira inicialização.");
            unzipAsset("sysroot.zip");
            done.createNewFile();
        }
        writeText(stampFile, stamp);
        binary.setExecutable(true, true);
        new File(sysroot, "lib/ld-linux-armhf.so.3").setExecutable(true, true);

        File game = new File(root, "game");
        File libDir = new File(game, "lib/armeabi-v7a");
        libDir.mkdirs();

        File external = Storage.mainDir(ctx);
        String where = Storage.describe(ctx);
        File targetSo = new File(libDir, "libModernCombat4.so");
        File looseSo = Storage.find(ctx, "libModernCombat4.so");
        if (looseSo == null || !looseSo.exists()) looseSo = Storage.find(ctx, "lib/libModernCombat4.so");
        if (looseSo == null || !looseSo.exists()) looseSo = Storage.find(ctx, "lib/armeabi-v7a/libModernCombat4.so");

        File apk = external == null ? null : Storage.firstWithSuffix(ctx, ".apk");

        if (looseSo != null && looseSo.exists()) {
            listener.onStatus("Carregando libModernCombat4.so solta…");
            Storage.copyFile(looseSo, targetSo);
        } else if (apk != null && apk.exists()) {
            listener.onStatus("Extraindo libModernCombat4.so do APK…");
            extractNative(apk, targetSo);
        } else if (!targetSo.exists() || targetSo.length() == 0) {
            throw new IOException("Nenhum arquivo 'libModernCombat4.so' ou APK original encontrado.\nColoque a .so ou o .apk do jogo na pasta:\n" + where);
        }

        if (apk != null && apk.exists()) {
            extractAssets(apk, new File(game, "assets"));
            relink(new File(game, "game.apk"), apk);
        } else {
            File dummyApk = new File(game, "game.apk");
            if (!dummyApk.exists()) dummyApk.createNewFile();
            new File(game, "assets").mkdirs();
        }
        new File(game, "files").mkdirs();

        File obb = external == null ? null : findObbAnywhere(ctx);
        if (obb == null && external != null) {
            File zip = Storage.firstWithSuffix(ctx, ".zip");
            if (zip != null) {
                extractCache(zip, new File(external, GAME_PACKAGE));
                obb = findObbAnywhere(ctx);
            }
        }
        if (obb == null) {
            File locked = external == null ? null : firstUnreadable(new File(external, GAME_PACKAGE));
            if (locked != null) {
                throw new IOException("O cache do jogo foi localizado, mas o aplicativo não tem permissão de leitura:\n" + locked.getPath()
                        + "\nConceda permissão de armazenamento nas configurações do Android: " + new File(external, GAME_PACKAGE).getPath()
                        + " -type d -exec chmod 2777 {} +\"");
            }
            new File(external, GAME_PACKAGE).mkdirs();
            throw new IOException("Cache do jogo não encontrado (arquivo " + MAIN_OBB + ").\nColoque o arquivo zip com o cache ou os arquivos .obb na pasta:\n" + where
                    + "\nOu coloque os dois arquivos .obb na pasta:\n" + external.getPath() + "/" + GAME_PACKAGE);
        }
        GameVersion version = GameVersion.detect(ctx);
        linkObb(new File(game, "obb"), new File(obb, MAIN_OBB), version.patch);
        saveFolder = version.saveFolder();
        appendLog("[wrapper] game data: " + obb.getPath() + ", version " + version.versionName
                + (version.patch != null ? ", patch " + version.patch.getName() : ", no patch")
                + (version.patchMatches() ? "" : " (not the one of this version)"));
    }

    private static void linkObb(File dir, File main, File patch) throws IOException {
        if (isLink(dir) || (dir.exists() && !dir.isDirectory())) dir.delete();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir " + dir);
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (isLink(f) && !f.getName().equals(main.getName()) && (patch == null || !f.getName().equals(patch.getName()))) f.delete();
            }
        }
        relink(new File(dir, main.getName()), main);
        if (patch != null) relink(new File(dir, patch.getName()), patch);
    }

    private static void relink(File link, File target) throws IOException {
        String now = "";
        try { now = Os.readlink(link.getPath()); } catch (ErrnoException ignored) { }
        if (now.equals(target.getPath())) return;
        deleteTree(link);
        link.delete();
        try {
            Os.symlink(target.getPath(), link.getPath());
        } catch (ErrnoException e) {
            throw new IOException("Falha ao vincular " + target.getName() + ": " + e.getMessage());
        }
    }

    private static File firstUnreadable(File dir) {
        if (!dir.isDirectory()) return null;
        File[] children = dir.listFiles();
        if (children == null) return dir;
        for (File c : children) {
            File f = firstUnreadable(c);
            if (f != null) return f;
        }
        return null;
    }

    static File findObbAnywhere(Context ctx) {
        for (File dir : Storage.sources(ctx)) {
            File f = findObb(dir);
            if (f != null) return f;
        }
        return null;
    }

    private static File findObb(File external) {
        File[] candidates = {
            new File(external, GAME_PACKAGE),
            external,
            new File(external, "obb"),
            new File(external, "obb/" + GAME_PACKAGE),
        };
        for (File c : candidates) if (new File(c, MAIN_OBB).isFile()) return c;
        return null;
    }

    private void launch() throws Exception {
        listener.onStatus("Iniciando o jogo…");
        File qemu = new File(ctx.getApplicationInfo().nativeLibraryDir, "libqemu.so");
        if (!qemu.exists()) throw new IOException("libqemu.so não encontrada em " + qemu.getParent());

        control.mkdirs();
        statusFile.delete();
        commandsFile.delete();
        commandsFile.createNewFile();
        fifo.delete();
        Os.mkfifo(fifo.getPath(), 0600);
        File glCmd = new File(root, "gl_cmd.pipe"), glReply = new File(root, "gl_reply.pipe");
        glCmd.delete();
        glReply.delete();
        Os.mkfifo(glCmd.getPath(), 0600);
        Os.mkfifo(glReply.getPath(), 0600);
        logFile.delete();
        gl.setRenderSize(renderW, renderH);
        gl.start();

        ProcessBuilder b = new ProcessBuilder(qemu.getPath());
        for (String arg : readText(new File(logFile.getParentFile(), "qemu_args.txt")).split("\n")) {
            if (!arg.trim().isEmpty()) b.command().add(arg.trim());
        }

        // Uma única leitura de settings, com os presets já aplicados, para todos os pontos abaixo.
        // Presets nunca sobrescrevem uma chave que o usuário já tenha salvo.
        Map<String, String> settings = withPresets(SettingsActivity.load(ctx));
        // Pós-processamento e Color Grading (Restauração dos tons e curvas de cor Gameloft 2012)
        String colorProfile = settings.getOrDefault("MC4_COLOR_PROFILE", "1");
        boolean ppEnabled = !"0".equals(colorProfile);
        float sat = 1.25f, con = 1.15f, gam = 0.90f, shp = 0.35f;
        if ("2".equals(colorProfile)) {
            // Cinematográfico (Contraste profundo)
            sat = 1.12f; con = 1.25f; gam = 0.88f; shp = 0.30f;
        } else if ("3".equals(colorProfile)) {
            // Suave / Balanceado
            sat = 1.10f; con = 1.08f; gam = 0.95f; shp = 0.20f;
        } else if ("1".equals(colorProfile)) {
            // Vívido (Gameloft 2012 Original: curvas S, realce de saturação e gamma)
            sat = 1.25f; con = 1.15f; gam = 0.90f; shp = 0.35f;
        }
        gl.setColorGrading(ppEnabled, sat, con, gam, shp);

        Map<String, String> env = b.environment();
        env.put("HOME", new File(root, "home").getPath());
        env.put("SDL_VIDEODRIVER", "dummy");
        env.put("SDL_AUDIODRIVER", "disk");
        env.put("SDL_DISKAUDIOFILE", fifo.getPath());
        env.put("MC4_AUDIO_FIFO", fifo.getPath());
        env.put("MC4_CONTROL_DIR", control.getPath());
        seedProfile(new File(root, "game"), saveFolder);
        File saveDir = new File(new File(root, "game"), saveFolder);
        saveDir.mkdirs();
        env.put("MC4_SAVEDIR", saveDir.getPath());
        appendLog("[wrapper] profile folder " + saveFolder);
        String customNick = settings.get("MC4_PLAYER_NAME");
        if (customNick != null && !customNick.trim().isEmpty()) {
            try {
                writeText(new File(saveDir, "player_profile.txt"), customNick.trim() + "\n");
            } catch (Exception e) {
                appendLog("[wrapper] aviso: falha ao salvar player_profile.txt: " + e.getMessage());
            }
        }
        env.put("MC4_GL_BRIDGE", "1");
        env.put("MC4_SCALE", "stretch");
        env.put("MC4_GL_CMD", glCmd.getPath());
        env.put("MC4_GL_REPLY", glReply.getPath());
        env.put("MC4_LANG", java.util.Locale.getDefault().getLanguage());
        env.put("MC4_WIDTH", String.valueOf(renderW));
        env.put("MC4_HEIGHT", String.valueOf(renderH));
        env.put("MC4_FB_PROBE_INTERVAL", "0");
        env.put("MC4_CURSOR", "0");
        if (touchMode) env.put("MC4_TOUCH", "1");

        // GPU spoof: gpu_name.txt (power user) < settings.txt (UI). The UI value wins.
        String gpu = readText(new File(logFile.getParentFile(), "gpu_name.txt")).trim();
        if (!gpu.isEmpty()) env.put("MC4_GL_RENDERER", gpu);

        File dir = logFile.getParentFile();
        for (String line : readText(new File(dir, "engine_env.txt")).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.trim().startsWith("#")) env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        // settings.txt wins over engine_env.txt; keys that only the wrapper reads are filtered out.
        for (Map.Entry<String, String> e : settings.entrySet())
            if (!isWrapperKey(e.getKey())) env.put(e.getKey(), e.getValue());

        b.command().add("-cpu");
        b.command().add("cortex-a15");
        b.command().add("-B");
        b.command().add("0x100000000");
        b.command().add("-L");
        b.command().add(new File(root, "sysroot").getPath());

        for (Map.Entry<String, String> entry : env.entrySet()) {
            b.command().add("-E");
            b.command().add(entry.getKey() + "=" + entry.getValue());
        }

        b.command().add(new File(root, "bin/mc4").getPath());
        b.command().add(new File(root, "game").getPath());

        if (!"1".equals(env.get("MC4_MONEY_INFINITE")) && env.containsKey("MC4_MONEY_RESTORE")) {
            SettingsActivity.consume(ctx, "MC4_MONEY_RESTORE");
            appendLog("[wrapper] money restore " + env.get("MC4_MONEY_RESTORE"));
        } else {
            env.remove("MC4_MONEY_RESTORE");
        }

        env.put("QEMU_SYSCALL_STATS", "1");
        env.put("QEMU_GUEST_PROF", "1");
        env.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
        File home = new File(root, "home");
        home.mkdirs();
        if (!new File(logFile.getParentFile(), "notrace.flag").exists()) env.put("LOADER_TRACE", "1");

        File diag = new File(logFile.getParentFile(), "diag_cmd.txt");
        if (diag.exists()) {
            env.put("QEMU", qemu.getPath());
            env.put("STRACE", new File(ctx.getApplicationInfo().nativeLibraryDir, "libmstrace.so").getPath());
            env.put("ROOT", root.getPath());
            b.command("/system/bin/sh", "-c", readText(diag));
        }

        String mask = readText(new File(logFile.getParentFile(), "cpu_mask.txt")).trim();
        if ("1".equals(settings.getOrDefault("CPU_PRIME", "1"))) mask = "c0";
        if (diag.exists()) mask = "";
        if (mask.matches("[0-9a-fA-F]{1,4}")) {
            b.command().add(0, "/system/bin/taskset");
            b.command().add(1, mask);
            appendLog("[wrapper] qemu pinned to cpu mask 0x" + mask);
        }

        b.redirectErrorStream(true);
        b.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

        running = true;
        final long startedAt = SystemClock.elapsedRealtime();
        appendLog("[wrapper] " + b.command());
        process = b.start();
        final Process p = process;

        int fpsLimit = 60;
        try { fpsLimit = Integer.parseInt(settings.getOrDefault("FPS_LIMIT", "60")); } catch (NumberFormatException ignored) { }
        gl.setFrameLimit(fpsLimit);
        perf.start(p, "1".equals(settings.get("PERF_HINT")), fpsLimit);
                appendLog("[wrapper] preset sharp=" + settings.getOrDefault("MC4_PRESET_SHARP", "0")
                + " smooth=" + settings.getOrDefault("MC4_PRESET_SMOOTH", "0")
                + " extreme=" + settings.getOrDefault("MC4_PRESET_EXTREME", "0")
                + " | render=" + renderW + "x" + renderH
                + " inflight=" + env.getOrDefault("MC4_GL_INFLIGHT", "2")
                + " pipeKB=" + env.getOrDefault("MC4_GL_PIPE_KB", "1024")
                + " rpEvery=" + env.getOrDefault("MC4_READPIXELS_EVERY", "3")
                + " hudInset=" + env.getOrDefault("MC4_HUD_INSET", "0")
                + " gpu=" + env.getOrDefault("MC4_GL_RENDERER", "(real)")
                + " | pp=" + settings.getOrDefault("MC4_POST_PROCESS", "0")
                + " ppStrength=" + settings.getOrDefault("MC4_POST_STRENGTH", "10"));

        new Thread(this::writerLoop, "mc4-commands").start();
        new Thread(this::audioLoop, "mc4-audio").start();
        new Thread(() -> {
            int code = -1;
            try { code = p.waitFor(); } catch (InterruptedException ignored) { }
            appendLog("[wrapper] qemu exited with code " + code
                    + (code > 128 ? " (signal " + (code - 128) + ")" : "")
                    + " after " + (SystemClock.elapsedRealtime() - startedAt) + " ms");
            if (running) {
                running = false;
                listener.onExit(code);
            }
        }, "mc4-watch").start();
    }

    private void writerLoop() {
        StringBuilder batch = new StringBuilder();
        try (FileOutputStream out = new FileOutputStream(commandsFile, true)) {
            while (running) {
                batch.setLength(0);
                synchronized (queue) {
                    while (queue.isEmpty() && running) queue.wait();
                    if (!running) break;
                    for (Cmd c : queue) batch.append(c.line).append('\n');
                    queue.clear();
                }
                out.write(batch.toString().getBytes("US-ASCII"));
                Thread.sleep(4);
            }
        } catch (Exception ignored) {
        }
    }

    private void audioLoop() {
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int latencyMs = 0;
        try { latencyMs = Integer.parseInt(SettingsActivity.load(ctx).getOrDefault("AUDIO_LATENCY_MS", "0")); } catch (NumberFormatException ignored) { }
        int bufBytes = Math.max(min, (SAMPLE_RATE * 4 * Math.max(0, Math.min(latencyMs, 1000)) / 1000) & ~3);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(bufBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        FileDescriptor fd = null;
        try {
            fd = Os.open(fifo.getPath(), OsConstants.O_RDWR | OsConstants.O_NONBLOCK, 0);
            int pipe = -1;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try { pipe = Os.fcntlInt(fd, 1031, 4096); } catch (ErrnoException ignored) { }
            }
            appendLog("[audio] track buffer " + bufBytes + " bytes (" + bufBytes * 1000L / (SAMPLE_RATE * 4) + " ms, min " + min
                    + "), pipe " + pipe + " bytes");
            StructPollfd[] polls = { new StructPollfd() };
            polls[0].fd = fd;
            polls[0].events = (short) OsConstants.POLLIN;
            byte[] buf = new byte[16384];
            boolean playing = false;
            int underruns = 0;
            long nextReport = SystemClock.elapsedRealtime() + 10000;
            while (running) {
                polls[0].revents = 0;
                if (Os.poll(polls, 100) <= 0) continue;
                int n;
                try {
                    n = Os.read(fd, buf, 0, buf.length);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EAGAIN) continue;
                    break;
                } catch (InterruptedIOException e) {
                    break;
                }
                if (n <= 0) continue;
                if (muted) java.util.Arrays.fill(buf, 0, n, (byte) 0);
                if (!playing) { track.play(); playing = true; }
                track.write(buf, 0, n);
                long now = SystemClock.elapsedRealtime();
                if (now >= nextReport) {
                    nextReport = now + 10000;
                    int u = track.getUnderrunCount();
                    if (u != underruns) appendLog("[audio] speaker ran dry " + (u - underruns) + " time(s) in 10 s");
                    underruns = u;
                }
            }
        } catch (ErrnoException ignored) {
        } finally {
            if (fd != null) try { Os.close(fd); } catch (ErrnoException ignored) { }
            track.release();
        }
    }

    private void unzipAsset(String name) throws IOException {
        String rootPath = root.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(ctx.getAssets().open(name)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                File out = new File(root, e.getName());
                if (!out.getCanonicalPath().startsWith(rootPath)) throw new IOException("bad zip entry " + e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                copy(z, new FileOutputStream(out), false);
            }
        }
    }

    private void extractNative(File apk, File out) throws IOException {
        try (ZipFile z = new ZipFile(apk)) {
            ZipEntry e = z.getEntry(NATIVE_LIB);
            if (e == null) throw new IOException("O APK não possui " + NATIVE_LIB + ".\nNecessária versão 1.2.3e ou 1.2.1a.");
            if (out.exists() && out.length() == e.getSize() && crc32(out) == e.getCrc()) return;
            copy(z.getInputStream(e), new FileOutputStream(out));
        }
    }

    private static long crc32(File f) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) crc.update(buf, 0, n);
        }
        return crc.getValue();
    }

    void log(String line) { appendLog("[app] " + line); }

    private void appendLog(String line) {
        try (FileOutputStream out = new FileOutputStream(logFile, true)) {
            out.write((line + "\n").getBytes("UTF-8"));
        } catch (IOException ignored) {
        }
    }

    private void appendLine(String line) {
        try (FileOutputStream out = new FileOutputStream(commandsFile, true)) {
            out.write((line + "\n").getBytes("US-ASCII"));
        } catch (IOException ignored) {
        }
    }

    private void extractCache(File zip, File dir) throws IOException {
        long total = 0, done = 0, shown = -1;
        int count = 0;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir " + dir);
        try (ZipFile z = new ZipFile(zip)) {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (isObbEntry(e)) total += Math.max(0, e.getSize());
            }
            en = z.entries();
            byte[] buf = new byte[1 << 20];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!isObbEntry(e)) continue;
                String n = e.getName();
                File out = new File(dir, n.substring(n.lastIndexOf('/') + 1));
                File part = new File(dir, out.getName() + ".part");
                try (InputStream in = z.getInputStream(e); OutputStream os = new FileOutputStream(part)) {
                    for (int r; (r = in.read(buf)) > 0; ) {
                        os.write(buf, 0, r);
                        done += r;
                        long pct = total > 0 ? done * 100 / total : 0;
                        if (pct != shown) {
                            shown = pct;
                            listener.onStatus("Descompactando cache: " + pct + "%");
                        }
                    }
                }
                if (!part.renameTo(out)) throw new IOException("rename " + part);
                count++;
            }
        }
        if (count == 0) throw new IOException("O arquivo zip não contém arquivos .obb\nNecessário o cache do jogo (1.2.3e ou 1.2.1a): " + zip.getName());
    }

    private static boolean isObbEntry(ZipEntry e) {
        String n = e.getName();
        String leaf = n.substring(n.lastIndexOf('/') + 1);
        return !e.isDirectory() && leaf.endsWith(".obb") && !leaf.startsWith("._") && !n.startsWith("__MACOSX/");
    }

    private void extractAssets(File apk, File dir) throws IOException {
        String base = dir.getCanonicalPath() + File.separator;
        try (ZipFile z = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.startsWith("assets/")) continue;
                File out = new File(dir, n.substring("assets/".length()));
                if (!out.getCanonicalPath().startsWith(base)) throw new IOException("bad zip entry " + n);
                if (out.isFile() && out.length() == e.getSize() && crc32(out) == e.getCrc()) continue;
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
                try (InputStream in = z.getInputStream(e)) {
                    copy(in, new FileOutputStream(out));
                }
            }
        }
    }

    private static int intField(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return 0;
        i += key.length();
        int j = i;
        while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
        try { return Integer.parseInt(s.substring(i, j)); } catch (NumberFormatException e) { return 0; }
    }

    private static String readText(File f) {
        if (!f.isFile()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(text.getBytes("UTF-8")); }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try (InputStream i = in) { copy(i, out, true); }
    }

    private static void copy(InputStream in, OutputStream out, boolean unused) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        try {
            while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
        } finally {
            out.close();
        }
    }

    private void seedProfile(File game, String folder) {
        if (folder.equals("saves")) return;
        File from = new File(game, "saves"), to = new File(game, folder), marker = new File(to, ".seeded");
        if (marker.exists() || !from.isDirectory()) return;
        try {
            copyTree(from, to);
            marker.createNewFile();
            appendLog("[wrapper] profile " + folder + " seeded from saves");
        } catch (IOException e) {
            appendLog("[wrapper] profile seeding failed: " + e.getMessage());
        }
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("mkdir " + dst);
        File[] list = src.listFiles();
        if (list == null) return;
        for (File f : list) {
            File t = new File(dst, f.getName());
            if (f.isDirectory()) copyTree(f, t);
            else if (f.isFile()) Storage.copyFile(f, t);
        }
    }

    private static boolean isLink(File f) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(f.getPath()).st_mode);
        } catch (ErrnoException e) {
            return false;
        }
    }

    private static void deleteTree(File f) {
        if (!isLink(f)) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteTree(c);
        }
        f.delete();
    }
}
