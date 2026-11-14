package com.muse.memorybox;

import com.muse.memorybox.embed.HashEmbedder;
import com.muse.memorybox.model.Chunk;
import com.muse.memorybox.model.MemoryVideo;
import com.muse.memorybox.model.SearchHit;
import com.muse.memorybox.services.Chunker;
import com.muse.memorybox.services.SearchEngine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SearchEngineTest {

    private MemoryVideo video(long id, String title, String summary,
                              List<String> keyPoints, List<String> tags, String category) {
        MemoryVideo v = new MemoryVideo();
        v.id = id;
        v.canonicalKey = "youtube:v" + id;
        v.originalUrl = "https://example.com/" + id;
        v.title = title;
        v.channel = "Channel";
        v.platform = "youtube";
        v.dateSaved = "2026-10-0" + id;
        v.durationSeconds = 300;
        v.language = "en";
        v.summary = summary;
        v.keyPoints = keyPoints;
        v.tags = tags;
        v.category = category;
        return v;
    }

    private Map<Long, List<Chunk>> library(MemoryVideo[] videos, String[][] transcripts) {
        Map<Long, List<Chunk>> map = new LinkedHashMap<>();
        for (int i = 0; i < videos.length; i++) {
            List<Chunk> chunks = new ArrayList<>();
            int start = 0;
            for (String text : transcripts[i]) {
                Chunk c = new Chunk(videos[i].id, text, start, start + 55,
                        Chunk.KIND_TRANSCRIPT);
                c.embedding = HashEmbedder.embed(text);
                chunks.add(c);
                start += 55;
            }
            for (Chunk meta : Chunker.metadataChunks(videos[i])) {
                meta.embedding = HashEmbedder.embed(meta.text);
                chunks.add(meta);
            }
            map.put(videos[i].id, chunks);
        }
        return map;
    }

    @Test
    public void mangledNameIsFoundThroughMetadata() {
        // Transcript only ever says "cloud"; title/tags say "Claude".
        MemoryVideo mangled = video(1, "Claude AI Guide for Beginners",
                "A guide to Claude by Anthropic.",
                Arrays.asList("Claude is made by Anthropic"),
                Arrays.asList("Claude", "AI"), "AI");
        MemoryVideo other = video(2, "Sourdough Bread Basics",
                "Baking bread at home.",
                Arrays.asList("Use a starter"),
                Arrays.asList("baking", "bread"), "Cooking");
        Map<Long, List<Chunk>> lib = library(
                new MemoryVideo[]{mangled, other},
                new String[][]{
                        {"Today we learn cloud AI, the cloud assistant from Anthropic, for total beginners.",
                         "The biggest cloud mistake is a vague prompt. Give cloud context and examples.",
                         "Cloud projects keep your files in one place so cloud remembers your work.",
                         "To finish, my cloud workflow: draft with cloud, critique, then polish."},
                        {"Mix flour and water and let the starter rise overnight.",
                         "Knead the dough and bake at a high temperature."}});

        SearchEngine engine = new SearchEngine();
        List<SearchHit> hits = engine.search("Claude",
                Arrays.asList(mangled, other), lib, null);
        assertTrue("expected at least one hit", hits.size() >= 1);
        assertEquals(1L, hits.get(0).video.id);
        assertEquals(1, hits.get(0).tier);
        assertEquals(1.0, hits.get(0).metadataMatch, 0.001);
    }

    @Test
    public void aboutTopicRanksAboveRelated() {
        MemoryVideo about = video(1, "Git Branching Explained",
                "All about git branches.",
                Arrays.asList("git branch creates a pointer"),
                Arrays.asList("git", "branching"), "Coding");
        MemoryVideo related = video(2, "My Coding Setup Tour",
                "Tools I use for coding.",
                Arrays.asList("A good editor matters"),
                Arrays.asList("coding", "setup"), "Coding");
        Map<Long, List<Chunk>> lib = library(
                new MemoryVideo[]{about, related},
                new String[][]{
                        {"Git branching lets you work on features in isolation with git branches.",
                         "Create a git branch, commit on the git branch, then merge the git branch.",
                         "Deleting a git branch after merge keeps the git history clean."},
                        {"My desk setup for coding, with a mention that I use git sometimes.",
                         "Keyboards and monitors for long coding sessions."}});

        SearchEngine engine = new SearchEngine();
        List<SearchHit> hits = engine.search("git branching",
                Arrays.asList(about, related), lib, null);
        assertEquals(1L, hits.get(0).video.id);
        if (hits.size() > 1) {
            assertTrue(hits.get(0).tier <= hits.get(1).tier
                    || hits.get(0).videoScore >= hits.get(1).videoScore);
            assertTrue(hits.get(0).videoScore > hits.get(1).videoScore);
        }
    }

    @Test
    public void filtersAreAppliedBeforeRanking() {
        MemoryVideo yt = video(1, "Claude Tips", "Claude tips summary.",
                Arrays.asList("tip"), Arrays.asList("Claude"), "AI");
        MemoryVideo ig = video(2, "Claude Tips", "Claude tips summary.",
                Arrays.asList("tip"), Arrays.asList("Claude"), "AI");
        ig.platform = "instagram";
        String[][] transcripts = {
                {"Claude tips for better prompts with Claude every day."},
                {"Claude tips for better prompts with Claude every day."}};
        Map<Long, List<Chunk>> lib = library(new MemoryVideo[]{yt, ig}, transcripts);

        SearchEngine.Filters f = new SearchEngine.Filters();
        f.platform = "instagram";
        SearchEngine engine = new SearchEngine();
        List<SearchHit> hits = engine.search("Claude tips",
                Arrays.asList(yt, ig), lib, f);
        assertEquals(1, hits.size());
        assertEquals(2L, hits.get(0).video.id);
    }

    @Test
    public void inVideoSearchReturnsTimestamps() {
        MemoryVideo v = video(1, "Cooking Show", "Cooking.",
                Arrays.asList("x"), Arrays.asList("cooking"), "Cooking");
        List<Chunk> chunks = new ArrayList<>();
        Chunk c1 = new Chunk(1, "First we chop the onions and garlic.", 0, 55,
                Chunk.KIND_TRANSCRIPT);
        c1.embedding = HashEmbedder.embed(c1.text);
        Chunk c2 = new Chunk(1, "Then we add the tomatoes and simmer the sauce.", 55, 110,
                Chunk.KIND_TRANSCRIPT);
        c2.embedding = HashEmbedder.embed(c2.text);
        chunks.add(c1);
        chunks.add(c2);

        SearchEngine engine = new SearchEngine();
        List<SearchEngine.InVideoHit> hits = engine.searchWithinVideo(1, "tomatoes", chunks);
        assertEquals(1, hits.size());
        assertEquals(55, hits.get(0).timestampSeconds);
    }
}
