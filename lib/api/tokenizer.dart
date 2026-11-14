// WordPiece tokenizer for multilingual-e5-small (PRD §11).
//
// Reads `assets/models/tokenizer.json` (the Hugging Face tokenizer file
// for intfloat/multilingual-e5-small) and tokenizes exactly the way the
// model was trained: lowercase is NOT applied (e5-small is cased — the
// tokenizer handles it), `[CLS]`/`[SEP]` are added, input is truncated
// to 512 tokens, and the e5 prefix `query: ` / `passage: ` is added by the
// caller (see lib/api/embeddings.dart).
//
// The tokenizer.json file itself is NOT in this repo: it is a model asset
// downloaded separately (see lib/api/embeddings.dart for the source +
// exact filenames). Until it exists, [WordPieceTokenizer.load] throws a
// clear [EmbeddingUnavailable] — the app saves chunks without vectors
// and backfills them later instead of failing ingest.

import 'dart:convert';

import 'package:flutter/services.dart';

class EmbeddingUnavailable implements Exception {
  EmbeddingUnavailable(this.message);

  final String message;

  @override
  String toString() => 'Embeddings unavailable: $message';
}

/// Tokenizer output for one text: token ids + attention mask.
class EncodedInput {
  EncodedInput({required this.inputIds, required this.attentionMask});

  final List<int> inputIds;
  final List<int> attentionMask;
}

class WordPieceTokenizer {
  WordPieceTokenizer._({
    required this.vocab,
    required this.clsId,
    required this.sepId,
    required this.padId,
    required this.unkId,
    required this.maxLen,
  });

  final Map<String, int> vocab;
  final int clsId;
  final int sepId;
  final int padId;
  final int unkId;
  final int maxLen;

  static const assetPath = 'assets/models/tokenizer.json';

  static Future<WordPieceTokenizer> load({String asset = assetPath}) async {
    late final String raw;
    try {
      raw = await rootBundle.loadString(asset);
    } catch (e) {
      throw EmbeddingUnavailable(
        'tokenizer.json not found at $asset. '
        'Download it from intfloat/multilingual-e5-small '
        '(see lib/api/embeddings.dart) and place it in assets/models/.',
      );
    }
    final json = jsonDecode(raw) as Map<String, dynamic>;
    final vocabJson = (json['model'] as Map)['vocab'] as Map<String, dynamic>;
    final vocab = vocabJson.map((k, v) => MapEntry(k, (v as num).toInt()));
    int id(String token) => vocab[token] ?? vocab['[UNK]']!;
    return WordPieceTokenizer._(
      vocab: vocab,
      clsId: id('[CLS]'),
      sepId: id('[SEP]'),
      padId: id('[PAD]'),
      unkId: id('[UNK]'),
      maxLen: 512,
    );
  }

  EncodedInput encode(String text) {
    final tokens = _wordpiece(text);
    // [CLS] text [SEP], truncated to maxLen.
    final ids = <int>[clsId];
    for (final t in tokens) {
      if (ids.length >= maxLen - 1) break;
      ids.add(vocab[t] ?? unkId);
    }
    ids.add(sepId);
    return EncodedInput(
      inputIds: ids,
      attentionMask: List<int>.filled(ids.length, 1),
    );
  }

  /// Greedy longest-match WordPiece over whitespace-separated words.
  List<String> _wordpiece(String text) {
    final out = <String>[];
    for (final word in text.split(RegExp(r'\s+'))) {
      if (word.isEmpty) continue;
      var start = 0;
      var failed = false;
      final sub = <String>[];
      while (start < word.length) {
        var end = word.length;
        String? found;
        while (end > start) {
          var piece = word.substring(start, end);
          if (start > 0) piece = '##$piece';
          if (vocab.containsKey(piece)) {
            found = piece;
            break;
          }
          end--;
        }
        if (found == null) {
          failed = true;
          break;
        }
        sub.add(found);
        start = end;
      }
      out.addAll(failed ? ['[UNK]'] : sub);
    }
    return out;
  }
}
