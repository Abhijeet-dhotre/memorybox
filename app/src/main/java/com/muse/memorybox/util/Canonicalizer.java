package com.muse.memorybox.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Canonicalizer — FR-2 dedupe, ported 1:1 from the verified Dart engine.
 *
 * Builds the canonical dedupe key "extractor:videoId" from a URL. The raw
 * URL string is NEVER used for duplicate detection: different spellings of
 * the same video (youtu.be short link, tracking/timestamp params,
 * m./music. subdomains, x.com vs twitter.com) all produce the same key.
 */
public final class Canonicalizer {

    private Canonicalizer() {
    }

    /** Returns "extractor:videoId", e.g. "youtube:dQw4w9WgXcQ". */
    public static String canonicalKey(String url) {
        Parsed parsed = parse(url);
        if (parsed == null) {
            return "unknown:" + stableHash(url == null ? "" : url.trim());
        }
        String host = parsed.host;
        if (isYouTubeHost(host)) {
            String id = youTubeId(parsed);
            if (id != null && !id.isEmpty()) return "youtube:" + id;
            return genericKey(host, parsed, true, null);
        }
        if (isTwitterHost(host)) {
            String id = twitterId(parsed.segments);
            if (id != null && !id.isEmpty()) return "twitter:" + id;
            return genericKey("twitter", parsed, false, null);
        }
        if (isInstagramHost(host)) {
            String code = instagramCode(parsed.segments);
            if (code != null && !code.isEmpty()) return "instagram:" + code;
            return genericKey(host, parsed, true, null);
        }
        if (isTikTokHost(host)) {
            String id = tikTokId(host, parsed.segments);
            if (id != null && !id.isEmpty()) return "tiktok:" + id;
            return genericKey(host, parsed, true, null);
        }
        if (isVimeoHost(host)) {
            String id = vimeoId(parsed.segments);
            if (id != null && !id.isEmpty()) return "vimeo:" + id;
            return genericKey(host, parsed, true, null);
        }
        return genericKey(host, parsed, true, extractorOf(url));
    }

    /** Platform string stored on the video (the yt-dlp-style extractor name). */
    public static String extractorOf(String url) {
        Parsed parsed = parse(url);
        if (parsed == null) return "unknown";
        String host = parsed.host;
        if (isYouTubeHost(host)) return "youtube";
        if (isTwitterHost(host)) return "twitter";
        if (isInstagramHost(host)) return "instagram";
        if (isTikTokHost(host)) return "tiktok";
        if (isVimeoHost(host)) return "vimeo";
        return genericExtractor(host);
    }

    // ------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------

    private static final class Parsed {
        final String host; // lowercased, www. stripped
        final URI uri;
        final List<String> segments;

        Parsed(String host, URI uri, List<String> segments) {
            this.host = host;
            this.uri = uri;
            this.segments = segments;
        }
    }

    private static Parsed parse(String rawUrl) {
        if (rawUrl == null) return null;
        String input = rawUrl.trim();
        if (input.isEmpty()) return null;
        if (!input.contains("://")) {
            if (input.startsWith("//")) {
                input = "https:" + input;
            } else {
                input = "https://" + input;
            }
        }
        URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException e) {
            return null;
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        List<String> segments = new ArrayList<>();
        String path = uri.getPath() == null ? "" : uri.getPath();
        for (String s : path.split("/")) {
            String t = s.trim();
            if (!t.isEmpty()) segments.add(t);
        }
        return new Parsed(host, uri, segments);
    }

