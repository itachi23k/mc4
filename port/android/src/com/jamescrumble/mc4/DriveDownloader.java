package com.jamescrumble.mc4;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import java.io.File;

/**
 * Baixa o APK e os OBBs de uma pasta pública do Google Drive para a pasta
 * de dados do jogo (BridgeGames/com.jamescrumble.mc4/), de onde o
 * GameProcess já os lê.
 *
 * Os arquivos são públicos, então não precisamos de OAuth: basta o fileId
 * que aparece na URL do Drive.
 *
 * Para adicionar/atualizar um arquivo:
 *   1. Abra o link no navegador.
 *   2. Copie o trecho entre /d/ e /view (ex: 183Nr5sXjztDQMGiF1v1XzR60otwFwtdq).
 *   3. Atualize a entrada em FILES abaixo.
 */
public final class DriveDownloader {
    private static final String TAG = "DriveDownloader";

    /** Um arquivo para baixar. O nome local precisa bater com o que o jogo espera. */
    private static final class DriveFile {
        final String name;   // nome no disco (não pode ser renomeado)
        final String id;     // fileId do Drive (o trecho entre /d/ e /view)
        DriveFile(String name, String id) { this.name = name; this.id = id; }
    }

    /**
     * Os três arquivos do pacote.
     *   - O APK pode ter qualquer nome; o GameProcess pega o primeiro .apk da pasta.
     *   - Os OBBs precisam dos nomes canônicos que o GameProcess procura
     *     (main.1100.<pkg>.obb e patch.12222.<pkg>.obb), senão ele não acha.
     */
    private static final DriveFile[] FILES = {
        new DriveFile("MC4-1.2.3e-Ultra-(ARM)-A-M.apk",
                "1VeSVTSmx-Zt5r_PJEo6-3JAurpIvnq_h"),
        new DriveFile("main.1100.com.gameloft.android.ANMP.GloftM4HM.obb",
                "183Nr5sXjztDQMGiF1v1XzR60otwFwtdq"),
        new DriveFile("patch.12222.com.gameloft.android.ANMP.GloftM4HM.obb",
                "1uRyPR_w6oYSAXooIbF6Uye9joBCHhSaT"),
    };

    /** Tamanho mínimo para considerar "completo" (protege contra download truncado). */
    private static final long MIN_VALID_BYTES = 1_000_000L; // 1 MB

    /** Pasta onde o GameProcess lê: BridgeGames/<package>/. Cria se faltar. */
    private static File targetDir(Context c) {
        return Storage.mainDir(c);
    }

