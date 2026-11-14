# Memory Box — Android (native Java)

**Send any video. Save only its words. Find it later by meaning.**

Native Android app built from **PRD v1.2 (14 Nov 2026)** — the product
requirements document for Memory Box. Share or paste a link (YouTube,
Instagram, X, TikTok — anything yt-dlp supports). The app saves **only
the transcript text** (~30–50 KB per video including vectors + keyword
index), builds a summary, key points, tags and a dynamic category, and
later finds the video by meaning — even if you forgot the title. No
video, audio or thumbnail files are ever stored. Temporary audio is
deleted immediately after transcription, even on failure.

Local-first: **no server, no account, no analytics, no ads** (ads are a
locked V2-only design in PRD §14 and are deliberately absent). Your own
Groq API key is entered in Settings and stored only on the device.

> **Framework note:** this build is **native Android in Java**
> (package `com.muse.memorybox`), reusing the on-device yt-dlp approach
> already proven in VibeVault (`youtubedl-android` 0.18.1: bundled
> CPython + yt-dlp + ffmpeg). No Flutter, no cross-platform layer. The
> PRD also describes a Windows .exe from one shared codebase (§9,
> FR-14) — a native-Java Android app cannot also be the Windows app, so
> the Windows app is a separate build and is not in this repo.

## What it does

- **Save:** Android share sheet → Memory Box, or paste a link on Home.
- **Dedupe first:** `Canonicalizer.canonicalKey(url)` → e.g.
  `youtube:dQw4w9WgXcQ`. `youtu.be/…`, `youtube.com/watch?v=…&si=&t=`,
  `x.com` vs `twitter.com` all map to the same key; a duplicate stops
  the save before any download.
- **Path A:** yt-dlp fetches only the caption/SRT file — no media.
- **Path B (fallback):** audio-only download into a job temp dir →
  low-bitrate mono mp3 via the bundled ffmpeg → split into ≤25-minute
  sections for long videos → Groq Whisper transcribes each part →
  segments stitched with correct timestamps → **temp dir deleted in a
  `finally`, success or failure**.
- **Memory:** Groq chat model creates title, ~5-line summary, key
  points, tags, and a 1–2 word category (prompted to prefer an existing
  category). Models used: transcription `whisper-large-v3-turbo`, chat
  `openai/gpt-oss-120b`.
- Transcript is chunked (~1–2 min); title/tags/summary/key points are
  stored as **extra chunks of the same video** so ASR-mangled names
  stay findable.

## Search (§7.1, exact)

Chunks are ranked by keyword + semantic scores merged with
**Reciprocal Rank Fusion (k=60)**, then per video:

```
VideoScore = 0.50·BestChunk + 0.30·min(StrongChunks,3)/3 + 0.20·MetadataMatch
Tier 1: score ≥ 0.70 AND (StrongChunks ≥ 2 OR MetadataMatch ≥ 0.5)
Tier 2: 0.45 ≤ score < 0.70, or a single strong chunk with no metadata match
Below 0.45: suppressed unless no Tier 1/2 result exists (shown as weak)
```

MetadataMatch uses an IDF-weighted fraction rule: ≥45% of the query's
content-term weight must appear in title/tags (→1.0) or summary/key
points too (→0.5), so a generic word can't fake a match while a rare
entity ("Claude") carries a query. Every result shows the title, the
**exact matching sentence**, and its **timestamp**; tapping opens the
original at that timestamp. Filters (§7.4): category chips, platform,
channel, plus search-inside-one-video on the detail screen. Categories
are dynamic — created from content, rename/merge/delete in Settings.

**Mangled names:** a video whose transcript only ever says "cloud" is
still found — in Tier 1 — by searching "Claude", because its
title/tags/summary are indexed as separate chunks (unit-tested in
`SearchEngineTest`).

## Storage (§11.2)

