package com.muse.memorybox;

import com.muse.memorybox.util.Canonicalizer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class CanonicalizerTest {

    @Test
    public void youTubeSpellingsCollapseToOneKey() {
        String key = "youtube:dQw4w9WgXcQ";
        assertEquals(key, Canonicalizer.canonicalKey("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals(key, Canonicalizer.canonicalKey(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=abc123&t=42"));
        assertEquals(key, Canonicalizer.canonicalKey(
                "https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"));
        assertEquals(key, Canonicalizer.canonicalKey(
                "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDxyz"));
        assertEquals(key, Canonicalizer.canonicalKey(
                "https://www.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals(key, Canonicalizer.canonicalKey("youtu.be/dQw4w9WgXcQ?t=9"));
    }

    @Test
    public void differentVideosHaveDifferentKeys() {
        assertNotEquals(
                Canonicalizer.canonicalKey("https://youtu.be/AAAAAAAAAAA"),
                Canonicalizer.canonicalKey("https://youtu.be/BBBBBBBBBBB"));
    }

    @Test
    public void xAndTwitterAreTheSameVideo() {
        String a = Canonicalizer.canonicalKey("https://x.com/someone/status/1234567890");
        String b = Canonicalizer.canonicalKey(
                "https://twitter.com/someone/status/1234567890?s=20");
        assertEquals("twitter:1234567890", a);
        assertEquals(a, b);
    }

    @Test
    public void instagramAndTikTok() {
        assertEquals("instagram:C8abcXYZ12_",
                Canonicalizer.canonicalKey("https://www.instagram.com/reel/C8abcXYZ12_/"));
        assertEquals("instagram:C8abcXYZ12_",
                Canonicalizer.canonicalKey("https://www.instagram.com/p/C8abcXYZ12_/?igsh=zz"));
        assertEquals("tiktok:7312345678901234567",
                Canonicalizer.canonicalKey(
                        "https://www.tiktok.com/@user/video/7312345678901234567"));
    }

    @Test
    public void extractorNames() {
        assertEquals("youtube", Canonicalizer.extractorOf("https://youtu.be/x"));
        assertEquals("twitter", Canonicalizer.extractorOf("https://x.com/a/status/1"));
        assertEquals("instagram", Canonicalizer.extractorOf("https://instagram.com/p/x"));
        assertEquals("tiktok", Canonicalizer.extractorOf("https://www.tiktok.com/@a/video/1"));
    }
}
