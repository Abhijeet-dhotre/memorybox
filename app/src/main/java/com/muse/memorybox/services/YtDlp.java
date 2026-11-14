package com.muse.memorybox.services;

import android.content.Context;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.muse.memorybox.model.Segment;

/**
 * Thin, blocking wrapper around youtubedl-android (bundled CPython +
 * yt-dlp + ffmpeg) — the on-device yt-dlp approach proven in VibeVault
 * (PRD §11.1). Every method blocks: call from a background thread.
 *
 * Path A (FR-3): fetch ONLY the caption file, no media at all.
 * Path B (FR-4): download ONLY the audio, temporarily, for transcription.
 */
public final class YtDlp {

    private static boolean initialized;

    /**
     * The bundled yt-dlp process is a shared native resource: executions
     * take turns under this lock. When several saves run in parallel,
     * their downloads/caption fetches interleave safely while the slow
     * network work (Groq transcription + summaries) still overlaps.
     */
    private static final Object EXECUTE_LOCK = new Object();

    /**
     * yt-dlp's own output lines go to the in-app log (VibeVault-style).
     * Download-progress lines are throttled to 25% steps so the log
     * stays readable; everything else is logged verbatim.
     */
    private static final kotlin.jvm.functions.Function3<Float, Long, String, kotlin.Unit>
            LOG_CALLBACK = new kotlin.jvm.functions.Function3<Float, Long, String, kotlin.Unit>() {
        private int lastProgressBucket = -1;

        @Override
        public kotlin.Unit invoke(Float progress, Long eta, String line) {
            if (line != null) {
                String text = line.trim();
                if (!text.isEmpty()) {
                    boolean progressLine = text.contains("% of")
                            || text.startsWith("[download]");
                    if (!progressLine) {
                        com.muse.memorybox.util.AppLog.log(text);
                    } else if (progress != null && progress >= 0f) {
                        int bucket = (int) (progress / 25f);
                        if (bucket != lastProgressBucket) {
                            lastProgressBucket = bucket;
                            com.muse.memorybox.util.AppLog.log(text);
                        }
                    }
                }
            }
            return kotlin.Unit.INSTANCE;
        }
    };

    private YtDlp() {
    }

    public static synchronized void init(Context context) throws YoutubeDLException {
        if (initialized) return;
        Context app = context.getApplicationContext();
        YoutubeDL.getInstance().init(app);
        FFmpeg.getInstance().init(app);
        initialized = true;
    }

    public static String versionName(Context context) {
        try {
            return YoutubeDL.getInstance().versionName(context.getApplicationContext());
        } catch (Exception e) {
            return null;
        }
    }

    /** Updates the bundled yt-dlp from GitHub (stable channel). */
    public static boolean update(Context context) throws YoutubeDLException {
        init(context);
        synchronized (EXECUTE_LOCK) {
            YoutubeDL.UpdateStatus status = YoutubeDL.getInstance().updateYoutubeDL(
                    context.getApplicationContext(), YoutubeDL.UpdateChannel.STABLE.INSTANCE);
            return status == YoutubeDL.UpdateStatus.DONE;
        }
    }

    private static final Object UPDATE_LOCK = new Object();
    private static boolean updateConfirmedThisProcess;

