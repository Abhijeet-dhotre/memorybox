package com.muse.memorybox.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared text primitives, ported 1:1 from the verified Dart engine so the
 * Java build produces identical tokens, hashes and embeddings.
 */
public final class Texts {

    private Texts() {
    }

    /**
     * Splits text into lowercase word tokens. A word character is any
     * Unicode letter, mark or number (so Devanagari vowel signs stay
     * attached to their base consonant — "क्लाउड" is one token).
     * Everything else is a separator.
     */
    public static List<String> tokenizeWords(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) return words;
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < lower.length()) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            boolean wordChar = type == Character.UPPERCASE_LETTER
                    || type == Character.LOWERCASE_LETTER
                    || type == Character.TITLECASE_LETTER
                    || type == Character.MODIFIER_LETTER
                    || type == Character.OTHER_LETTER
                    || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.DECIMAL_DIGIT_NUMBER
                    || type == Character.LETTER_NUMBER
                    || type == Character.OTHER_NUMBER;
            if (wordChar) {
                buf.appendCodePoint(cp);
            } else if (buf.length() > 0) {
                words.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) words.add(buf.toString());
        return words;
    }

    /** FNV-1a 32-bit hash over the UTF-8 bytes of {@code s} (unsigned, in a long). */
    public static long fnv1a32(String s) {
        long h = 2166136261L; // FNV offset basis
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h = h ^ (b & 0xFFL);
            h = (h * 16777619L) & 0xFFFFFFFFL; // FNV prime, kept in 32 bits
        }
        return h;
    }

    /** Formats whole seconds as m:ss (or h:mm:ss past an hour). */
    public static String formatTimestamp(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) return h + ":" + String.format(Locale.ROOT, "%02d:%02d", m, s);
        return m + ":" + String.format(Locale.ROOT, "%02d", s);
    }

    /** Splits text into sentences on . ! ? । and newlines. */
    public static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            buf.appendCodePoint(cp);
            if (cp == '.' || cp == '!' || cp == '?' || cp == 0x0964 || cp == '\n') {
                String s = buf.toString().trim();
                if (!s.isEmpty()) out.add(s);
                buf.setLength(0);
            }
        }
        String tail = buf.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        if (out.isEmpty() && !text.trim().isEmpty()) out.add(text.trim());
        return out;
    }

    public static String join(String sep, Iterable<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    public static String trimTo(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }
}
