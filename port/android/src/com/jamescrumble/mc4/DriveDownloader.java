package com.jamescrumble.mc4;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import java.io.File;

/**
 * Baixa o APK e os OBBs de uma pasta pública do Google Drive.
 *
 * No Android 10+, o DownloadManager NÃO aceita setDestinationUri apontando para
 * /sdcard/... (nem com MANAGE_EXTERNAL_STORAGE), e crasha o app. A solução é
 * deixar o DownloadManager gravar na pasta pública de Downloads (que ele gerencia
 * sem permissão extra) e depois mover o arquivo para BridgeGames/<pkg>/ via
 * Storage.copyFile.
 */
public final class DriveDownloader {
    private static final String TAG = "DriveDownloader";

    /** Um arquivo para baixar. O nome local precisa bater com o que o jogo espera. */
    private static final class DriveFile {
        final String name;   // nome no disco (não pode ser renomeado)
        final String id;     // fileId do Drive (o trecho entre /d/ e /view)
        DriveFile(String name, String id) { this.name = name; this.id = id; }
    }

    private static final DriveFile[] FILES = {
        new DriveFile("MC4-1.2.3e-Ultra-(ARM)-A-M.apk",
                "1VeSVTSmx-Zt5r_PJEo6-3JAurpIvnq_h"),
        new DriveFile("main.1100.com.gameloft.android.ANMP.GloftM4HM.obb",
                "183Nr5sXjztDQMGiF1v1XzR60otwFwtdq"),
        new DriveFile("patch.12222.com.gameloft.android.ANMP.GloftM4HM.obb",
                "1uRyPR_w6oYSAXooIbF6Uye9joBCHhSaT"),
    };

    private static final long MIN_VALID_BYTES = 1_000_000L; // 1 MB

    /** Pasta final onde o GameProcess lê. */
    static File targetDir(Context c) {
        return Storage.mainDir(c);
    }

    /** Pasta onde o DownloadManager escreve (público e acessível sem permissão). */
    private static File downloadStagingDir() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    /** True quando o arquivo já está na pasta do jogo, completo. */
    private static boolean haveTarget(Context c, DriveFile f) {
        File local = new File(targetDir(c), f.name);
        return local.exists() && local.length() >= MIN_VALID_BYTES;
    }

