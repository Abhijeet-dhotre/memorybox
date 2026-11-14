// WebVTT caption parsing (Path A, PRD §4).
//
// yt-dlp writes `--sub-format vtt` files. A .vtt file is a sequence of
// cues: `00:01:02.000 --> 00:01:05.500` followed by one or more text lines.
// Auto-captions also carry inline tags (`<c>`, `<00:01:02.500>`) and
// duplicate/overlapping cues; this parser strips the tags, merges cues
// with identical text, and drops empty cues. The result is a flat list of
// timed segments the chunker can work with — the transcript is what is
// saved, never the video.

/// One timed line of transcript.
class CaptionSegment {
  CaptionSegment({
    required this.startSeconds,
    required this.endSeconds,
    required this.text,
  });

  final double startSeconds;
  final double endSeconds;
  final String text;
}

/// Parses a .vtt file's contents into timed segments. Returns an empty
/// list when the file has no usable cues (the caller then falls back to
/// Path B instead of saving an empty transcript).
List<CaptionSegment> parseVtt(String vttText) {
  final lines = vttText.split('\n');
  final segments = <CaptionSegment>[];
  var i = 0;

  double? parseTs(String s) {
    // 00:01:02.000 or 01:02.000
    final m = RegExp(r'^(?:(\d+):)?(\d+):(\d+)[.,](\d{3})$').firstMatch(s.trim());
    if (m == null) return null;
    final h = int.tryParse(m.group(1) ?? '0') ?? 0;
    final min = int.tryParse(m.group(2)!) ?? 0;
    final sec = int.tryParse(m.group(3)!) ?? 0;
    final ms = int.tryParse(m.group(4)!) ?? 0;
    return h * 3600 + min * 60 + sec + ms / 1000;
  }

  while (i < lines.length) {
    final line = lines[i].trim();
    i++;
    if (line.isEmpty || line == 'WEBVTT' || line.startsWith('NOTE')) continue;
    // A cue id line precedes the timestamp line in some files.
    String tsLine = line;
    if (!line.contains('-->')) {
      if (i >= lines.length) break;
      tsLine = lines[i].trim();
      i++;
      if (!tsLine.contains('-->')) continue;
    }
    final parts = tsLine.split('-->');
    final start = parseTs(parts[0]);
    final end = parts.length > 1 ? parseTs(parts[1].trim().split(' ')[0]) : null;
    if (start == null || end == null) continue;

    final textLines = <String>[];
    while (i < lines.length && lines[i].trim().isNotEmpty) {
      textLines.add(lines[i].trim());
      i++;
    }
    var text = textLines.join(' ');
    // Strip inline VTT tags: <c>, </c>, <00:01:02.500>, <v Speaker>, &amp; etc.
    text = text.replaceAll(RegExp(r'<[^>]*>'), '');
    text = text
        .replaceAll('&amp;', '&')
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .replaceAll('&nbsp;', ' ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
    if (text.isEmpty) continue;
    segments.add(CaptionSegment(startSeconds: start, endSeconds: end, text: text));
  }

  // Auto-captions repeat the same line across overlapping cues; merge
  // exact duplicates that overlap in time.
  final merged = <CaptionSegment>[];
  for (final s in segments) {
    if (merged.isNotEmpty) {
      final last = merged.last;
      if (last.text == s.text && s.startSeconds <= last.endSeconds + 0.5) {
        merged[merged.length - 1] = CaptionSegment(
          startSeconds: last.startSeconds,
          endSeconds: s.endSeconds > last.endSeconds ? s.endSeconds : last.endSeconds,
          text: last.text,
        );
        continue;
      }
    }
    merged.add(s);
  }
  return merged;
}

/// Plain-text transcript of the whole video (segments joined in order),
/// used for the Groq summary prompt.
String segmentsToPlainText(List<CaptionSegment> segments) =>
    segments.map((s) => s.text).join(' ');
