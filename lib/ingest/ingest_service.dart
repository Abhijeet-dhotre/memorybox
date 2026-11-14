// Ingest pipeline (PRD §4, §11, §12).
//
// Flow, in order:
//   1. Dedupe — extractor+video ID already saved? Stop, offer the existing
//      video. Never save the same video twice.
//   2. Path A — fetch captions only (`--skip-download --write-subs`).
//      No video, no audio, nothing downloaded.
//   3. Path B (only when Path A finds no captions) — download audio only,
//      compress to 16k mono, split into ≤20 min parts, transcribe each
//      part with Groq Whisper, delete EVERY temp file in a `finally`
//      block (success AND failure). The transcript is what is saved.
//   4. Chunk the transcript into 1–2 min timestamped chunks.
//   5. Groq chat: summary + key points + tags + dynamic category.
//   6. Embeddings (when the ONNX model is available): one 384-dim vector
//      per chunk. When unavailable, chunks are saved without vectors and
//      can be backfilled later — ingest never fails for missing embeddings.
//   7. Save video + chunks + FTS + vectors in ONE transaction.
//
// Progress is reported through [onProgress] so the Add screen can show
// what is happening; failures throw with a human-readable message
// (PRD §2: honest failure, never a silent partial save).

import 'dart:io';

import 'package:path_provider/path_provider.dart';

import '../api/embeddings.dart';
import '../api/groq.dart';
import '../api/tokenizer.dart';
import '../db/database.dart';
import '../db/key_store.dart';
import 'captions.dart';
import 'chunker.dart';
import 'ytdlp.dart';

/// Ingest progress, in order. The UI shows [label] as-is.
enum IngestStage {
  checking('Checking if this video is already saved…'),
  fetchingInfo('Reading video details…'),
  fetchingCaptions('Getting captions (no video downloaded)…'),
  downloadingAudio('Downloading audio only…'),
  compressingAudio('Compressing audio…'),
  transcribing('Transcribing with Groq Whisper…'),
  chunking('Splitting transcript into searchable chunks…'),
  summarizing('Writing summary, key points and tags…'),
  embedding('Computing on-device embeddings…'),
  saving('Saving to your library…'),
  done('Saved');

  const IngestStage(this.label);

  final String label;
}

class IngestResult {
  IngestResult({required this.videoId, required this.alreadySaved});

  final int videoId;
  final bool alreadySaved;
}

class IngestService {
  IngestService({
    required this.db,
    required this.keyStore,
    YtDlpRunner? ytDlp,
    GroqClient Function(String apiKey)? groqFactory,
    EmbeddingService? embeddings,
  })  : _ytDlp = ytDlp ?? YtDlpRunner(),
        _groqFactory = groqFactory ?? GroqClient.new,
        _embeddings = embeddings;

  final MemoryBoxDatabase db;
  final GroqKeyStore keyStore;
  final YtDlpRunner _ytDlp;
  final GroqClient Function(String apiKey) _groqFactory;
  final EmbeddingService? _embeddings;

