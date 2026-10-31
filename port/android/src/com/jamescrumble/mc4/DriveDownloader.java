package com.jamescrumble.mc4;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Baixa o APK e os OBBs de uma pasta pública do Google Drive.
 *
 * Compatível com Android 8 até Android 15 (Vanilla Ice Cream / API 35) e Android 16 (Baklava / API 36).
 *
 * No Android 10+, o DownloadManager não permite destinos arbitrários fora da sandbox sem erros.
 * Além disso, nos Androids 14, 15 e 16 com Scoped Storage estrito:
 * 1. O Google Drive exige confirmação de download (&confirm=t) para arquivos grandes e binários
 *    (evitando página HTML de aviso de vírus).
 * 2. O DownloadManager baixa na pasta pública DIRECTORY_DOWNLOADS e os dados concluídos
 *    são transferidos usando ParcelFileDescriptor (dm.openDownloadedFile) ou ContentResolver,
 *    garantindo que mesmo se o arquivo for renomeado pelo sistema ou o caminho direto for
 *    restrito por scoped storage, o arquivo é lido e copiado com sucesso para BridgeGames/<pkg>/.
 * 3. Após a transferência bem-sucedida, a entrada no DownloadManager e o arquivo temporário são limpos.
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

    /**
     * Cancela e remove tarefas anteriores no DownloadManager com falha ou pendentes deste arquivo,
     * para não haver conflito de nomes ou travamento no Android 14/15/16.
     */
    private static void cancelExisting(Context c, String fileId) {
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        try (Cursor cur = dm.query(new DownloadManager.Query())) {
            if (cur != null) {
                while (cur.moveToNext()) {
                    int uIdx = cur.getColumnIndex(DownloadManager.COLUMN_URI);
                    if (uIdx < 0) continue;
                    String uri = cur.getString(uIdx);
                    if (uri != null && uri.contains(fileId)) {
                        int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_PAUSED) {
                            long id = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                            dm.remove(id);
                            Log.i(TAG, "Removida tarefa anterior em falha/pausada: id=" + id);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Erro ao limpar tarefas anteriores: " + e.getMessage());
        }
    }

    /** Enfileira um download. Retorna o id, ou -1 quando o arquivo já existe ou falhou. */
    private static long enqueue(Context c, DriveFile f) {
        if (haveTarget(c, f)) {
            Log.i(TAG, "already have " + f.name);
            return -1;
        }

        // Se o arquivo já está completo no staging (Downloads), não baixa de novo.
        File staged = new File(downloadStagingDir(), f.name);
        if (staged.exists()) {
            if (staged.length() >= MIN_VALID_BYTES) {
                Log.i(TAG, "already staged " + f.name + " (" + staged.length() + " bytes)");
                return -1;
            } else {
                // Arquivo parcial/corrompido: apaga para o DownloadManager não falhar
                if (!staged.delete()) {
                    Log.w(TAG, "Não foi possível apagar staging incompleto antigo: " + staged);
                }
            }
        }

        cancelExisting(c, f.id);

        // Link direto do Google Drive com confirm=t para ignorar aviso de vírus e obter o binário
        Uri uri = Uri.parse("https://drive.usercontent.google.com/download?id=" + f.id + "&export=download&confirm=t");

        DownloadManager.Request req = new DownloadManager.Request(uri);
        req.setTitle(f.name);
        req.setDescription("Baixando dados do Modern Combat 4");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setAllowedOverMetered(true);
        req.setAllowedOverRoaming(false);
        req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);

        // Header User-Agent para compatibilidade com CDN do Google Drive
        req.addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; Mobile)");

        // Destino na pasta pública de Downloads do sistema
        try {
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, f.name);
        } catch (IllegalStateException e) {
            Log.w(TAG, "setDestinationInExternalPublicDir falhou: " + e.getMessage() + ", tentando prosseguir");
        }

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
        } catch (Exception e) {
            Log.e(TAG, "General exception enqueuing " + f.name + ": " + e);
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
            Toast.makeText(a, queued + " download(s) iniciado(s). Acompanhe o progresso.",
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
            new AlertDialog.Builder(a)
                    .setTitle("Acesso a Todos os Arquivos")
                    .setMessage("Para armazenar os dados do jogo (~1.5 GB) em BridgeGames, conceda a permissão de acesso aos arquivos.")
                    .setPositiveButton("Permitir", (d, w) -> Storage.requestAccess(a, 2))
                    .setNegativeButton("Cancelar", null)
                    .show();
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
     * Move o arquivo baixado para BridgeGames/<pkg>/.
     * Suporta Android 14, 15 e 16 usando ParcelFileDescriptor, ContentResolver e fallback de arquivo direto.
     */
    public static boolean moveIfFinished(Context c, Progress p) {
        if (p == null) return false;
        return moveIfFinished(c, p.name, p.id, p.localUri);
    }

    public static boolean moveIfFinished(Context c, String name) {
        return moveIfFinished(c, name, -1, null);
    }

    public static boolean moveIfFinished(Context c, String name, long downloadId, String localUriStr) {
        File target = new File(targetDir(c), name);
        if (target.exists() && target.length() >= MIN_VALID_BYTES) return true;

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.e(TAG, "cannot create " + parent);
            return false;
        }

        File tmp = new File(target.getPath() + ".part");
        if (tmp.exists() && !tmp.delete()) {
            Log.w(TAG, "Não foi possível apagar temp antigo: " + tmp);
        }

        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        boolean copied = false;

        // 1. Tentar abrir diretamente via DownloadManager ParcelFileDescriptor (ideal para Android 14/15/16)
        if (downloadId > 0 && dm != null) {
            try (ParcelFileDescriptor pfd = dm.openDownloadedFile(downloadId)) {
                if (pfd != null) {
                    try (InputStream in = new FileInputStream(pfd.getFileDescriptor());
                         OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[1 << 17]; // 128 KB
                        for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
                        copied = true;
                        Log.i(TAG, "Copiado via ParcelFileDescriptor para " + name);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "openDownloadedFile falhou para id=" + downloadId + ": " + e.getMessage());
            }
        }

        // 2. Se falhar, tentar abrir via ContentResolver se houver localUri (content://...)
        if (!copied && localUriStr != null && localUriStr.startsWith("content://")) {
            try {
                Uri uri = Uri.parse(localUriStr);
                try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                    if (in != null) {
                        try (OutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[1 << 17];
                            for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
                            copied = true;
                            Log.i(TAG, "Copiado via ContentResolver para " + name);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "ContentResolver falhou para " + localUriStr + ": " + e.getMessage());
            }
        }

        // 3. Fallback: tentar ler direto do sistema de arquivos em DIRECTORY_DOWNLOADS
        File staged = new File(downloadStagingDir(), name);
        if (!copied && staged.exists() && staged.length() >= MIN_VALID_BYTES) {
            try {
                Storage.copyFile(staged, tmp);
                copied = true;
                Log.i(TAG, "Copiado via Storage.copyFile de staged para " + name);
            } catch (Exception e) {
                Log.e(TAG, "copyFile falhou de staged para " + name + ": " + e.getMessage());
            }
        }

        if (!copied || tmp.length() < MIN_VALID_BYTES) {
            if (tmp.exists()) tmp.delete();
            return false;
        }

        // Renomear atomicamente de .part para o arquivo de destino final
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "could not delete old target " + target);
        }
        if (tmp.renameTo(target)) {
            Log.i(TAG, "Mover finalizado: " + name + " -> " + target + " (" + target.length() + " bytes)");
            // Limpa registro do DownloadManager para não duplicar espaço
            if (downloadId > 0 && dm != null) {
                try {
                    dm.remove(downloadId);
                } catch (Exception ignored) {}
            }
            if (staged.exists()) {
                staged.delete();
            }
            return true;
        } else {
            Log.e(TAG, "Falha ao renomear " + tmp + " para " + target);
            if (tmp.exists()) tmp.delete();
            return false;
        }
    }

    /** Estado de um download específico, pronto para a UI. */
    public static final class Progress {
        public final long id;
        public final String name;
        public final int status;          // DownloadManager.STATUS_*
        public final int reason;          // DownloadManager.ERROR_* ou PAUSED_*
        public final long bytesDone;
        public final long bytesTotal;
        public final String localUri;

        public Progress(long id, String name, int status, int reason, long done, long total, String localUri) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.reason = reason;
            this.bytesDone = done;
            this.bytesTotal = total;
            this.localUri = localUri;
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

        public String formatReason() {
            switch (reason) {
                case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                    return "Espaço insuficiente no aparelho";
                case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                    return "Arquivo já existe em Downloads";
                case DownloadManager.ERROR_CANNOT_RESUME:
                    return "Não foi possível retomar o download";
                case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                    return "Armazenamento não encontrado";
                case DownloadManager.ERROR_HTTP_DATA_ERROR:
                    return "Erro nos dados HTTP";
                case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                    return "Muitos redirecionamentos";
                case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                    return "Erro de resposta HTTP";
                default:
                    if (reason >= 400 && reason < 600) return "Erro HTTP " + reason;
                    return "Falha na conexão";
            }
        }
    }

    /**
     * Consulta o DownloadManager para o estado atual dos arquivos.
     * Retorna uma entrada por arquivo, na mesma ordem de FILES.
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
                out[i] = new Progress(-1, f.name, DownloadManager.STATUS_SUCCESSFUL, 0,
                        local.length(), local.length(), null);
                continue;
            }

            // Está no staging (Downloads) pronto? Marca como concluído (o poll move).
            File staged = new File(staging, f.name);
            if (staged.exists() && staged.length() >= MIN_VALID_BYTES) {
                out[i] = new Progress(-1, f.name, DownloadManager.STATUS_SUCCESSFUL, 0,
                        staged.length(), staged.length(), Uri.fromFile(staged).toString());
                continue;
            }

            // Senão consulta o DownloadManager.
            Progress result = null;
            if (dm != null) {
                DownloadManager.Query q = new DownloadManager.Query();
                try (Cursor cur = dm.query(q)) {
                    if (cur != null) {
                        while (cur.moveToNext()) {
                            int uIdx = cur.getColumnIndex(DownloadManager.COLUMN_URI);
                            if (uIdx < 0) continue;
                            String uri = cur.getString(uIdx);
                            if (uri == null || !uri.contains(f.id)) continue;

                            long id = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                            int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));

                            int reason = 0;
                            int rIdx = cur.getColumnIndex(DownloadManager.COLUMN_REASON);
                            if (rIdx >= 0) reason = cur.getInt(rIdx);

                            long done  = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            long total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

                            String localUri = null;
                            int lIdx = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                            if (lIdx >= 0) localUri = cur.getString(lIdx);

                            result = new Progress(id, f.name, status, reason, done, total, localUri);
                            break;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Erro ao consultar DM: " + e.getMessage());
                }
            }
            out[i] = result != null ? result : new Progress(-1, f.name, 0, 0, 0, 0, null);
        }
        return out;
    }

    /** True quando algum download está ativo. */
    public static boolean anyActive(Context c) {
        for (Progress p : snapshot(c)) if (p.isActive()) return true;
        return false;
    }
}
