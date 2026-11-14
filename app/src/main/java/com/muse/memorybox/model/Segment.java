package com.muse.memorybox.model;

/** One timestamped transcript segment (from captions or Whisper). */
public final class Segment {
    public final String text;
    public final int startSeconds;
    public final int endSeconds;

    public Segment(String text, int startSeconds, int endSeconds) {
        this.text = text == null ? "" : text;
        this.startSeconds = startSeconds;
        this.endSeconds = Math.max(endSeconds, startSeconds);
    }
}
