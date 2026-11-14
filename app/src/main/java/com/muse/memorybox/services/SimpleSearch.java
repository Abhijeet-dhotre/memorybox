package com.muse.memorybox.services;

import com.muse.memorybox.model.Chunk;
import com.muse.memorybox.model.MemoryVideo;
import com.muse.memorybox.model.SearchHit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plain, fast text search (replaced the semantic/embedding search at
 * the user's request): the query words are matched literally against
 * title, tags, category, channel, summary, key points and the
 * transcript text. No embeddings, no scoring model — just weighted
 * word matching, best matches first.
 */
public final class SimpleSearch {

    private SimpleSearch() {
    }

    public static List<String> tokens(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        for (String t : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    public static List<SearchHit> search(String query, List<MemoryVideo> videos,
                                         Map<Long, List<Chunk>> transcriptByVideo) {
        List<SearchHit> hits = new ArrayList<>();
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) return hits;
        String phrase = query.trim().toLowerCase(Locale.ROOT);

        for (MemoryVideo v : videos) {
            String title = lower(v.title);
            String category = lower(v.category);
            String channel = lower(v.channel);
            String summary = lower(v.summary);
            String keyPoints = lower(join(v.keyPoints));
            String tags = lower(join(v.tags));

            int score = 0;
            boolean metaHit = false;
            if (!phrase.isEmpty() && title.contains(phrase)) {
                score += 12;
                metaHit = true;
            }
            for (String t : tokens) {
                if (title.contains(t)) { score += 6; metaHit = true; }
                if (tags.contains(t)) { score += 5; metaHit = true; }
                if (category.contains(t)) { score += 4; metaHit = true; }
                if (keyPoints.contains(t)) { score += 3; metaHit = true; }
                if (summary.contains(t)) { score += 3; metaHit = true; }
                if (channel.contains(t)) { score += 2; metaHit = true; }
            }

            // Transcript: first matching chunk gives the snippet + moment.
            Chunk matchedChunk = null;
            int strongChunks = 0;
            int transcriptScore = 0;
            List<Chunk> chunks = transcriptByVideo.get(v.id);
            if (chunks != null) {
                for (Chunk c : chunks) {
                    String text = lower(c.text);
                    boolean chunkHit = !phrase.isEmpty() && text.contains(phrase);
                    if (!chunkHit) {
                        for (String t : tokens) {
                            if (text.contains(t)) { chunkHit = true; break; }
                        }
                    }
                    if (chunkHit) {
                        strongChunks++;
                        if (matchedChunk == null) matchedChunk = c;
                    }
                }
                transcriptScore = Math.min(strongChunks * 2, 8);
            }
            score += transcriptScore;
            if (score <= 0) continue;

            String snippet;
            int timestamp = 0;
            if (matchedChunk != null) {
                snippet = snippetAround(matchedChunk.text, tokens, phrase);
                timestamp = matchedChunk.startSeconds;
            } else if (!v.summary.isEmpty()) {
                snippet = v.summary.length() > 180
                        ? v.summary.substring(0, 180) + "…" : v.summary;
            } else {
                snippet = v.title;
            }
            double normalized = Math.min(1.0, score / 20.0);
            int tier = metaHit ? 1 : 2;
            hits.add(new SearchHit(v, normalized, tier, snippet, timestamp,
                    strongChunks, metaHit ? 1.0 : 0.0, normalized));
        }

        hits.sort((a, b) -> {
            int byScore = Double.compare(b.videoScore, a.videoScore);
            if (byScore != 0) return byScore;
            return b.video.dateSaved.compareTo(a.video.dateSaved);
        });
        return hits;
    }

    /** A readable window of text around the first matched word. */
    public static String snippetAround(String text, List<String> tokens, String phrase) {
        if (text == null || text.isEmpty()) return "";
        String lowerText = text.toLowerCase(Locale.ROOT);
        int idx = phrase != null && !phrase.isEmpty() ? lowerText.indexOf(phrase) : -1;
        if (idx < 0) {
            for (String t : tokens) {
                idx = lowerText.indexOf(t);
                if (idx >= 0) break;
            }
        }
        if (idx < 0) idx = 0;
        int start = Math.max(0, idx - 50);
        int end = Math.min(text.length(), idx + 150);
        String snippet = text.substring(start, end).trim();
        if (start > 0) snippet = "…" + snippet;
        if (end < text.length()) snippet = snippet + "…";
        return snippet;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String join(List<String> parts) {
        if (parts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.toString();
    }
}
