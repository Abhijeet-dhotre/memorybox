package com.muse.memorybox.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.muse.memorybox.MemoryBoxApp;
import com.muse.memorybox.R;
import com.muse.memorybox.data.AppSettings;
import com.muse.memorybox.data.MemoryDb;
import com.muse.memorybox.model.Chunk;
import com.muse.memorybox.model.MemoryDraft;
import com.muse.memorybox.model.MemoryVideo;
import com.muse.memorybox.model.Segment;
import com.muse.memorybox.model.VideoCategories;
import com.muse.memorybox.ui.MainActivity;
import com.muse.memorybox.util.Canonicalizer;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The save flow (FR-1…FR-6), as a foreground service so a long Path B
 * transcription survives the user leaving the app:
 *
 *   dedupe (canonical key) → info → Path A captions → Path B temp audio
 *   → Groq memory creation → chunk + embed → save text only.
 *
 * Temporary audio lives in a job folder under the cache dir and is
 * deleted in a finally block — on success, on failure, always (FR-4).
 * Stale job folders from interrupted runs are swept on app start.
 */
public final class IngestionService extends Service {

    private static final String TAG = "IngestionService";
    public static final String EXTRA_URL = "com.muse.memorybox.extra.URL";

    public static final String ACTION_RESULT = "com.muse.memorybox.INGESTION_RESULT";
    public static final String EXTRA_STATUS = "status"; // saved | duplicate | failed
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_TITLE = "title";

    private static final int NOTIF_ID = 1001;
    /** Section length for long-audio splitting (FR-6): 25 min at 32 kbps
     *  mono is ~6 MB — far under Groq's 25 MB cap. */
    static final int SECTION_SECONDS = 1500;

    /** How many saves run at the same time; extra saves queue up. */
    private static final int PARALLEL_JOBS = 2;
    private static final AtomicInteger ACTIVE = new AtomicInteger(0);
    private static final AtomicInteger JOB_SEQ = new AtomicInteger(0);
    private static final AtomicInteger RESULT_NOTIF_ID = new AtomicInteger(2000);
    private static final int ALERT_NOTIF_ID = 990001;
    /** Serializes database access across parallel jobs (SQLite file). */
    private static final Object DB_LOCK = new Object();

    private ExecutorService workers;

    public static boolean isRunning() {
        return ACTIVE.get() > 0;
    }

    public static int activeCount() {
        return ACTIVE.get();
    }

    /** Queues a save job for {@code url}. Always accepted — a second,
     *  third… save is never turned away or dropped. */
    public static void start(Context context, String url) {
        Intent intent = new Intent(context, IngestionService.class);
        intent.putExtra(EXTRA_URL, url);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        workers = Executors.newFixedThreadPool(PARALLEL_JOBS);
    }

