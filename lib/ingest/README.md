# ingest — Phase 1 built, Phase 2 completes it

- `ytdlp.dart` — `YtDlpRunner`: one Dart API over two platform paths.
  Desktop (Windows/Linux/macOS): bundled yt-dlp binary via `Process.run`.
  Android: MethodChannel `memorybox/ytdlp` → `YtDlpBridge.kt`, which uses
  the VibeVault-proven youtubedl-android library (no yt-dlp executable on
  Android). Covers Path A (captions only), Path B (temp audio), ffmpeg
  compress/split, and human-readable yt-dlp errors. No media is ever kept.

Phase 2 adds `captions.dart` (VTT parse), `chunker.dart` (1–2 min chunks),
and `ingest_service.dart` (dedupe → Path A/B → chunk → Groq summary/tags
→ embeddings → SQLite save; temp audio deleted in `finally`).
