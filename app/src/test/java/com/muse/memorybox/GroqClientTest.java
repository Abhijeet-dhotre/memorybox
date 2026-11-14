package com.muse.memorybox;

import com.muse.memorybox.model.Segment;
import com.muse.memorybox.services.GroqClient;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GroqClientTest {

    @Test
    public void transcriptTextUsesTimestamps() {
        List<Segment> segs = Arrays.asList(
                new Segment("hello", 0, 5),
                new Segment("world", 65, 70));
        String text = GroqClient.buildTranscriptText(segs);
        assertEquals("[0:00] hello\n[1:05] world", text);
    }

    @Test
    public void transcriptTextCapsHeadAndTail() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("word ");
        List<Segment> segs = Arrays.asList(new Segment(big.toString().trim(), 0, 600));
        String text = GroqClient.buildTranscriptText(segs, 1000);
        assertTrue(text.contains("omitted from the prompt"));
        assertTrue(text.length() < 1400);
    }

    @Test
    public void extractJsonObjectStripsFences() throws Exception {
        String raw = "```json\n{\"title\": \"X\", \"tags\": []}\n```";
        assertEquals("{\"title\": \"X\", \"tags\": []}",
                GroqClient.extractJsonObject(raw));
    }

    @Test
    public void userPromptCarriesFixedCategoriesOnly() {
        String prompt = GroqClient.buildMemoryUserPrompt("T", "C",
                Arrays.asList(new Segment("body", 0, 3)),
                Arrays.asList("AI", "Coding"));
        assertTrue(prompt.contains("Fixed categories: Entertainment, Education"));
        assertTrue(prompt.contains("Artificial Intelligence"));
        assertTrue(prompt.contains("MUST use exactly one of these"));
        assertTrue(prompt.contains("No other category is allowed"));
    }
}