    /**
     * Confirms yt-dlp is current, running the self-update if it has not
     * succeeded yet in this process. A success (updated now, or already
     * the latest) is remembered in {@link AppSettings} across restarts;
     * a failure is NOT cached, so the next caller may retry.
     *
     * @return true when yt-dlp is confirmed up to date.
     */
    public static boolean ensureUpdated(Context context) {
        synchronized (UPDATE_LOCK) {
            if (updateConfirmedThisProcess) return true;
            try {
                update(context);
                updateConfirmedThisProcess = true;
                com.muse.memorybox.data.AppSettings.setYtdlpUpdateConfirmed(context);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** Equivalent of {@code yt-dlp --dump-json <url>}. */
    public static VideoInfo fetchInfo(Context context, String url)
            throws YoutubeDLException, InterruptedException, YoutubeDL.CanceledException {
        init(context);
        synchronized (EXECUTE_LOCK) {
            return YoutubeDL.getInstance().getInfo(url);
        }
    }

    /**
     * Path A: downloads only the caption/SRT file into {@code dir} and
     * parses it. Manual subs first, then auto-generated subs.
     *
     * @return parsed segments, or null when the video has no captions.
     */
    public static List<Segment> fetchCaptions(Context context, String url, File dir)
            throws YoutubeDLException, InterruptedException, YoutubeDL.CanceledException,
            IOException {
        init(context);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create temp folder: " + dir);
        }
        for (String flag : new String[]{"--write-subs", "--write-auto-subs"}) {
            Set<String> before = snapshotNames(dir);
            YoutubeDLRequest request = new YoutubeDLRequest(url);
            request.addOption("--skip-download");   // FR-3: no video, no audio
            request.addOption("--no-playlist");
            request.addOption(flag);
            request.addOption("--sub-format", "srt/vtt/best");
            request.addOption("--sub-langs", "all");
            request.addOption("-o", dir.getAbsolutePath() + "/caps.%(ext)s");
            YoutubeDLResponse response;
            synchronized (EXECUTE_LOCK) {
                response = YoutubeDL.getInstance().execute(request, "mb-caps", LOG_CALLBACK);
            }
            if (response.getExitCode() != 0) continue;
            File caps = pickCaptionFile(dir, before);
            if (caps == null) continue;
            String content = readUtf8(caps);
            List<Segment> segments = SrtParser.parseCaptions(content);
            if (!segments.isEmpty()) return segments;
        }
        return null;
    }

    /**
     * Path B: downloads only the audio into {@code dir}, extracted to a
     * small mono mp3 by the bundled ffmpeg, and returns the file. The
     * caller owns the file and MUST delete it (FR-4).
     */
    public static File downloadAudio(Context context, String url, File dir, String processId)
            throws YoutubeDLException, InterruptedException, YoutubeDL.CanceledException {
        return downloadAudioInternal(context, url, dir, processId, null);
    }

    /**
     * Path B for long videos: downloads one time section
     * [startSeconds, endSeconds) of the audio, extracted the same way.
     * Sections keep every uploaded part under Groq's file-size limit
     * (FR-6) without needing a standalone ffmpeg step.
     */
    public static File downloadAudioSection(Context context, String url, File dir,
                                            String processId, int startSeconds, int endSeconds)
            throws YoutubeDLException, InterruptedException, YoutubeDL.CanceledException {
        String section = "*" + formatHms(startSeconds) + "-" + formatHms(endSeconds);
        return downloadAudioInternal(context, url, dir, processId, section);
    }

    private static File downloadAudioInternal(Context context, String url, File dir,
                                              String processId, String section)
            throws YoutubeDLException, InterruptedException, YoutubeDL.CanceledException {
        init(context);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new YoutubeDLException("Could not create temp folder: " + dir);
        }
        Set<String> before = snapshotNames(dir);
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--no-playlist");
        request.addOption("--newline");
        request.addOption("-f", "bestaudio/best");
        request.addOption("-x");
        request.addOption("--audio-format", "mp3");
        request.addOption("--audio-quality", "5");
        // Low-bitrate mono: keeps long audio under the transcription limit.
        request.addOption("--postprocessor-args", "ffmpeg:-ac 1 -ar 16000 -b:a 32k");
        if (section != null) {
            request.addOption("--download-sections", section);
            request.addOption("--force-keyframes-at-cuts");
        }
        request.addOption("-o", dir.getAbsolutePath() + "/audio.%(ext)s");
        YoutubeDLResponse response;
        synchronized (EXECUTE_LOCK) {
            response = YoutubeDL.getInstance().execute(request, processId, LOG_CALLBACK);
        }
        if (response.getExitCode() != 0) {
            throw new YoutubeDLException(summarizeError(response.getErr()));
        }
        File result = findProducedFile(dir, before);
        if (result == null) {
            throw new YoutubeDLException("yt-dlp finished but produced no audio file");
        }
        return result;
    }

    static String formatHms(int totalSeconds) {
        int h = totalSeconds / 3600;
        int m = (totalSeconds % 3600) / 60;
        int s = totalSeconds % 60;
        return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
    }

    private static String summarizeError(String err) {
        if (err == null || err.trim().isEmpty()) return "yt-dlp failed";
        String[] lines = err.trim().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("ERROR")) return line;
        }
        return lines[lines.length - 1].trim();
    }

    private static Set<String> snapshotNames(File dir) {
        Set<String> names = new HashSet<>();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) names.add(f.getName());
        return names;
    }

    private static File pickCaptionFile(File dir, Set<String> before) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            if (!f.isFile() || before.contains(f.getName()) || f.length() == 0) continue;
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".srt") || name.endsWith(".vtt")) {
                if (best == null || f.length() > best.length()) best = f;
            }
        }
        return best;
    }

    private static File findProducedFile(File dir, Set<String> before) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            if (!f.isFile() || before.contains(f.getName()) || f.length() == 0) continue;
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".part") || name.endsWith(".tmp")
                    || name.endsWith(".ytdl") || name.endsWith(".json")) continue;
            if (best == null || f.length() > best.length()) best = f;
        }
        return best;
    }

    private static String readUtf8(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        }
    }
}
