# Calibration record — PRD §7.2 (search gate)

**Status: GATE PASSED for the V1 fallback embedder** (Tier 1 precision
85.5% ≥ 80% target). This record was produced BEFORE any search UI work,
as §7.2 requires. Re-run after ANY change of embedding model, chunk
size or threshold (§7.2 regression rule):

```
python3 tools/calibration/run_calibration.py
~/workspace/.dart-sdk/dart-sdk/bin/dart run test/search_ranking_test.dart
~/workspace/.dart-sdk/dart-sdk/bin/dart run test/mangled_name_test.dart
```

## What was calibrated

The §7.1 formula structure was NOT changed:

```
VideoScore = 0.50*BestChunk + 0.30*min(StrongChunks,3)/3 + 0.20*MetadataMatch
Tier 1: score >= 0.70 AND (StrongChunks >= 2 OR MetadataMatch >= 0.5)
Tier 2: 0.45 <= score < 0.70 · below 0.45 suppressed unless no T1/T2 exists
Hybrid: keyword + semantic rankings merged with Reciprocal Rank Fusion, k=60
```

Embedder under test: **HashEmbeddingService**, the deterministic lexical
fallback that ships in V1 (word unigrams + bigrams + character trigrams,
FNV-1a signed hashing into 384 dims, L2-normalized — see
`lib/services/embedding_service.dart`). The Python runner ports that
embedder and `SearchService.rankVideos` exactly; Dart↔Python outputs
were verified bit-identical (same nonzero dimensions and checksums for
English, Devanagari and romanized-Hinglish inputs, overlap cosine
0.735533 in both). The production model (quantized multilingual-e5-small,
ONNX stub) needs its OWN calibration run before it ships — thresholds
do not transfer between embedders.

## Test set composition

- Seed library: `assets/seed_memories.json` — **36 synthetic videos**
  (26 English, 6 Hindi-Devanagari, 4 romanized Hinglish), 4–10
  transcript segments each, topics: AI/Claude, coding, cooking, fitness,
  finance, history, music, GATE prep, blogging, internet. Includes two
  mangled-name videos (s03, s04) whose transcripts say only "cloud"
  while title/tags/summary say "Claude", and two long videos (9–10
  segments).
- Queries: `tools/calibration/queries.json` — **exactly 50 labelled
  queries**: 15 exact/paraphrase (en), 10 mangled-name (en), 10
  broad-topic (en), 15 cross-language (8 Devanagari-Hindi, 7 romanized
  Hinglish). Each expected video was hand-labelled Tier 1 (mainly about
  the topic) or Tier 2 (wider field) before tuning, per §7.2.
- Chunking in the runner: each seed segment = one transcript chunk,
  plus the four metadata chunks (title / tags / summary / keyPoints)
  exactly as `Chunker.metadataChunks` builds them.

## Thresholds chosen (hash fallback)

| Constant | PRD start | Calibrated | Notes |
|---|---|---|---|
| strongChunkThreshold | 0.62 | **0.25** | Hash-embedding cosines for genuinely matching chunks cluster at 0.17–0.35 (short query vs ~55 s chunk dilutes cosine); 0.62 would count almost nothing as strong. 0.62 remains the Dart `SearchConfig` default for the e5 model; the fallback must be constructed with 0.25. |
| tier1Threshold | 0.70 | 0.70 | unchanged |
| tier2Threshold | 0.45 | 0.45 | unchanged |
| RRF k | 60 | 60 | unchanged |
| keywordWeight / semanticWeight | 1.0 / 1.0 | 1.0 / 1.0 | neutral won the sweep |
| metadataChunkBoost | 0 | 0 | off; 0.15 was tried, no gain |
| MetadataMatch fraction | any-term | **IDF-weighted ≥ 0.45** | See below. |

One rule refinement (not a formula change): §7.1's "the searched term
appears in the title or tags" is measured as an **IDF-weighted fraction
of the query's content terms** — at least 45% of the query's total term
weight (idf = ln(1 + N/df) over the searched library) must appear in
title/tags for 1.0, or in title/tags/summary/keyPoints combined for
0.5. The plain any-term rule failed calibration badly: generic words
("basics", "works", "work") in unrelated titles produced Tier 1 false
positives (SQL for a Git query, a blogging video for a Claude query).
With IDF weighting, rare entity terms ("Claude", "git") carry a query
while filler words cannot fake a match. Terms absent from every
video's metadata are dropped from the fraction.

