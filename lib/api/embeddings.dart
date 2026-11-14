// On-device embeddings (PRD §11, §11.2).
//
// Model: quantized multilingual-e5-small (intfloat, 384-dim), run locally
// with ONNX Runtime (`flutter_onnxruntime`). Groq has no embeddings API,
// so vectors are NEVER computed server-side — this is the only embedding
// path in the app, and it works fully offline once the model files exist.
//
// Model files (NOT in this repo — large binaries, downloaded separately):
//   assets/models/e5-small-int8.onnx   — the quantized ONNX model
//     (Hugging Face: intfloat/multilingual-e5-small, ONNX export with
//     dynamic quantization, or the `onnx` community export of the same)
//   assets/models/tokenizer.json       — the matching tokenizer file
//     (same repo, `tokenizer.json`)
// Place both files in assets/models/ and rebuild. Until they exist,
// [EmbeddingService] reports unavailable and ingest saves chunks without
// vectors (FTS search still works; vectors are backfilled later — see
// lib/db/database.dart `backfillEmbedding`).
//
// Encoding follows the e5 recipe exactly: `passage: <text>` for chunks,
// `query: <text>` for search queries, mean pooling over the last hidden
// state (attention-masked), then L2 normalisation. The search service
// relies on normalisation: cosine similarity is computed from the L2
// distance sqlite-vec returns as `1 - d^2 / 2`.

import 'dart:math' as math;

import 'package:flutter_onnxruntime/flutter_onnxruntime.dart';

import 'tokenizer.dart';

/// Embedding dimension for the whole app (PRD §11: e5-small = 384).
/// Must match `vecDimensions` in lib/db/vec_loader.dart.
const embeddingDimensions = 384;

class EmbeddingService {
  EmbeddingService._(this._session, this._tokenizer);

  final OrtSession _session;
  final WordPieceTokenizer _tokenizer;

  static const modelAsset = 'assets/models/e5-small-int8.onnx';

  static bool _unavailableReported = false;

  /// Loads the model + tokenizer. Throws [EmbeddingUnavailable] when the
  /// model files are missing (caller treats this as "embeddings off",
  /// never as a crash).
  static Future<EmbeddingService> load() async {
    final tokenizer = await WordPieceTokenizer.load();
    late final OrtSession session;
    try {
      session = await OnnxRuntime().createSessionFromAsset(modelAsset);
    } catch (e) {
      throw EmbeddingUnavailable(
        'ONNX model not found at $modelAsset. '
        'Download the quantized multilingual-e5-small ONNX model and '
        'place it in assets/models/. ($e)',
      );
    }
    return EmbeddingService._(session, tokenizer);
  }

  /// True when the model files exist (cheap check, no session created).
  static Future<bool> get isAvailable async {
    try {
      await WordPieceTokenizer.load();
      return true;
    } on EmbeddingUnavailable {
      return false;
    }
  }

  /// Embeds one chunk of transcript text. Returns a 384-dim L2-normalised
  /// vector (list of doubles, length [embeddingDimensions]).
  Future<List<double>> embedPassage(String text) => _embed('passage: $text');

  /// Embeds one search query.
  Future<List<double>> embedQuery(String text) => _embed('query: $text');

  Future<List<double>> _embed(String prefixedText) async {
    final enc = _tokenizer.encode(prefixedText);
    final seqLen = enc.inputIds.length;
    final inputIds = await OrtValue.fromList(enc.inputIds, [1, seqLen]);
    final attentionMask =
        await OrtValue.fromList(enc.attentionMask, [1, seqLen]);
    final tokenTypeIds =
        await OrtValue.fromList(List<int>.filled(seqLen, 0), [1, seqLen]);
    final outputs = await _session.run({
      'input_ids': inputIds,
      'attention_mask': attentionMask,
      'token_type_ids': tokenTypeIds,
    });
    final lastHidden = await outputs['last_hidden_state']!.asList();
    // last_hidden_state: [1][seqLen][384]. Mean-pool over tokens where
    // attention_mask == 1 (CLS included, padding excluded).
    final tokens = lastHidden[0] as List;
    final pooled = List<double>.filled(embeddingDimensions, 0.0);
    var count = 0;
    for (var i = 0; i < seqLen; i++) {
      if (enc.attentionMask[i] == 0) continue;
      final row = tokens[i] as List;
      for (var j = 0; j < embeddingDimensions; j++) {
        pooled[j] += (row[j] as num).toDouble();
      }
      count++;
    }
    for (var j = 0; j < embeddingDimensions; j++) {
      pooled[j] /= count;
    }
    // L2 normalise.
    final norm = math.sqrt(pooled.fold<double>(0, (a, b) => a + b * b));
    if (norm == 0) return pooled;
    return pooled.map((v) => v / norm).toList();
  }

  /// Embeds many passages in sequence (one ONNX call per chunk — the
  /// model is small and chunks are short; batching is a later optimisation).
  Future<List<List<double>>> embedPassages(List<String> texts) async {
    final out = <List<double>>[];
    for (final t in texts) {
      out.add(await embedPassage(t));
    }
    return out;
  }

  /// Logs the unavailability once (so the UI can show a single honest
  /// "embeddings off" note instead of an error per video).
  static void noteUnavailableOnce(String message) {
    if (_unavailableReported) return;
    _unavailableReported = true;
    // Deliberately no print: the Settings screen surfaces this state.
    assert(() {
      // ignore: avoid_print
      print('Memory Box: $message');
      return true;
    }());
  }
}
