// Transcript chunking (PRD §4, §10, §11.2).
//
// Chunks are the unit of search: each chunk gets one FTS row and one
// 384-dim vector. PRD target is 1–2 minutes per chunk. Short videos
// (< 2 min) are one chunk. Chunks break on sentence boundaries where
// possible so a chunk never starts mid-sentence; when a single sentence
// runs past the cap it is hard-split at the cap. Timestamps are kept so
// search results can jump to the exact moment in the original video.

import '../db/database.dart';
import 'captions.dart';

/// Target chunk length in seconds (PRD: 1–2 minutes).
const targetChunkSeconds = 90.0;

/// Hard cap: a chunk is never longer than this, even mid-sentence.
const maxChunkSeconds = 120.0;

/// Minimum chunk length: tiny tail chunks are merged into the previous
/// chunk instead of being saved on their own.
const minChunkSeconds = 15.0;

List<TranscriptChunk> chunkTranscript(List<CaptionSegment> segments) {
  final chunks = <TranscriptChunk>[];
  if (segments.isEmpty) return chunks;

  var start = segments.first.startSeconds;
  var end = segments.first.endSeconds;
  final buf = StringBuffer();

  void flush() {
    final text = buf.toString().trim();
    if (text.isEmpty) return;
    chunks.add(
      TranscriptChunk(
        id: 0, // assigned by the database on insert
        videoId: 0,
        startSeconds: start,
        endSeconds: end,
        text: text,
      ),
    );
    buf.clear();
  }

  for (var i = 0; i < segments.length; i++) {
    final s = segments[i];
    final span = s.endSeconds - start;
    final endsSentence = RegExp(r'[.!?…]["\')]?$').hasMatch(s.text.trim());

    if (buf.isNotEmpty && span > maxChunkSeconds) {
      // Hard split, even mid-sentence.
      flush();
      start = s.startSeconds;
    } else if (buf.isNotEmpty && span >= targetChunkSeconds && endsSentence) {
      flush();
      start = s.startSeconds;
    }
    if (buf.isNotEmpty) buf.write(' ');
    buf.write(s.text);
    end = s.endSeconds;
  }
  flush();

  // Merge a tiny tail chunk into the previous one.
  if (chunks.length >= 2) {
    final last = chunks.last;
    if (last.endSeconds - last.startSeconds < minChunkSeconds) {
      final prev = chunks[chunks.length - 2];
      chunks[chunks.length - 2] = TranscriptChunk(
        id: 0,
        videoId: 0,
        startSeconds: prev.startSeconds,
        endSeconds: last.endSeconds,
        text: '${prev.text} ${last.text}',
      );
      chunks.removeLast();
    }
  }
  return chunks;
}