    /** Enfileira um download. Retorna o id, ou -1 quando o arquivo já existe ou falhou. */
    private static long enqueue(Context c, DriveFile f) {
        File dir = targetDir(c);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "cannot create " + dir);
            return -1;
        }

        File dest = new File(dir, f.name);
        if (dest.exists() && dest.length() >= MIN_VALID_BYTES) {
            Log.i(TAG, "already have " + f.name + " (" + dest.length() + " bytes), skipping");
            return -1;
        }

        // URL de download direto. O export=download força o Drive a mandar os bytes
        // crus em vez da página HTML de visualização.
        Uri uri = Uri.parse("https://drive.google.com/uc?export=download&id=" + f.id);

        DownloadManager.Request req = new DownloadManager.Request(uri);
        req.setTitle(f.name);
        req.setDescription("Baixando dados do jogo");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setAllowedOverMetered(true);
        req.setAllowedOverRoaming(false);
        // O DownloadManager escreve direto na pasta do jogo.
        req.setDestinationUri(Uri.fromFile(dest));

        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Log.e(TAG, "no DownloadManager");
            return -1;
        }
        long id = dm.enqueue(req);
        Log.i(TAG, "queued " + f.name + " (" + f.id + ") -> id=" + id);
        return id;
    }

    /**
     * Downloads missing files (or all of them). Asks the system DownloadManager
     * to do the work; progress shows in the notification shade. The user can
     * leave the app; the next game start picks whatever finished.
     */
    public static void downloadAll(Activity a) {
        int queued = 0;
        for (DriveFile f : FILES) {
            if (enqueue(a, f) >= 0) queued++;
        }
        if (queued == 0) {
            Toast.makeText(a, "Todos os arquivos já estão na pasta.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(a, queued + " download(s) iniciado(s). Acompanhe na barra de notificações.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Mostra a confirmação e dispara o download. Usado pelo SettingsActivity. */
    public static void confirmAndDownload(Activity a) {
        if (allPresent(a)) {
            Toast.makeText(a, "Os arquivos já estão na pasta do jogo.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Baixar dados do jogo")
                .setMessage("Vai baixar o APK e os OBBs (~1.5 GB) de uma pasta pública.\n\n"
                        + "Recomendado estar no Wi-Fi. O download continua mesmo se você sair do app.")
                .setPositiveButton("Baixar", (d, w) -> downloadAll(a))
                .setNegativeButton("Cancelar", null)
                .show();
    }

        // Container da barra de progresso (invisível por padrão).
        LinearLayout progressBox = new LinearLayout(this);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setPadding(0, px(12), 0, 0);
        progressBox.setVisibility(View.GONE);

        // Uma linha por arquivo: nome + barra + percentual.
        final TextView[] labels = new TextView[3];
        final ProgressBar[] bars = new ProgressBar[3];
        final TextView[] percents = new TextView[3];
        for (int i = 0; i < 3; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);

            labels[i] = text("", 12, FG, false);
            bars[i] = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bars[i].setMax(100);
            percents[i] = text("0%", 11, DIM, false);

            row.addView(labels[i]);
            row.addView(bars[i]);
            row.addView(percents[i]);
            progressBox.addView(row);
        }
        col.addView(progressBox);

        // Referência mutável para o runnable (precisa se re-postar).
        final boolean[] polling = { false };
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (!polling[0]) return;
            DriveDownloader.Progress[] ps = DriveDownloader.snapshot(this);
            boolean anyActive = false;
            for (int i = 0; i < ps.length; i++) {
                DriveDownloader.Progress p = ps[i];
                labels[i].setText(p.name);
                bars[i].setProgress(p.percent());
                percents[i].setText(p.percent() + "%  ("
                        + humanSize(p.bytesDone) + " / " + humanSize(p.bytesTotal) + ")");
                if (p.isActive()) anyActive = true;
                if (p.isFailed()) percents[i].setText("Falhou. Verifique a conexão.");
                if (p.isDone())  percents[i].setText("Concluído (" + humanSize(p.bytesTotal) + ")");
            }
            if (!anyActive) {
                polling[0] = false;
                progressBox.postDelayed(() -> {
                    if (DriveDownloader.allPresent(this)) {
                        progressBox.setVisibility(View.GONE);
                        Toast.makeText(this, "Todos os arquivos baixados! Inicie o jogo.",
                                Toast.LENGTH_LONG).show();
                    }
                }, 500);
                return;
            }
            progressBox.postDelayed(poll[0], 1000);
        };

        downloadBtn.setOnClickListener(v -> {
            if (DriveDownloader.allPresent(this)) {
                Toast.makeText(this, "Os arquivos já estão na pasta do jogo.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!Storage.hasAccess(this)) {
                Toast.makeText(this, "Conceda acesso a todos os arquivos primeiro.", Toast.LENGTH_LONG).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Baixar dados do jogo")
                    .setMessage("Vai baixar o APK e os OBBs (~1.5 GB) de uma pasta pública.\n\n"
                            + "Recomendado estar no Wi-Fi. O download continua mesmo se você sair do app.")
                    .setPositiveButton("Baixar", (d, w) -> {
                        progressBox.setVisibility(View.VISIBLE);
                        DriveDownloader.downloadAll(this);
                        polling[0] = true;
                        progressBox.post(poll[0]);
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
    
    /** True quando todos os arquivos já estão no disco com tamanho plausível. */
    public static boolean allPresent(Context c) {
        File dir = targetDir(c);
        for (DriveFile f : FILES) {
            File local = new File(dir, f.name);
            if (!local.exists() || local.length() < MIN_VALID_BYTES) return false;
        }
        return true;
    }
}

    /** Estado de um download específico, pronto para a UI. */
    public static final class Progress {
        public final String name;
        public final int status;          // DownloadManager.STATUS_*
        public final long bytesDone;
        public final long bytesTotal;
        public Progress(String name, int status, long done, long total) {
            this.name = name; this.status = status;
            this.bytesDone = done; this.bytesTotal = total;
        }
        /** 0..100, ou 0 quando o total é desconhecido. */
        public int percent() {
            if (bytesTotal <= 0) return 0;
            return (int) Math.min(100, bytesDone * 100 / bytesTotal);
        }
        public boolean isDone() {
            return status == DownloadManager.STATUS_SUCCESSFUL;
        }
        public boolean isActive() {
            return status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_PAUSED;
        }
        public boolean isFailed() {
            return status == DownloadManager.STATUS_FAILED;
        }
    }

    /**
     * Consulta o DownloadManager para o estado atual dos 3 arquivos.
     * Retorna uma entrada por arquivo, na mesma ordem de FILES.
     * Para arquivos já baixados antes (não enfileirados nesta sessão),
     * retorna STATUS_SUCCESSFUL com bytesDone == bytesTotal == tamanho local.
     */
    public static Progress[] snapshot(Context c) {
        Progress[] out = new Progress[FILES.length];
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        File dir = targetDir(c);

        for (int i = 0; i < FILES.length; i++) {
            DriveFile f = FILES[i];
            File local = new File(dir, f.name);

            // Se o arquivo já está completo localmente, nem consulta o DM.
            if (local.exists() && local.length() >= MIN_VALID_BYTES) {
                out[i] = new Progress(f.name, DownloadManager.STATUS_SUCCESSFUL,
                        local.length(), local.length());
                continue;
            }

            // Caso contrário, procura no DM um download ativo para essa URL.
            // O DM não nos dá o "name" do arquivo diretamente, então casamos por URI.
            DownloadManager.Query q = new DownloadManager.Query();
            // Não tem filtro por URI nativo; pegamos todos e casamos manualmente.
            // Como são só 3-5 downloads no total, é rápido.
            try (android.database.Cursor cur = dm.query(q)) {
                if (cur == null) { out[i] = new Progress(f.name, 0, 0, 0); continue; }
                boolean found = false;
                while (cur.moveToNext()) {
                    String uri = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_URI));
                    if (uri == null || !uri.contains(f.id)) continue;
                    int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long done  = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    out[i] = new Progress(f.name, status, done, total);
                    found = true;
                    break;
                }
                if (!found) out[i] = new Progress(f.name, 0, 0, 0);
            }
        }
        return out;
    }

    /** True quando algum download está ativo (para saber quando parar o polling). */
    public static boolean anyActive(Context c) {
        for (Progress p : snapshot(c)) if (p.isActive()) return true;
        return false;
    }