    /** Enfileira um download. Retorna o id, ou -1 quando o arquivo já existe ou falhou. */
    private static long enqueue(Context c, DriveFile f) {
        if (haveTarget(c, f)) {
            Log.i(TAG, "already have " + f.name);
            return -1;
        }

        // Se o arquivo já está completo no staging (Downloads), não baixa de novo.
        File staged = new File(downloadStagingDir(), f.name);
        if (staged.exists() && staged.length() >= MIN_VALID_BYTES) {
            Log.i(TAG, "already staged " + f.name + " (" + staged.length() + " bytes)");
            return -1;
        }

        Uri uri = Uri.parse("https://drive.google.com/uc?export=download&id=" + f.id);

        DownloadManager.Request req = new DownloadManager.Request(uri);
        req.setTitle(f.name);
        req.setDescription("Baixando dados do jogo");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setAllowedOverMetered(true);
        req.setAllowedOverRoaming(false);

        // IMPORTANTE: destino é a pasta pública de Downloads, que o DownloadManager
        // controla sozinho. setDestinationUri(/sdcard/BridgeGames/...) crasha no
        // Android 10+, porque o DM não tem permissão de escrita fora da sua sandbox.
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, f.name);

        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Log.e(TAG, "no DownloadManager");
            return -1;
        }
        try {
            long id = dm.enqueue(req);
            Log.i(TAG, "queued " + f.name + " (" + f.id + ") -> id=" + id);
            return id;
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException enqueuing " + f.name + ": " + e);
            return -1;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "IllegalArgumentException enqueuing " + f.name + ": " + e);
            return -1;
        }
    }

    /** Dispara o download dos arquivos que ainda faltam. */
    public static void downloadAll(Activity a) {
        int queued = 0;
        for (DriveFile f : FILES) {
            if (enqueue(a, f) >= 0) queued++;
        }
        if (queued == 0) {
            Toast.makeText(a, "Todos os arquivos já estão prontos.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(a, queued + " download(s) iniciado(s). Acompanhe na barra de notificações.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Mostra a confirmação e dispara o download. */
    public static void confirmAndDownload(Activity a) {
        if (allPresent(a)) {
            Toast.makeText(a, "Os arquivos já estão na pasta do jogo.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Storage.hasAccess(a)) {
            Toast.makeText(a, "Conceda acesso a todos os arquivos primeiro.", Toast.LENGTH_LONG).show();
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

    /** True quando todos os arquivos já estão na pasta final com tamanho plausível. */
    public static boolean allPresent(Context c) {
        for (DriveFile f : FILES) if (!haveTarget(c, f)) return false;
        return true;
    }

    /**
     * Se o DownloadManager terminou o arquivo (ele está em /sdcard/Download/<nome>),
     * move para BridgeGames/<pkg>/. Retorna true quando o arquivo está na pasta final.
     * Chamado pelo poll do SettingsActivity.
     */
    public static boolean moveIfFinished(Context c, String name) {
        File target = new File(targetDir(c), name);
        if (target.exists() && target.length() >= MIN_VALID_BYTES) return true;

        File staged = new File(downloadStagingDir(), name);
        if (!staged.exists() || staged.length() < MIN_VALID_BYTES) return false;

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.e(TAG, "cannot create " + parent);
            return false;
        }
        try {
            Storage.copyFile(staged, target);
            if (target.length() >= MIN_VALID_BYTES) {
                // Apaga o staging só depois de confirmar que a cópia tem tamanho bom.
                if (!staged.delete()) Log.w(TAG, "could not delete staged " + staged);
                Log.i(TAG, "moved " + name + " -> " + target);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "move failed for " + name + ": " + e);
        }
        return false;
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
     * Consulta o DownloadManager para o estado atual dos arquivos.
     * Retorna uma entrada por arquivo, na mesma ordem de FILES.
     *   - Se já está na pasta final: STATUS_SUCCESSFUL.
     *   - Se está em staging (Downloads) pronto: STATUS_SUCCESSFUL (o moveIfFinished
     *     será chamado pelo poll e vai copiar).
     *   - Senão, consulta o DM e devolve o status atual.
     */
    public static Progress[] snapshot(Context c) {
        Progress[] out = new Progress[FILES.length];
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        File target = targetDir(c);
        File staging = downloadStagingDir();

        for (int i = 0; i < FILES.length; i++) {
            DriveFile f = FILES[i];

            // Já está na pasta final? Pronto.
            File local = new File(target, f.name);
            if (local.exists() && local.length() >= MIN_VALID_BYTES) {
                out[i] = new Progress(f.name, DownloadManager.STATUS_SUCCESSFUL,
                        local.length(), local.length());
                continue;
            }

            // Está no staging (Downloads) pronto? Marca como concluído (o poll move).
            File staged = new File(staging, f.name);
            if (staged.exists() && staged.length() >= MIN_VALID_BYTES) {
                out[i] = new Progress(f.name, DownloadManager.STATUS_SUCCESSFUL,
                        staged.length(), staged.length());
                continue;
            }

            // Senão consulta o DM.
            DownloadManager.Query q = new DownloadManager.Query();
            Progress result = null;
            try (Cursor cur = dm.query(q)) {
                if (cur != null) {
                    while (cur.moveToNext()) {
                        String uri = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_URI));
                        if (uri == null || !uri.contains(f.id)) continue;
                        int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long done  = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        result = new Progress(f.name, status, done, total);
                        break;
                    }
                }
            }
            out[i] = result != null ? result : new Progress(f.name, 0, 0, 0);
        }
        return out;
    }

    /** True quando algum download está ativo. */
    public static boolean anyActive(Context c) {
        for (Progress p : snapshot(c)) if (p.isActive()) return true;
        return false;
    }
}
