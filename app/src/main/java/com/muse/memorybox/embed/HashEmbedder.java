package com.muse.memorybox.embed;

import com.muse.memorybox.util.Texts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic local fallback embedder (384-dim), ported 1:1 from the
 * verified Dart HashEmbeddingService.
 *
 * HONEST LIMIT: it is NOT a semantic model. It measures lexical overlap
 * (word unigrams weight 1.0, word bigrams 0.8, character trigrams 0.35,
 * FNV-1a signed hashing into 384 dims, L2-normalized), so it cannot relate
 * synonyms with no shared words and cannot bridge scripts. Cross-language
 * recall leans on the metadata chunks, exactly as PRD §6/§7.1 anticipate.
 * The production target is quantized multilingual-e5-small via ONNX
 * (PRD §11) — see {@link OnnxEmbedderStub}. Thresholds calibrated for
 * this embedder (strong-chunk 0.25) do NOT transfer to e5-small.
 */
public final class HashEmbedder {

    public static final int DIMENSION = 384;

    private HashEmbedder() {
    }

    /** Feature extraction shared with the calibration runner. */
    public static Map<String, Double> hashFeatures(String text) {
        Map<String, Double> features = new LinkedHashMap<>();
        List<String> words = Texts.tokenizeWords(text);
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            add(features, "w:" + w, 1.0);
            if (i + 1 < words.size()) {
                add(features, "b:" + w + " " + words.get(i + 1), 0.8);
            }
            int[] cps = w.codePoints().toArray();
            if (cps.length < 3) {
                add(features, "c:" + w, 0.35);
            } else {
                for (int j = 0; j + 3 <= cps.length; j++) {
                    String tri = new String(cps, j, 3);
                    add(features, "c:" + tri, 0.35);
                }
            }
        }
        return features;
    }

    private static void add(Map<String, Double> map, String key, double weight) {
        Double cur = map.get(key);
        map.put(key, (cur == null ? 0.0 : cur) + weight);
    }

    /** Embeds text into a 384-dim L2-normalized vector. */
    public static float[] embed(String text) {
        float[] vec = new float[DIMENSION];
        for (Map.Entry<String, Double> e : hashFeatures(text).entrySet()) {
            long h = Texts.fnv1a32(e.getKey());
            int index = (int) (h % DIMENSION);
            double sign = ((h >> 16) & 1L) == 0 ? 1.0 : -1.0;
            vec[index] += (float) (sign * e.getValue());
        }
        double norm = 0.0;
        for (float v : vec) norm += (double) v * v;
        if (norm == 0.0) return vec;
        double inv = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < vec.length; i++) vec[i] = (float) (vec[i] * inv);
        return vec;
    }

    /** Cosine similarity; 0 when either vector is empty/mismatched/zero-norm. */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Encodes an embedding as little-endian Float32 bytes (sqlite-vec layout). */
    public static byte[] toBytes(float[] embedding) {
        byte[] out = new byte[embedding.length * 4];
        for (int i = 0; i < embedding.length; i++) {
            int bits = Float.floatToIntBits(embedding[i]);
            out[i * 4] = (byte) (bits & 0xFF);
            out[i * 4 + 1] = (byte) ((bits >> 8) & 0xFF);
            out[i * 4 + 2] = (byte) ((bits >> 16) & 0xFF);
            out[i * 4 + 3] = (byte) ((bits >> 24) & 0xFF);
        }
        return out;
    }

    /** Decodes little-endian Float32 bytes back into a vector. */
    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null) return new float[0];
        float[] out = new float[bytes.length / 4];
        for (int i = 0; i < out.length; i++) {
            int bits = (bytes[i * 4] & 0xFF)
                    | ((bytes[i * 4 + 1] & 0xFF) << 8)
                    | ((bytes[i * 4 + 2] & 0xFF) << 16)
                    | ((bytes[i * 4 + 3] & 0xFF) << 24);
            out[i] = Float.intBitsToFloat(bits);
        }
        return out;
    }
}
