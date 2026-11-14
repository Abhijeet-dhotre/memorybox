#!/usr/bin/env python3
"""Memory Box §7.2 calibration runner (Python 3, stdlib only).

Mirrors the Dart implementation 1:1:
  * lib/services/embedding_service.dart — HashEmbeddingService
    (same tokenizer, same n-gram features/weights, same FNV-1a hash,
    same 384 dims, same signed hashing, same L2 normalization)
  * lib/services/search_service.dart — SearchService.rankVideos
    (same keyword score, same RRF k=60 merge, same BestChunk per-query
    normalization, same StrongChunks / MetadataMatch rules, same
    §7.1 formula, tiers and suppression)

Chunking note: each seed segment is treated as one transcript chunk
(the app's Chunker targets ~90 s chunks from ~55 s segments, so this
is the closest offline equivalent), plus the four metadata chunks
(title / tags / summary / keyPoints) exactly as Chunker.metadataChunks
builds them.

Usage: python3 tools/calibration/run_calibration.py
Exit code is 0 when the §7.2 gate passes (Tier1 precision >= 80%),
1 otherwise. Thresholds below are the CALIBRATED values for the hash
fallback embedder; see CALIBRATION.md for the record. The PRD starting
values for the production e5 model (strong 0.62) remain the defaults
in Dart SearchConfig — thresholds are per-embedder (§7.2).
"""

import json
import math
import os
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

# ---------------------------------------------------------------------------
# Calibrated configuration (mirrors Dart SearchConfig fields)
# ---------------------------------------------------------------------------
DIM = 384
STRONG_CHUNK_THRESHOLD = 0.25   # calibrated for HashEmbeddingService
TIER1_THRESHOLD = 0.70          # PRD §7.1 — unchanged
TIER2_THRESHOLD = 0.45          # PRD §7.1 — unchanged
RRF_K = 60.0                    # PRD §7.1 — unchanged
KEYWORD_WEIGHT = 1.0            # calibration knob — neutral
SEMANTIC_WEIGHT = 1.0           # calibration knob — neutral
METADATA_CHUNK_BOOST = 0.0      # calibration knob — off

KIND_INDEX = {"transcript": 0, "title": 1, "tags": 2, "summary": 3,
              "keyPoints": 4}

METADATA_STOPWORDS = {
    'the', 'a', 'an', 'of', 'to', 'in', 'on', 'for', 'and', 'or', 'how',
    'what', 'is', 'are', 'was', 'were', 'video', 'videos', 'about', 'with',
    'my', 'your', 'this', 'that', 'it', 'its', 'by', 'at', 'from',
    'ka', 'ki', 'ke', 'ko', 'me', 'mein', 'hai', 'hain', 'se', 'aur', 'ek',
    'ye', 'woh', 'kya', 'kaise', 'liye', 'bhi', 'kar', 'raha', 'rahi',
    'का', 'की', 'के', 'में', 'है', 'हैं', 'और', 'एक', 'को', 'से', 'पर',
    'यह', 'वह', 'क्या', 'कैसे',
}

# ---------------------------------------------------------------------------
# HashEmbeddingService mirror (embedding_service.dart)
# ---------------------------------------------------------------------------

def _is_word_char(ch):
    return unicodedata.category(ch)[0] in ("L", "M", "N")


def tokenize_words(text):
    words, buf = [], []
    for ch in text.lower():
        if _is_word_char(ch):
            buf.append(ch)
        elif buf:
            words.append("".join(buf))
            buf = []
    if buf:
        words.append("".join(buf))
    return words


def fnv1a32(s):
    h = 2166136261
    for byte in s.encode("utf-8"):
        h ^= byte
        h = (h * 16777619) & 0xFFFFFFFF
    return h


def hash_features(text):
    features = {}

    def add(key, weight):
        features[key] = features.get(key, 0.0) + weight

    words = tokenize_words(text)
    for i, w in enumerate(words):
        add("w:" + w, 1.0)
        if i + 1 < len(words):
            add("b:" + w + " " + words[i + 1], 0.8)
        chars = list(w)  # unicode code points
        if len(chars) < 3:
            add("c:" + w, 0.35)
        else:
            for j in range(len(chars) - 2):
                add("c:" + "".join(chars[j:j + 3]), 0.35)
    return features


def embed(text):
    vec = [0.0] * DIM
    for feature, weight in hash_features(text).items():
        h = fnv1a32(feature)
        sign = 1.0 if ((h >> 16) & 1) == 0 else -1.0
        vec[h % DIM] += sign * weight
    norm = math.sqrt(sum(v * v for v in vec))
    if norm == 0.0:
        return vec
    return [v / norm for v in vec]


def cosine(a, b):
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = sum(x * x for x in a)
    nb = sum(y * y for y in b)
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (math.sqrt(na) * math.sqrt(nb))

# ---------------------------------------------------------------------------
# SearchService.rankVideos mirror (search_service.dart)
# ---------------------------------------------------------------------------

def keyword_score(query, text):
    q = set(tokenize_words(query))
    if not q:
        return 0.0
    c = set(tokenize_words(text))
    return len(q & c) / len(q)


