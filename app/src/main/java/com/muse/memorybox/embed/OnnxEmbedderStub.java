package com.muse.memorybox.embed;

/**
 * Production embedder (PRD §11): quantized multilingual-e5-small on
 * device. NOT wired up in V1 — the app ships {@link HashEmbedder}.
 *
 * Integration point for the real model:
 *   1. Bundle the quantized multilingual-e5-small ONNX model (384-dim).
 *   2. Load it with the ONNX runtime, tokenize with the model's WordPiece
 *      tokenizer (prefix queries with "query: " and passages with
 *      "passage: " as e5 requires).
 *   3. Mean-pool over the attention mask and L2-normalize.
 *   4. Re-run tools/calibration (PRD §7.2 regression rule) before
 *      shipping: thresholds calibrated for the hash fallback do NOT
 *      carry over to this model.
 */
public final class OnnxEmbedderStub {
    private OnnxEmbedderStub() {
    }

    public static float[] embed(String text) {
        throw new UnsupportedOperationException(
                "ONNX e5-small embedder is a stub in V1 — use HashEmbedder.");
    }
}