    private static String queryParam(URI uri, String name) {
        String q = uri.getRawQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (k.equals(name)) {
                String v = eq >= 0 ? pair.substring(eq + 1) : "";
                try {
                    return java.net.URLDecoder.decode(v, "UTF-8");
                } catch (Exception e) {
                    return v;
                }
            }
        }
        return null;
    }

    private static boolean hostIs(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static boolean isYouTubeHost(String host) {
        return host.equals("youtu.be") || hostIs(host, "youtube.com") || hostIs(host, "youtu.be");
    }

    private static boolean isTwitterHost(String host) {
        return hostIs(host, "twitter.com") || hostIs(host, "x.com");
    }

    private static boolean isInstagramHost(String host) {
        return hostIs(host, "instagram.com");
    }

    private static boolean isTikTokHost(String host) {
        return hostIs(host, "tiktok.com");
    }

    private static boolean isVimeoHost(String host) {
        return hostIs(host, "vimeo.com");
    }

    // ------------------------------------------------------------------
    // Per-platform id extraction. list/si/t/feature are NEVER identity.
    // ------------------------------------------------------------------

    private static String youTubeId(Parsed parsed) {
        String host = parsed.host;
        List<String> segments = parsed.segments;
        if (host.equals("youtu.be") || host.endsWith(".youtu.be")) {
            return segments.isEmpty() ? null : segments.get(0);
        }
        if (segments.isEmpty()) return queryParam(parsed.uri, "v");
        switch (segments.get(0)) {
            case "watch":
                return queryParam(parsed.uri, "v");
            case "shorts":
            case "embed":
            case "live":
            case "v":
                return segments.size() >= 2 ? segments.get(1) : null;
            default:
                return queryParam(parsed.uri, "v");
        }
    }

    private static String twitterId(List<String> segments) {
        for (int i = 0; i < segments.size(); i++) {
            String s = segments.get(i);
            if ((s.equals("status") || s.equals("statuses")) && i + 1 < segments.size()) {
                return segments.get(i + 1);
            }
        }
        return null;
    }

    private static String instagramCode(List<String> segments) {
        for (int i = 0; i < segments.size(); i++) {
            String marker = segments.get(i);
            if ((marker.equals("p") || marker.equals("reel") || marker.equals("reels")
                    || marker.equals("tv")) && i + 1 < segments.size()) {
                return segments.get(i + 1);
            }
        }
        return null;
    }

    private static String tikTokId(String host, List<String> segments) {
        if (host.equals("vm.tiktok.com") || host.equals("vt.tiktok.com")) {
            return segments.isEmpty() ? null : segments.get(0);
        }
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).equals("video") && i + 1 < segments.size()) {
                return segments.get(i + 1);
            }
        }
        return null;
    }

    private static String vimeoId(List<String> segments) {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).equals("video") && i + 1 < segments.size()) {
                return segments.get(i + 1);
            }
        }
        if (!segments.isEmpty() && isNumeric(segments.get(0))) return segments.get(0);
        return segments.isEmpty() ? null : segments.get(0);
    }

    // ------------------------------------------------------------------
    // Generic fallback
    // ------------------------------------------------------------------

    private static String genericKey(String host, Parsed parsed,
                                     boolean useHostAsPrefix, String extractorForPrefix) {
        String prefix = useHostAsPrefix ? host
                : (extractorForPrefix != null ? extractorForPrefix : host);
        List<String> segments = parsed.segments;
        if (!segments.isEmpty()) {
            String last = segments.get(segments.size() - 1);
            if (isNumeric(last) || last.length() >= 8) {
                return prefix + ":" + last;
            }
        }
        String pathKey = Texts.join("/", segments);
        String query = parsed.uri.getRawQuery() == null ? "" : parsed.uri.getRawQuery();
        String material = query.isEmpty() ? pathKey : pathKey + "?" + query;
        return prefix + ":" + stableHash(material.isEmpty() ? host : material);
    }

    private static String genericExtractor(String host) {
        List<String> parts = new ArrayList<>();
        for (String p : host.split("\\.")) if (!p.isEmpty()) parts.add(p);
        if (parts.size() >= 3) {
            String secondLast = parts.get(parts.size() - 2);
            if (secondLast.equals("co") || secondLast.equals("com")) {
                return parts.get(parts.size() - 3);
            }
        }
        if (parts.size() >= 2) return parts.get(parts.size() - 2);
        return host;
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Deterministic FNV-1a 32-bit hash, hex-encoded (8 chars). */
    public static String stableHash(String input) {
        long hash = 0x811c9dc5L;
        // Mirror the Dart port: iterate UTF-16 code units, low byte then high byte.
        for (int i = 0; i < input.length(); i++) {
            int unit = input.charAt(i);
            hash ^= unit & 0xff;
            hash = (hash * 0x01000193L) & 0xffffffffL;
            int high = (unit >> 8) & 0xff;
            if (high != 0) {
                hash ^= high;
                hash = (hash * 0x01000193L) & 0xffffffffL;
            }
        }
        String hex = Long.toHexString(hash);
        StringBuilder sb = new StringBuilder();
        for (int i = hex.length(); i < 8; i++) sb.append('0');
        sb.append(hex);
        return sb.toString();
    }
}
