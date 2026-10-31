package com.jamescrumble.mc4;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Game settings. They are kept in settings.txt (KEY=VALUE per line) next to the game log; GameProcess
 * adds them to the engine's environment after engine_env.txt, so a menu value wins over a hand-edited one.
 * This is the app's launcher screen: "Jogar" saves and starts the game (the game process is restarted
 * so that it always begins with the values shown here).
 */
public final class SettingsActivity extends Activity {
    private static final String TAG = "MC4_Settings";
    private static final int REQ_NOTIF = 101;
    private static final int BG = Color.rgb(18, 22, 26), FG = Color.rgb(230, 235, 238), DIM = Color.rgb(140, 150, 156),
            ACCENT = Color.rgb(128, 203, 196);

    private final Map<String, String> values = new LinkedHashMap<>();
    private float dp;
    private TextView folderInfo, versionInfo;
    private boolean hadAccess, asked;

    // ---- cards customizados de download individual ----
    private LinearLayout dlContainer;
    private TextView dlSummaryText;
    private Button dlAllBtn;
    private final TextView[] dlCardTitles = new TextView[3];
    private final TextView[] dlCardBadges = new TextView[3];
    private final TextView[] dlCardSubtexts = new TextView[3];
    private final ProgressBar[] dlCardBars = new ProgressBar[3];
    private final Button[] dlCardActionBtns = new Button[3];
    private boolean dlPolling;
    private Runnable dlPoll;
    private BroadcastReceiver dlReceiver;

    /** The existing settings file (main folder first, then the other), or where it would be written. */
    static File file(Context c) {
        return Storage.find(c, "settings.txt");
    }

