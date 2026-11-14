package com.muse.memorybox.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the first usable URL out of arbitrary shared text (same behaviour
 * as the VibeVault / ShareDL UrlExtractor).
 */
public final class UrlExtractor {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)\\b((?:https?://|www\\.)[^\\s<>\"'\\[\\]{}|]+)");

    private static final String TRAILING_JUNK = ".,;:!?)";

    private UrlExtractor() {
    }

    /** @return the first http(s) URL found in {@code text}, or null. */
    public static String extract(String text) {
        if (text == null) return null;
        Matcher matcher = URL_PATTERN.matcher(text.trim());
        if (!matcher.find()) return null;
        String url = matcher.group(1);
        while (url.length() > 0
                && TRAILING_JUNK.indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.regionMatches(true, 0, "www.", 0, 4)) {
            url = "https://" + url;
        }
        return url;
    }
}
