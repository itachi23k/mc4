package com.jamescrumble.mc4;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
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
 * Suporta downloads individuais, cancelamento por arquivo, persistência de ID no SharedPreferences
 * e consulta direta por setFilterById, garantindo rastreamento 100% confiável no app sem depender da barra de status.
 */
public final class DriveDownloader {
    private static final String TAG = "DriveDownloader";
    private static final String PREF_NAME = "mc4_downloads";

    /** Um arquivo para baixar com metadados detalhados para a UI. */
    public static final class DriveFile {
        public final String name;        // nome no disco (não pode ser renomeado)
        public final String id;          // fileId do Drive (o trecho entre /d/ e /view)
        public final String title;       // título legível para o usuário
        public final String desc;        // descrição do conteúdo
        public final long approxSize;    // tamanho aproximado em bytes

        public DriveFile(String name, String id, String title, String desc, long approxSize) {
            this.name = name;
            this.id = id;
            this.title = title;
            this.desc = desc;
            this.approxSize = approxSize;
        }
    }

    public static final DriveFile[] FILES = {
        new DriveFile("MC4-1.2.3e-Ultra-(ARM)-A-M.apk",
                "1VeSVTSmx-Zt5r_PJEo6-3JAurpIvnq_h",
                "APK do Jogo (v1.2.3e Ultra)",
                "Binários ARM nativos e launcher (~43 MB)",
                45_000_000L),
        new DriveFile("main.1100.com.gameloft.android.ANMP.GloftM4HM.obb",
                "183Nr5sXjztDQMGiF1v1XzR60otwFwtdq",
                "Cache Principal (main.1100.obb)",
                "Cenários, texturas e áudio do jogo (~1.25 GB)",
                1_250_000_000L),
        new DriveFile("patch.12222.com.gameloft.android.ANMP.GloftM4HM.obb",
                "1uRyPR_w6oYSAXooIbF6Uye9joBCHhSaT",
                "Patch de Atualização (patch.12222.obb)",
                "Modelos de alta fidelidade e correções (~310 MB)",
                310_000_000L),
    };

    private static final long MIN_VALID_BYTES = 1_000_000L; // 1 MB

    public static long getDownloadId(Context c, String fileName) {
        return c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getLong("dl_id_" + fileName, -1);
    }

    public static void setDownloadId(Context c, String fileName, long id) {
        c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putLong("dl_id_" + fileName, id).apply();
    }

    public static void clearDownloadId(Context c, String fileName) {
        c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().remove("dl_id_" + fileName).apply();
    }

    /** Pasta final onde o GameProcess lê. */
    public static File targetDir(Context c) {
        return Storage.mainDir(c);
    }

    /** Pasta onde o DownloadManager escreve (público e acessível sem permissão). */
    public static File downloadStagingDir() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    /** True quando o arquivo já está na pasta do jogo, completo. */
    public static boolean haveTarget(Context c, DriveFile f) {
        File local = new File(targetDir(c), f.name);
        return local.exists() && local.length() >= MIN_VALID_BYTES;
    }

    public static boolean haveTarget(Context c, int index) {
        if (index < 0 || index >= FILES.length) return false;
        return haveTarget(c, FILES[index]);
    }

