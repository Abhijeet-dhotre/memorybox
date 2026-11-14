package com.muse.memorybox.services;

import com.muse.memorybox.embed.HashEmbedder;
import com.muse.memorybox.model.Chunk;
import com.muse.memorybox.model.MemoryVideo;
import com.muse.memorybox.model.SearchHit;
import com.muse.memorybox.util.Texts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hybrid search + §7.1 ranking for Memory Box, ported 1:1 from the
 * verified Dart SearchService.
 *
 *   VideoScore = 0.50*BestChunk + 0.30*min(StrongChunks,3)/3
 *              + 0.20*MetadataMatch
 *
 * Keyword and semantic rankings are merged with Reciprocal Rank Fusion
 * (k=60). BestChunk is the video's best hybrid score normalized per
 * query — raw cosine is never used directly as a threshold signal.
 * StrongChunks counts chunks whose raw cosine passes the strong-chunk
 * threshold. MetadataMatch is the IDF-weighted fraction rule calibrated
 * in §7.2 (fraction &gt;= 0.45 of the query's content-term weight in
 * title/tags → 1.0, in summary/keyPoints too → 0.5).
 *
 *   Tier 1: score &gt;= 0.70 AND (StrongChunks &gt;= 2 OR MetadataMatch &gt;= 0.5)
 *   Tier 2: 0.45 &lt;= score &lt; 0.70
 *   Below 0.45: suppressed unless no Tier 1/2 result exists.
 */
public final class SearchEngine {

    /** Ranking constants. Thresholds are calibrated per embedding model (§7.2). */
    public static final class Config {
        /** Calibrated for the shipped hash fallback embedder (§7.2 record:
         *  matching chunks cluster at cosine 0.17–0.35). The PRD starting
         *  value 0.62 applies to multilingual-e5-small only. */
        public double strongChunkThreshold = 0.25;
        public double tier1Threshold = 0.70;
        public double tier2Threshold = 0.45;
        public double rrfK = 60.0;
        public double keywordWeight = 1.0;
        public double semanticWeight = 1.0;
        public double metadataChunkBoost = 0.0;
    }

    /** §7.4 filters, applied BEFORE ranking. A null field = no filter. */
    public static final class Filters {
        public String platform;
        public String channel;
        public String category;
        public String language;
        public String savedFrom; // ISO date, inclusive
        public String savedTo;   // ISO date, inclusive
        public Integer minDurationSeconds;
        public Integer maxDurationSeconds;

        public boolean accepts(MemoryVideo v) {
            if (platform != null && !platform.equals(v.platform)) return false;
            if (channel != null && !channel.equals(v.channel)) return false;
            if (category != null && !category.equals(v.category)) return false;
            if (language != null && !language.equals(v.language)) return false;
            if (savedFrom != null && v.dateSaved.compareTo(savedFrom) < 0) return false;
            if (savedTo != null && v.dateSaved.compareTo(savedTo) > 0) return false;
            if (minDurationSeconds != null && v.durationSeconds < minDurationSeconds) return false;
            if (maxDurationSeconds != null && v.durationSeconds > maxDurationSeconds) return false;
            return true;
        }
    }

    /** Precomputed per-chunk signals consumed by {@link #rankVideos}. */
    public static final class ChunkScore {
        public final Chunk chunk;
        public final double cosine;
        public final double keywordScore;

        public ChunkScore(Chunk chunk, double cosine, double keywordScore) {
            this.chunk = chunk;
            this.cosine = cosine;
            this.keywordScore = keywordScore;
        }
    }

    /** One hit from in-video search (§7.4). */
    public static final class InVideoHit {
        public final Chunk chunk;
        public final String sentence;
        public final int timestampSeconds;
        public final double score;

        public InVideoHit(Chunk chunk, String sentence, int timestampSeconds, double score) {
            this.chunk = chunk;
            this.sentence = sentence;
            this.timestampSeconds = timestampSeconds;
            this.score = score;
        }
    }

    private final Config config;

    public SearchEngine() {
        this(new Config());
    }

    public SearchEngine(Config config) {
        this.config = config;
    }

    // ------------------------------------------------------------------
    // Public search entry points
    // ------------------------------------------------------------------

