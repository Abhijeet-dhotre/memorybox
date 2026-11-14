package com.muse.memorybox.services;

import com.muse.memorybox.model.MemoryDraft;
import com.muse.memorybox.model.Segment;
import com.muse.memorybox.util.Texts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Groq API over plain HTTPS (no SDK, PRD §11.1). The API key comes from
 * Settings — it is NEVER hardcoded or shipped in the app.
 *
 * Models (live-verified on the user's Groq account, Nov 2026):
 * transcription {@code whisper-large-v3-turbo}; memory creation
 * {@code openai/gpt-oss-120b}. The Llama ids used by early drafts are
 * not on the account's catalog.
 *
 * Cost rule (PRD §12): the app runs on Groq's free tier, so a 429 is a
 * first-class outcome — {@link RateLimitException} with a clear message,
 * never a silent failure.
 */
public final class GroqClient {

    public static final String TRANSCRIPTION_MODEL = "whisper-large-v3-turbo";
    public static final String CHAT_MODEL = "openai/gpt-oss-120b";
    /** Parts must stay at or under this size to fit the provider's 25 MB cap. */
    public static final long TRANSCRIPTION_MAX_BYTES = 24L * 1024 * 1024;

    private static final String BASE = "https://api.groq.com/openai/v1";

    private final String apiKey;

    public static class GroqException extends Exception {
        public GroqException(String message) {
            super(message);
        }

        public GroqException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Groq free-tier rate limit (HTTP 429) — PRD §12 cost rule. */
    public static final class RateLimitException extends GroqException {
        public final Integer retryAfterSeconds;

        public RateLimitException(Integer retryAfterSeconds) {
            super("Groq free-tier limit reached. Please wait a little and try "
                    + "saving again — nothing was charged and your library is unchanged.");
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    public GroqClient(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean hasKey() {
        return !apiKey.isEmpty();
    }

    // ------------------------------------------------------------------
    // Whisper transcription (Path B)
    // ------------------------------------------------------------------

    /**
     * Transcribes one audio file with Whisper (verbose_json segments).
     * Rejects files over {@link #TRANSCRIPTION_MAX_BYTES} — the caller
     * splits long audio into parts first (FR-6).
     */
    public List<Segment> transcribeAudio(File audio, String language)
            throws IOException, GroqException {
        if (!hasKey()) throw new GroqException("No Groq API key — add it in Settings.");
        if (audio.length() > TRANSCRIPTION_MAX_BYTES) {
            throw new GroqException("Audio part is too large for transcription ("
                    + audio.length() + " bytes). Split it first.");
        }
        String boundary = "----MemoryBoxBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = open("/audio/transcriptions");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);
        conn.setReadTimeout(300_000);

        try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
            writeField(out, boundary, "model", TRANSCRIPTION_MODEL);
            writeField(out, boundary, "response_format", "verbose_json");
            writeField(out, boundary, "timestamp_granularities[]", "segment");
            if (language != null && !language.isEmpty()) {
                writeField(out, boundary, "language", language);
            }
            // file part
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""
                    + audio.getName() + "\"\r\n");
            out.writeBytes("Content-Type: audio/mpeg\r\n\r\n");
            try (FileInputStream in = new FileInputStream(audio)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            out.writeBytes("\r\n--" + boundary + "--\r\n");
            out.flush();
        }

        JSONObject json = readJsonResponse(conn);
        List<Segment> segments = SrtParser.parseWhisperVerboseJson(json);
        String detected = SrtParser.whisperLanguage(json);
        if (detected != null) lastDetectedLanguage = detected;
        return segments;
    }

    /** Language detected by the most recent transcription, if any. */
    public volatile String lastDetectedLanguage;

    /** One audio part plus the timestamp offset its transcript needs. */
    public static final class AudioPart {
        public final File file;
        public final int offsetSeconds;

        public AudioPart(File file, int offsetSeconds) {
            this.file = file;
            this.offsetSeconds = offsetSeconds;
        }
    }

    /**
     * Transcribes pre-split parts and stitches the segments back onto the
     * original timeline by shifting each part by its offset (FR-6).
     */
    public List<Segment> transcribeLongAudio(List<AudioPart> parts, String language)
            throws IOException, GroqException {
        List<Segment> stitched = new ArrayList<>();
        for (AudioPart part : parts) {
            for (Segment s : transcribeAudio(part.file, language)) {
                stitched.add(new Segment(s.text,
                        s.startSeconds + part.offsetSeconds,
                        s.endSeconds + part.offsetSeconds));
            }
        }
        stitched.sort((a, b) -> Integer.compare(a.startSeconds, b.startSeconds));
        return stitched;
    }

    // ------------------------------------------------------------------
    // Memory creation (chat model)
    // ------------------------------------------------------------------

    public MemoryDraft createMemory(String title, String channel,
                                    List<Segment> transcript,
                                    List<String> existingCategories)
            throws IOException, GroqException {
        if (!hasKey()) throw new GroqException("No Groq API key — add it in Settings.");
        JSONObject payload = new JSONObject();
        try {
            payload.put("model", CHAT_MODEL);
            payload.put("temperature", 0.3);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", buildMemorySystemPrompt()));
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", buildMemoryUserPrompt(
                            title, channel, transcript, existingCategories)));
            payload.put("messages", messages);
        } catch (JSONException e) {
            throw new GroqException("Could not build memory request", e);
        }

        HttpURLConnection conn = open("/chat/completions");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setReadTimeout(180_000);
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
            out.write(body);
            out.flush();
        }
        JSONObject json = readJsonResponse(conn);
        try {
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new GroqException("Groq returned no completion.");
            }
            String content = choices.getJSONObject(0)
                    .getJSONObject("message").optString("content", "");
            return parseMemoryDraft(content, title);
        } catch (JSONException e) {
            throw new GroqException("Could not parse Groq memory response", e);
        }
    }

    // ------------------------------------------------------------------
    // Prompt building + response parsing (pure, unit-testable)
    // ------------------------------------------------------------------

    /** Renders a transcript as "[m:ss] text" lines, capped head+tail. */
    public static String buildTranscriptText(List<Segment> transcript) {
        return buildTranscriptText(transcript, 28000);
    }

    public static String buildTranscriptText(List<Segment> transcript, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (Segment seg : transcript) {
            if (sb.length() > 0) sb.append('\n');
            sb.append('[').append(Texts.formatTimestamp(seg.startSeconds)).append("] ")
                    .append(seg.text);
        }
        String full = sb.toString();
        if (full.length() <= maxChars) return full;
        int headLen = (int) (maxChars * 0.6);
        int tailLen = maxChars - headLen;
        return full.substring(0, headLen)
                + "\n[... middle of transcript omitted from the prompt;"
                + " the full transcript is still saved and searchable ...]\n"
                + full.substring(full.length() - tailLen);
    }

    /** The system prompt for memory creation: strict JSON only, fixed schema. */
    public static String buildMemorySystemPrompt() {
        return "You create a memory card for a saved video transcript. "
                + "Reply with a single JSON object and NOTHING else (no markdown, no "
                + "commentary). Schema: "
                + "{\"title\": string, \"summary\": string, \"keyPoints\": [string], "
                + "\"tags\": [string], \"category\": string, \"language\": string}. "
                + "Rules: title is clean and specific; summary is about 5 lines; "
                + "keyPoints are the 3-7 most useful facts or steps; tags are 3-6 "
                + "short lowercase keywords; category MUST be exactly one of the "
                + "fixed categories listed in the user prompt — never invent another "
                + "category; language is the ISO code of the transcript language "
                + "(e.g. \"en\", \"hi\"). Write the summary and key points in the same "
                + "language as the transcript.";
    }

    /** The user prompt; the model MUST choose from the fixed category list only. */
    public static String buildMemoryUserPrompt(String title, String channel,
                                               List<Segment> transcript,
                                               List<String> existingCategories) {
        String categoriesLine = "Fixed categories: "
                + Texts.join(", ", com.muse.memorybox.model.VideoCategories.ALL) + ". "
                + "You MUST use exactly one of these as \"category\". "
                + "No other category is allowed.";
        return "Video title: " + title + "\n"
                + "Channel / account: " + channel + "\n"
                + categoriesLine + "\n\n"
                + "Transcript:\n" + buildTranscriptText(transcript);
    }

    /** Extracts the JSON object out of a chat response (fences tolerated). */
    public static String extractJsonObject(String raw) throws GroqException {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) s = s.substring(firstNewline + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new GroqException("No JSON object found in model response.");
        }
        return s.substring(start, end + 1);
    }

    /** Parses a chat response into a MemoryDraft (fallback title tolerated). */
    public static MemoryDraft parseMemoryDraft(String content, String fallbackTitle)
            throws GroqException {
        JSONObject decoded;
        try {
            decoded = new JSONObject(extractJsonObject(content));
        } catch (JSONException e) {
            throw new GroqException("Memory response was not a JSON object.", e);
        }
        MemoryDraft draft = new MemoryDraft();
        draft.title = str(decoded.optString("title", ""), fallbackTitle);
        draft.summary = decoded.optString("summary", "").trim();
        draft.keyPoints = strList(decoded.has("keyPoints")
                ? decoded.opt("keyPoints") : decoded.opt("key_points"));
        draft.tags = strList(decoded.opt("tags"));
        String category = decoded.optString("category", "Uncategorized").trim()
                .replaceAll("\\s+", " ");
        draft.category = category.isEmpty() ? "Uncategorized" : category;
        String lang = decoded.optString("language", "en").trim();
        draft.language = lang.isEmpty() ? "en" : lang;
        return draft;
    }

    private static String str(String value, String fallback) {
        String s = value == null ? "" : value.trim();
        return s.isEmpty() ? (fallback == null ? "" : fallback) : s;
    }

    private static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } else if (v instanceof String && !((String) v).trim().isEmpty()) {
            out.add(((String) v).trim());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private HttpURLConnection open(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("User-Agent", "MemoryBox-Android/1.0");
        conn.setConnectTimeout(60_000);
        conn.setReadTimeout(120_000);
        return conn;
    }

    private static void writeField(DataOutputStream out, String boundary,
                                   String name, String value) throws IOException {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private JSONObject readJsonResponse(HttpURLConnection conn)
            throws IOException, GroqException {
        int code = conn.getResponseCode();
        if (code == 429) {
            String retry = conn.getHeaderField("Retry-After");
            Integer retryAfter = null;
            if (retry != null) {
                try {
                    retryAfter = Integer.parseInt(retry.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            throw new RateLimitException(retryAfter);
        }
        InputStream stream = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(stream);
        if (code < 200 || code >= 300) {
            String detail = body;
            try {
                JSONObject err = new JSONObject(body);
                detail = err.optJSONObject("error") != null
                        ? err.getJSONObject("error").optString("message", body)
                        : body;
            } catch (JSONException ignored) {
            }
            throw new GroqException(String.format(Locale.ROOT,
                    "Groq request failed (HTTP %d): %s", code, Texts.trimTo(detail, 300)));
        }
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new GroqException("Groq returned an unreadable response.", e);
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
