package com.muse.memorybox.model;

import java.util.ArrayList;
import java.util.List;

/** AI output of memory creation (FR-5), produced by the Groq chat model. */
public final class MemoryDraft {
    public String title = "";
    public String summary = "";
    public List<String> keyPoints = new ArrayList<>();
    public List<String> tags = new ArrayList<>();
    /** Dynamic, 1–2 words, never from a fixed list (FR-9). */
    public String category = "Uncategorized";
    /** ISO-ish language code of the transcript, e.g. "en", "hi". */
    public String language = "en";
}