    static Map<String, String> load(Context c) {
        Map<String, String> m = new LinkedHashMap<>();
        File f = file(c);
        if (!f.isFile()) return m;

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            Log.e(TAG, "Erro ao carregar configurações", e);
        }
        return m;
    }

    private static void write(Context c, Map<String, String> m) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(new File(Storage.mainDir(c), "settings.txt"))) {
            out.write(sb.toString().getBytes("UTF-8"));
        }
    }

    /** Removes a one-shot value from the saved settings once the game has taken it. */
    static void consume(Context c, String key) {
        Map<String, String> m = load(c);
        if (m.remove(key) == null) return;
        try {
            write(c, m);
        } catch (IOException e) {
            Log.e(TAG, "Falha ao consumir chave: " + key, e);
        }
    }

    /** Save logic handled in a background thread to prevent UI lockup (ANR). */
    private void saveAnd(Runnable onDone) {
        new Thread(() -> {
            try {
                write(this, values);
                runOnUiThread(() -> {
                    if (onDone == null) Toast.makeText(this, "Configurações Salvas", Toast.LENGTH_SHORT).show();
                    if (onDone != null) onDone.run();
                });
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, "Não foi possível salvar: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "mc4-settings-save").start();
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

        versionInfo = text("", 13, ACCENT, false);
        versionInfo.setPadding(0, px(8), 0, 0);
        versionInfo.setVisibility(View.GONE);
        col.addView(versionInfo);

        // ---- INSTALAÇÃO (download do Google Drive) ----
        col.addView(section("Instalação"));
        col.addView(text(
                "Baixe o APK e os arquivos de cache (OBBs) individualmente ou todos de uma vez.",
                12, DIM, false));
        col.addView(buildDownloadSection());

        // ---- PRESETS ----
        col.addView(section("Presets de Qualidade"));
        col.addView(toggle("Nítido", "MC4_PRESET_SHARP", false,
                "Renderiza em 1440p interno e reduz para a tela — ganho real de nitidez em qualquer painel.\n"
              + "Só aplica se você não tiver definido a Resolução de Renderização abaixo.\n"
              + "Pode aquecer o aparelho em cenas pesadas."));

        col.addView(toggle("Fluido", "MC4_PRESET_SMOOTH", false,
                "3 quadros em voo, pipe GL de 2 MB e dicas de desempenho ao Android.\n"
              + "Reduz engasgos em troca de ~1 quadro de latência.\n"
              + "Não altera resolução."));

        col.addView(toggle("Extremo", "MC4_PRESET_EXTREME", false,
                "Combina Nítido + Fluido e força glReadPixels sem cache (lens flare preciso).\n"
              + "Suas escolhas manuais nas seções abaixo sempre ganham do preset.\n"
              + "Não ativa sombras/LOD/culling: isso vive dentro da engine nativa."));

        // ---- GRÁFICOS ----
        col.addView(section("Gráficos"));
        col.addView(choice("Resolução de Renderização (altura)", "RENDER_HEIGHT",
                new int[] { 480, 720, 1080, 1440, 2160 }, 1080, "p",
                "1080p = Nativo. Valores maiores ativam supersampling (extrema nitidez, mas exige mais da GPU).\n"
              + "Definir aqui cancela o supersampling do preset Nítido/Extremo."));

        col.addView(choice("HUD Inset", "MC4_HUD_INSET",
                new int[] { 0, 3, 5, 8, 12 },
                new String[] { "Off", "3%", "5%", "8%", "12%" }, 0, "",
                "Afasta botões e minimapa das bordas curvas/notch.\n"
              + "A imagem 3D ocupa a tela inteira; só a camada 2D (HUD) encolhe."));

        col.addView(choice("Cache de glReadPixels", "MC4_READPIXELS_EVERY",
                new int[] { 1, 3, 6 },
                new String[] { "Sempre real", "Padrão", "Agressivo" }, 3, "",
                "A engine lê pixels para o lens flare. Sem cache (1) é mais preciso, mas custa mais.\n"
              + "O preset Extremo força 1."));

        col.addView(choice("Limite de FPS", "FPS_LIMIT", new int[] { 30, 60, 90, 120 }, 60, " fps",
                "Taxa de atualização da tela e limite máximo de FPS."));
        col.addView(toggle("Contador de FPS", "FPS_COUNTER", true, "Exibe um pequeno contador verde em tempo real no topo da tela."));

        // ---- APARÊNCIA AVANÇADA ----
        col.addView(section("Aparência Avançada"));
        col.addView(choice("Cores e Curvas de Tom (Color Grading)", "MC4_COLOR_PROFILE",
                new int[] { 1, 2, 3, 0 },
                new String[] { "Vívido (Recomendado)", "Cinematográfico", "Suave", "Desligado" }, 1, "",
                "Restaura a paleta vibrante e o contraste original do jogo de 2012 com correção de gama e curvas S.\n"
              + "Vívido: Cores ricas e iluminação intensa da versão original.\n"
              + "Cinematográfico: Contraste dramático e sombras profundas.\n"
              + "Suave: Tons sutis e menor agressividade."));
        col.addView(textInput("Nome da GPU (spoof)", "MC4_GL_RENDERER", "",
                "A engine escolhe o perfil gráfico pelo nome da GPU. Se seu aparelho for reconhecido\n"
              + "como uma GPU antiga, digitar o nome de uma top (ex.: Adreno (TM) 740) pode liberar\n"
              + "efeitos extras — ou rebaixar, se ela não reconhecer. Deixe vazio para usar o nome real.\n"
              + "Equivale a colocar gpu_name.txt na pasta do jogo (o valor daqui ganha do arquivo)."));

        // ---- CRÉDITOS ----
        col.addView(section("Créditos e Moedas"));
        col.addView(toggle("Créditos Infinitos", "MC4_MONEY_INFINITE", false,
                "Exibe 9.999.999 créditos e compras não debitam. O saldo real é preservado ao desativar."));
        col.addView(amount("Definir Créditos", "MC4_MONEY_RESTORE", 9999999,
                "Digite o valor e clique em «Jogar»: o saldo será definido para essa quantia uma vez ao carregar o perfil."));

        // ---- REDE ----
        col.addView(section("Rede & Multiplayer (Wi-Fi Local)"));
        col.addView(textInput("Nome do Jogador (Nickname)", "MC4_PLAYER_NAME", "Player", "Apelido exibido nas salas de rede local Wi-Fi."));
        col.addView(toggle("Depuração Detalhada de Rede", "MC4_DEBUG_NET", true, "Registra pacotes UDP, binds e endereços de pares no arquivo de log."));

        // ---- ÁUDIO ----
        col.addView(section("Áudio"));
        col.addView(choice("Buffer de Áudio", "AUDIO_LATENCY_MS", new int[] { 0, 200, 400 }, 0, " ms",
                "0 = latência mínima do aparelho. Valores maiores se o áudio falhar em cenas pesadas."));

        // ---- DESEMPENHO ----
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

        Button reset = button("Restaurar Padrão");
        reset.setOnClickListener(v -> {
            values.clear();
            Storage.deleteEverywhere(this, "settings.txt");
            recreate();
        });

        Button saveOnly = button("Salvar");
        saveOnly.setOnClickListener(v -> saveAnd(null));

        Button play = button("Jogar");
        play.setOnClickListener(v -> saveAnd(this::restartGame));

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

        registerDownloadReceiver();
    }

    @Override protected void onResume() {
        super.onResume();
        boolean now = Storage.hasAccess(this);
        if (now && !hadAccess) moveFiles();
        hadAccess = now;
        showFolder();

        updateDownloadUI();
        if (DriveDownloader.anyActive(this)) {
            startProgressPolling();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterDownloadReceiver();
        dlPolling = false;
        if (dlContainer != null && dlPoll != null) {
            dlContainer.removeCallbacks(dlPoll);
        }
    }

    private void registerDownloadReceiver() {
        if (dlReceiver != null) return;
        dlReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    runOnUiThread(() -> {
                        updateDownloadUI();
                        startProgressPolling();
                    });
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        // No Android 14+ (API 34, 35, 36), broadcasts do sistema precisam de RECEIVER_EXPORTED
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dlReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(dlReceiver, filter);
        }
    }

    private void unregisterDownloadReceiver() {
        if (dlReceiver != null) {
            try {
                unregisterReceiver(dlReceiver);
            } catch (Exception ignored) {}
            dlReceiver = null;
        }
    }

    private void startProgressPolling() {
        if (dlPoll == null) {
            dlPoll = () -> {
                if (!dlPolling && !DriveDownloader.anyActive(this)) {
                    updateDownloadUI();
                    return;
                }
                updateDownloadUI();
                if (dlContainer != null) {
                    if (DriveDownloader.anyActive(this)) {
                        dlContainer.postDelayed(dlPoll, 500);
                    } else {
                        dlPolling = false;
                    }
                }
            };
        }
        if (dlPolling) return;
        dlPolling = true;
        if (dlContainer != null) {
            dlContainer.post(dlPoll);
        }
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIF);
            }
        }
    }

    private android.graphics.drawable.GradientDrawable cardBg(int bg, int stroke) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(px(10));
        gd.setStroke(px(1), stroke);
        return gd;
    }

    private android.graphics.drawable.GradientDrawable badgeDrawable(int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(px(12));
        return gd;
    }

    private android.graphics.drawable.Drawable createProgressDrawable() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.rgb(36, 46, 54));
        bg.setCornerRadius(px(4));

        android.graphics.drawable.GradientDrawable progress = new android.graphics.drawable.GradientDrawable();
        progress.setColor(ACCENT);
        progress.setCornerRadius(px(4));
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                progress, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);

        android.graphics.drawable.Drawable[] layers = { bg, clip };
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(layers);
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        return ld;
    }

    private View buildDownloadSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(14), px(14), px(14), px(14));
        box.setBackground(cardBg(Color.rgb(20, 26, 32), Color.rgb(38, 50, 60)));

        dlSummaryText = text("Verificando arquivos…", 13, ACCENT, true);
        dlSummaryText.setPadding(0, 0, 0, px(10));
        box.addView(dlSummaryText);

        dlAllBtn = button("⬇ Baixar Todos os Arquivos Faltantes");
        dlAllBtn.setOnClickListener(v -> checkPermissionAndDownloadAll());
        box.addView(dlAllBtn);

        // Lista de cards individuais
        LinearLayout cardsList = new LinearLayout(this);
        cardsList.setOrientation(LinearLayout.VERTICAL);
        cardsList.setPadding(0, px(12), 0, 0);

        for (int i = 0; i < 3; i++) {
            final int fileIndex = i;
            DriveDownloader.DriveFile f = DriveDownloader.FILES[i];

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(12), px(10), px(12), px(10));
            card.setBackground(cardBg(Color.rgb(26, 34, 42), Color.rgb(45, 60, 72)));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, px(6), 0, px(6));
            card.setLayoutParams(cardLp);

            // Linha 1: Título do arquivo + Badge de status
            LinearLayout headerRow = new LinearLayout(this);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);

            dlCardTitles[i] = text(f.title, 13, FG, true);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            headerRow.addView(dlCardTitles[i], titleLp);

            dlCardBadges[i] = new TextView(this);
            dlCardBadges[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            dlCardBadges[i].setTextColor(Color.WHITE);
            dlCardBadges[i].setTypeface(dlCardBadges[i].getTypeface(), android.graphics.Typeface.BOLD);
            dlCardBadges[i].setPadding(px(8), px(3), px(8), px(3));
            dlCardBadges[i].setGravity(Gravity.CENTER);
            headerRow.addView(dlCardBadges[i]);
            card.addView(headerRow);

            // Linha 2: Descrição e tamanho
            dlCardSubtexts[i] = text(f.name + " • " + humanSize(f.approxSize), 11, DIM, false);
            dlCardSubtexts[i].setPadding(0, px(4), 0, px(6));
            card.addView(dlCardSubtexts[i]);

            // Linha 3: Barra de progresso customizada
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(0);
            bar.setProgressDrawable(createProgressDrawable());
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, px(8));
            barLp.setMargins(0, px(2), 0, px(8));
            bar.setLayoutParams(barLp);
            dlCardBars[i] = bar;
            card.addView(bar);

            // Linha 4: Botão de ação individual
            Button btn = new Button(this);
            btn.setText("Baixar");
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btn.setAllCaps(false);
            btn.setPadding(px(12), px(6), px(12), px(6));
            btn.setOnClickListener(v -> onCardActionClicked(fileIndex));
            dlCardActionBtns[i] = btn;
            card.addView(btn);

            cardsList.addView(card);
        }

        box.addView(cardsList);
        dlContainer = box;
        updateDownloadUI();
        return box;
    }

    private void onCardActionClicked(int index) {
        if (!Storage.hasAccess(this)) {
            askAccess();
            return;
        }
        requestNotifPermissionIfNeeded();
        DriveDownloader.Progress[] ps = DriveDownloader.snapshot(this);
        if (index >= ps.length) return;
        DriveDownloader.Progress p = ps[index];

        if (p.isDone() || DriveDownloader.haveTarget(this, index)) {
            Toast.makeText(this, "Arquivo já instalado na pasta do jogo.", Toast.LENGTH_SHORT).show();
            updateDownloadUI();
            return;
        }

        if (p.isActive()) {
            DriveDownloader.cancelOne(this, index);
            updateDownloadUI();
        } else {
            DriveDownloader.downloadOne(this, index);
            startProgressPolling();
        }
    }

    private void checkPermissionAndDownloadAll() {
        if (DriveDownloader.allPresent(this)) {
            Toast.makeText(this, "Todos os arquivos já estão na pasta do jogo.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Storage.hasAccess(this)) {
            askAccess();
            return;
        }
        requestNotifPermissionIfNeeded();
        new AlertDialog.Builder(this)
                .setTitle("Baixar Todos os Arquivos")
                .setMessage("Deseja baixar os arquivos restantes (~1.5 GB)?\n\nRecomendado usar Wi-Fi. Você poderá acompanhar o progresso em tempo real nesta tela.")
                .setPositiveButton("Baixar Todos", (d, w) -> {
                    DriveDownloader.downloadAll(this);
                    startProgressPolling();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void updateDownloadUI() {
        DriveDownloader.Progress[] ps = DriveDownloader.snapshot(this);
        int installedCount = 0;
        long totalBytesDone = 0;
        long totalBytesTotal = 0;
        boolean anyActive = false;

        for (int i = 0; i < ps.length && i < 3; i++) {
            DriveDownloader.Progress p = ps[i];
            DriveDownloader.DriveFile f = DriveDownloader.FILES[i];
            boolean installed = DriveDownloader.haveTarget(this, i);

            if (installed) {
                installedCount++;
                totalBytesDone += (p.bytesTotal > 0 ? p.bytesTotal : f.approxSize);
                totalBytesTotal += (p.bytesTotal > 0 ? p.bytesTotal : f.approxSize);
                dlCardBadges[i].setText("✓ Pronto");
                dlCardBadges[i].setBackground(badgeDrawable(0xff2e7d32));
                dlCardSubtexts[i].setText(f.name + " • " + humanSize(p.bytesTotal > 0 ? p.bytesTotal : f.approxSize) + " (Instalado)");
                dlCardBars[i].setProgress(100);
                dlCardActionBtns[i].setText("✓ Verificado no jogo");
                dlCardActionBtns[i].setEnabled(false);
                dlCardActionBtns[i].setAlpha(0.5f);
            } else if (p.isDone()) {
                anyActive = true;
                boolean moved = DriveDownloader.moveIfFinished(this, p);
                if (moved) {
                    installedCount++;
                    dlCardBadges[i].setText("✓ Pronto");
                    dlCardBadges[i].setBackground(badgeDrawable(0xff2e7d32));
                    dlCardSubtexts[i].setText(f.name + " (Instalado com sucesso)");
                    dlCardBars[i].setProgress(100);
                    dlCardActionBtns[i].setText("✓ Verificado no jogo");
                    dlCardActionBtns[i].setEnabled(false);
                    dlCardActionBtns[i].setAlpha(0.5f);
                } else {
                    dlCardBadges[i].setText("Transferindo…");
                    dlCardBadges[i].setBackground(badgeDrawable(0xff00838f));
                    dlCardSubtexts[i].setText("Copiando para a pasta do jogo (BridgeGames)…");
                    dlCardBars[i].setProgress(99);
                    dlCardActionBtns[i].setText("Transferindo…");
                    dlCardActionBtns[i].setEnabled(false);
                    dlCardActionBtns[i].setAlpha(0.7f);
                }
            } else if (p.isActive()) {
                anyActive = true;
                totalBytesDone += p.bytesDone;
                totalBytesTotal += (p.bytesTotal > 0 ? p.bytesTotal : f.approxSize);
                dlCardBadges[i].setText("⬇ " + p.percent() + "%");
                dlCardBadges[i].setBackground(badgeDrawable(0xff00838f));
                dlCardSubtexts[i].setText(humanSize(p.bytesDone) + " / " + humanSize(p.bytesTotal > 0 ? p.bytesTotal : f.approxSize)
                        + " • " + p.percent() + "% concluído");
                dlCardBars[i].setProgress(p.percent());
                dlCardActionBtns[i].setText("✕ Cancelar");
                dlCardActionBtns[i].setEnabled(true);
                dlCardActionBtns[i].setAlpha(1.0f);
            } else if (p.isFailed()) {
                dlCardBadges[i].setText("⚠ Falhou");
                dlCardBadges[i].setBackground(badgeDrawable(0xffc62828));
                dlCardSubtexts[i].setText("Erro: " + p.formatReason());
                dlCardBars[i].setProgress(0);
                dlCardActionBtns[i].setText("↻ Tentar Novamente");
                dlCardActionBtns[i].setEnabled(true);
                dlCardActionBtns[i].setAlpha(1.0f);
            } else {
                totalBytesTotal += f.approxSize;
                dlCardBadges[i].setText("Pendente");
                dlCardBadges[i].setBackground(badgeDrawable(0xff37474f));
                dlCardSubtexts[i].setText(f.name + " • " + humanSize(f.approxSize));
                dlCardBars[i].setProgress(0);
                dlCardActionBtns[i].setText("⬇ Baixar Este Arquivo (" + humanSize(f.approxSize) + ")");
                dlCardActionBtns[i].setEnabled(true);
                dlCardActionBtns[i].setAlpha(1.0f);
            }
        }

        if (dlSummaryText != null) {
            if (installedCount >= 3) {
                dlSummaryText.setText("✓ Todos os 3 arquivos instalados! Toque em «Jogar».");
                dlSummaryText.setTextColor(Color.rgb(129, 199, 132));
                if (dlAllBtn != null) {
                    dlAllBtn.setText("✓ Todos os Arquivos Instalados");
                    dlAllBtn.setEnabled(false);
                    dlAllBtn.setAlpha(0.5f);
                }
            } else {
                long remaining = Math.max(0, totalBytesTotal - totalBytesDone);
                dlSummaryText.setText(installedCount + " de 3 arquivos instalados ("
                        + humanSize(totalBytesDone) + " / " + humanSize(totalBytesTotal) + (remaining > 0 ? " • " + humanSize(remaining) + " restantes)" : ")"));
                dlSummaryText.setTextColor(ACCENT);
                if (dlAllBtn != null) {
                    dlAllBtn.setText(anyActive ? "⬇ Baixando Arquivos…" : "⬇ Baixar Todos os Faltantes (" + (3 - installedCount) + " restantes)");
                    dlAllBtn.setEnabled(!anyActive);
                    dlAllBtn.setAlpha(anyActive ? 0.6f : 1.0f);
                }
            }
        }
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

    private void moveFiles() {
        new Thread(() -> {
            int n = Storage.migrate(getApplicationContext(), t -> runOnUiThread(() -> folderInfo.setText(t)));
            runOnUiThread(() -> {
                showFolder();
                if (n > 0) Toast.makeText(this, "Arquivos transferidos para BridgeGames: " + n, Toast.LENGTH_LONG).show();
            });
        }, "bridge-storage").start();
    }

    private void restartGame() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    // ------------------------------------------------------------ helpers

    private static String humanSize(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
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
            try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
                long length = raf.length();
                long readLength = Math.min(length, 64 * 1024);
                long startPos = length - readLength;

                raf.seek(startPos);
                byte[] buffer = new byte[(int) readLength];
                raf.readFully(buffer);

                String tail = new String(buffer, "UTF-8");
                String[] lines = tail.split("\n");
                int startLine = Math.max(0, lines.length - 120);
                if (startPos > 0 && startLine == 0) startLine = 1;
                for (int i = startLine; i < lines.length; i++) sb.append(lines[i]).append('\n');
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