def metadata_terms(query):
    return [t for t in dict.fromkeys(tokenize_words(query))
            if t not in METADATA_STOPWORDS]


METADATA_FRACTION_THRESHOLD = 0.45


def _metadata_tokens(video, chunks, title_level):
    out = set()
    if title_level:
        out |= set(tokenize_words(video["title"]))
        for t in video["tags"]:
            out |= set(tokenize_words(t))
        for c in chunks:
            if c["kind"] in ("title", "tags"):
                out |= set(tokenize_words(c["text"]))
    else:
        out |= set(tokenize_words(video["summary"]))
        for k in video["keyPoints"]:
            out |= set(tokenize_words(k))
        for c in chunks:
            if c["kind"] in ("summary", "keyPoints"):
                out |= set(tokenize_words(c["text"]))
    return out


def metadata_idf(corpus_token_sets):
    df = {}
    for s in corpus_token_sets:
        for t in s:
            df[t] = df.get(t, 0) + 1
    n = len(corpus_token_sets)
    return {t: math.log(1 + n / d) for t, d in df.items()}


def metadata_match(query, video, chunks, idf):
    """IDF-weighted fraction rule — mirror of metadataMatchFor in Dart."""
    terms = metadata_terms(query)
    if not terms:
        return 0.0
    title_set = _metadata_tokens(video, chunks, True)
    summary_set = _metadata_tokens(video, chunks, False)
    terms = [t for t in terms if t in idf]
    if not terms:
        return 0.0
    total = sum(idf[t] for t in terms)
    in_title = sum(idf[t] for t in terms if t in title_set)
    in_summary = sum(idf[t] for t in terms if t in summary_set)
    if total == 0.0:
        return 0.0
    if in_title / total >= METADATA_FRACTION_THRESHOLD:
        return 1.0
    if (in_title + in_summary) / total >= METADATA_FRACTION_THRESHOLD:
        return 0.5
    return 0.0


def _tie_break_key(inp):
    c = inp["chunk"]
    return (c["videoId"], c["startSeconds"], KIND_INDEX[c["kind"]], c["text"])


def hybrid_scores(inputs):
    by_kw = sorted(inputs, key=lambda i: (-i["keyword"],) + _tie_break_key(i))
    by_cos = sorted(inputs, key=lambda i: (-i["cosine"],) + _tie_break_key(i))
    rank_kw = {id(i): r + 1 for r, i in enumerate(by_kw)}
    rank_sem = {id(i): r + 1 for r, i in enumerate(by_cos)}
    out = {}
    for i in inputs:
        h = (KEYWORD_WEIGHT / (RRF_K + rank_kw[id(i)])
             + SEMANTIC_WEIGHT / (RRF_K + rank_sem[id(i)]))
        if i["chunk"]["kind"] != "transcript":
            h *= 1.0 + METADATA_CHUNK_BOOST
        out[id(i)] = h
    return out


def rank_videos(query, videos, scores_by_video, chunks_by_video):
    """videos: list of seed dicts with int 'id'. Returns ranked results."""
    by_video, all_inputs = {}, []
    for v in videos:
        lst = scores_by_video.get(v["id"], [])
        if not lst:
            continue
        by_video[v["id"]] = lst
        all_inputs.extend(lst)
    if not all_inputs:
        return []
    hybrids = hybrid_scores(all_inputs)
    global_max = max(hybrids.values()) if hybrids else 0.0
    corpus_sets = []
    for v in videos:
        if v["id"] not in by_video:
            continue
        ch = chunks_by_video[v["id"]]
        corpus_sets.append(_metadata_tokens(v, ch, True)
                           | _metadata_tokens(v, ch, False))
    idf = metadata_idf(corpus_sets)

    results = []
    for v in videos:
        lst = by_video.get(v["id"])
        if lst is None:
            continue
        best_hybrid = max(hybrids[id(i)] for i in lst)
        strong = sum(1 for i in lst if i["cosine"] >= STRONG_CHUNK_THRESHOLD)
        best_chunk = best_hybrid / global_max if global_max else 0.0
        meta = metadata_match(query, v, [i["chunk"] for i in lst], idf)
        score = 0.50 * best_chunk + 0.30 * min(strong, 3) / 3 + 0.20 * meta
        if score >= TIER1_THRESHOLD and (strong >= 2 or meta >= 0.5):
            tier = 1
        elif score >= TIER2_THRESHOLD:
            tier = 2
        else:
            tier = 0
        results.append({"video": v, "score": score, "tier": tier,
                        "strong": strong, "meta": meta,
                        "bestChunk": best_chunk})

    def order(r):
        tier_rank = 0 if r["tier"] == 1 else (1 if r["tier"] == 2 else 2)
        return (tier_rank, -r["score"], r["video"]["title"])

    shown = [r for r in results if r["tier"] > 0]
    if shown:
        return sorted(shown, key=order)
    return sorted(results, key=order)

# ---------------------------------------------------------------------------
# Calibration run
# ---------------------------------------------------------------------------

