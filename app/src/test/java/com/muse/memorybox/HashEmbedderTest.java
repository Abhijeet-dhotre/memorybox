package com.muse.memorybox;

import com.muse.memorybox.embed.HashEmbedder;
import com.muse.memorybox.util.Texts;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Parity anchors come from tools/calibration/run_calibration.py — the
 * Python mirror of this embedder (verified bit-identical to the Dart
 * engine in the §7.2 calibration record). If these break, the Java
 * embedder has drifted and the calibration numbers no longer apply.
 */
public class HashEmbedderTest {

    @Test
    public void fnvMatchesPythonMirror() {
        assertEquals(1301246302L, Texts.fnv1a32("w:claude"));
    }

    @Test
    public void embeddingMatchesPythonMirror() {
        float[] v = HashEmbedder.embed("Claude AI complete guide for beginners");
        assertEquals(384, v.length);
        int nonzero = 0;
        double sum = 0;
        for (float x : v) {
            if (x != 0f) nonzero++;
            sum += x;
        }
        assertEquals(31, nonzero);
        assertEquals(0.292979, v[15], 0.002);
        assertEquals(0.102543, v[36], 0.002);
        assertEquals(-0.102543, v[52], 0.002);
        assertEquals(-0.234383, v[86], 0.002);
        assertEquals(-0.058596, sum, 0.002);
    }

    @Test
    public void vectorsAreNormalizedAndDeterministic() {
        float[] a = HashEmbedder.embed("some transcript text about java");
        float[] b = HashEmbedder.embed("some transcript text about java");
        double norm = 0;
        for (float x : a) norm += (double) x * x;
        assertEquals(1.0, Math.sqrt(norm), 0.001);
        assertEquals(1.0, HashEmbedder.cosine(a, b), 0.0001);
    }

    @Test
    public void cosineMatchesPythonMirror() {
        float[] a = HashEmbedder.embed("Claude AI complete guide for beginners");
        float[] b = HashEmbedder.embed("Claude prompting tips and tricks");
        float[] c = HashEmbedder.embed("how to bake sourdough bread at home");
        assertEquals(0.119543, HashEmbedder.cosine(a, b), 0.002);
        assertEquals(-0.041362, HashEmbedder.cosine(a, c), 0.002);
        assertTrue(HashEmbedder.cosine(a, b) > HashEmbedder.cosine(a, c));
    }

    @Test
    public void bytesRoundTrip() {
        float[] a = HashEmbedder.embed("round trip test vector");
        float[] back = HashEmbedder.fromBytes(HashEmbedder.toBytes(a));
        assertEquals(a.length, back.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], back[i], 0.0);
        }
    }

    @Test
    public void devanagariIsOneToken() {
        assertEquals(1, Texts.tokenizeWords("क्लाउड").size());
        assertEquals("क्लाउड", Texts.tokenizeWords("क्लाउड").get(0));
    }
}
