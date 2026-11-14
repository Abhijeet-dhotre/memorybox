package com.muse.memorybox.model;

/** One ranked search result (PRD §7.1). */
public final class SearchHit {
    public final MemoryVideo video;
    public final double videoScore;
    /** 1, 2, or 0 = weak / suppressed-but-shown. */
    public final int tier;
    public final String matchingSentence;
    public final int matchingTimestampSeconds;
    public final int strongChunks;
    public final double metadataMatch; // 1.0 / 0.5 / 0.0
    public final double bestChunk;     // normalized per query, [0,1]

    public SearchHit(MemoryVideo video, double videoScore, int tier,
                     String matchingSentence, int matchingTimestampSeconds,
                     int strongChunks, double metadataMatch, double bestChunk) {
        this.video = video;
        this.videoScore = videoScore;
        this.tier = tier;
        this.matchingSentence = matchingSentence;
        this.matchingTimestampSeconds = matchingTimestampSeconds;
        this.strongChunks = strongChunks;
        this.metadataMatch = metadataMatch;
        this.bestChunk = bestChunk;
    }
}