def build_library():
    with open(os.path.join(ROOT, "assets", "seed_memories.json"),
              encoding="utf-8") as f:
        seeds = json.load(f)
    videos, chunks_by_video = [], {}
    for idx, s in enumerate(seeds):
        vid = idx + 1
        v = dict(s)
        v["id"] = vid
        videos.append(v)
        chunks = []
        for segm in s["segments"]:
            chunks.append({"videoId": vid, "text": segm["text"],
                           "startSeconds": segm["start"],
                           "kind": "transcript"})
        chunks.append({"videoId": vid, "text": s["title"],
                       "startSeconds": 0, "kind": "title"})
        chunks.append({"videoId": vid, "text": " ".join(s["tags"]),
                       "startSeconds": 0, "kind": "tags"})
        chunks.append({"videoId": vid, "text": s["summary"],
                       "startSeconds": 0, "kind": "summary"})
        chunks.append({"videoId": vid, "text": " ".join(s["keyPoints"]),
                       "startSeconds": 0, "kind": "keyPoints"})
        for c in chunks:
            c["embedding"] = embed(c["text"])
        chunks_by_video[vid] = chunks
    return videos, chunks_by_video


def main():
    videos, chunks_by_video = build_library()
    with open(os.path.join(HERE, "queries.json"), encoding="utf-8") as f:
        queries = json.load(f)
    by_seed = {v["seedId"]: v for v in videos}

    print("Memory Box §7.2 calibration — hash fallback embedder")
    print(f"Seed videos: {len(videos)}   Labelled queries: {len(queries)}")
    print("Thresholds: strongChunk=%.2f tier1=%.2f tier2=%.2f "
          "rrfK=%.0f keywordW=%.1f semanticW=%.1f metadataBoost=%.2f"
          % (STRONG_CHUNK_THRESHOLD, TIER1_THRESHOLD, TIER2_THRESHOLD,
             RRF_K, KEYWORD_WEIGHT, SEMANTIC_WEIGHT, METADATA_CHUNK_BOOST))
    print("-" * 72)

    tier1_returned = tier1_correct = 0
    violations = 0
    recall = {}  # language -> [hits, total]
    per_query_notes = []

    for q in queries:
        qemb = embed(q["query"])
        scores = {}
        for v in videos:
            lst = []
            for c in chunks_by_video[v["id"]]:
                lst.append({"chunk": c,
                            "cosine": cosine(qemb, c["embedding"]),
                            "keyword": keyword_score(q["query"], c["text"])})
            scores[v["id"]] = lst
        results = rank_videos(q["query"], videos, scores, chunks_by_video)
        position = {r["video"]["seedId"]: i for i, r in enumerate(results)}
        expected = {e["seedId"]: e["tier"] for e in q["expected"]}

        for r in results:
            if r["tier"] == 1:
                tier1_returned += 1
                if expected.get(r["video"]["seedId"]) == 1:
                    tier1_correct += 1
        t1s = [s for s, t in expected.items() if t == 1]
        t2s = [s for s, t in expected.items() if t == 2]
        for a in t1s:
            for b in t2s:
                if a in position and b in position and position[b] < position[a]:
                    violations += 1
        lang = q["language"]
        hits, total = recall.get(lang, (0, 0))
        top5 = [r["video"]["seedId"] for r in results[:5]]
        for seed_id in expected:
            total += 1
            if seed_id in top5:
                hits += 1
        recall[lang] = (hits, total)

        got_t1 = [r["video"]["seedId"] for r in results if r["tier"] == 1]
        missed_t1 = [s for s in t1s if s not in got_t1]
        extra_t1 = [s for s in got_t1 if expected.get(s) != 1]
        if missed_t1 or extra_t1:
            per_query_notes.append(
                f"  {q['id']} [{q['group']}/{lang}] '{q['query']}': "
                f"missed-T1={missed_t1} unexpected-T1={extra_t1}")

    precision = (tier1_correct / tier1_returned) if tier1_returned else 0.0
    print(f"Tier1 precision: {precision:.1%} "
          f"({tier1_correct}/{tier1_returned} returned Tier1 results correct) "
          f"— target >= 80%")
    print(f"Tier1-above-Tier2 violations: {violations} — target 0")
    print("Recall@5 by language (reported separately per §6, never averaged):")
    for lang in ("en", "hi", "hinglish"):
        if lang in recall:
            h, t = recall[lang]
            print(f"  {lang:8s}: {h / t:.1%} ({h}/{t} expected videos in top 5)")
    en_r = recall["en"][0] / recall["en"][1]
    hx_r = recall["hinglish"][0] / recall["hinglish"][1]
    print(f"Hinglish gap vs English: {(en_r - hx_r) * 100:.1f} points "
          f"(§6: a gap > 15 points must be declared a known limit)")
    if per_query_notes:
        print("Queries with Tier1 misses / surprises:")
        for n in per_query_notes:
            print(n)
    ok = precision >= 0.80
    print("-" * 72)
    print("GATE:", "PASS" if ok else "FAIL",
          "(Tier1 precision >= 80%)" if ok else
          "(Tier1 precision below 80% — do not ship search UI)")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
