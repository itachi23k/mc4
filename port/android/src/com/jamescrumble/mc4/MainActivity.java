package com.jamescrumble.mc4;

import java.io.File;
import java.util.Locale;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.input.InputManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/**
 * Full-screen shell around the emulated game. The picture comes from {@link GlBridge}, which renders the
 * game's OpenGL ES calls straight onto {@link #glView}; the overlay on top shows the loading screen and the
 * on-screen controls and turns touches and gamepad input into commands for the port.
 */
public final class MainActivity extends Activity {
    private enum Phase { SETUP, LOADING, PLAYING, ERROR }

    private static final int MAX_POINTERS = 20;
    private static final int ZONE_NONE = 0, ZONE_LEFT = 1, ZONE_RIGHT = 2, ZONE_CURSOR = 3, ZONE_BUTTON = 4;

    private static GameProcess running;

    private GameProcess game;
    private SurfaceView glView;
    private Overlay overlay;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile Phase phase = Phase.SETUP;
    private volatile String message = "Preparando arquivos…";
    private volatile boolean menuMode = true;
    private volatile int modeEpoch;
    private volatile long loadingSince;

    private boolean showFps = true;
    private int fpsLimit = 60;
    private WifiManager.MulticastLock multicastLock;

    // NEW: teclado on-screen
    private EditText kbdField;
    private volatile boolean kbdVisible;
    private String kbdLast = "";
    private boolean kbdSuppress = false;

