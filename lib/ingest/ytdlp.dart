// yt-dlp access for Memory Box (PRD §11, §11.1).
//
// Two platform paths, one Dart API — the rest of the app only calls
// [YtDlpRunner] and never cares which one is in use:
//
// * Desktop (Windows, and Linux/macOS for development): the yt-dlp binary
//   is called as a child process with [Process.run]. On Windows the app
//   bundles yt-dlp.exe + ffmpeg.exe next to the .exe (see
//   windows/CMakeLists.txt); on other desktops the binary is resolved from
//   PATH. This is the path the Phase 1 spike proves.
// * Android: there is no yt-dlp executable on the device. The call goes
//   over a MethodChannel to the native bridge
//   (android/app/src/main/kotlin/.../YtDlpBridge.kt), which uses the
//   VibeVault-proven `youtubedl-android` library (CPython + yt-dlp +
//   QuickJS bundled in the APK) with the same arguments.
//
// No video or audio is ever kept: Path A downloads only the caption file,
// Path B downloads only temporary audio that the caller deletes (see
// lib/ingest/ingest_service.dart, which deletes in a `finally` block).

import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Result of a yt-dlp invocation.
class YtDlpResult {
  YtDlpResult({required this.exitCode, required this.stdout, required this.stderr});

  final int exitCode;
  final String stdout;
  final String stderr;

  bool get ok => exitCode == 0;
}

/// What yt-dlp reports about one video (subset of --dump-json we need).
class VideoInfo {
  VideoInfo({
    required this.extractor,
    required this.videoId,
    required this.title,
    required this.channel,
    required this.durationSeconds,
    required this.webpageUrl,
    required this.subtitles,
  });

  final String extractor; // e.g. 'youtube'
  final String videoId;
  final String title;
  final String channel;
  final int durationSeconds;
  final String webpageUrl;

  /// Language codes with a caption track (manual or automatic).
  final List<String> subtitles;

  /// Canonical dedupe key (PRD §4): extractor + video ID, never the URL.
  String get dedupeKey => '$extractor:$videoId';
}

class YtDlpException implements Exception {
  YtDlpException(this.message, {this.details});

  final String message;
  final String? details;

  @override
  String toString() =>
      'YtDlpException: $message${details == null ? '' : ' ($details)'}';
}

class YtDlpRunner {
  static const _channel = MethodChannel('memorybox/ytdlp');

  /// Where the desktop yt-dlp binary lives.
  ///
  /// Windows: bundled next to the .exe. Anything else: PATH lookup.
  Future<String> get binaryPath async {
    if (Platform.isWindows) {
      final exeDir = File(Platform.resolvedExecutable).parent.path;
      final bundled = File('$exeDir\\yt-dlp.exe');
      if (await bundled.exists()) return bundled.path;
      return 'yt-dlp.exe';
    }
    return 'yt-dlp';
  }

  Future<YtDlpResult> _runDesktop(List<String> args, {String? cwd}) async {
    final exe = await binaryPath;
    final result = await Process.run(exe, args, workingDirectory: cwd);
    return YtDlpResult(
      exitCode: result.exitCode,
      stdout: result.stdout as String,
      stderr: result.stderr as String,
    );
  }

  Future<YtDlpResult> _runAndroid(List<String> args, {String? cwd}) async {
    try {
      final out = await _channel.invokeMethod<Map<Object?, Object?>>(
        'run',
        {'args': args, 'cwd': cwd},
      );
      return YtDlpResult(
        exitCode: (out?['exitCode'] as int?) ?? 1,
        stdout: (out?['stdout'] as String?) ?? '',
        stderr: (out?['stderr'] as String?) ?? '',
      );
    } on PlatformException catch (e) {
      throw YtDlpException('Android yt-dlp bridge failed', details: e.message);
    } on MissingPluginException {
      throw YtDlpException(
        'Android yt-dlp bridge is not registered. '
        'The native YtDlpBridge is missing from this build.',
      );
    }
  }

  Future<YtDlpResult> run(List<String> args, {String? cwd}) {
    if (Platform.isAndroid) return _runAndroid(args, cwd: cwd);
    return _runDesktop(args, cwd: cwd);
  }