Knob sweep (Tier 1 precision / violations / recall@5 en·hi·hinglish):
strong 0.25 + fraction 0.45 → **.842→.855 after two labelling
consistency fixes / 2 / .74·.64·.67** (chosen — best balance);
strong 0.25 + fraction 0.50 → .877 / 3 / .73·.64·.60 (Hinglish recall
drops); strong 0.28 + fraction 0.50 → .925 / 4 / .71·.64·.60 (recall
and ordering degrade); strong 0.22 → .79–.81 (below gate at fraction
0.50); keywordWeight 1.2 and metadataBoost 0.15 → no improvement.

## Measured results (actual runner output)

```
Memory Box §7.2 calibration — hash fallback embedder
Seed videos: 36   Labelled queries: 50
Thresholds: strongChunk=0.25 tier1=0.70 tier2=0.45 rrfK=60 keywordW=1.0 semanticW=1.0 metadataBoost=0.00
------------------------------------------------------------------------
Tier1 precision: 85.5% (65/76 returned Tier1 results correct) — target >= 80%
Tier1-above-Tier2 violations: 2 — target 0
Recall@5 by language (reported separately per §6, never averaged):
  en      : 74.3% (78/105 expected videos in top 5)
  hi      : 64.3% (9/14 expected videos in top 5)
  hinglish: 66.7% (10/15 expected videos in top 5)
Hinglish gap vs English: 7.6 points (§6: a gap > 15 points must be declared a known limit)
GATE: PASS (Tier1 precision >= 80%)
```

Dart tests: `test/search_ranking_test.dart` — 22/22 PASS (formula
arithmetic, single-strong-chunk caps at exactly 0.60 and can never be
Tier 1, suppression, tier ordering, filters).
`test/mangled_name_test.dart` — 10/10 PASS: query "Claude" finds the
"cloud"-only-transcript video in **Tier 1, score 1.000**, via its
metadata chunks (MetadataMatch 1.0, 4 strong chunks, all metadata).

## Per-language results, stated plainly (§6)

- **English recall@5: 74.3%.** Even the baseline is modest. The hash
  embedder is lexical: paraphrases that share vocabulary work,
  synonyms with no shared words do not ("artificial intelligence"
  misses the machine-learning and LLM videos, whose transcripts never
  use that phrase). This is the fallback's ceiling, not a tuning miss.
- **Hindi (Devanagari) recall@5: 64.3%.** Same-script queries match
  same-script videos well; the misses are cross-script expectations
  (English videos labelled relevant for Hindi queries), which a lexical
  embedder cannot bridge. Metadata in the video's own script is what
  carries Hindi retrieval.
- **Romanized Hinglish recall@5: 66.7% — 7.6 points below English.**
  The gap is **within** the §6 15-point declaration threshold, so V1
  does not carry a §6 known-limit declaration for Hinglish on this
  set. But the honest reading is narrower than the headline: Hinglish
  recall is propped up by English-named entities (Claude) and by
  Hinglish videos' own romanized metadata; pure romanized-Hindi
  vocabulary queries (q44–q46, q48–q50) are the weakest slice, and
  Whisper's inconsistent real-world Hinglish spellings will make live
  results worse than this synthetic set. No material may claim
  "works in Hinglish" without the 66.7% number beside it (§6).

## Known limits of this calibration

1. **Tier1-above-Tier2 violations: 2, target 0 — not met.** In two
   cases a labelled Tier 2 Claude video outranks a labelled Tier 1
   for a narrow Claude query (the system cannot lexically separate
   "Claude for coding" from "Claude prompting tips" beyond the one
   distinguishing word). The §7.2 build gate (Tier 1 precision ≥ 80%)
   passes; the stricter §15 ordering ideal does not, on the fallback
   embedder. Expected to close with real e5 embeddings; must be
   re-measured then.
2. **Tier 1 precision is bought partly with Claude-cluster density:**
   11 of 76 returned Tier 1 results are wrong, mostly Claude-adjacent
   videos for narrow Claude queries and one Flutter tutorial for
   "ChatGPT tutorial" (shared generic term "tutorial" at the 0.45
   fraction edge).
3. Per-query BestChunk normalization means the best video of any
   result set always scores ≥ 0.50; the tier-0 "weak match" display
   branch therefore only fires under raised tier thresholds. This is
   a property of the locked §7.1 formula, recorded here so nobody
   "fixes" it by changing the formula.
4. The seed library is synthetic and written by the same hand as the
   queries; a user-built library will be noisier. Treat all numbers
   as an upper bound for the fallback embedder.