    @Override
    public void onDestroy() {
        if (workers != null) workers.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String url = intent == null ? null : intent.getStringExtra(EXTRA_URL);
        startForeground(NOTIF_ID, buildNotification("Saving to Memory Box…",
                url == null ? "" : url));
        if (url == null || url.trim().isEmpty()) {
            finish("failed", "No link received.", null);
            if (ACTIVE.get() <= 0) {
                stopForeground(true);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        final String target = url.trim();
        ACTIVE.incrementAndGet();
        updateNotification("Saving to Memory Box…", target);
        com.muse.memorybox.util.AppLog.log("Save requested: " + target);
        workers.execute(() -> {
            try {
                runJob(target);
            } finally {
                if (ACTIVE.decrementAndGet() <= 0) {
                    stopForeground(true);
                    stopSelf();
                }
            }
        });
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------
    // The job
    // ------------------------------------------------------------------

    private void runJob(String url) {
        MemoryDb db = new MemoryDb(this);
        int seq = JOB_SEQ.incrementAndGet();
        File jobDir = new File(getCacheDir(),
                "ingest_" + System.currentTimeMillis() + "_" + seq);
        try {
            // 1. Dedupe FIRST, on the canonical key — never the raw link (FR-2).
            String canonicalKey = Canonicalizer.canonicalKey(url);
            boolean alreadySaved;
            synchronized (DB_LOCK) {
                alreadySaved = db.existsByCanonicalKey(canonicalKey);
            }
            if (alreadySaved) {
                finish("duplicate", "Already saved — this video is in your library.", null);
                return;
            }

            // 2. A complete save needs the user's Groq key (PRD §11).
            String groqKey = AppSettings.getGroqKey(this);
            if (groqKey.isEmpty()) {
                finish("failed",
                        "Add your Groq API key in Settings first — Memory Box uses it "
                                + "to create the summary, key points and tags.", null);
                return;
            }
            GroqClient groq = new GroqClient(groqKey);

            // 2b. yt-dlp gate (the user's rule): a save — especially a
            // background share, where the app was never opened — must not
            // run on a yt-dlp that has never been updated. The app updates
            // it automatically at startup; if that has never succeeded
            // (e.g. the first-ever use is a share with no network), pop
            // an alert telling the user to update first and stop here.
            // If it HAS been updated before, a failed re-check just means
            // we proceed with the previously updated yt-dlp.
            updateNotification("Checking yt-dlp is up to date…", url);
            if (!YtDlp.ensureUpdated(this)
                    && !AppSettings.isYtdlpUpdateConfirmed(this)) {
                com.muse.memorybox.util.AppLog.log("Save stopped \u2014 yt-dlp has never been updated");
                notifyUpdateYtdlpFirst();
                return;
            }

            // 3. Source info (title / channel / duration).
            updateNotification("Reading video info…", url);
            VideoInfo info = YtDlp.fetchInfo(this, url);
            String sourceTitle = info.getTitle() != null ? info.getTitle() : "";
            String channel = info.getUploader() != null ? info.getUploader() : "";
            com.muse.memorybox.util.AppLog.log("Video: " + sourceTitle + (channel.isEmpty() ? "" : " \u2014 " + channel));
            int duration = Math.max(0, info.getDuration());
            if (info.getId() != null && !info.getId().isEmpty()) {
                // Prefer the extractor's own id for the dedupe key.
                String resolved = Canonicalizer.extractorOf(url) + ":" + info.getId();
                boolean resolvedSaved;
                synchronized (DB_LOCK) {
                    resolvedSaved = db.existsByCanonicalKey(resolved);
                }
                if (resolvedSaved) {
                    finish("duplicate",
                            "Already saved — this video is in your library.", null);
                    return;
                }
                canonicalKey = resolved;
            }

            if (!jobDir.exists() && !jobDir.mkdirs()) {
                finish("failed", "Could not create a temp folder for this save.", null);
                return;
            }

            // 4. Transcript — Path A (captions only) …
            updateNotification("Looking for captions…", sourceTitle);
            com.muse.memorybox.util.AppLog.log("Looking for captions…");
            List<Segment> segments = YtDlp.fetchCaptions(this, url, jobDir);

            // … Path B (temporary audio → Whisper) when there are none.
            if (segments == null || segments.isEmpty()) {
                updateNotification("No captions — transcribing the audio…", sourceTitle);
                com.muse.memorybox.util.AppLog.log("No captions \u2014 transcribing the audio…");
                segments = transcribeViaAudio(url, jobDir, duration, groq, seq);
            }
            if (segments == null || segments.isEmpty()) {
                finish("failed",
                        "No transcript could be made: this video has no captions and "
                                + "its audio could not be transcribed. Nothing was saved.",
                        null);
                return;
            }
            if (duration == 0) {
                duration = segments.get(segments.size() - 1).endSeconds;
            }

            // 5. Memory creation (summary / key points / tags / category).
            updateNotification("Creating the memory…", sourceTitle);
            MemoryDraft draft = groq.createMemory(
                    sourceTitle, channel, segments, VideoCategories.ALL);

            MemoryVideo video = new MemoryVideo();
            video.canonicalKey = canonicalKey;
            video.originalUrl = info.getWebpageUrl() != null ? info.getWebpageUrl() : url;
            video.thumbnailUrl = info.getThumbnail() != null ? info.getThumbnail() : "";
            video.title = draft.title != null && !draft.title.isEmpty()
                    ? draft.title : sourceTitle;
            video.channel = channel;
            video.platform = Canonicalizer.extractorOf(url);
            video.dateSaved = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                    .format(new Date());
            video.durationSeconds = duration;
            video.language = draft.language;
            if ((video.language == null || video.language.isEmpty())
                    && groq.lastDetectedLanguage != null) {
                video.language = groq.lastDetectedLanguage;
            }
            video.summary = draft.summary;
            video.keyPoints = draft.keyPoints;
            video.tags = draft.tags;
            video.category = CategoryResolver.resolveCategory(
                    draft.category, VideoCategories.ALL);

            // 6. Chunk, embed, save — text only.
            List<Chunk> chunks = new ArrayList<>(
                    Chunker.chunkSegments(0, segments));
            chunks.addAll(Chunker.metadataChunks(video));
            long id;
            synchronized (DB_LOCK) {
                id = db.insertMemory(video, chunks);
            }
            if (id < 0) {
                finish("duplicate", "Already saved — this video is in your library.", null);
                return;
            }
            finish("saved", "Saved to Memory Box.", video.title);
        } catch (GroqClient.RateLimitException e) {
            finish("failed", e.getMessage(), null);
        } catch (Exception e) {
            Log.e(TAG, "Ingestion failed", e);
            finish("failed", "Could not save this video: " + honestReason(e), null);
        } finally {
            // FR-4: temporary audio is deleted in the same job, even on failure.
            deleteRecursive(jobDir);
            db.close();
        }
    }

    /** Path B: temp audio → (sections if long) → Whisper → stitched segments. */
    private List<Segment> transcribeViaAudio(String url, File jobDir, int duration,
                                             GroqClient groq, int jobSeq) throws Exception {
        String processId = "mb-audio-" + System.currentTimeMillis() + "-" + jobSeq;
        List<GroqClient.AudioPart> parts = new ArrayList<>();

        boolean useSections = duration > SECTION_SECONDS;
        File single = null;
        if (!useSections) {
            single = YtDlp.downloadAudio(this, url, jobDir, processId);
            if (single.length() > GroqClient.TRANSCRIPTION_MAX_BYTES && duration > 0) {
                useSections = true; // fall through to sectioned download
            } else {
                parts.add(new GroqClient.AudioPart(single, 0));
            }
        }
        if (useSections) {
            int total = duration > 0 ? duration : SECTION_SECONDS * 4;
            int index = 0;
            for (int start = 0; start < total; start += SECTION_SECONDS) {
                int end = Math.min(start + SECTION_SECONDS, total);
                File partDir = new File(jobDir, "part" + index);
                File part = YtDlp.downloadAudioSection(
                        this, url, partDir, processId + "-" + index, start, end);
                parts.add(new GroqClient.AudioPart(part, start));
                index++;
                updateNotification("Transcribing part " + index + "…", "");
            }
        }
        return groq.transcribeLongAudio(parts, null);
    }

    /** Maps engine errors to the honest, user-facing reasons of PRD §2/§5. */
    private static String honestReason(Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("private") || lower.contains("login") || lower.contains("sign in")
                || lower.contains("cookies")) {
            return "this video is private or needs a login Memory Box does not have.";
        }
        if (lower.contains("unavailable") || lower.contains("not available")
                || lower.contains("404")) {
            return "the video is unavailable at that link.";
        }
        if (lower.contains("unable to resolve") || lower.contains("network")
                || lower.contains("timeout") || lower.contains("failed to connect")) {
            return "the network could not reach the source. Check the connection and retry.";
        }
        return msg;
    }

    // ------------------------------------------------------------------
    // Notifications + result broadcast
    // ------------------------------------------------------------------

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, MemoryBoxApp.CHANNEL_INGESTION)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        int active = ACTIVE.get();
        String shownTitle = active > 1
                ? title + "  (" + active + " saves running)"
                : title;
        nm.notify(NOTIF_ID, buildNotification(shownTitle, text));
    }

