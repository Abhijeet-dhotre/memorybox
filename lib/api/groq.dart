// Groq API client (PRD §11, §12).
//
// Two Groq calls, both on the free tier, both with the user's own key
// (see lib/db/key_store.dart — the key is never hardcoded):
//   * Whisper `whisper-large-v3-turbo` — transcribes Path B temp audio.
//   * Chat `llama-3.3-70b-versatile` — one call per video: summary,
//     key points, tags, and a dynamic category from the transcript.
//     The PRD names no chat model; this default is recorded here and in
//     PHASE_REPORT.md so it can be changed in one place.
// Plain `dart:io` HttpClient — no third-party Groq package, so the app
// never depends on an unmaintained wrapper.

import 'dart:convert';
import 'dart:io';

class GroqException implements Exception {
  GroqException(this.message, {this.statusCode, this.details});

  final String message;
  final int? statusCode;
  final String? details;

  @override
  String toString() => 'Groq error${statusCode == null ? '' : ' ($statusCode)'}: $message';
}

/// The summary + metadata Groq returns for one video.
class VideoSummary {
  VideoSummary({
    required this.summary,
    required this.keyPoints,
    required this.tags,
    required this.category,
  });

  final String summary; // 2–4 sentences, plain words
  final List<String> keyPoints; // 3–6 short points
  final List<String> tags; // 3–8 short tags
  final String category; // dynamic — created from this video's content
}

class GroqClient {
  GroqClient(this.apiKey);

  final String apiKey;

  static const _base = 'https://api.groq.com/openai/v1';
  static const whisperModel = 'whisper-large-v3-turbo';
  static const chatModel = 'llama-3.3-70b-versatile';

  /// Transcribes one audio file (Path B). The file must already be
  /// compressed/split by the caller (see lib/ingest/ytdlp.dart); Groq's
  /// upload limit is 25 MB per request. Returns plain transcript text.
  Future<String> transcribe(File audioFile) async {
    final boundary =
        '----memorybox${DateTime.now().millisecondsSinceEpoch}';
    final request =
        await HttpClient().postUrl(Uri.parse('$_base/audio/transcriptions'));
    request.headers.set('Authorization', 'Bearer $apiKey');
    request.headers.set('Content-Type', 'multipart/form-data; boundary=$boundary');

    void field(String name, String value) {
      request.write('--$boundary\r\n');
      request.write('Content-Disposition: form-data; name="$name"\r\n\r\n');
      request.write('$value\r\n');
    }

    field('model', whisperModel);
    field('response_format', 'json');
    field('language', 'en');
    request.write('--$boundary\r\n');
    request.write(
      'Content-Disposition: form-data; name="file"; '
      'filename="${audioFile.uri.pathSegments.last}"\r\n',
    );
    request.write('Content-Type: audio/mpeg\r\n\r\n');
    await request.addStream(audioFile.openRead());
    request.write('\r\n--$boundary--\r\n');

    final response = await request.close();
    final body = await response.transform(utf8.decoder).join();
    if (response.statusCode != 200) {
      throw GroqException(
        _groqErrorMessage(body),
        statusCode: response.statusCode,
        details: body,
      );
    }
    final json = jsonDecode(body) as Map<String, dynamic>;
    return (json['text'] as String? ?? '').trim();
  }

  /// Summarises a full transcript into [VideoSummary]. One call per video.
  /// The prompt asks for plain words (the user's standing preference) and
  /// for a category invented from the content — never from a fixed list.
  Future<VideoSummary> summarize(String title, String transcript) async {
    final prompt = '''
You are Memory Box, a tool that saves what a video said so its owner can
find it later by meaning. Read the transcript below and reply with JSON
only, no other text:

{
  "summary": "2-4 sentences, plain simple words, what the video actually says",
  "key_points": ["3-6 short points, each under 15 words"],
  "tags": ["3-8 short lowercase tags, e.g. python, cooking, gate-exam"],
  "category": "one short category invented from THIS video's content, e.g. Cooking, Python Tutorials, Exam Prep — never pick from a fixed list"
}

Title: $title

Transcript:
$transcript
''';
    final body = await _chat([
      {
        'role': 'system',
        'content':
            'You reply with valid JSON only. Use plain simple words, no jargon.',
      },
      {'role': 'user', 'content': prompt},
    ]);
    final content =
        (body['choices'] as List).first['message']['content'] as String;
    final json = jsonDecode(_stripCodeFences(content)) as Map<String, dynamic>;
    return VideoSummary(
      summary: (json['summary'] as String? ?? '').trim(),
      keyPoints: _stringList(json['key_points']),
      tags: _stringList(json['tags']),
      category: (json['category'] as String? ?? 'Uncategorized').trim(),
    );
  }

  Future<Map<String, dynamic>> _chat(
    List<Map<String, String>> messages,
  ) async {
    final request =
        await HttpClient().postUrl(Uri.parse('$_base/chat/completions'));
    request.headers.set('Authorization', 'Bearer $apiKey');
    request.headers.set('Content-Type', 'application/json');
    request.write(
      jsonEncode({
        'model': chatModel,
        'messages': messages,
        'temperature': 0.3,
        'response_format': {'type': 'json_object'},
      }),
    );
    final response = await request.close();
    final body = await response.transform(utf8.decoder).join();
    if (response.statusCode != 200) {
      throw GroqException(
        _groqErrorMessage(body),
        statusCode: response.statusCode,
        details: body,
      );
    }
    return jsonDecode(body) as Map<String, dynamic>;
  }

  static String _stripCodeFences(String s) {
    var t = s.trim();
    if (t.startsWith('```')) {
      t = t.replaceFirst(RegExp(r'^```(?:json)?\s*'), '').replaceFirst(RegExp(r'\s*```$'), '');
    }
    return t;
  }

  static List<String> _stringList(dynamic v) {
    if (v is! List) return [];
    return v.map((e) => e.toString().trim()).where((e) => e.isNotEmpty).toList();
  }

  static String _groqErrorMessage(String body) {
    try {
      final json = jsonDecode(body) as Map<String, dynamic>;
      final err = json['error'];
      if (err is Map && err['message'] is String) return err['message'] as String;
    } catch (_) {}
    return 'Groq request failed. Check the API key and try again.';
  }
}
