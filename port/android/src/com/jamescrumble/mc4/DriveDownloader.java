package com.jamescrumble.mc4;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import java.io.File;

/**
 * Baixa o APK e os OBBs de uma pasta pública do Google Drive para a pasta de
 * dados do jogo (BridgeGames/com.jamescrumble.mc4/), de onde o GameProcess já
 * os lê. Não usa OAuth: os arquivos são públicos, só precisamos do fileId.
 *
 * Para pegar o fileId de um arquivo no Drive:
 *   - Abra a pasta no navegador, clique com o botão direito no arquivo → "Compartilhar"
 *   - O link é https://drive.google.com/file/d/<FILE_ID>/view
 *   - O <FILE_ID> é o que vai em DriveFile.id abaixo.
 */
public final class DriveDownloader {
    private static final String TAG = "DriveDownloader";

    /** Um arquivo para baixar. O nome é o nome local (tem que bater com o que o jogo espera). */
    private static final class DriveFile {
        final String name;   // ex: "main.1100.com.gameloft.android.ANMP.GloftM4HM.obb"
        final String id;     // fileId do Drive
        DriveFile(String name, String id) { this.name = name; this.id = id; }
    }

    /**
     * Lista fixa dos arquivos da pasta. Atualize quando trocar de versão do jogo.
     * Os nomes DOS OBBs SÃO FIXOS no wrapper (ver GameProcess.GAME_PACKAGE):
     *   main.1100.<pkg>.obb  e  patch.12222.<pkg>.obb
     * O APK pode ter qualquer nome (o GameProcess pega o primeiro .apk da pasta).
     */
    private static final DriveFile[] FILES = {
        new DriveFile("mc4-1.2.3e.apk", "1AbCdEfGhIjKlMnOpQrStUvWxYz_123456"),
        new DriveFile("main.1100.com.gameloft.android.ANMP.GloftM4HM.obb", "1BcDeFgHiJkLmNoPqRsTuVwXyZa_234567"),
        new DriveFile("patch.12222.com.gameloft.android.ANMP.GloftM4HM.obb", "1CdEfGhIjKlMnOpQrStUvWxYzAb_345678"),
    };

    /** Where the game reads from: BridgeGames/<package>/. Created if missing. */
    static File targetDir(Context c) {
        return Storage.mainDir(c);
    }

    /** Starts the download of one file. Returns the DownloadManager id, or -1 on failure. */
    private static long enqueue(Context c, DriveFile f) {
        File target = targetDir(c);
        if (!target.exists() && !target.mkdirs()) {
            Log.e(TAG, "cannot create " + target);
            return -1;
        }

        // Se já existe com tamanho > 0, pula. (Se quiser revalidar por hash, é aqui.)
        File existing = new File(target, f.name);
        if (existing.exists() && existing.length() > 0) {
            Log.i(TAG, "already have " + f.name + " (" + existing.length() + " bytes), skipping");
            return -1;
        }

        Uri uri = Uri.parse("https://drive.google.com/uc?export=download&id=" + f.id);
        DownloadManager.Request req = new DownloadManager.Request(uri);
        req.setTitle(f.name);
        req.setDescription("Baixando dados do jogo");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setAllowedOverMetered(true);
        req.setAllowedOverRoaming(false);
        // O DownloadManager escreve direto na pasta do jogo. O arquivo só
        // aparece com o nome final quando o download termina sem erro.
        req.setDestinationUri(Uri.fromFile(existing));

        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Log.e(TAG, "no DownloadManager");
            return -1;
        }
        long id = dm.enqueue(req);
        Log.i(TAG, "queued " + f.name + " -> id=" + id);
        return id;
    }

    /**
     * Downloads all files (or only the missing ones). Asks the system DownloadManager
     * to do the work; progress shows in the notification shade. Completion is not
     * awaited here — the user can check the notification, and the next game start
     * picks whatever finished. (Se quiser esperar e dar um Toast no fim, veja o
     * BroadcastReceiver em onDownloadComplete().)
     */
    public static void downloadAll(Activity a) {
        int queued = 0;
        for (DriveFile f : FILES) {
            long id = enqueue(a, f);
            if (id >= 0) queued++;
        }
        if (queued == 0) {
            Toast.makeText(a, "Todos os arquivos já estão na pasta.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(a, queued + " download(s) iniciado(s). Acompanhe na barra de notificações.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Convenience: returns true when every file in FILES is already on disk. */
    public static boolean allPresent(Context c) {
        File dir = targetDir(c);
        for (DriveFile f : FILES) {
            File local = new File(dir, f.name);
            if (!local.exists() || local.length() == 0) return false;
        }
        return true;
    }
}
