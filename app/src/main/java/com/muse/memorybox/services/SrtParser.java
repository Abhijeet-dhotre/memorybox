package com.muse.memorybox.services;

import com.muse.memorybox.model.Segment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses caption text (SRT + WebVTT) and Whisper verbose_json into
 * segments, with rolling-caption dedupe (YouTube auto-captions repeat the
 * tail of the previous cue). Ported 1:1 from the verified Dart parser.
 */
public final class SrtParser {

    private SrtParser() {
    }

    public static List<Segment> parseSrt(String content) {
        return dedupeRolling(parseCues(content));
    }

    public static List<Segment> parseWebVtt(String content) {
        return dedupeRolling(parseCues(content));
    }

    /** Auto-detects format: a WEBVTT header means WebVTT, else SRT. */
    public static List<Segment> parseCaptions(String content) {
        String trimmed = content == null ? "" : content.trim();
        // trimLeft equivalent
        int i = 0;
        while (i < content.length() && Character.isWhitespace(content.charAt(i))) i++;
        String left = content.substring(i);
        if (left.startsWith("WEBVTT")) return parseWebVtt(content);
        return parseSrt(content);
    }

    /** Parses the segments array of a Whisper verbose_json response. */
    public static List<Segment> parseWhisperVerboseJson(JSONObject json) {
        List<Segment> out = new ArrayList<>();
        JSONArray raw = json.optJSONArray("segments");
        if (raw == null) return out;
        for (int k = 0; k < raw.length(); k++) {
            JSONObject item = raw.optJSONObject(k);
            if (item == null) continue;
            String text = item.optString("text", "").trim();
            if (text.isEmpty()) continue;
            int start = (int) Math.round(item.optDouble("start", 0));
            int end = (int) Math.round(item.optDouble("end", start));
            if (end < start) end = start;
            out.add(new Segment(text, start, end));
        }
        return out;
    }

    public static String whisperLanguage(JSONObject json) {
        String lang = json.optString("language", "");
        return lang.isEmpty() ? null : lang;
    }

    // ------------------------------------------------------------------
    // Cue parsing (shared by SRT and WebVTT)
    // ------------------------------------------------------------------

    private static final class Cue {
        final int startSeconds;
        final int endSeconds;
        final List<String> lines;

        Cue(int s, int e, List<String> lines) {
            startSeconds = s;
            endSeconds = e;
            this.lines = lines;
        }
    }

    private static List<Cue> parseCues(String content) {
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] blocks = normalized.split("\n\\s*\n");
        List<Cue> cues = new ArrayList<>();
        for (String block : blocks) {
            String[] lines = block.split("\n", -1);
            int timingIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) {
                    timingIdx = i;
                    break;
                }
            }
            if (timingIdx < 0) continue;
            String first = lines[0].trim().toUpperCase();
            if (first.startsWith("NOTE") || first.equals("STYLE") || first.equals("REGION")) {
                continue;
            }
            int[] timing = parseTimingLine(lines[timingIdx]);
            if (timing == null) continue;
            List<String> textLines = new ArrayList<>();
            for (int i = timingIdx + 1; i < lines.length; i++) {
                String cleaned = cleanLine(lines[i]);
                if (!cleaned.isEmpty()) textLines.add(cleaned);
            }
            if (textLines.isEmpty()) continue;
            List<String> deduped = new ArrayList<>();
            for (String l : textLines) {
                if (deduped.isEmpty() || !deduped.get(deduped.size() - 1).equals(l)) {
                    deduped.add(l);
                }
            }
            cues.add(new Cue(timing[0], timing[1], deduped));
        }
        return cues;
    }

    private static int[] parseTimingLine(String line) {
        String[] parts = line.split("\\s+-->\\s+");
        if (parts.length < 2) return null;
        Integer start = parseTimestamp(parts[0].trim());
        String endToken = parts[1].trim().split("\\s+")[0];
        Integer end = parseTimestamp(endToken);
        if (start == null || end == null) return null;
        return new int[]{start, end};
    }

    private static final Pattern TIMESTAMP = Pattern.compile(
            "^(?:(\\d+):)?(\\d{1,2}):(\\d{2})(?:[.,](\\d{1,3}))?$");

    private static Integer parseTimestamp(String raw) {
        Matcher m = TIMESTAMP.matcher(raw.trim());
        if (!m.matches()) return null;
        int hours = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
        int minutes = Integer.parseInt(m.group(2));
        int seconds = Integer.parseInt(m.group(3));
        double fraction = 0.0;
        if (m.group(4) != null) {
            String frac = m.group(4);
            while (frac.length() < 3) frac = frac + "0";
            fraction = Integer.parseInt(frac) / 1000.0;
        }
        return (int) Math.round(hours * 3600 + minutes * 60 + seconds + fraction);
    }

    private static String cleanLine(String line) {
        String s = line.replaceAll("<[^>]*>", "");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return s.replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------
    // Rolling-caption dedupe
    // ------------------------------------------------------------------

    private static List<Segment> dedupeRolling(List<Cue> cues) {
        List<Segment> out = new ArrayList<>();
        String prevFullText = null;
        List<String> prevLines = new ArrayList<>();

        for (Cue cue : cues) {
            String fullText = joinSpace(cue.lines);
            String text = fullText;

            if (prevFullText != null) {
                if (text.equals(prevFullText)) {
                    extendLastEnd(out, cue.endSeconds);
                    prevLines = cue.lines;
                    continue;
                }
                if (text.startsWith(prevFullText + " ")) {
                    text = text.substring(prevFullText.length()).trim();
                } else {
                    List<String> remaining = new ArrayList<>(cue.lines);
                    while (!remaining.isEmpty() && prevLines.contains(remaining.get(0))) {
                        remaining.remove(0);
                    }
                    text = joinSpace(remaining);
                }
            }

            if (text.isEmpty()) {
                extendLastEnd(out, cue.endSeconds);
            } else if (!out.isEmpty() && out.get(out.size() - 1).text.equals(text)) {
                extendLastEnd(out, cue.endSeconds);
            } else {
                int end = Math.max(cue.endSeconds, cue.startSeconds);
                out.add(new Segment(text, cue.startSeconds, end));
            }
            prevFullText = fullText;
            prevLines = cue.lines;
        }
        return out;
    }

    private static void extendLastEnd(List<Segment> out, int end) {
        if (out.isEmpty()) return;
        Segment last = out.get(out.size() - 1);
        if (end > last.endSeconds) {
            out.set(out.size() - 1, new Segment(last.text, last.startSeconds, end));
        }
    }

    private static String joinSpace(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(l);
        }
        return sb.toString();
    }
}
