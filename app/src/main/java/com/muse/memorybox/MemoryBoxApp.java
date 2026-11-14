package com.muse.memorybox;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import com.muse.memorybox.data.MemoryDb;
import com.muse.memorybox.services.IngestionService;
import com.muse.memorybox.services.YtDlp;
import com.muse.memorybox.util.AppLog;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application entry point: notification channel, yt-dlp warm-up (first
 * init unpacks the bundled Python runtime), stale temp sweep (PRD §12:
 * temp audio from an interrupted job is deleted on restart), and the
 * one-time removal of the v1.0 starter examples (the user asked for no
 * default examples — the library now starts empty).
 */
public final class MemoryBoxApp extends Application {

    public static final String CHANNEL_INGESTION = "ingestion";
    public static final String CHANNEL_ALERTS = "alerts";
    private static final String TAG = "MemoryBoxApp";

    @Override
    public void onCreate() {
        super.onCreate();
        com.muse.memorybox.util.CrashReporter.install(this);
        AppLog.init(this);
        AppLog.log("Memory Box started");
        createChannel();

        ExecutorService warmup = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "memorybox-warmup");
            t.setDaemon(true);
            return t;
        });
        warmup.execute(() -> {
            sweepStaleTemp();
            // Remove the v1.0 default examples if an earlier install
            // seeded them; a no-op (one indexed query) afterwards.
            MemoryDb db = new MemoryDb(MemoryBoxApp.this);
            try {
                int removed = db.deleteSeedExamples();
                if (removed > 0) {
                    Log.i(TAG, "Removed " + removed + " default example memories");
                }
            } catch (Exception e) {
                Log.e(TAG, "Example cleanup failed", e);
            } finally {
                db.close();
            }
            try {
                YtDlp.init(MemoryBoxApp.this);
                AppLog.log("yt-dlp engine ready");
            } catch (Exception e) {
                Log.e(TAG, "yt-dlp engine warm-up failed", e);
                AppLog.log("yt-dlp engine failed to start: " + e.getMessage());
            }
            // No manual update button (the user's request): yt-dlp
            // updates itself automatically on every app start. This runs
            // on the background warm-up thread, never blocks the UI, and
            // if the device is offline the bundled version keeps working.
            boolean upToDate = YtDlp.ensureUpdated(MemoryBoxApp.this);
            Log.i(TAG, upToDate ? "yt-dlp confirmed up to date on launch"
                    : "yt-dlp auto-update could not complete on launch");
            AppLog.log(upToDate ? "yt-dlp up to date \u2014 checked automatically"
                    : "yt-dlp update couldn't complete (offline?) \u2014 will retry");
        });
        warmup.shutdown();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_INGESTION, "Saving memories",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Progress and results of Memory Box saves");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
            // High-importance channel so alerts (e.g. "update yt-dlp
            // first") actually pop up on screen.
            NotificationChannel alerts = new NotificationChannel(
                    CHANNEL_ALERTS, "Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            alerts.setDescription("Things that need your attention, like updating yt-dlp");
            if (nm != null) nm.createNotificationChannel(alerts);
        }
    }

    /** Deletes leftover ingest_* job folders from interrupted saves. */
    private void sweepStaleTemp() {
        File[] files = getCacheDir().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith("ingest_")) {
                IngestionService.deleteRecursive(f);
            }
        }
    }
}