    /** Hybrid search over the whole library (§7.1). Filters applied first. */
    public List<SearchHit> search(String query, List<MemoryVideo> videos,
                                  Map<Long, List<Chunk>> chunksByVideo, Filters filters) {
        List<MemoryVideo> kept = new ArrayList<>();
        for (MemoryVideo v : videos) {
            if (filters == null || filters.accepts(v)) kept.add(v);
        }
        float[] queryEmbedding = HashEmbedder.embed(query);
        Map<Long, List<ChunkScore>> scores = new LinkedHashMap<>();
        for (MemoryVideo video : kept) {
            List<Chunk> chunks = chunksByVideo.get(video.id);
            if (chunks == null) chunks = new ArrayList<>();
            List<ChunkScore> list = new ArrayList<>();
            for (Chunk chunk : chunks) {
                float[] emb = chunk.embedding != null
                        ? chunk.embedding : HashEmbedder.embed(chunk.text);
                list.add(new ChunkScore(chunk,
                        HashEmbedder.cosine(queryEmbedding, emb),
                        keywordScore(query, chunk.text)));
            }
            scores.put(video.id, list);
        }
        return rankVideos(query, kept, scores, config);
    }

    /** §7.4 search inside one video: timestamped hits, best first. */
    public List<InVideoHit> searchWithinVideo(long videoId, String query, List<Chunk> chunks) {
        float[] queryEmbedding = HashEmbedder.embed(query);
        List<ChunkScore> inputs = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (chunk.videoId != videoId) continue;
            float[] emb = chunk.embedding != null
                    ? chunk.embedding : HashEmbedder.embed(chunk.text);
            inputs.add(new ChunkScore(chunk,
                    HashEmbedder.cosine(queryEmbedding, emb),
                    keywordScore(query, chunk.text)));
        }
        double[] hybrids = hybridScores(inputs, config);
        double maxHybrid = 0.0;
        for (double h : hybrids) if (h > maxHybrid) maxHybrid = h;
        List<InVideoHit> hits = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            ChunkScore input = inputs.get(i);
            if (input.keywordScore <= 0 && input.cosine < 0.15) continue;
            hits.add(new InVideoHit(input.chunk,
                    matchingSentenceFor(query, input.chunk.text),
                    input.chunk.startSeconds,
                    maxHybrid == 0 ? 0.0 : hybrids[i] / maxHybrid));
        }
        hits.sort((a, b) -> {
            int byScore = Double.compare(b.score, a.score);
            if (byScore != 0) return byScore;
            return Integer.compare(a.timestampSeconds, b.timestampSeconds);
        });
        return hits;
    }

    // ------------------------------------------------------------------
    // THE §7.1 RANKING FORMULA (pure function of precomputed scores)
    // ------------------------------------------------------------------

    public static List<SearchHit> rankVideos(String query, List<MemoryVideo> videos,
                                             Map<Long, List<ChunkScore>> chunkScoresByVideo,
                                             Config config) {
        List<ChunkScore> allInputs = new ArrayList<>();
        Map<Long, List<ChunkScore>> byVideo = new LinkedHashMap<>();
        for (MemoryVideo video : videos) {
            List<ChunkScore> list = chunkScoresByVideo.get(video.id);
            if (list == null || list.isEmpty()) continue;
            byVideo.put(video.id, list);
            allInputs.addAll(list);
        }
        if (allInputs.isEmpty()) return new ArrayList<>();

        double[] hybrids = hybridScores(allInputs, config);
        // Map each ChunkScore (identity) to its hybrid score.
        Map<ChunkScore, Double> hybridByInput = new HashMap<>();
        double globalMax = 0.0;
        for (int i = 0; i < allInputs.size(); i++) {
            hybridByInput.put(allInputs.get(i), hybrids[i]);
            if (hybrids[i] > globalMax) globalMax = hybrids[i];
        }

        // Corpus IDF for the MetadataMatch rule.
        List<Set<String>> corpusSets = new ArrayList<>();
        Map<Long, List<Chunk>> chunksById = new HashMap<>();
        for (MemoryVideo video : videos) {
            List<ChunkScore> list = byVideo.get(video.id);
            if (list == null) continue;
            List<Chunk> chunksOnly = new ArrayList<>();
            for (ChunkScore cs : list) chunksOnly.add(cs.chunk);
            chunksById.put(video.id, chunksOnly);
            Set<String> set = new HashSet<>(metadataTokens(video, chunksOnly, true));
            set.addAll(metadataTokens(video, chunksOnly, false));
            corpusSets.add(set);
        }
        Map<String, Double> idf = metadataIdf(corpusSets);

        List<SearchHit> results = new ArrayList<>();
        for (MemoryVideo video : videos) {
            List<ChunkScore> list = byVideo.get(video.id);
            if (list == null) continue;

            ChunkScore best = null;
            double bestHybrid = -1.0;
            int strong = 0;
            for (ChunkScore input : list) {
                double h = hybridByInput.get(input);
                if (h > bestHybrid) {
                    bestHybrid = h;
                    best = input;
                }
                if (input.cosine >= config.strongChunkThreshold) strong++;
            }
            double bestChunk = globalMax == 0 ? 0.0 : bestHybrid / globalMax;
            double meta = metadataMatchFor(query, video, chunksById.get(video.id), idf);
            double score = 0.50 * bestChunk
                    + 0.30 * Math.min(strong, 3) / 3.0
                    + 0.20 * meta;

            int tier;
            if (score >= config.tier1Threshold && (strong >= 2 || meta >= 0.5)) {
                tier = 1;
            } else if (score >= config.tier2Threshold) {
                tier = 2;
            } else {
                tier = 0;
            }
            results.add(new SearchHit(video, score, tier,
                    matchingSentenceFor(query, best.chunk.text),
                    best.chunk.startSeconds, strong, meta, bestChunk));
        }

        Comparator<SearchHit> order = (a, b) -> {
            int byTier = Integer.compare(tierRank(a), tierRank(b));
            if (byTier != 0) return byTier;
            int byScore = Double.compare(b.videoScore, a.videoScore);
            if (byScore != 0) return byScore;
            return a.video.title.compareTo(b.video.title);
        };

        List<SearchHit> shown = new ArrayList<>();
        for (SearchHit r : results) if (r.tier > 0) shown.add(r);
        if (!shown.isEmpty()) {
            shown.sort(order);
            return shown;
        }
        // Suppression rule: weak matches show only when nothing reached T1/T2.
        results.sort(order);
        return results;
    }

    private static int tierRank(SearchHit r) {
        return r.tier == 1 ? 0 : r.tier == 2 ? 1 : 2;
    }

    /** Reciprocal Rank Fusion (k=60) over keyword and semantic rankings. */
    static double[] hybridScores(List<ChunkScore> inputs, Config config) {
        int n = inputs.size();
        double[] out = new double[n];
        if (n == 0) return out;

        Integer[] byKeyword = indices(n);
        Arrays.sort(byKeyword, (a, b) -> {
            int c = Double.compare(inputs.get(b).keywordScore, inputs.get(a).keywordScore);
            return c != 0 ? c : tieBreak(inputs.get(a), inputs.get(b));
        });
        Integer[] byCosine = indices(n);
        Arrays.sort(byCosine, (a, b) -> {
            int c = Double.compare(inputs.get(b).cosine, inputs.get(a).cosine);
            return c != 0 ? c : tieBreak(inputs.get(a), inputs.get(b));
        });
        int[] rankKw = new int[n];
        int[] rankSem = new int[n];
        for (int i = 0; i < n; i++) {
            rankKw[byKeyword[i]] = i + 1;
            rankSem[byCosine[i]] = i + 1;
        }
        for (int i = 0; i < n; i++) {
            double hybrid = config.keywordWeight / (config.rrfK + rankKw[i])
                    + config.semanticWeight / (config.rrfK + rankSem[i]);
            if (!inputs.get(i).chunk.isTranscript()) {
                hybrid *= 1.0 + config.metadataChunkBoost;
            }
            out[i] = hybrid;
        }
        return out;
    }

    private static Integer[] indices(int n) {
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        return idx;
    }

    private static int tieBreak(ChunkScore a, ChunkScore b) {
        int byVideo = Long.compare(a.chunk.videoId, b.chunk.videoId);
        if (byVideo != 0) return byVideo;
        int byStart = Integer.compare(a.chunk.startSeconds, b.chunk.startSeconds);
        if (byStart != 0) return byStart;
        int byKind = Integer.compare(Chunk.kindOrdinal(a.chunk.kind),
                Chunk.kindOrdinal(b.chunk.kind));
        if (byKind != 0) return byKind;
        return a.chunk.text.compareTo(b.chunk.text);
    }

    // ------------------------------------------------------------------
    // MetadataMatch (mangled-name defence)
    // ------------------------------------------------------------------

    private static final Set<String> METADATA_STOPWORDS = new HashSet<>(Arrays.asList(
            "the", "a", "an", "of", "to", "in", "on", "for", "and", "or", "how",
            "what", "is", "are", "was", "were", "video", "videos", "about", "with",
            "my", "your", "this", "that", "it", "its", "by", "at", "from",
            // romanized Hindi function words
            "ka", "ki", "ke", "ko", "me", "mein", "hai", "hain", "se", "aur", "ek",
            "ye", "woh", "kya", "kaise", "liye", "bhi", "kar", "raha", "rahi",
            // Devanagari function words
            "का", "की", "के", "में", "है", "हैं", "और", "एक", "को", "से", "पर",
            "यह", "वह", "क्या", "कैसे"
    ));

    /** Query terms used for MetadataMatch: word tokens minus stopwords. */
    public static List<String> metadataTerms(String query) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String t : Texts.tokenizeWords(query)) {
            if (!METADATA_STOPWORDS.contains(t) && seen.add(t)) out.add(t);
        }
        return out;
    }

    /** IDF-weighted fraction threshold from the §7.2 calibration. */
    public static final double METADATA_FRACTION_THRESHOLD = 0.45;

    static Set<String> metadataTokens(MemoryVideo video, List<Chunk> chunks,
                                      boolean titleLevel) {
        Set<String> out = new HashSet<>();
        if (titleLevel) {
            out.addAll(Texts.tokenizeWords(video.title));
            for (String t : video.tags) out.addAll(Texts.tokenizeWords(t));
            for (Chunk c : chunks) {
                if (Chunk.KIND_TITLE.equals(c.kind) || Chunk.KIND_TAGS.equals(c.kind)) {
                    out.addAll(Texts.tokenizeWords(c.text));
                }
            }
        } else {
            out.addAll(Texts.tokenizeWords(video.summary));
            for (String k : video.keyPoints) out.addAll(Texts.tokenizeWords(k));
            for (Chunk c : chunks) {
                if (Chunk.KIND_SUMMARY.equals(c.kind) || Chunk.KIND_KEY_POINTS.equals(c.kind)) {
                    out.addAll(Texts.tokenizeWords(c.text));
                }
            }
        }
        return out;
    }

    /** idf(t) = ln(1 + N / df(t)) over the searched library's metadata. */
    static Map<String, Double> metadataIdf(List<Set<String>> corpusTokenSets) {
        Map<String, Integer> df = new HashMap<>();
        for (Set<String> set : corpusTokenSets) {
            for (String t : set) df.put(t, df.getOrDefault(t, 0) + 1);
        }
        int n = corpusTokenSets.size();
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            idf.put(e.getKey(), Math.log(1.0 + (double) n / e.getValue()));
        }
        return idf;
    }

    /**
     * MetadataMatch per §7.1: 1.0 title/tags, 0.5 summary/keyPoints only,
     * else 0 — measured as an IDF-weighted fraction of the query's content
     * terms (calibrated, §7.2). Pass idf == null for the plain any-term rule.
     */
    public static double metadataMatchFor(String query, MemoryVideo video,
                                          List<Chunk> chunks, Map<String, Double> idf) {
        List<String> terms = metadataTerms(query);
        if (terms.isEmpty()) return 0.0;
        Set<String> titleSet = metadataTokens(video, chunks, true);
        Set<String> summarySet = metadataTokens(video, chunks, false);

        if (idf == null) {
            for (String t : terms) if (titleSet.contains(t)) return 1.0;
            for (String t : terms) if (summarySet.contains(t)) return 0.5;
            return 0.0;
        }
        List<String> known = new ArrayList<>();
        for (String t : terms) if (idf.containsKey(t)) known.add(t);
        if (known.isEmpty()) return 0.0;
        double total = 0.0, inTitle = 0.0, inSummary = 0.0;
        for (String t : known) {
            double w = idf.get(t);
            total += w;
            if (titleSet.contains(t)) inTitle += w;
            if (summarySet.contains(t)) inSummary += w;
        }
        if (total == 0.0) return 0.0;
        if (inTitle / total >= METADATA_FRACTION_THRESHOLD) return 1.0;
        if ((inTitle + inSummary) / total >= METADATA_FRACTION_THRESHOLD) return 0.5;
        return 0.0;
    }

    // ------------------------------------------------------------------
    // Keyword score + matching sentence
    // ------------------------------------------------------------------

    static double keywordScore(String query, String text) {
        Set<String> q = new HashSet<>(Texts.tokenizeWords(query));
        if (q.isEmpty()) return 0.0;
        Set<String> c = new HashSet<>(Texts.tokenizeWords(text));
        int hits = 0;
        for (String t : q) if (c.contains(t)) hits++;
        return (double) hits / q.size();
    }

    /** The sentence of chunkText containing a query term; else the first. */
    public static String matchingSentenceFor(String query, String chunkText) {
        List<String> sentences = Texts.splitSentences(chunkText);
        if (sentences.isEmpty()) return "";
        Set<String> terms = new HashSet<>(metadataTerms(query));
        if (!terms.isEmpty()) {
            for (String s : sentences) {
                Set<String> tokens = new HashSet<>(Texts.tokenizeWords(s));
                for (String t : terms) {
                    if (tokens.contains(t)) return s;
                }
            }
        }
        return sentences.get(0);
    }
}