    /**
     * Cancela e remove tarefas anteriores no DownloadManager com falha ou pendentes deste arquivo.
     */
    public static void cancelExisting(Context c, String fileName, String fileId) {
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        long savedId = getDownloadId(c, fileName);
        if (savedId > 0) {
            try { dm.remove(savedId); } catch (Exception ignored) {}
            clearDownloadId(c, fileName);
        }
        try (Cursor cur = dm.query(new DownloadManager.Query())) {
            if (cur != null) {
                while (cur.moveToNext()) {
                    int tIdx = cur.getColumnIndex(DownloadManager.COLUMN_TITLE);
                    String title = tIdx >= 0 ? cur.getString(tIdx) : null;
                    int uIdx = cur.getColumnIndex(DownloadManager.COLUMN_URI);
                    String uri = uIdx >= 0 ? cur.getString(uIdx) : null;
                    if ((title != null && title.equals(fileName)) || (uri != null && uri.contains(fileId))) {
                        int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_RUNNING) {
                            long id = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                            try { dm.remove(id); } catch (Exception ignored) {}
                            Log.i(TAG, "Cancelada tarefa anterior: id=" + id + " para " + fileName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Erro ao limpar tarefas anteriores: " + e.getMessage());
        }
    }

    /** Enfileira um download individual. Retorna o ID gerado, ou -1 se já existe ou falhou. */
    public static long enqueue(Context c, int index) {
        if (index < 0 || index >= FILES.length) return -1;
        DriveFile f = FILES[index];

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
                if (!staged.delete()) {
                    Log.w(TAG, "Não foi possível apagar staging incompleto antigo: " + staged);
                }
            }
        }

        cancelExisting(c, f.name, f.id);

        // Link direto do Google Drive com confirm=t para ignorar aviso de vírus e obter o binário
        Uri uri = Uri.parse("https://drive.usercontent.google.com/download?id=" + f.id + "&export=download&confirm=t");

        DownloadManager.Request req = new DownloadManager.Request(uri);
        req.setTitle(f.name);
        req.setDescription("Modern Combat 4: " + f.title);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setAllowedOverMetered(true);
        req.setAllowedOverRoaming(false);
        req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);

        req.addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; Mobile)");

        try {
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, f.name);
        } catch (IllegalStateException e) {
            Log.w(TAG, "setDestinationInExternalPublicDir falhou: " + e.getMessage() + ", prosseguindo");
        }

        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Log.e(TAG, "no DownloadManager");
            return -1;
        }
        try {
            long id = dm.enqueue(req);
            setDownloadId(c, f.name, id);
            Log.i(TAG, "queued " + f.name + " (" + f.id + ") -> id=" + id);
            return id;
        } catch (Exception e) {
            Log.e(TAG, "Exception enqueuing " + f.name + ": " + e);
            return -1;
        }
    }

    /** Baixa um único arquivo individualmente. */
    public static long downloadOne(Activity a, int index) {
        if (index < 0 || index >= FILES.length) return -1;
        DriveFile f = FILES[index];
        if (haveTarget(a, f)) {
            Toast.makeText(a, f.title + " já está instalado.", Toast.LENGTH_SHORT).show();
            return -1;
        }
        long id = enqueue(a, index);
        if (id > 0) {
            Toast.makeText(a, "Iniciando download: " + f.title, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(a, "Falha ao enfileirar: " + f.title, Toast.LENGTH_SHORT).show();
        }
        return id;
    }

    /** Cancela o download de um único arquivo. */
    public static void cancelOne(Context c, int index) {
        if (index < 0 || index >= FILES.length) return;
        DriveFile f = FILES[index];
        cancelExisting(c, f.name, f.id);
        File staged = new File(downloadStagingDir(), f.name);
        if (staged.exists()) staged.delete();
        File tmp = new File(new File(targetDir(c), f.name).getPath() + ".part");
        if (tmp.exists()) tmp.delete();
        Toast.makeText(c, "Download cancelado: " + f.title, Toast.LENGTH_SHORT).show();
    }

    /** Dispara o download de todos os arquivos que ainda faltam. */
    public static int downloadAll(Activity a) {
        int queued = 0;
        for (int i = 0; i < FILES.length; i++) {
            if (haveTarget(a, FILES[i])) continue;
            if (enqueue(a, i) >= 0) queued++;
        }
        if (queued == 0) {
            Toast.makeText(a, "Todos os arquivos já estão prontos.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(a, queued + " download(s) iniciado(s). Acompanhe o progresso.",
                    Toast.LENGTH_LONG).show();
        }
        return queued;
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
        if (target.exists() && target.length() >= MIN_VALID_BYTES) {
            clearDownloadId(c, name);
            return true;
        }

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
                Uri contentUri = Uri.parse(localUriStr);
                try (InputStream in = c.getContentResolver().openInputStream(contentUri);
                     OutputStream out = new FileOutputStream(tmp)) {
                    if (in != null) {
                        byte[] buf = new byte[1 << 17];
                        for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
                        copied = true;
                        Log.i(TAG, "Copiado via ContentResolver para " + name);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "openInputStream falhou para " + localUriStr + ": " + e.getMessage());
            }
        }

        // 3. Fallback: tentar ler direto do staging em Downloads/
        File staged = new File(downloadStagingDir(), name);
        if (!copied && staged.exists() && staged.length() >= MIN_VALID_BYTES) {
            try (InputStream in = new FileInputStream(staged);
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 17];
                for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
                copied = true;
                Log.i(TAG, "Copiado de Downloads/ para " + name);
            } catch (Exception e) {
                Log.w(TAG, "Cópia direta falhou: " + e.getMessage());
            }
        }

        if (!copied || !tmp.exists() || tmp.length() < MIN_VALID_BYTES) {
            Log.e(TAG, "Falha na transferência de " + name + " (tamanho inválido)");
            if (tmp.exists()) tmp.delete();
            return false;
        }

        if (tmp.renameTo(target)) {
            Log.i(TAG, "Transferido com sucesso para BridgeGames: " + target.getPath() + " (" + target.length() + " bytes)");
            clearDownloadId(c, name);
            if (downloadId > 0 && dm != null) {
                try { dm.remove(downloadId); } catch (Exception ignored) {}
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
            return (int) Math.min(100, Math.max(0, bytesDone * 100 / bytesTotal));
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
     * Consulta o estado atual dos arquivos com filtro direto por ID.
     * Retorna uma entrada por arquivo, na mesma ordem de FILES.
     */
    public static Progress[] snapshot(Context c) {
        Progress[] out = new Progress[FILES.length];
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        File target = targetDir(c);
        File staging = downloadStagingDir();

        for (int i = 0; i < FILES.length; i++) {
            DriveFile f = FILES[i];

            // 1. Já está na pasta final? Pronto e verificado.
            File local = new File(target, f.name);
            if (local.exists() && local.length() >= MIN_VALID_BYTES) {
                clearDownloadId(c, f.name);
                out[i] = new Progress(-1, f.name, DownloadManager.STATUS_SUCCESSFUL, 0,
                        local.length(), local.length(), null);
                continue;
            }

            // 2. Está no staging (Downloads) pronto? Pronto para mover.
            File staged = new File(staging, f.name);
            if (staged.exists() && staged.length() >= MIN_VALID_BYTES) {
                out[i] = new Progress(-1, f.name, DownloadManager.STATUS_SUCCESSFUL, 0,
                        staged.length(), staged.length(), Uri.fromFile(staged).toString());
                continue;
            }

            // 3. Consulta direta pelo DownloadId registrado
            long savedId = getDownloadId(c, f.name);
            Progress result = null;

            if (savedId > 0 && dm != null) {
                DownloadManager.Query q = new DownloadManager.Query().setFilterById(savedId);
                try (Cursor cur = dm.query(q)) {
                    if (cur != null && cur.moveToFirst()) {
                        int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        int reason = 0;
                        int rIdx = cur.getColumnIndex(DownloadManager.COLUMN_REASON);
                        if (rIdx >= 0) reason = cur.getInt(rIdx);

                        long done  = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        if (total <= 0) total = f.approxSize;

                        String localUri = null;
                        int lIdx = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                        if (lIdx >= 0) localUri = cur.getString(lIdx);

                        result = new Progress(savedId, f.name, status, reason, done, total, localUri);
                    } else {
                        // Id não existe mais no DM
                        clearDownloadId(c, f.name);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Erro ao consultar DM por ID " + savedId + ": " + e.getMessage());
                }
            }

            // 4. Fallback caso não haja ID salvo: pesquisa por título
            if (result == null && dm != null) {
                DownloadManager.Query q = new DownloadManager.Query();
                try (Cursor cur = dm.query(q)) {
                    if (cur != null) {
                        while (cur.moveToNext()) {
                            int tIdx = cur.getColumnIndex(DownloadManager.COLUMN_TITLE);
                            String title = tIdx >= 0 ? cur.getString(tIdx) : null;
                            int uIdx = cur.getColumnIndex(DownloadManager.COLUMN_URI);
                            String uri = uIdx >= 0 ? cur.getString(uIdx) : null;

                            if ((title != null && title.equals(f.name)) || (uri != null && uri.contains(f.id))) {
                                long id = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                                int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                                int reason = 0;
                                int rIdx = cur.getColumnIndex(DownloadManager.COLUMN_REASON);
                                if (rIdx >= 0) reason = cur.getInt(rIdx);

                                long done  = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                                long total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                                if (total <= 0) total = f.approxSize;

                                String localUri = null;
                                int lIdx = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                                if (lIdx >= 0) localUri = cur.getString(lIdx);

                                setDownloadId(c, f.name, id);
                                result = new Progress(id, f.name, status, reason, done, total, localUri);
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Erro no fallback DM: " + e.getMessage());
                }
            }

            out[i] = result != null ? result : new Progress(-1, f.name, 0, 0, 0, f.approxSize, null);
        }
        return out;
    }

    /** True quando algum download está ativo. */
    public static boolean anyActive(Context c) {
        for (Progress p : snapshot(c)) if (p.isActive()) return true;
        return false;
    }
}