One SQLite file, `memory_box.db`: `videos`, `transcript_chunks`,
`chunk_vectors` (little-endian Float32 BLOBs — the sqlite-vec layout),
`chunks_fts` (FTS5, best-effort). Android's system SQLite cannot load
extensions, so sqlite-vec cannot run on it in V1; cosine similarity is
computed in Java over the stored vectors, and if a device's SQLite
lacks FTS5 the search degrades to the in-Java keyword scoring with
identical semantics. Export/import in Settings moves the one file.

## Embeddings

Shipped V1 embedder: the deterministic **hash fallback** (word
unigrams + bigrams + character trigrams, FNV-1a signed hashing into
384 dims, L2-normalized). Honest limit: it measures lexical overlap,
not true semantics — it cannot bridge synonyms with no shared words or
different scripts; cross-language recall leans on the metadata chunks.
The production target, quantized multilingual-e5-small via ONNX, is
stubbed in `embed/OnnxEmbedderStub` with its integration steps; its
thresholds must be re-calibrated before it ships (§7.2 regression
rule).

## Calibration gate (§7.2)

`tools/calibration/` carries a labelled gate: 36-video seed library +
exactly 50 labelled queries. The app itself ships with an **empty
library**. As recorded in `tools/calibration/CALIBRATION.md`:

```
Tier 1 precision: 85.5% (65/76)   — target ≥ 80%
Tier 1-above-Tier 2 violations: 2
Recall@5  en: 74.3% · hi: 64.3% · hinglish: 66.7%   (reported separately, per §6)
Calibrated strong-chunk threshold for the hash embedder: 0.25
(the PRD's 0.62 starting value is for e5-small and is kept for that model)
```

Re-run after ANY change of embedding model, chunk size or threshold:

```
python3 tools/calibration/run_calibration.py
```

## Build

Android Studio project (AGP 8.7.3 / Gradle 8.11.1, JDK 17, min SDK 24,
target SDK 34, version 1.8 / versionCode 9, one APK per ABI + universal):

```
./gradlew assembleDebug        # APKs per ABI + universal
./gradlew testDebugUnitTest    # engine unit tests (JUnit 4)
```

## Project layout

```
app/src/main/java/com/muse/memorybox/
  model/       MemoryVideo, Chunk, Segment, SearchHit, MemoryDraft, VideoCategories
  data/        MemoryDb (SQLite), AppSettings (Groq key, secure on-device)
  services/    IngestionService, YtDlp, GroqClient, SearchEngine, SimpleSearch,
               Chunker, SrtParser, CategoryResolver
  embed/       HashEmbedder (ships), OnnxEmbedderStub (e5-small target)
  ui/          MainActivity, DetailActivity, SettingsActivity,
               ShareReceiverActivity, MemoryAdapter, ThumbnailLoader
  util/        Canonicalizer, UrlExtractor, Texts, AppLog, CrashReporter
app/src/main/res/          layouts, themes, drawables
app/src/test/              engine unit tests (JUnit 4)
tools/calibration/         50-query labelled gate + runner
```

## Still stubbed / honest limits

- ONNX e5-small embedder (stub; hash fallback ships).
- sqlite-vec native extension (vectors stored in its layout; cosine in Java).
- Instagram/X often need a login Memory Box does not have — those saves
  fail honestly with the reason, never a fake memory (PRD §5).
- Groq free-tier 429s surface as a clear "wait and retry" message.

## Known gaps in this repo snapshot

- **`gradle/wrapper/gradle-wrapper.jar` is not in this repo.** It is a
  binary and could not be pushed with this snapshot — restore it before
  building (`gradle wrapper` regenerates it, or copy it from a local
  checkout that has it). Without it `./gradlew` will not run.
- **`app/proguard-rules.pro` is missing.** `app/build.gradle` references
  it for the release build, so a release build needs that file added;
  debug builds are unaffected.
- No Groq API key is shipped anywhere in this repo — enter yours in
  Settings on first run.
