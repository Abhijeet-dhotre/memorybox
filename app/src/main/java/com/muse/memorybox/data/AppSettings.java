package com.muse.memorybox.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores the user's own Groq API key on-device only (PRD §11).
 * No key is shipped inside the app.
 */
public final class AppSettings {

    private static final String PREFS = "memory_box_settings";
    private static final String KEY_GROQ = "groq_api_key";
    private static final String KEY_SEED_LOADED = "seed_loaded_v1";
    private static final String KEY_YTDLP_UPDATED = "ytdlp_update_confirmed";

    private AppSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getGroqKey(Context context) {
        return prefs(context).getString(KEY_GROQ, "");
    }

    public static void setGroqKey(Context context, String key) {
        prefs(context).edit().putString(KEY_GROQ, key == null ? "" : key.trim()).apply();
    }

    public static boolean isSeedLoaded(Context context) {
        return prefs(context).getBoolean(KEY_SEED_LOADED, false);
    }

    public static void setSeedLoaded(Context context) {
        prefs(context).edit().putBoolean(KEY_SEED_LOADED, true).apply();
    }

    /** True once a yt-dlp self-update has succeeded at least once. */
    public static boolean isYtdlpUpdateConfirmed(Context context) {
        return prefs(context).getBoolean(KEY_YTDLP_UPDATED, false);
    }

    public static void setYtdlpUpdateConfirmed(Context context) {
        prefs(context).edit().putBoolean(KEY_YTDLP_UPDATED, true).apply();
    }
}
