# Memory Box

Send any video. Save only its words. Find it later by meaning.

Product Requirements Document: **PRD v1.2, 14 November 2026**
(`~/workspace/your_files/memory-box-prd/memory-box-prd.pdf` — the build follows
it exactly; the spec is not changed by this repo.)

## Stack (locked by the PRD)

- **Flutter / Dart**, one codebase for **Android + Windows**
- **SQLite**, single `.db` file — `videos`, `transcript_chunks`
  (1–2 min, timestamped), **FTS5** keyword index, **sqlite-vec** vector table
- **yt-dlp** bundled binary (Windows: `yt-dlp.exe` + `ffmpeg.exe`;
  Android: on-device via the VibeVault-proven `youtubedl-android` approach)
  - Path A: captions direct (`--skip-download --write-subs
    --write-auto-subs --sub-langs en`)
  - Path B: temp audio only → Groq Whisper → delete audio immediately
- **Groq API** (free tier) for Whisper transcription + summary / key points /
  tags. The Groq key is entered by the user in Settings, never hardcoded.
  No key is shipped in this repo.
- **ONNX Runtime**, quantized **multilingual-e5-small** (384-dim) on device
  for embeddings. Groq has no embeddings API — Groq is never used for vectors.
- **Transcript-only storage.** No video, no audio saved. Dedupe by
  extractor + video ID (e.g. `youtube:VIDEO_ID`). 30–50 KB per video,
  30–50 MB per 1,000 videos.
- **Hybrid search**: FTS5 keyword + sqlite-vec semantic, tiered ranking
  `VideoScore = 0.50×BestChunk + 0.30×min(StrongChunks,3)/3 + 0.20×MetadataMatch`,
  Tier 1 ≥ 0.70 + (≥2 strong chunks or metadata match), Tier 2 ≥ 0.45.
  Titles/tags/summary indexed as extra chunks (ASR errors like
  Claude → cloud).
- **Dynamic categories** (auto-created from content, never predefined).
  Filters: source, channel/account, category, date/language/length,
  video-only, search inside a single video.
- **V1 only**: local-only, no server, no account, no sync, **no ads**.
  Ads + admin dashboard are V2 — they are not built in V1.

## Build order (PRD §11.1, §13)

1. **Phase 0** — Flutter project scaffold (this commit).
2. **Phase 1 — proof gates** (see `PHASE1_REPORT.md`):
   (1) yt-dlp runs from Flutter on Android + Windows;
   (2) sqlite-vec loads on Android via the bundled SQLite build.
   If either fails, the failure is faced before UI work — not worked around
   by changing the spec.
3. **Phase 2** — core ingest (share sheet / paste box → Path A/B → chunk →
   Groq summary → SQLite).
4. **Phase 3** — search (FTS5 + vectors + tiered ranking). The 50-query
   labelled test set is built first; Tier 1 precision must be ≥ 80%
   before any search UI.
5. **Phase 4** — UI (home, memory cards, search, video detail with
   timestamp jump, filters, categories, export/import `.db`).
6. **Phase 5** — test on Android + Windows, fix, deliver V1.

## Status

- **Phase 0: done.** Flutter scaffold, `lib/` structure (`ui`, `db`,
  `ingest`, `search`, `api`), minimal compiling `main.dart`.
  `flutter analyze` passes — see `PHASE1_REPORT.md` for versions and
  build results.
- **Phase 1: see `PHASE1_REPORT.md`** for the per-gate PASS/FAIL evidence.
- **Phase 2–5: not started.** The `lib/db`, `lib/ingest`, `lib/search`,
  `lib/api`, `lib/ui` folders contain only short READMEs stating what is
  not built yet. There is no placeholder/stub implementation anywhere.

## Layout

```text
lib/
  main.dart        # minimal Phase 0 shell (compiles, states status)
  ui/              # screens — Phase 4
  db/              # SQLite + FTS5 + sqlite-vec access — Phase 2/3
  ingest/          # yt-dlp Path A/B, chunking — Phase 2
  search/          # hybrid ranking, tiered results — Phase 3
  api/             # Groq HTTP client (key from Settings only) — Phase 2
spikes/
  ytdlp_spike/     # Gate 1: yt-dlp called as a process from Dart
  sqlitevec_spike/ # Gate 2: sqlite-vec load + vec0 KNN from Dart
```

## Building

```sh
flutter pub get
flutter analyze
flutter build apk --debug        # Android (needs Android SDK 36)
flutter build windows            # Windows .exe — Windows machine only,
                                 # cannot be built on Linux
```

Toolchain used for Phase 0/1 (see `PHASE1_REPORT.md` for exact versions):
Flutter stable, JDK 17, Android SDK 36, yt-dlp 2026.10.26, ffmpeg 8.1.2.
