package com.muse.memorybox.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The in-app activity log shown at the bottom of the home screen
 * (VibeVault-style): timestamped lines for app start, yt-dlp updates,
 * saves, searches and yt-dlp's own output. Kept in memory and mirrored
 * to a small file, so background shares — which happen while the UI is
 * closed — are still visible the next time the app opens.
 */
public final class AppLog {

    private static final int MAX_LINES = 300;
    private static final List<String> LINES = new ArrayList<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static File logFile;

    private AppLog() {
    }

    public static synchronized void init(Context context) {
        if (logFile != null) return;
        logFile = new File(context.getApplicationContext().getFilesDir(), "app_log.txt");
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) LINES.add(line);
            }
            while (LINES.size() > MAX_LINES) LINES.remove(0);
        } catch (Exception ignored) {
            // No log file yet — first run.
        }
    }

    public static void log(String message) {
        if (message == null) return;
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        String line = stamp + "  " + message.replace('\n', ' ').trim();
        if (line.length() <= 9) return;
        synchronized (AppLog.class) {
            LINES.add(line);
            while (LINES.size() > MAX_LINES) LINES.remove(0);
            if (logFile != null) {
                try (FileWriter writer = new FileWriter(logFile, true)) {
                    writer.write(line);
                    writer.write("\n");
                } catch (Exception ignored) {
                    // Logging must never break the app.
                }
            }
        }
        notifyListeners();
    }

    public static synchronized String getAll() {
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) sb.append(line).append('\n');
        return sb.toString().trim();
    }

    public static void clear() {
        synchronized (AppLog.class) {
            LINES.clear();
            if (logFile != null) {
                try (FileWriter writer = new FileWriter(logFile, false)) {
                    writer.write("");
                } catch (Exception ignored) {
                    // Logging must never break the app.
                }
            }
        }
        notifyListeners();
    }

    public static void addListener(Runnable listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable listener : LISTENERS) {
            MAIN.post(listener);
        }
    }
}
