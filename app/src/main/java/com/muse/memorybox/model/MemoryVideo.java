package com.muse.memorybox.model;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** One saved memory: a video reduced to text + metadata (PRD §10). */
public final class MemoryVideo {
    public long id; // 0 = not persisted yet
    /** Dedupe key "extractor:videoId" — NEVER the raw URL (FR-2). */
    public String canonicalKey = "";
    public String originalUrl = "";
    /** Link to the thumbnail image — only the link is stored, never the
     *  image file (PRD §10). Empty when the source gives none. */
    public String thumbnailUrl = "";
    public String title = "";
    public String channel = "";
    /** Extractor name, e.g. "youtube", "instagram", "twitter". */
    public String platform = "";
    /** ISO date string, e.g. "2026-11-14". */
    public String dateSaved = "";
    public int durationSeconds = 0;
    /** ISO-ish language code, auto-detected, e.g. "en", "hi". */
    public String language = "";
    public String summary = "";
    public List<String> keyPoints = new ArrayList<>();
    public List<String> tags = new ArrayList<>();
    /** Dynamic category, 1–2 words, never from a fixed list (FR-9). */
    public String category = "Uncategorized";

    public static String listToJson(List<String> items) {
        JSONArray arr = new JSONArray();
        if (items != null) {
            for (String s : items) arr.put(s == null ? "" : s);
        }
        return arr.toString();
    }

    public static List<String> listFromJson(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i, ""));
            return out;
        } catch (JSONException e) {
            out.add(json);
            return out;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MemoryVideo
                && canonicalKey.equals(((MemoryVideo) o).canonicalKey);
    }

    @Override
    public int hashCode() {
        return canonicalKey.hashCode();
    }

    @Override
    public String toString() {
        return "MemoryVideo(" + canonicalKey + ", " + title + ")";
    }
}
