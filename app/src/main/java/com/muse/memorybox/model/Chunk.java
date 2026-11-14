package com.muse.memorybox.model;

/**
 * One chunk of a video's transcript (or one metadata chunk).
 * Metadata kinds (title / tags / summary / keyPoints) are separate chunks
 * of the same video — the PRD §7.1 mangled-name defence: the title holds
 * the correct spelling ("Claude") even when the transcript only ever
 * says "cloud".
 */
public final class Chunk {
    public static final String KIND_TRANSCRIPT = "transcript";
    public static final String KIND_TITLE = "title";
    public static final String KIND_TAGS = "tags";
    public static final String KIND_SUMMARY = "summary";
    public static final String KIND_KEY_POINTS = "keyPoints";

    /** Order used for deterministic ranking tie-breaks (mirrors the Dart port). */
    public static int kindOrdinal(String kind) {
        if (kind == null) return 0;
        switch (kind) {
            case KIND_TRANSCRIPT: return 0;
            case KIND_TITLE: return 1;
            case KIND_TAGS: return 2;
            case KIND_SUMMARY: return 3;
            case KIND_KEY_POINTS: return 4;
            default: return 0;
        }
    }

    public long id; // 0 = not persisted yet
    /** Set by MemoryDb.insertMemory once the owning video has a real id. */
    public long videoId;
    public final String text;
    public final int startSeconds;
    public final int endSeconds;
    public final String kind;
    /** 384-dim embedding, loaded from chunk_vectors when present. */
    public float[] embedding;

    public Chunk(long videoId, String text, int startSeconds, int endSeconds, String kind) {
        this.videoId = videoId;
        this.text = text == null ? "" : text;
        this.startSeconds = startSeconds;
        this.endSeconds = endSeconds;
        this.kind = kind == null ? KIND_TRANSCRIPT : kind;
    }

    public boolean isTranscript() {
        return KIND_TRANSCRIPT.equals(kind);
    }
}