  /// `yt-dlp --dump-json <url>`: metadata + which caption languages exist.
  /// Throws [YtDlpException] when the video is private, login-only,
  /// blocked, or the URL is not a video (PRD §2: honest failure, never a
  /// silent partial save).
  Future<VideoInfo> fetchInfo(String url) async {
    final r = await run(['--dump-json', '--no-playlist', url]);
    if (!r.ok) {
      throw YtDlpException(_humanMessage(r.stderr), details: r.stderr);
    }
    return _parseDumpJson(r.stdout);
  }

  VideoInfo _parseDumpJson(String jsonText) {
    // --dump-json prints one JSON object per line; we only ask for one video.
    final firstLine = jsonText
        .split('\n')
        .firstWhere((l) => l.trim().startsWith('{'), orElse: () => '');
    if (firstLine.isEmpty) {
      throw YtDlpException('yt-dlp returned no video info');
    }
    // Minimal hand-rolled field extraction: avoids a JSON dependency and
    // keeps the parse tolerant of the many extra fields yt-dlp emits.
    String str(String key) {
      final m = RegExp('"$key"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"')
          .firstMatch(firstLine);
      if (m == null) return '';
      return m.group(1)!.replaceAll(r'\"', '"').replaceAll(r'\\', r'\');
    }

    int num(String key) {
      final m =
          RegExp('"$key"\\s*:\\s*(\\d+)').firstMatch(firstLine);
      return m == null ? 0 : int.parse(m.group(1)!);
    }

    final subtitles = <String>{};
    for (final section in ['"subtitles"', '"automatic_captions"']) {
      final start = firstLine.indexOf(section);
      if (start < 0) continue;
      // Language codes appear as "en": {...} keys inside the section.
      final sectionText = firstLine.substring(start, (start + 20000).clamp(0, firstLine.length));
      for (final m in RegExp(r'"([a-z]{2}(?:-[A-Za-z0-9]+)?)"\s*:\s*\[')
          .allMatches(sectionText)) {
        subtitles.add(m.group(1)!);
      }
    }
    return VideoInfo(
      extractor: str('extractor'),
      videoId: str('id'),
      title: str('title'),
      channel: str('uploader'),
      durationSeconds: num('duration'),
      webpageUrl: str('webpage_url'),
      subtitles: subtitles.toList()..sort(),
    );
  }

  /// Path A (PRD §4): fetch only the caption file, no video, no audio.
  /// Returns the downloaded .vtt/.srt file, or null when the video has no
  /// caption in any of [languages] (caller then falls back to Path B).
  /// The file is created inside [workDir]; the caller owns it.
  Future<File?> fetchCaptions(
    String url,
    Directory workDir, {
    List<String> languages = const ['en'],
  }) async {
    final r = await run(
      [
        '--skip-download',
        '--write-subs',
        '--write-auto-subs',
        '--sub-langs',
        languages.join(','),
        '--sub-format',
        'vtt',
        '--no-playlist',
        url,
      ],
      cwd: workDir.path,
    );
    if (!r.ok) {
      throw YtDlpException(_humanMessage(r.stderr), details: r.stderr);
    }
    final vtts = workDir
        .listSync()
        .whereType<File>()
        .where((f) => f.path.endsWith('.vtt') || f.path.endsWith('.srt'))
        .toList();
    if (vtts.isEmpty) return null;
    vtts.sort((a, b) => b.lengthSync().compareTo(a.lengthSync()));
    return vtts.first;
  }

  /// Path B (PRD §4): download only the audio track into [workDir].
  /// Returns the downloaded audio file. The caller must delete it (and any
  /// compressed/split derivatives) when transcription finishes — on success
  /// AND on failure.
  Future<File> fetchAudio(String url, Directory workDir) async {
    final r = await run(
      [
        '-f',
        'bestaudio',
        '--no-playlist',
        '-o',
        'temp_audio.%(ext)s',
        url,
      ],
      cwd: workDir.path,
    );
    if (!r.ok) {
      throw YtDlpException(_humanMessage(r.stderr), details: r.stderr);
    }
    final audios = workDir
        .listSync()
        .whereType<File>()
        .where((f) => f.path.contains('temp_audio.'))
        .toList();
    if (audios.isEmpty) {
      throw YtDlpException('yt-dlp reported success but no audio file appeared');
    }
    return audios.first;
  }

  /// ffmpeg path: Windows bundles ffmpeg.exe next to the .exe; Android
  /// uses the ffmpeg that ships with youtubedl-android (invoked through
  /// the bridge as `ffmpeg`); other desktops use PATH.
  Future<String> get ffmpegPath async {
    if (Platform.isWindows) {
      final exeDir = File(Platform.resolvedExecutable).parent.path;
      final bundled = File('$exeDir\\ffmpeg.exe');
      if (await bundled.exists()) return bundled.path;
      return 'ffmpeg.exe';
    }
    return 'ffmpeg';
  }

  /// Compress [audioFile] to low-bitrate mono (PRD §4/§11: long audio is
  /// compressed and split under the transcription size limit). Returns the
  /// compressed file. [maxSeconds] splits are the caller's job — see
  /// [splitAudio] — this step only compresses.
  Future<File> compressAudio(File audioFile, File outFile) async {
    final result = await _runFfmpeg([
      '-y',
      '-v',
      'error',
      '-i',
      audioFile.path,
      '-ac',
      '1',
      '-ar',
      '16000',
      '-b:a',
      '16k',
      outFile.path,
    ]);
    if (result.exitCode != 0 || !await outFile.exists()) {
      throw YtDlpException(
        'ffmpeg compression failed',
        details: result.stderr,
      );
    }
    return outFile;
  }

  /// Split [audioFile] into parts of at most [maxSeconds] each, for
  /// transcription uploads. Returns the part files in order. Parts are
  /// created in [outDir] and must be deleted by the caller.
  Future<List<File>> splitAudio(
    File audioFile,
    Directory outDir, {
    int maxSeconds = 1200,
  }) async {
    final result = await _runFfmpeg([
      '-y',
      '-v',
      'error',
      '-i',
      audioFile.path,
      '-f',
      'segment',
      '-segment_time',
      '$maxSeconds',
      '-c',
      'copy',
      '${outDir.path}/part_%03d.mp3',
    ]);
    if (result.exitCode != 0) {
      throw YtDlpException('ffmpeg split failed', details: result.stderr);
    }
    final parts = outDir
        .listSync()
        .whereType<File>()
        .where((f) => f.path.contains('part_'))
        .toList()
      ..sort((a, b) => a.path.compareTo(b.path));
    return parts;
  }

  /// ffmpeg invocation. Desktop: the bundled binary (Windows) or PATH.
  /// Android: there is no ffmpeg executable — the call goes to the bundled
  /// mobile-ffmpeg inside the YtDlpBridge (channel `memorybox/ytdlp`,
  /// method `ffmpeg`).
  Future<YtDlpResult> _runFfmpeg(List<String> args) async {
    if (Platform.isAndroid) {
      try {
        final out = await _channel.invokeMethod<Map<Object?, Object?>>(
          'ffmpeg',
          {'args': args},
        );
        return YtDlpResult(
          exitCode: (out?['exitCode'] as int?) ?? 1,
          stdout: (out?['stdout'] as String?) ?? '',
          stderr: (out?['stderr'] as String?) ?? '',
        );
      } on PlatformException catch (e) {
        throw YtDlpException('Android ffmpeg bridge failed', details: e.message);
      }
    }
    final ffmpeg = await ffmpegPath;
    final result = await Process.run(ffmpeg, args);
    return YtDlpResult(
      exitCode: result.exitCode,
      stdout: result.stdout as String,
      stderr: result.stderr as String,
    );
  }

  static String _humanMessage(String stderr) {
    final lower = stderr.toLowerCase();
    if (lower.contains('private video') || lower.contains('video is private')) {
      return 'This video is private.';
    }
    if (lower.contains('login required') || lower.contains('sign in')) {
      return 'This video needs a login Memory Box does not have.';
    }
    if (lower.contains('video unavailable') || lower.contains('not available')) {
      return 'This video is unavailable or was removed.';
    }
    if (lower.contains('unsupported url')) {
      return 'This link is not a video Memory Box can open.';
    }
    if (lower.contains('http error 429') || lower.contains('too many requests')) {
      return 'The source is rate-limiting requests. Try again later.';
    }
    final first = stderr
        .split('\n')
        .map((l) => l.trim())
        .firstWhere(
          (l) => l.startsWith('ERROR:'),
          orElse: () => 'yt-dlp failed. See details.',
        );
    return first.replaceFirst('ERROR: ', '');
  }

  /// Kept for tests of the ranking math only; not used by the app.
  @visibleForTesting
  static String humanMessageForTest(String stderr) => _humanMessage(stderr);
}
