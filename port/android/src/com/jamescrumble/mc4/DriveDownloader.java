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
