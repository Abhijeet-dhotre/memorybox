package com.muse.memorybox;

import com.muse.memorybox.model.Chunk;
import com.muse.memorybox.model.MemoryVideo;
import com.muse.memorybox.model.Segment;
import com.muse.memorybox.services.Chunker;
import com.muse.memorybox.services.SrtParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChunkerAndSrtTest {

    @Test
    public void segmentsGroupIntoRoughlyNinetySecondChunks() {
        List<Segment> segs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            segs.add(new Segment("segment " + i, i * 55, i * 55 + 55));
        }
        List<Chunk> chunks = Chunker.chunkSegments(7, segs);
        // 55s segments group 2-per-chunk under the 120s cap.
        assertEquals(3, chunks.size());
        assertEquals(0, chunks.get(0).startSeconds);
        assertEquals(110, chunks.get(0).endSeconds);
        assertEquals(7, chunks.get(0).videoId);
        assertTrue(chunks.get(0).text.contains("segment 0"));
        assertTrue(chunks.get(0).text.contains("segment 1"));
    }

    @Test
    public void overLongSegmentIsHardSplit() {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 200; i++) words.append("word").append(i).append(' ');
        List<Chunk> chunks = Chunker.chunkSegments(1,
                Arrays.asList(new Segment(words.toString().trim(), 0, 300)));
        assertTrue(chunks.size() >= 3);
        assertEquals(0, chunks.get(0).startSeconds);
        assertEquals(300, chunks.get(chunks.size() - 1).endSeconds);
    }

    @Test
    public void metadataChunksSkipEmptyFields() {
        MemoryVideo v = new MemoryVideo();
        v.id = 5;
        v.title = "A Title";
        v.tags = Arrays.asList("one", "two");
        v.summary = "";
        v.keyPoints = new ArrayList<>();
        List<Chunk> meta = Chunker.metadataChunks(v);
        assertEquals(2, meta.size()); // title + tags only
        assertEquals(Chunk.KIND_TITLE, meta.get(0).kind);
        assertEquals("one, two", meta.get(1).text);
    }

    @Test
    public void srtParses() {
        String srt = "1\n00:00:01,000 --> 00:00:04,000\nHello everyone\n\n"
                + "2\n00:00:04,500 --> 00:00:08,000\nwelcome back to the channel\n";
        List<Segment> segs = SrtParser.parseSrt(srt);
        assertEquals(2, segs.size());
        assertEquals("Hello everyone", segs.get(0).text);
        assertEquals(1, segs.get(0).startSeconds);
        assertEquals(5, segs.get(1).startSeconds); // 4.5 rounds to 5
    }

    @Test
    public void vttParsesAndStripsTags() {
        String vtt = "WEBVTT\n\n00:01.500 --> 00:04.000 align:start position:0%\n"
                + "<v Speaker>Hi there</v>\n";
        List<Segment> segs = SrtParser.parseWebVtt(vtt);
        assertEquals(1, segs.size());
        assertEquals("Hi there", segs.get(0).text);
        assertEquals(2, segs.get(0).startSeconds);
    }

    @Test
    public void rollingCaptionsAreDeduped() {
        String srt = "1\n00:00:00,000 --> 00:00:02,000\nHello\n\n"
                + "2\n00:00:02,000 --> 00:00:04,000\nHello everyone\n\n"
                + "3\n00:00:04,000 --> 00:00:06,000\nHello everyone welcome back\n";
        List<Segment> segs = SrtParser.parseSrt(srt);
        StringBuilder all = new StringBuilder();
        for (Segment s : segs) all.append(s.text).append('|');
        assertEquals("Hello|everyone|welcome back|", all.toString());
    }
}
