package com.jamescrumble.mc4;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Game settings. They are kept in settings.txt (KEY=VALUE per line) next to the game log; GameProcess
 * adds them to the engine's environment after engine_env.txt, so a menu value wins over a hand-edited one.
 * This is the app's launcher screen: "Jogar" saves and starts the game (the game process is restarted
 * so that it always begins with the values shown here).
 */
public final class SettingsActivity extends Activity {
    private static final int BG = Color.rgb(18, 22, 26), FG = Color.rgb(230, 235, 238), DIM = Color.rgb(140, 150, 156),
            ACCENT = Color.rgb(128, 203, 196);

    private final Map<String, String> values = new LinkedHashMap<>();
    private float dp;
    private TextView folderInfo, versionInfo;
    private boolean hadAccess, asked;

    /** The existing settings file (main folder first, then the other), or where it would be written. */
    static File file(Context c) {
        return Storage.find(c, "settings.txt");
    }

    static Map<String, String> load(Context c) {
        Map<String, String> m = new LinkedHashMap<>();
        File f = file(c);
        if (!f.isFile()) return m;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[2048];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            for (String line : out.toString("UTF-8").split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException ignored) { }
        return m;
    }

    private static void write(Context c, Map<String, String> m) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        try (FileOutputStream out = new FileOutputStream(new File(Storage.mainDir(c), "settings.txt"))) {
            out.write(sb.toString().getBytes("UTF-8"));
        }
    }

    /** Removes a one-shot value from the saved settings once the game has taken it. */
    static void consume(Context c, String key) {
        Map<String, String> m = load(c);
        if (m.remove(key) == null) return;
        try { write(c, m); } catch (IOException ignored) { }
    }

    private boolean save() {
        try {
            write(this, values);
            return true;
        } catch (IOException e) {
            Toast.makeText(this, "Não foi possível salvar: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        values.putAll(load(this));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(20), px(16), px(20), px(24));

        col.addView(text("Modern Combat 4 — Configurações", 22, FG, true));
        col.addView(text("Ajuste as opções e toque em «Jogar» — o jogo iniciará com estes valores.", 13, DIM, false));
        folderInfo = text("", 12, DIM, false);
        folderInfo.setPadding(0, px(6), 0, 0);
        col.addView(folderInfo);
        // Which build of the game is in the folder and what that means; filled in by showVersion().
        versionInfo = text("", 13, ACCENT, false);
        versionInfo.setPadding(0, px(8), 0, 0);
        versionInfo.setVisibility(View.GONE);
        col.addView(versionInfo);

        col.addView(section("Gráficos"));
        col.addView(choice("Resolução de Renderização (altura)", "RENDER_HEIGHT", new int[] { 480, 540, 720 }, 720, "p",
                "Largura calculada proporcionalmente. Menor = mais rápido/leve."));

        col.addView(choice("Qualidade Gráfica", "MC4_GENERATION", new int[] { 0, 1, 3 },
                new String[] { "Baixa", "Média", "Ultra" }, 3, "",
                "Nível de efeitos e detalhes visuais da engine. Independe do APK. Menor = mais rápido."));

        col.addView(choice("Limite de FPS", "FPS_LIMIT", new int[] { 30, 60, 90, 120 }, 60, " fps",
                "Taxa de atualização da tela e limite máximo de FPS."));
        col.addView(toggle("Contador de FPS", "FPS_COUNTER", true, "Exibe um pequeno contador verde em tempo real no topo da tela."));

        col.addView(section("Créditos e Moedas"));
        col.addView(toggle("Créditos Infinitos", "MC4_MONEY_INFINITE", false,
                "Exibe 9.999.999 créditos e compras não debitam. O saldo real é preservado ao desativar."));
        col.addView(amount("Definir Créditos", "MC4_MONEY_RESTORE", 9999999,
                "Digite o valor e clique em «Jogar»: o saldo será definido para essa quantia uma vez ao carregar o perfil."));

        col.addView(section("Rede & Multiplayer (Wi-Fi Local)"));
        col.addView(textInput("Nome do Jogador (Nickname)", "MC4_PLAYER_NAME", "Player", "Apelido exibido nas salas de rede local Wi-Fi."));
        col.addView(toggle("Depuração Detalhada de Rede", "MC4_DEBUG_NET", true, "Registra pacotes UDP, binds e endereços de pares no arquivo de log."));

        col.addView(section("Áudio"));
        col.addView(choice("Buffer de Áudio", "AUDIO_LATENCY_MS", new int[] { 0, 200, 400 }, 0, " ms",
                "0 = latência mínima do aparelho. Valores maiores se o áudio falhar em cenas pesadas."));

        col.addView(section("Desempenho"));
        col.addView(choice("Quadros na Fila (In-Flight)", "MC4_GL_INFLIGHT", new int[] { 1, 2, 3 }, 2, "",
                "Quadros preparados antecipadamente. 2 = maior fluidez, 1 = menor latência de toque."));
        col.addView(toggle("Fixar em Núcleos Rápidos (Prime/Big)", "CPU_PRIME", true,
                "Mantém a emulação nos dois núcleos mais rápidos da CPU."));
        col.addView(toggle("Dicas de Desempenho ao Android (ADPF)", "PERF_HINT", false,
                "Informa os tempos de renderização de cada quadro ao sistema operacional."));
        col.addView(toggle("Profiling e Diagnóstico no Log", "QEMU_GUEST_PROF", true,
                "Registra periodicamente no log o tempo gasto pela engine para diagnóstico."));

        ScrollView sv = new ScrollView(this);
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The buttons stay at the bottom whatever the scroll position.
        Button reset = button("Restaurar Padrão");
        reset.setOnClickListener(v -> {
            values.clear();
            Storage.deleteEverywhere(this, "settings.txt");
            recreate();
        });
        Button saveOnly = button("Salvar");
        saveOnly.setOnClickListener(v -> {
            if (save()) Toast.makeText(this, "Configurações Salvas", Toast.LENGTH_SHORT).show();
        });
        Button play = button("Jogar");
        play.setOnClickListener(v -> { if (save()) restartGame(); });
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(px(16), px(4), px(16), px(8));
        for (Button bt : new Button[] { reset, saveOnly, play }) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(px(4), 0, px(4), 0);
            bar.addView(bt, lp);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        hadAccess = Storage.hasAccess(this);
        showFolder();
        if (!hadAccess) askAccess();
        else moveFiles();
    }

    @Override protected void onResume() {
        super.onResume();
        // Back from the system page: if the access has just been given, take the files over.
        boolean now = Storage.hasAccess(this);
        if (now && !hadAccess) moveFiles();
        hadAccess = now;
        showFolder();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (Storage.hasAccess(this)) { hadAccess = true; moveFiles(); }
        showFolder();
    }

    private void showFolder() {
        if (folderInfo == null) return;
        File main = Storage.mainDir(this);
        folderInfo.setText("Pasta do jogo: " + main.getPath() + (Storage.hasAccess(this) ? ""
                : "\n(Sem acesso a todos os arquivos — usando pasta interna em Android/data)"));
        showVersion();
    }

    /** Looks at the APK and the cache off the UI thread and tells which version of the game they are. */
    private void showVersion() {
        new Thread(() -> {
            String notice = GameVersion.detect(getApplicationContext()).notice();
            runOnUiThread(() -> {
                if (isDestroyed() || versionInfo == null) return;
                versionInfo.setText(notice == null ? "" : notice);
                versionInfo.setVisibility(notice == null ? View.GONE : View.VISIBLE);
            });
        }, "bridge-version").start();
    }

    /** Asks once per launch of this screen for access to all files; the game works without it. */
    private void askAccess() {
        if (asked) return;
        asked = true;
        new AlertDialog.Builder(this)
                .setTitle("Acesso a Todos os Arquivos")
                .setMessage("Para armazenar o APK, cache (.obb) e dados na pasta compartilhada\n"
                        + Storage.shared(this).getPath() + "\no aplicativo precisa de permissão de acesso aos arquivos. Sem essa permissão, os dados são lidos da pasta interna em Android/data.")
                .setPositiveButton("Permitir", (d, w) -> Storage.requestAccess(this, 2))
                .setNegativeButton("Depois", null)
                .show();
    }

    /** Creates BridgeGames/<package> and moves the files of Android/data into it (off the UI thread: it may copy). */
    private void moveFiles() {
        new Thread(() -> {
            int n = Storage.migrate(getApplicationContext(), t -> runOnUiThread(() -> folderInfo.setText(t)));
            runOnUiThread(() -> {
                showFolder();
                if (n > 0) Toast.makeText(this, "Arquivos transferidos para BridgeGames: " + n, Toast.LENGTH_LONG).show();
            });
        }, "bridge-storage").start();
    }

    /**
     * Starts the game with the saved values. CLEAR_TASK destroys a MainActivity that is still alive, so its
     * GameProcess (which reads settings.txt when it is created) is rebuilt from scratch.
     */
    private void restartGame() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    // ------------------------------------------------------------ widgets

    private int px(int v) { return Math.round(v * dp); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px(h)));
        return v;
    }

    private View section(String title) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, px(22), 0, px(4));
        l.addView(text(title.toUpperCase(), 12, ACCENT, true));
        return l;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private int intOf(String key, int def) {
        try { return Integer.parseInt(values.getOrDefault(key, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }

    private View slider(String title, String key, int min, int max, int step, int def, String unit, String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, px(10), 0, px(4));
        TextView head = text("", 16, FG, false);
        SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / step);
        int cur = Math.max(min, Math.min(max, intOf(key, def)));
        bar.setProgress((cur - min) / step);
        Runnable show = () -> head.setText(title + ": " + (min + bar.getProgress() * step) + unit);
        show.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                show.run();
                values.put(key, String.valueOf(min + p * step));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        row.addView(head);
        row.addView(bar);
        row.addView(text(hint, 12, DIM, false));
        return row;
    }

    private View choice(String title, String key, int[] options, int def, String unit, String hint) {
        return choice(title, key, options, null, def, unit, hint);
    }

    /** One button per option; labels (when given) replace the number on the button. */
    private View choice(String title, String key, int[] options, String[] labels, int def, String unit, String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, px(10), 0, px(4));
        row.addView(text(title, 16, FG, false));
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.START);
        int cur = intOf(key, def);
        Button[] bs = new Button[options.length];
        for (int i = 0; i < options.length; i++) {
            final int o = options[i];
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(labels != null ? labels[i] : o + unit);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            bs[i] = b;
            b.setOnClickListener(v -> {
                values.put(key, String.valueOf(o));
                for (int j = 0; j < bs.length; j++) bs[j].setAlpha(options[j] == o ? 1f : 0.45f);
            });
            b.setAlpha(o == cur ? 1f : 0.45f);
            line.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        row.addView(line);
        row.addView(text(hint, 12, DIM, false));
        return row;
    }

    /** A whole number typed by hand; empty means "not set" and removes the key. */
    private View amount(String title, String key, int max, String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, px(10), 0, px(4));
        row.addView(text(title, 16, FG, false));
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setFilters(new InputFilter[] { new InputFilter.LengthFilter(String.valueOf(max).length()) });
        in.setTextColor(FG);
        in.setHintTextColor(DIM);
        in.setHint("não definido");
        in.setText(values.getOrDefault(key, ""));
        in.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                String t = e.toString().trim();
                if (t.isEmpty()) { values.remove(key); return; }
                try { values.put(key, String.valueOf(Math.min(max, Long.parseLong(t)))); } catch (NumberFormatException x) { values.remove(key); }
            }
        });
        row.addView(in);
        row.addView(text(hint, 12, DIM, false));
        return row;
    }

    private View textInput(String title, String key, String def, String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, px(10), 0, px(4));
        row.addView(text(title, 16, FG, false));
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        in.setTextColor(FG);
        in.setHintTextColor(DIM);
        in.setHint(def);
        in.setText(values.getOrDefault(key, ""));
        in.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                String t = e.toString().trim();
                if (t.isEmpty()) { values.remove(key); return; }
                values.put(key, t);
            }
        });
        row.addView(in);
        row.addView(text(hint, 12, DIM, false));
        return row;
    }

    private void showLogDialog() {
        File log = Storage.find(this, "mc4.log");
        if (!log.isFile()) log = Storage.find(this, "game.log");
        if (!log.isFile()) log = new File(getFilesDir(), "mc4.log");
        StringBuilder sb = new StringBuilder();
        if (log.isFile()) {
            try (FileInputStream in = new FileInputStream(log)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                String full = out.toString("UTF-8");
                String[] lines = full.split("\n");
                int start = Math.max(0, lines.length - 120);
                for (int i = start; i < lines.length; i++) {
                    sb.append(lines[i]).append('\n');
                }
            } catch (Exception e) {
                sb.append("Erro ao ler log: ").append(e.getMessage());
            }
        } else {
            sb.append("Nenhum arquivo de log encontrado em:\n").append(log.getPath());
        }

        final String logContent = sb.toString();
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(logContent);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(FG);
        tv.setPadding(px(16), px(12), px(16), px(12));
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("Log do Jogo & Diagnóstico")
                .setView(sv)
                .setPositiveButton("Copiar Log", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("MC4 Log", logContent));
                        Toast.makeText(this, "Log copiado para a área de transferência!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private View toggle(String title, String key, boolean def, String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, px(10), 0, px(4));
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextColor(FG);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sw.setChecked(intOf(key, def ? 1 : 0) != 0);
        sw.setOnCheckedChangeListener((b, on) -> values.put(key, on ? "1" : "0"));
        row.addView(sw);
        row.addView(text(hint, 12, DIM, false));
        return row;
    }
}
