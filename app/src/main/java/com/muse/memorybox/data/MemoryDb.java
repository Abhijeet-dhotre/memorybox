package com.muse.memorybox.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import com.muse.memorybox.embed.HashEmbedder;
import com.muse.memorybox.model.Chunk;
import com.muse.memorybox.model.MemoryVideo;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single SQLite library file (PRD §11.2): videos, transcript chunks,
 * the FTS5 keyword index and chunk vectors in ONE .db file. Vectors are
 * stored as little-endian Float32 BLOBs — the exact layout sqlite-vec
 * uses — because Android's system SQLite cannot load extensions, so
 * cosine similarity is computed in Java in V1 (same approach as the
 * verified engine). If FTS5 is unavailable in the device SQLite build,
 * the app degrades cleanly: SearchEngine computes keyword scores in Java
 * with identical semantics.
 */
public final class MemoryDb extends SQLiteOpenHelper {

    public static final String DB_NAME = "memory_box.db";
    private static final int DB_VERSION = 2;

    private Boolean ftsAvailable; // lazy probe

    public MemoryDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS videos ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "canonical_key TEXT NOT NULL UNIQUE,"
                + "original_url TEXT NOT NULL,"
                + "thumbnail_url TEXT NOT NULL DEFAULT '',"
                + "title TEXT NOT NULL DEFAULT '',"
                + "channel TEXT NOT NULL DEFAULT '',"
                + "platform TEXT NOT NULL DEFAULT '',"
                + "date_saved TEXT NOT NULL,"
                + "duration_seconds INTEGER NOT NULL DEFAULT 0,"
                + "language TEXT NOT NULL DEFAULT '',"
                + "summary TEXT NOT NULL DEFAULT '',"
                + "key_points TEXT NOT NULL DEFAULT '[]',"
                + "tags TEXT NOT NULL DEFAULT '[]',"
                + "category TEXT NOT NULL DEFAULT 'Uncategorized')");
        db.execSQL("CREATE TABLE IF NOT EXISTS transcript_chunks ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "video_id INTEGER NOT NULL REFERENCES videos(id) ON DELETE CASCADE,"
                + "text TEXT NOT NULL,"
                + "start_seconds INTEGER NOT NULL DEFAULT 0,"
                + "end_seconds INTEGER NOT NULL DEFAULT 0,"
                + "kind TEXT NOT NULL DEFAULT 'transcript')");
        db.execSQL("CREATE TABLE IF NOT EXISTS chunk_vectors ("
                + "chunk_id INTEGER PRIMARY KEY REFERENCES transcript_chunks(id) ON DELETE CASCADE,"
                + "embedding BLOB NOT NULL,"
                + "dim INTEGER NOT NULL DEFAULT 384)");
        tryCreateFts(db);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunks_video_id ON transcript_chunks(video_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_videos_category ON videos(category)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_videos_platform ON videos(platform)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_videos_date_saved ON videos(date_saved)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v1.2: thumbnail link per video (only the link, PRD §10).
            db.execSQL("ALTER TABLE videos ADD COLUMN thumbnail_url "
                    + "TEXT NOT NULL DEFAULT ''");
        }
    }

    private boolean tryCreateFts(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5("
                    + "chunk_id UNINDEXED, text)");
            ftsAvailable = Boolean.TRUE;
            return true;
        } catch (SQLiteException e) {
            ftsAvailable = Boolean.FALSE;
            return false;
        }
    }

    private boolean ftsReady(SQLiteDatabase db) {
        if (ftsAvailable == null) return tryCreateFts(db);
        return ftsAvailable;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    public boolean existsByCanonicalKey(String canonicalKey) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM videos WHERE canonical_key = ? LIMIT 1",
                new String[]{canonicalKey})) {
            return c.moveToFirst();
        }
    }

    /**
     * Inserts one memory with all its chunks (embeddings computed here if
     * a chunk does not already carry one).
     *
     * @return the new video id, or -1 if the canonical key already exists.
     */
    public long insertMemory(MemoryVideo video, List<Chunk> chunks) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("canonical_key", video.canonicalKey);
            v.put("original_url", video.originalUrl);
            v.put("thumbnail_url", video.thumbnailUrl == null ? "" : video.thumbnailUrl);
            v.put("title", video.title);
            v.put("channel", video.channel);
            v.put("platform", video.platform);
            v.put("date_saved", video.dateSaved);
            v.put("duration_seconds", video.durationSeconds);
            v.put("language", video.language);
            v.put("summary", video.summary);
            v.put("key_points", MemoryVideo.listToJson(video.keyPoints));
            v.put("tags", MemoryVideo.listToJson(video.tags));
            v.put("category", video.category);
            long videoId = db.insertWithOnConflict("videos", null, v,
                    SQLiteDatabase.CONFLICT_IGNORE);
            if (videoId < 0) return -1; // duplicate (FR-2)
            video.id = videoId;

            boolean fts = ftsReady(db);
            for (Chunk chunk : chunks) {
                chunk.videoId = videoId;
                ContentValues cv = new ContentValues();
                cv.put("video_id", videoId);
                cv.put("text", chunk.text);
                cv.put("start_seconds", chunk.startSeconds);
                cv.put("end_seconds", chunk.endSeconds);
                cv.put("kind", chunk.kind);
                long chunkId = db.insertOrThrow("transcript_chunks", null, cv);
                chunk.id = chunkId;

                float[] emb = chunk.embedding != null
                        ? chunk.embedding : HashEmbedder.embed(chunk.text);
                chunk.embedding = emb;
                ContentValues vv = new ContentValues();
                vv.put("chunk_id", chunkId);
                vv.put("embedding", HashEmbedder.toBytes(emb));
                vv.put("dim", emb.length);
                db.insertOrThrow("chunk_vectors", null, vv);

                if (fts) {
                    try {
                        ContentValues fv = new ContentValues();
                        fv.put("chunk_id", chunkId);
                        fv.put("text", chunk.text);
                        db.insertOrThrow("chunks_fts", null, fv);
                    } catch (SQLiteException ignored) {
                        fts = false; // degrade to in-Java keyword index
                    }
                }
            }
            db.setTransactionSuccessful();
            return videoId;
        } finally {
            db.endTransaction();
        }
    }

    /** FR-12: deleting removes transcript, summary, chunks and search entries. */
    public void deleteVideo(long videoId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            List<Long> chunkIds = new ArrayList<>();
            try (Cursor c = db.rawQuery(
                    "SELECT id FROM transcript_chunks WHERE video_id = ?",
                    new String[]{String.valueOf(videoId)})) {
                while (c.moveToNext()) chunkIds.add(c.getLong(0));
            }
            db.delete("chunk_vectors", "chunk_id IN (SELECT id FROM transcript_chunks WHERE video_id = ?)",
                    new String[]{String.valueOf(videoId)});
            if (ftsReady(db)) {
                for (Long cid : chunkIds) {
                    try {
                        db.delete("chunks_fts", "chunk_id = ?",
                                new String[]{String.valueOf(cid)});
                    } catch (SQLiteException ignored) {
                        break;
                    }
                }
            }
            db.delete("transcript_chunks", "video_id = ?",
                    new String[]{String.valueOf(videoId)});
            db.delete("videos", "id = ?", new String[]{String.valueOf(videoId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * One-time cleanup (v1.1): removes the synthetic starter examples that
     * v1.0 seeded by default — they are the only memories whose original
     * URL points at example.com. Real user memories are never touched.
     *
     * @return how many example memories were removed.
     */
    public int deleteSeedExamples() {
        List<Long> ids = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id FROM videos WHERE original_url LIKE 'https://example.com/%'",
                null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        for (Long id : ids) deleteVideo(id);
        return ids.size();
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public List<MemoryVideo> getAllVideos() {
        List<MemoryVideo> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT * FROM videos ORDER BY date_saved DESC, id DESC", null)) {
            while (c.moveToNext()) out.add(readVideo(c));
        }
        return out;
    }

    public MemoryVideo getVideo(long id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT * FROM videos WHERE id = ?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? readVideo(c) : null;
        }
    }

    public List<Chunk> getChunks(long videoId) {
        List<Chunk> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT tc.id, tc.video_id, tc.text, tc.start_seconds, tc.end_seconds,"
                + " tc.kind, cv.embedding FROM transcript_chunks tc"
                + " LEFT JOIN chunk_vectors cv ON cv.chunk_id = tc.id"
                + " WHERE tc.video_id = ? ORDER BY tc.kind = 'transcript' DESC,"
                + " tc.start_seconds ASC, tc.id ASC";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(videoId)})) {
            while (c.moveToNext()) {
                Chunk chunk = new Chunk(c.getLong(1), c.getString(2),
                        c.getInt(3), c.getInt(4), c.getString(5));
                chunk.id = c.getLong(0);
                if (!c.isNull(6)) chunk.embedding = HashEmbedder.fromBytes(c.getBlob(6));
                out.add(chunk);
            }
        }
        return out;
    }

    public Map<Long, List<Chunk>> getAllChunksByVideo() {
        Map<Long, List<Chunk>> out = new LinkedHashMap<>();
        for (MemoryVideo v : getAllVideos()) {
            out.put(v.id, getChunks(v.id));
        }
        return out;
    }

    /**
     * Lightweight transcript texts for plain text search: ONE query, no
     * embedding vectors loaded or decoded — this is what keeps search
     * and filtering fast.
     */
    public Map<Long, List<Chunk>> getAllTranscriptTextsByVideo() {
        Map<Long, List<Chunk>> out = new LinkedHashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT video_id, text, start_seconds, end_seconds, kind"
                + " FROM transcript_chunks WHERE kind = 'transcript'"
                + " ORDER BY video_id ASC, start_seconds ASC, id ASC";
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                Chunk chunk = new Chunk(c.getLong(0), c.getString(1),
                        c.getInt(2), c.getInt(3), c.getString(4));
                out.computeIfAbsent(chunk.videoId, k -> new ArrayList<>()).add(chunk);
            }
        }
        return out;
    }

    public int countVideos() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM videos", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public List<String> categories() {
        return distinctColumn("category");
    }

    public List<String> channels() {
        return distinctColumn("channel");
    }

    public List<String> platforms() {
        return distinctColumn("platform");
    }

    private List<String> distinctColumn(String column) {
        List<String> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT DISTINCT " + column + " FROM videos WHERE " + column
                        + " <> '' ORDER BY " + column + " COLLATE NOCASE ASC", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Category management (§7.3: rename / merge / delete)
    // ------------------------------------------------------------------

    public void renameCategory(String from, String to) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("category", to);
        db.update("videos", v, "category = ?", new String[]{from});
    }

    public void mergeCategories(String from, String into) {
        renameCategory(from, into);
    }

    public void deleteCategory(String name) {
        renameCategory(name, "Uncategorized");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private MemoryVideo readVideo(Cursor c) {
        MemoryVideo v = new MemoryVideo();
        v.id = c.getLong(c.getColumnIndexOrThrow("id"));
        v.canonicalKey = c.getString(c.getColumnIndexOrThrow("canonical_key"));
        v.originalUrl = c.getString(c.getColumnIndexOrThrow("original_url"));
        int thumbCol = c.getColumnIndex("thumbnail_url");
        v.thumbnailUrl = thumbCol >= 0 && !c.isNull(thumbCol) ? c.getString(thumbCol) : "";
        v.title = c.getString(c.getColumnIndexOrThrow("title"));
        v.channel = c.getString(c.getColumnIndexOrThrow("channel"));
        v.platform = c.getString(c.getColumnIndexOrThrow("platform"));
        v.dateSaved = c.getString(c.getColumnIndexOrThrow("date_saved"));
        v.durationSeconds = c.getInt(c.getColumnIndexOrThrow("duration_seconds"));
        v.language = c.getString(c.getColumnIndexOrThrow("language"));
        v.summary = c.getString(c.getColumnIndexOrThrow("summary"));
        v.keyPoints = MemoryVideo.listFromJson(
                c.getString(c.getColumnIndexOrThrow("key_points")));
        v.tags = MemoryVideo.listFromJson(c.getString(c.getColumnIndexOrThrow("tags")));
        v.category = c.getString(c.getColumnIndexOrThrow("category"));
        return v;
    }

    /** Size of the single library file, for the honest 30–50 KB/video budget. */
    public long databaseSizeBytes(Context context) {
        File f = context.getDatabasePath(DB_NAME);
        return f.exists() ? f.length() : 0;
    }
}