    private void setRate(android.view.Surface surf) {
        if (Build.VERSION.SDK_INT >= 30 && surf != null && surf.isValid()) {
            try { surf.setFrameRate(fpsLimit, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT); } catch (RuntimeException ignored) { }
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        showFps = !"0".equals(SettingsActivity.load(this).get("FPS_COUNTER"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("mc4_multicast_lock");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
            }
        } catch (Throwable ignored) { }

        try { fpsLimit = Integer.parseInt(SettingsActivity.load(this).getOrDefault("FPS_LIMIT", "60")); } catch (NumberFormatException ignored) { }
        WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.preferredRefreshRate = fpsLimit;
        getWindow().setAttributes(wlp);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        GameProcess.Listener listener = new GameProcess.Listener() {
            @Override public void onStatus(String text) { message = text; }
            @Override public void onExit(int code) {
                StringBuilder sb = new StringBuilder("O jogo encerrou (código ").append(code).append(").\n");
                File log = game.logFile();
                if (log != null && log.isFile()) {
                    sb.append("Últimos registros do log:\n");
                    try (java.io.FileInputStream in = new java.io.FileInputStream(log)) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        String[] lines = out.toString("UTF-8").split("\n");
                        int start = Math.max(0, lines.length - 6);
                        for (int i = start; i < lines.length; i++) {
                            if (!lines[i].trim().isEmpty()) {
                                sb.append("• ").append(lines[i].trim()).append("\n");
                            }
                        }
                    } catch (Throwable ignored) { }
                    sb.append("\nArquivo de log: ").append(log.getPath());
                }
                message = sb.toString();
                phase = Phase.ERROR;
            }
        };

        boolean attach = running != null && running.isRunning();
        if (attach) {
            game = running;
            game.setListener(listener);
            game.log("activity recreated; attaching to the running game");
            phase = game.gl().swaps() > 0 ? Phase.PLAYING : Phase.LOADING;
            loadingSince = SystemClock.uptimeMillis();
        } else {
            game = new GameProcess(getApplicationContext(), listener);
            running = game;
        }

        FrameLayout root = new FrameLayout(this);
        glView = new SurfaceView(this);
        SurfaceHolder holder = glView.getHolder();
        holder.setFormat(PixelFormat.OPAQUE);

        holder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                if (h.getSurface() != null && h.getSurface().isValid()) {
                    setRate(h.getSurface());
                    game.gl().setSurface(h.getSurface());
                }
            }
            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
                if (h.getSurface() != null && h.getSurface().isValid()) {
                    setRate(h.getSurface());
                    game.gl().setSurface(h.getSurface());
                }
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                if (isFinishing()) {
                    game.gl().setSurface(null);
                }
            }
        });

        root.addView(glView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new Overlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // NEW: campo flutuante do teclado. GONE por padrão; o botão [⌨] do overlay o revela.
        kbdField = new EditText(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xdd101820);
        bg.setCornerRadius(14 * getResources().getDisplayMetrics().density);
        bg.setStroke(Math.round(2 * getResources().getDisplayMetrics().density), 0xff4fc3f7);
        kbdField.setBackground(bg);
        kbdField.setTextColor(0xfff2f5f7);
        kbdField.setHintTextColor(0x88f2f5f7);
        kbdField.setHint("Digite o nick…");
        int padH = Math.round(20 * getResources().getDisplayMetrics().density);
        int padV = Math.round(12 * getResources().getDisplayMetrics().density);
        kbdField.setPadding(padH, padV, padH, padV);
        kbdField.setTextSize(20);
        kbdField.setSingleLine(true);
        kbdField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        kbdField.setVisibility(View.GONE);
        FrameLayout.LayoutParams kbdLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        int marginTop = Math.round(20 * getResources().getDisplayMetrics().density);
        int marginSide = Math.round(80 * getResources().getDisplayMetrics().density);
        kbdLp.setMargins(marginSide, marginTop, marginSide, 0);
        root.addView(kbdField, kbdLp);

        
                kbdField.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || 
               (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN)) {
                
                String text = kbdField.getText().toString();
                // Envia a string completa pelo pipe para o C++
                game.command("text " + text, null); 
                
                hideKeyboard();
                return true;
            }
            return false;
        });

        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (im != null) im.registerInputDeviceListener(padWatcher, ui);

        if (attach) {
            if (phase == Phase.PLAYING) game.setMuted(false);
        } else if (Build.VERSION.SDK_INT < 30 && Storage.runtimePermissions(this, false).length > 0) {
            requestPermissions(Storage.runtimePermissions(this, false), 1);
        } else {
            startGame();
        }
        ui.post(poller);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        startGame();
    }

    private void startGame() {
        new Thread(() -> {
            try {
                game.start();
                if (game.isRunning()) {
                    message = "Carregando o jogo…\nA primeira inicialização pode levar alguns minutos.";
                    loadingSince = SystemClock.uptimeMillis();
                    phase = Phase.LOADING;
                }
            } catch (Exception e) {
                message = e.getMessage() != null ? e.getMessage() : e.toString();
                phase = Phase.ERROR;
            }
        }, "mc4-setup").start();
    }

    // ------------------------------------------------------------ NEW: teclado on-screen

        // Mude de "private void showKeyboard()" para:
    private void showKeyboard(String initialText) {
        if (kbdVisible || kbdField == null || phase != Phase.PLAYING) return;
        kbdVisible = true;
        kbdSuppress = true;
        
        // Define o texto vindo do C++ e posiciona o cursor no fim
        kbdField.setText(initialText);
        if (initialText != null && !initialText.isEmpty()) {
            kbdField.setSelection(initialText.length());
        }
        
        kbdSuppress = false;
        kbdLast = initialText != null ? initialText : "";
        
        kbdField.setVisibility(View.VISIBLE);
        kbdField.post(() -> {
            kbdField.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(kbdField, InputMethodManager.SHOW_IMPLICIT);
        });
        game.setMuted(true);
        if (overlay != null) overlay.invalidate();
    }

    private void hideKeyboard() {
        if (!kbdVisible || kbdField == null) return;
        kbdVisible = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(kbdField.getWindowToken(), 0);
        kbdField.setVisibility(View.GONE);
        kbdSuppress = true;
        kbdField.setText("");
        kbdSuppress = false;
        kbdLast = "";
        if (phase == Phase.PLAYING) game.setMuted(false);
        if (overlay != null) overlay.invalidate();
    }

    private static int keyCodeFor(char ch) {
        if (ch >= 'a' && ch <= 'z') return KeyEvent.KEYCODE_A + (ch - 'a');
        if (ch >= 'A' && ch <= 'Z') return KeyEvent.KEYCODE_A + (ch - 'A');
        if (ch >= '0' && ch <= '9') return KeyEvent.KEYCODE_0 + (ch - '0');
        switch (ch) {
            case ' ':  return KeyEvent.KEYCODE_SPACE;
            case '.':  return KeyEvent.KEYCODE_PERIOD;
            case ',':  return KeyEvent.KEYCODE_COMMA;
            case '-':  return KeyEvent.KEYCODE_MINUS;
            case '_':  return KeyEvent.KEYCODE_MINUS;
            case '\n': return KeyEvent.KEYCODE_ENTER;
            default:   return -1;
        }
    }

    private volatile String fpsText = "";

    private final Runnable poller = new Runnable() {
        private long lastReport = SystemClock.uptimeMillis();
        private long fpsAt = SystemClock.uptimeMillis(), fpsSwaps;

                @Override public void run() {
            Phase p = phase;
            long t = SystemClock.uptimeMillis();
            if (showFps && p == Phase.PLAYING && t - fpsAt >= 500) {
                long s = game.gl().swaps();
                fpsText = String.format(Locale.US, "%.1f FPS", (s - fpsSwaps) * 1000.0 / (t - fpsAt));
                fpsSwaps = s;
                fpsAt = t;
                overlay.invalidate();
            }
            if (p == Phase.LOADING || p == Phase.PLAYING) {
                int[] s = game.readStatus();
                if (s != null) {
                    boolean menu = s[1] == 1;
                    if (p == Phase.LOADING && (menu || s[0] > 300)) {
                        phase = Phase.PLAYING;
                        game.setMuted(false);
                        overlay.invalidate();
                    }
                    if (menu != menuMode) {
                        menuMode = menu;
                        modeEpoch++;
                        overlay.invalidate();
                    }
                    long now = SystemClock.uptimeMillis();
                    if (now - lastReport >= 5000) {
                        lastReport = now;
                        game.log(String.format(Locale.US, "phase=%s frame=%d menu=%d presented=%d", phase, s[0], s[1], game.gl().swaps()));
                    }
                }

                // NEW: Verifica se o jogo (C++) pediu para abrir o teclado
                if (p == Phase.PLAYING && !kbdVisible) {
                    File kbReq = new File(getFilesDir(), "ctl/keyboard_request");
                    if (kbReq.exists()) {
                        String initialText = "";
                        try {
                            java.util.List<String> lines = java.nio.file.Files.readAllLines(kbReq.toPath());
                            if (lines.size() > 0) initialText = lines.get(0);
                        } catch (Exception ignored) { }
                        
                        kbReq.delete(); // Deleta o arquivo para não abrir duas vezes
                        showKeyboard(initialText);
                    }
                }
            }
            if (phase != Phase.PLAYING) overlay.invalidate();
            ui.postDelayed(this, phase == Phase.PLAYING ? 250 : 40);
        }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            glView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override protected void onPause() {
        game.setMuted(true);
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (phase == Phase.PLAYING && !kbdVisible) game.setMuted(false);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(poller);
        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (im != null) im.unregisterInputDeviceListener(padWatcher);
        if (multicastLock != null) {
            try {
                if (multicastLock.isHeld()) multicastLock.release();
            } catch (Throwable ignored) { }
            multicastLock = null;
        }
        if (isFinishing()) {
            game.stop();
            if (running == game) running = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ gamepad

    private boolean padOn;

    private void padUsed() {
        if (!game.touchMode() || padOn) return;
        padOn = true;
        overlay.releaseFingers();
        game.command("pad 1", null);
    }

    private static boolean isPad(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private final InputManager.InputDeviceListener padWatcher = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { }
        @Override public void onInputDeviceChanged(int id) { }
        @Override public void onInputDeviceRemoved(int id) {
            if (!padOn) return;
            for (int other : InputDevice.getDeviceIds()) {
                if (isPad(InputDevice.getDevice(other))) return;
            }
            padOn = false;
            game.command("pad 0", null);
        }
    };

    private static String padButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "a";
            case KeyEvent.KEYCODE_BUTTON_B: return "b";
            case KeyEvent.KEYCODE_BUTTON_X: return "x";
            case KeyEvent.KEYCODE_BUTTON_Y: return "y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "l1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "r1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "l2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "r2";
            case KeyEvent.KEYCODE_BUTTON_START: return "start";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            default: return null;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        // NEW: Back fecha o teclado antes de ir para o jogo.
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && kbdVisible) {
            if (e.getAction() == KeyEvent.ACTION_UP) hideKeyboard();
            return true;
        }
        int action = e.getAction();
        boolean edge = (action == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) || action == KeyEvent.ACTION_UP;
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && game.touchMode() && phase == Phase.PLAYING) {
            if (edge) game.command("key 4 " + (action == KeyEvent.ACTION_DOWN ? "down" : "up"), null);
            return true;
        }
        String name = padButton(e.getKeyCode());
        int src = e.getSource();
        boolean pad = (src & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_DPAD | InputDevice.SOURCE_JOYSTICK)) != 0;
        if (name == null || !pad) return super.dispatchKeyEvent(e);
        padUsed();
        if (edge) {
            game.command("button " + name + (action == KeyEvent.ACTION_DOWN ? " down" : " up"), null);
        }
        return true;
    }

    private float padLx, padLy, padRx, padRy;
    private int hatX, hatY;

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == 0 || e.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(e);
        }
        padUsed();
        float lx = dead(e.getAxisValue(MotionEvent.AXIS_X)), ly = dead(e.getAxisValue(MotionEvent.AXIS_Y));
        float rx = dead(e.getAxisValue(MotionEvent.AXIS_Z)), ry = dead(e.getAxisValue(MotionEvent.AXIS_RZ));
        if (changed(lx, padLx) || changed(ly, padLy)) {
            padLx = lx; padLy = ly;
            game.command(String.format(Locale.US, "stick left %.3f %.3f", lx, ly), "stick left");
        }
        if (changed(rx, padRx) || changed(ry, padRy)) {
            padRx = rx; padRy = ry;
            game.command(String.format(Locale.US, "stick right %.3f %.3f", rx, ry), "stick right");
        }
        int hx = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_X));
        int hy = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if (hx != hatX) {
            if (hatX != 0) game.command("button " + (hatX < 0 ? "left" : "right") + " up", null);
            if (hx != 0) game.command("button " + (hx < 0 ? "left" : "right") + " down", null);
            hatX = hx;
        }
        if (hy != hatY) {
            if (hatY != 0) game.command("button " + (hatY < 0 ? "up" : "down") + " up", null);
            if (hy != 0) game.command("button " + (hy < 0 ? "up" : "down") + " down", null);
            hatY = hy;
        }
        return true;
    }

    private static float dead(float v) { return Math.abs(v) < 0.15f ? 0f : v; }
    private static boolean changed(float a, float b) { return Math.abs(a - b) > 0.02f; }

    // ------------------------------------------------------------ overlay

    private static final class Button {
        final String label, name;
        final RectF rect = new RectF();
        Button(String label, String name) { this.label = label; this.name = name; }
    }

    private final class Overlay extends View {
        private final float density = getResources().getDisplayMetrics().density;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private final RectF kbdBtn = new RectF();   // NEW: botão [⌨]
        private final Button[] buttons = {
            new Button("L1", "l1"), new Button("R1", "r1"), new Button("A", "a"),
            new Button("B", "b"), new Button("X", "x"), new Button("Y", "y"),
            new Button("II", "start"),
        };

        private final int[] zone = new int[MAX_POINTERS];
        private final int[] buttonOf = new int[MAX_POINTERS];
        private final float[] originX = new float[MAX_POINTERS], originY = new float[MAX_POINTERS];
        private int cursorPointer = -1;
        private int seenEpoch;

        Overlay(Context c) {
            super(c);
            setFocusable(true);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float s = 48 * density, gap = 8 * density, margin = 16 * density;
            float rowW = 3 * s + 2 * gap;
            float left = (w - rowW) / 2f;
            float bottom = h - margin;
            for (int i = 0; i < 6; i++) {
                int row = 1 - i / 3, col = i % 3;
                float x = left + col * (s + gap), y = bottom - (row + 1) * s - row * gap;
                buttons[i].rect.set(x, y, x + s, y + s);
            }
            buttons[6].rect.set((w - s) / 2f, margin, (w + s) / 2f, margin + s);
            // NEW: botão [⌨] no canto superior direito.
            float kb = 44 * density;
            kbdBtn.set(w - margin - kb, margin, w - margin, margin + kb);
        }

        @Override protected void onDraw(Canvas c) {
            Phase p = phase;
            if (p == Phase.PLAYING) {
                c.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
                if (!menuMode && (!game.touchMode() || padOn)) drawButtons(c);
                if (showFps) drawFps(c);
                drawKbdButton(c);   // NEW: sempre visível durante o jogo
            } else {
                drawLoading(c, p, SystemClock.uptimeMillis());
            }
        }

        private void drawFps(Canvas c) {
            String s = fpsText;
            if (s.isEmpty()) return;
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(13 * density);
            float x = getWidth() / 2f, y = 18 * density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x99000000);
            float w = paint.measureText(s) / 2 + 6 * density;
            c.drawRect(x - w, y - 14 * density, x + w, y + 5 * density, paint);
            paint.setColor(0xff7cfc00);
            c.drawText(s, x, y, paint);
        }

        // NEW: botão [⌨] do canto superior direito
        private void drawKbdButton(Canvas c) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(kbdVisible ? 0xcc4fc3f7 : 0x77000000);
            c.drawRoundRect(kbdBtn, 10 * density, 10 * density, paint);
            paint.setColor(0xffffffff);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(20 * density);
            c.drawText("⌨", kbdBtn.centerX(), kbdBtn.centerY() + 7 * density, paint);
        }

        private void drawButtons(Canvas c) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(16 * density);
            for (Button b : buttons) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x55ffffff);
                c.drawRoundRect(b.rect, 12 * density, 12 * density, paint);
                paint.setColor(0xddffffff);
                c.drawText(b.label, b.rect.centerX(), b.rect.centerY() + 6 * density, paint);
            }
        }

        private void drawLoading(Canvas c, Phase p, long now) {
            int w = getWidth(), h = getHeight();
            c.drawColor(0xff0b0f14);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xfff2f5f7);
            paint.setTextSize(36 * density);
            c.drawText("Modern Combat 4", w / 2f, h * 0.30f, paint);

            paint.setTextSize(16 * density);
            paint.setColor(p == Phase.ERROR ? 0xffff8a80 : 0xffb7c1c9);
            float y = h * 0.42f;
            for (String line : message.split("\n")) {
                c.drawText(line, w / 2f, y, paint);
                y += 24 * density;
            }
            if (p == Phase.ERROR) return;

            float barW = Math.min(w * 0.5f, 320 * density), barH = 6 * density;
            float x0 = (w - barW) / 2f, y0 = Math.max(y + 12 * density, h * 0.60f);
            paint.setColor(0xff1f2933);
            bar.set(x0, y0, x0 + barW, y0 + barH);
            c.drawRoundRect(bar, barH, barH, paint);
            float seg = barW * 0.3f;
            float t = (now % 1400) / 1400f;
            float sx = x0 + (barW + seg) * t - seg;
            float l = Math.max(x0, sx), r = Math.min(x0 + barW, sx + seg);
            paint.setColor(0xff4fc3f7);
            if (r > l) {
                bar.set(l, y0, r, y0 + barH);
                c.drawRoundRect(bar, barH, barH, paint);
            }
            if (p == Phase.LOADING) {
                paint.setColor(0xff7b8794);
                paint.setTextSize(13 * density);
                c.drawText("Decorrido: " + (now - loadingSince) / 1000 + " s", w / 2f, y0 + 30 * density, paint);
            }
        }

        // -------------------------------------------------- touch

        private float clamp(float v) { return Math.max(-1f, Math.min(1f, v)); }

        private void sendCursor(float x, float y) {
            float gx = x * game.renderWidth() / Math.max(1, getWidth());
            float gy = y * game.renderHeight() / Math.max(1, getHeight());
            game.command(String.format(Locale.US, "cursor %.1f %.1f", gx, gy), "cursor");
        }

        private void pointerDown(int id, float x, float y) {
            if (id >= MAX_POINTERS) return;
            zone[id] = ZONE_NONE;
            if (!menuMode) {
                for (int i = 0; i < buttons.length; i++) {
                    if (buttons[i].rect.contains(x, y)) {
                        zone[id] = ZONE_BUTTON;
                        buttonOf[id] = i;
                        game.command("button " + buttons[i].name + " down", null);
                        return;
                    }
                }
                float frac = x / Math.max(1, getWidth());
                if (frac < 0.35f || frac > 0.65f) {
                    zone[id] = frac < 0.35f ? ZONE_LEFT : ZONE_RIGHT;
                    originX[id] = x;
                    originY[id] = y;
                    return;
                }
            }
            if (cursorPointer < 0) {
                cursorPointer = id;
                zone[id] = ZONE_CURSOR;
                sendCursor(x, y);
                game.command("click down", null);
            }
        }

        private void pointerMove(float x, float y, int id) {
            if (id >= MAX_POINTERS) return;
            switch (zone[id]) {
                case ZONE_CURSOR:
                    sendCursor(x, y);
                    break;
                case ZONE_LEFT:
                case ZONE_RIGHT: {
                    float radius = 70 * density;
                    boolean left = zone[id] == ZONE_LEFT;
                    game.command(String.format(Locale.US, "stick %s %.3f %.3f", left ? "left" : "right",
                            clamp((x - originX[id]) / radius), clamp((y - originY[id]) / radius)),
                            left ? "stick left" : "stick right");
                    break;
                }
                default:
                    break;
            }
        }

        private void pointerUp(int id) {
            if (id >= MAX_POINTERS) return;
            switch (zone[id]) {
                case ZONE_CURSOR:
                    cursorPointer = -1;
                    game.command("click up", null);
                    break;
                case ZONE_LEFT:
                    game.command("stick left 0 0", "stick left");
                    break;
                case ZONE_RIGHT:
                    game.command("stick right 0 0", "stick right");
                    break;
                case ZONE_BUTTON:
                    game.command("button " + buttons[buttonOf[id]].name + " up", null);
                    break;
                default:
                    break;
            }
            zone[id] = ZONE_NONE;
        }

        private void releaseAll() {
            for (int id = 0; id < MAX_POINTERS; id++) if (zone[id] != ZONE_NONE) pointerUp(id);
        }

        // ------------------------------------------- touch, phone style

        private final boolean[] fingerDown = new boolean[MAX_POINTERS];
        private final int[] fingerX = new int[MAX_POINTERS], fingerY = new int[MAX_POINTERS];

        void releaseFingers() {
            for (int id = 0; id < MAX_POINTERS; id++) {
                if (!fingerDown[id]) continue;
                fingerDown[id] = false;
                game.command("touch 0 " + fingerX[id] + " " + fingerY[id] + " " + id, null);
            }
        }

        private void touch(int action, MotionEvent e, int index) {
            int id = e.getPointerId(index);
            if (id >= MAX_POINTERS) return;
            if (action == 1) fingerDown[id] = true;
            else if (action == 0) { if (!fingerDown[id]) return; fingerDown[id] = false; }
            else if (!fingerDown[id]) return;
            int gx = Math.round(e.getX(index) * game.renderWidth() / Math.max(1, getWidth()));
            int gy = Math.round(e.getY(index) * game.renderHeight() / Math.max(1, getHeight()));
            fingerX[id] = gx;
            fingerY[id] = gy;
            game.command("touch " + action + " " + gx + " " + gy + " " + id + " " + e.getEventTime(),
                    action == 2 ? "touch " + id : null);
        }

        private void nativeTouch(MotionEvent e) {
            int idx = e.getActionIndex();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    touch(1, e, idx);
                    break;
                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < e.getPointerCount(); i++) touch(2, e, i);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    touch(0, e, idx);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    for (int i = 0; i < e.getPointerCount(); i++) touch(0, e, i);
                    break;
                default:
                    break;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (phase != Phase.PLAYING) return true;

            // NEW: botão [⌨] interceptado antes de tudo (funciona em touch mode e em pad mode).
            int idx = e.getActionIndex();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                float x = e.getX(idx), y = e.getY(idx);
                if (kbdBtn.contains(x, y)) {
                    if (kbdVisible) hideKeyboard(); else showKeyboard("");
                    return true;
                }
            }

            // NEW: enquanto o teclado está visível, o jogo não recebe toques.
            if (kbdVisible) return true;

            if (game.touchMode() && !padOn) {
                nativeTouch(e);
                return true;
            }
            if (seenEpoch != modeEpoch) {
                seenEpoch = modeEpoch;
                releaseAll();
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    pointerDown(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                    break;
                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < e.getPointerCount(); i++) pointerMove(e.getX(i), e.getY(i), e.getPointerId(i));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    pointerUp(e.getPointerId(idx));
                    break;
                case MotionEvent.ACTION_CANCEL:
                    releaseAll();
                    break;
                default:
                    break;
            }
            return true;
        }
    }
}