  /// Saves the video at [url]. Returns the saved (or already-saved)
  /// video's id. Throws [YtDlpException]/[GroqException] with a readable
  /// message on failure — the caller shows it and saves nothing.
  Future<IngestResult> ingest(
    String url, {
    void Function(IngestStage stage)? onProgress,
  }) async {
    void progress(IngestStage s) => onProgress?.call(s);

    progress(IngestStage.checking);
    final info = await _ytDlp.fetchInfo(url);
    if (info.videoId.isEmpty) {
      throw YtDlpException('Could not read this video. Check the link and try again.');
    }
    final existingId = db.videoIdFor(info.dedupeKey);
    if (existingId != null) {
      return IngestResult(videoId: existingId, alreadySaved: true);
    }

    final apiKey = await keyStore.read();
    if (apiKey == null || apiKey.isEmpty) {
      throw GroqException(
        'Add your Groq API key in Settings first (free at console.groq.com). '
        'Memory Box needs it to transcribe and summarise.',
      );
    }
    final groq = _groqFactory(apiKey);

    // Work directory for temp files (captions, temp audio, parts). It is
    // deleted wholesale at the end, whatever happens.
    final tmpBase = await getTemporaryDirectory();
    final workDir = Directory(
      '${tmpBase.path}/ingest_${DateTime.now().millisecondsSinceEpoch}',
    );
    await workDir.create(recursive: true);
    try {
      // --- transcript -------------------------------------------------
      progress(IngestStage.fetchingCaptions);
      final captionFile = await _ytDlp.fetchCaptions(info.webpageUrl, workDir);
      final List<CaptionSegment> segments;
      String language = 'en';
      if (captionFile != null) {
        language = _captionLanguage(captionFile);
        final vtt = await captionFile.readAsString();
        segments = parseVtt(vtt);
      } else {
        // Path B: no captions — temp audio → Whisper.
        progress(IngestStage.downloadingAudio);
        final audio = await _ytDlp.fetchAudio(info.webpageUrl, workDir);
        progress(IngestStage.compressingAudio);
        final compressed = await _ytDlp.compressAudio(
          audio,
          File('${workDir.path}/audio_16k.mp3'),
        );
        final parts = await _ytDlp.splitAudio(compressed, workDir);
        progress(IngestStage.transcribing);
        final texts = <String>[];
        for (final part in parts) {
          texts.add(await groq.transcribe(part));
        }
        // Whisper returns plain text without timestamps; split it into
        // ~90 s pseudo-segments by word count (audio length / words).
        segments = _plainTextToSegments(
          texts.join(' '),
          info.durationSeconds,
        );
      }
      if (segments.isEmpty) {
        throw YtDlpException(
          'No transcript could be made for this video '
          '(no captions, and transcription returned nothing).',
        );
      }

      // --- chunks -----------------------------------------------------
      progress(IngestStage.chunking);
      final chunks = chunkTranscript(segments);
      final plainText = segmentsToPlainText(segments);

      // --- summary ----------------------------------------------------
      progress(IngestStage.summarizing);
      final summary = await groq.summarize(info.title, plainText);

      // --- embeddings -------------------------------------------------
      progress(IngestStage.embedding);
      List<List<double>> embeddings = [];
      final emb = _embeddings ?? await _tryLoadEmbeddings();
      if (emb != null) {
        embeddings = await emb.embedPassages(
          chunks.map((c) => c.text).toList(),
        );
      } else {
        EmbeddingService.noteUnavailableOnce(
          'Embeddings unavailable — chunks saved without vectors; '
          'search still works by text. Add the ONNX model to enable vectors.',
        );
      }

      // --- save -------------------------------------------------------
      progress(IngestStage.saving);
      final videoId = db.insertVideo(
        video: Video(
          id: 0,
          dedupeKey: info.dedupeKey,
          url: info.webpageUrl,
          title: info.title,
          channel: info.channel,
          durationSeconds: info.durationSeconds,
          source: info.extractor.isEmpty ? 'unknown' : info.extractor,
          summary: summary.summary,
          keyPoints: summary.keyPoints,
          tags: summary.tags,
          category: summary.category,
          language: language,
          createdAt: DateTime.now(),
        ),
        chunks: chunks,
        embeddings: embeddings,
      );
      progress(IngestStage.done);
      return IngestResult(videoId: videoId, alreadySaved: false);
    } finally {
      // Temp audio, parts, and caption files are deleted here — on success
      // AND on failure. The transcript is what is saved, never the media.
      if (await workDir.exists()) {
        await workDir.delete(recursive: true);
      }
    }
  }

  Future<EmbeddingService?> _tryLoadEmbeddings() async {
    try {
      return await EmbeddingService.load();
    } on EmbeddingUnavailable {
      return null;
    }
  }

  /// Language code from a caption filename like `title.en.vtt`.
  static String _captionLanguage(File captionFile) {
    final name = captionFile.uri.pathSegments.last;
    final m = RegExp(r'\.([a-z]{2}(?:-[A-Za-z0-9]+)?)\.(?:vtt|srt)$')
        .firstMatch(name);
    return m?.group(1) ?? 'en';
  }

  /// Turns Whisper plain text (no timestamps) into timed pseudo-segments:
  /// words are spread evenly across the video duration, ~90 s per segment.
  /// Timestamps from Path B are approximate — the detail screen says so.
  static List<CaptionSegment> _plainTextToSegments(
    String text,
    int durationSeconds,
  ) {
    final words = text.split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();
    if (words.isEmpty || durationSeconds <= 0) return [];
    final totalSeconds = durationSeconds.toDouble();
    final wordsPerSecond = words.length / totalSeconds;
    final segments = <CaptionSegment>[];
    var i = 0;
    while (i < words.length) {
      final start = i / wordsPerSecond;
      var endIdx = (i + (targetChunkSeconds * wordsPerSecond)).round();
      if (endIdx <= i) endIdx = i + 1;
      if (endIdx > words.length) endIdx = words.length;
      final end =
          endIdx >= words.length ? totalSeconds : endIdx / wordsPerSecond;
      final chunkWords = words.sublist(i, endIdx);
      segments.add(
        CaptionSegment(
          startSeconds: start,
          endSeconds: end > start ? end : start + 1,
          text: chunkWords.join(' '),
        ),
      );
      i = endIdx;
    }
    return segments;
  }
}
