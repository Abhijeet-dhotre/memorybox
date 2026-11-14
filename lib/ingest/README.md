# ingest — Phase 2 built

- `ytdlp.dart` (Phase 1) — yt-dlp runner, desktop + Android bridge.
- `captions.dart` — WebVTT parser (strips inline tags, merges duplicate
  auto-caption cues) → timed `CaptionSegment`s.
- `chunker.dart` — 1–2 min chunks (target 90 s, hard cap 120 s, sentence
  boundaries where possible, tiny tails merged).
- `ingest_service.dart` — the pipeline: dedupe → Path A captions → Path B
  temp audio + Groq Whisper (deleted in `finally`) → chunk → Groq summary
  / key points / tags / dynamic category → ONNX embeddings when available
  → one-transaction save. Progress via `IngestStage`.
