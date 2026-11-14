package com.muse.memorybox.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Self-diagnostics: any crash's stack trace is saved, and the next
 * launch shows it on a fully programmatic screen (no XML inflation, so
 * the trace displays even if a layout is what crashed). The crash
 * itself still propagates to the system handler exactly as before.
 */
public final class CrashReporter {

    private static final String FILE = "last_crash.txt";
    private static final String PREFS = "crash_reporter";
    private static final String KEY_PENDING = "pending";

    private CrashReporter() {
    }

    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            saveTrace(app, error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    public static String traceToString(Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public static void saveTrace(Context ctx, Throwable error) {
        try {
            Context app = ctx.getApplicationContext();
            String trace = "Memory Box crash report\n" + traceToString(error);
            File f = new File(app.getFilesDir(), FILE);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(trace.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_PENDING, true).commit();
        } catch (Exception ignored) {
            // Never let reporting break the normal crash flow.
        }
    }

    public static boolean hasPending(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_PENDING, false)
                    && new File(ctx.getFilesDir(), FILE).exists();
        } catch (Exception e) {
            return false;
        }
    }

    private static String readAndClear(Context ctx) {
        String trace = "(no trace found)";
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] data = new byte[(int) f.length()];
                int read = in.read(data);
                trace = new String(data, 0, Math.max(read, 0),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Exception ignored) {
        }
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_PENDING, false).commit();
        } catch (Exception ignored) {
        }
        return trace;
    }

    /**
     * If a previous run crashed, replaces the activity's content with a
     * plain programmatic trace screen and returns true. Safe to call
     * before setContentView — it inflates nothing from XML.
     */
    public static boolean showPendingTrace(Activity activity) {
        if (!hasPending(activity)) return false;
        showTraceScreen(activity, readAndClear(activity));
        return true;
    }

    /** Renders the trace on a simple built-in screen (no XML). */
    public static void showTraceScreen(Activity activity, String trace) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 22, 29));
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(activity);
        title.setText("Memory Box hit an error — please screenshot this whole screen and send it");
        title.setTextColor(Color.rgb(242, 229, 138));
        title.setTextSize(16);
        root.addView(title);

        TextView body = new TextView(activity);
        body.setText(trace);
        body.setTextColor(Color.rgb(232, 237, 244));
        body.setTextSize(11);
        body.setTextIsSelectable(true);
        body.setPadding(0, pad, 0, pad);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button close = new Button(activity);
        close.setText("Close");
        close.setOnClickListener(v -> activity.finish());
        root.addView(close);

        activity.setContentView(root);
    }
}
