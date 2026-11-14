package com.muse.memorybox.services;

import com.muse.memorybox.model.Chunk;
import com.muse.memorybox.model.MemoryVideo;
import com.muse.memorybox.model.Segment;

import java.util.ArrayList;
import java.util.List;

/** Splits timestamped transcripts into searchable chunks (PRD §7.1). */
public final class Chunker {

    private Chunker() {
    }

    /**
     * Groups timestamped segments into ~60–120s chunks; never splits
     * mid-segment unless a single segment exceeds the window (then
     * hard-split by words, proportionally by time).
     */
    public static List<Chunk> chunkSegments(long videoId, List<Segment> segs) {
        return chunkSegments(videoId, segs, 90, 120);
    }

    public static List<Chunk> chunkSegments(long videoId, List<Segment> segs,
                                            int targetSeconds, int maxSeconds) {
        List<Chunk> chunks = new ArrayList<>();
        if (segs == null || segs.isEmpty()) return chunks;

        List<String> currentTexts = new ArrayList<>();
        Integer currentStart = null;
        Integer currentEnd = null;

        for (Segment seg : segs) {
            int segDuration = seg.endSeconds - seg.startSeconds;

            if (segDuration > maxSeconds) {
                // Flush pending, then hard-split this segment.
                if (!currentTexts.isEmpty() && currentStart != null) {
                    chunks.add(new Chunk(videoId, joinTrimmed(currentTexts),
                            currentStart, currentEnd, Chunk.KIND_TRANSCRIPT));
                    currentTexts.clear();
                    currentStart = null;
                    currentEnd = null;
                }
                chunks.addAll(splitLongSegment(videoId, seg, targetSeconds));
                continue;
            }

            if (currentTexts.isEmpty()) {
                currentStart = seg.startSeconds;
                currentEnd = seg.endSeconds;
                currentTexts.add(seg.text.trim());
                continue;
            }

            int currentDuration = currentEnd - currentStart;
            int wouldBeDuration = seg.endSeconds - currentStart;
            if (currentDuration >= targetSeconds || wouldBeDuration > maxSeconds) {
                chunks.add(new Chunk(videoId, joinTrimmed(currentTexts),
                        currentStart, currentEnd, Chunk.KIND_TRANSCRIPT));
                currentTexts.clear();
                currentStart = seg.startSeconds;
                currentEnd = seg.endSeconds;
                currentTexts.add(seg.text.trim());
            } else {
                currentTexts.add(seg.text.trim());
                currentEnd = seg.endSeconds;
            }
        }
        if (!currentTexts.isEmpty() && currentStart != null) {
            chunks.add(new Chunk(videoId, joinTrimmed(currentTexts),
                    currentStart, currentEnd, Chunk.KIND_TRANSCRIPT));
        }
        return chunks;
    }

    private static String joinTrimmed(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.toString().trim();
    }

    /** Hard-splits one over-long segment by words, proportionally by time. */
    private static List<Chunk> splitLongSegment(long videoId, Segment seg, int targetSeconds) {
        List<Chunk> result = new ArrayList<>();
        int duration = seg.endSeconds - seg.startSeconds;
        List<String> words = new ArrayList<>();
        for (String w : seg.text.trim().split("\\s+")) {
            if (!w.isEmpty()) words.add(w);
        }
        if (words.isEmpty()) {
            result.add(new Chunk(videoId, seg.text.trim(),
                    seg.startSeconds, seg.endSeconds, Chunk.KIND_TRANSCRIPT));
            return result;
        }
        int pieces = (int) Math.ceil((double) duration / targetSeconds);
        if (pieces < 1) pieces = 1;
        if (pieces > words.size()) pieces = words.size();
        int wordsPerPiece = (int) Math.ceil((double) words.size() / pieces);
        for (int i = 0; i < pieces; i++) {
            int startWord = i * wordsPerPiece;
            if (startWord >= words.size()) break;
            int endWord = Math.min(startWord + wordsPerPiece, words.size());
            boolean isLast = endWord >= words.size();
            int pieceStart = seg.startSeconds + (int) Math.round((double) duration * i / pieces);
            int pieceEnd = isLast ? seg.endSeconds
                    : seg.startSeconds + (int) Math.round((double) duration * (i + 1) / pieces);
            result.add(new Chunk(videoId,
                    joinTrimmed(words.subList(startWord, endWord)),
                    i == 0 ? seg.startSeconds : pieceStart, pieceEnd,
                    Chunk.KIND_TRANSCRIPT));
            if (isLast) break;
        }
        return result;
    }

    /**
     * Builds the extra metadata chunks (start=end=0): title, tags, summary,
     * key points — the §7.1 mangled-name defence. Empty fields are skipped.
     */
    public static List<Chunk> metadataChunks(MemoryVideo v) {
        List<Chunk> chunks = new ArrayList<>();
        addMeta(chunks, v.id, Chunk.KIND_TITLE, v.title);
        List<String> tags = new ArrayList<>();
        for (String t : v.tags) if (t != null && !t.trim().isEmpty()) tags.add(t);
        if (!tags.isEmpty()) {
            // Dart engine joins tags with ", " for the tags chunk.
            StringBuilder sb = new StringBuilder();
            for (String t : tags) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(t);
            }
            chunks.add(new Chunk(v.id, sb.toString(), 0, 0, Chunk.KIND_TAGS));
        }
        addMeta(chunks, v.id, Chunk.KIND_SUMMARY, v.summary);
        List<String> kps = new ArrayList<>();
        for (String k : v.keyPoints) if (k != null && !k.trim().isEmpty()) kps.add(k);
        addMeta(chunks, v.id, Chunk.KIND_KEY_POINTS, joinTrimmed(kps));
        return chunks;
    }

    private static void addMeta(List<Chunk> chunks, long videoId, String kind, String text) {
        if (text == null) return;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return;
        chunks.add(new Chunk(videoId, trimmed, 0, 0, kind));
    }
}