    private void finish(String status, String message, String title) {
        com.muse.memorybox.util.AppLog.log("Result [" + status + "] " + (title != null ? title + " \u2014 " : "") + message);
        // Final (non-progress) notification.
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pending = PendingIntent.getActivity(this, 1, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification done = new NotificationCompat.Builder(this, MemoryBoxApp.CHANNEL_INGESTION)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("saved".equals(status)
                            ? "Memory saved" : "Memory Box")
                    .setContentText(title != null ? title : message)
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(title != null ? title + " — " + message : message))
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build();
            // Unique id per job: parallel saves never overwrite each
            // other's result notifications. The foreground (progress)
            // notification is removed by the service when the last
            // running job finishes — not here.
            nm.notify(RESULT_NOTIF_ID.incrementAndGet(), done);
        }
        broadcastResult(status, message, title);
    }

    private void broadcastResult(String status, String message, String title) {
        Intent broadcast = new Intent(ACTION_RESULT);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(EXTRA_STATUS, status);
        broadcast.putExtra(EXTRA_MESSAGE, message);
        if (title != null) broadcast.putExtra(EXTRA_TITLE, title);
        sendBroadcast(broadcast);
    }

    /**
     * Heads-up alert for a background save that cannot run because
     * yt-dlp has never been updated: tells the user to update first.
     * Tapping it opens the app (which updates yt-dlp automatically).
     */
    private void notifyUpdateYtdlpFirst() {
        String message = "Memory Box couldn't update yt-dlp, so this video "
                + "wasn't saved. Connect to the internet and open Memory Box "
                + "once — yt-dlp updates itself — then share the video again.";
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pending = PendingIntent.getActivity(this, 2, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification alert = new NotificationCompat.Builder(this, MemoryBoxApp.CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Update yt-dlp first")
                    .setContentText("Open Memory Box once with internet — "
                            + "yt-dlp updates itself — then share again.")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build();
            nm.notify(ALERT_NOTIF_ID, alert);
        }
        // If the app happens to be open, show the same message there too.
        broadcastResult("failed", message, null);
    }

    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
