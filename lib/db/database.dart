// Memory Box database (PRD §10, §11.2, §12).
//
// One SQLite file, `memorybox.db`, in the app documents directory.
// Tables:
//   videos            — one row per saved video (dedupe key: extractor:id).
//   transcript_chunks — timestamped 1–2 min chunks; rowid == vec0 rowid.
//   chunk_fts         — FTS5 index over title + chunk text (+ summary/tags).
//   categories        — dynamic categories, created from content only.
//   chunk_vectors     — vec0 table, created by lib/db/vec_loader.dart.
// Everything is local-only (PRD V1): no server, no sync, no account.
//
// A video is only ever written inside one transaction, so a failed ingest
// never leaves a half-saved video behind (PRD §2: honest failure).

import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqlite3/sqlite3.dart';

import 'vec_loader.dart';

/// One saved video (a "memory").
class Video {
  Video({
    required this.id,
    required this.dedupeKey,
    required this.url,
    required this.title,
    required this.channel,
    required this.durationSeconds,
    required this.source,
    required this.summary,
    required this.keyPoints,
    required this.tags,
    required this.category,
    required this.language,
    required this.createdAt,
  });

  final int id;
  final String dedupeKey; // e.g. 'youtube:VIDEO_ID'
  final String url;
  final String title;
  final String channel;
  final int durationSeconds;
  final String source; // extractor, e.g. 'youtube'
  final String summary;
  final List<String> keyPoints;
  final List<String> tags;
  final String category; // dynamic, from content — never predefined
  final String language; // transcript language, e.g. 'en'
  final DateTime createdAt;
}

/// One timestamped transcript chunk (PRD §4: 1–2 minutes).
class TranscriptChunk {
  TranscriptChunk({
    required this.id,
    required this.videoId,
    required this.startSeconds,
    required this.endSeconds,
    required this.text,
  });

  final int id; // rowid — also the vec0 rowid
  final int videoId;
  final double startSeconds;
  final double endSeconds;
  final String text;
}

class MemoryBoxDatabase {
  MemoryBoxDatabase._(this._db);

  final Database _db;

  Database get raw => _db;

  static const _schemaVersion = 1;

  /// Opens (creating if needed) the app database and applies the schema.
  /// Call [ensureSqliteVecLoaded] first — see lib/db/vec_loader.dart.
  static Future<MemoryBoxDatabase> open() async {
    final dir = await getApplicationDocumentsDirectory();
    final path = p.join(dir.path, 'memorybox.db');
    final db = sqlite3.open(path);
    final mdb = MemoryBoxDatabase._(db);
    mdb._applySchema();
    return mdb;
  }

  /// In-memory database (used by the §7.2 test-set runner).
  static MemoryBoxDatabase openInMemory() {
    final db = sqlite3.openInMemory();
    final mdb = MemoryBoxDatabase._(db);
    mdb._applySchema();
    return mdb;
  }

  void _applySchema() {
    _db.execute('PRAGMA foreign_keys = ON');
    _db.execute('PRAGMA journal_mode = WAL');
    _db.execute('''
      CREATE TABLE IF NOT EXISTS videos (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        dedupe_key TEXT NOT NULL UNIQUE,
        url TEXT NOT NULL,
        title TEXT NOT NULL,
        channel TEXT NOT NULL DEFAULT '',
        duration_seconds INTEGER NOT NULL DEFAULT 0,
        source TEXT NOT NULL,
        summary TEXT NOT NULL DEFAULT '',
        key_points_json TEXT NOT NULL DEFAULT '[]',
        tags_json TEXT NOT NULL DEFAULT '[]',
        category TEXT NOT NULL DEFAULT 'Uncategorized',
        language TEXT NOT NULL DEFAULT 'en',
        created_at INTEGER NOT NULL
      )
    ''');
    _db.execute('''
      CREATE TABLE IF NOT EXISTS transcript_chunks (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        video_id INTEGER NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
        start_seconds REAL NOT NULL,
        end_seconds REAL NOT NULL,
        text TEXT NOT NULL
      )
    ''');
    _db.execute('''
      CREATE VIRTUAL TABLE IF NOT EXISTS chunk_fts USING fts5(
        title, text, tokenize='unicode61'
      )
    ''');
    _db.execute('''
      CREATE TABLE IF NOT EXISTS categories (
        name TEXT PRIMARY KEY,
        created_at INTEGER NOT NULL
      )
    ''');
    _db.execute(
      'CREATE INDEX IF NOT EXISTS idx_chunks_video ON transcript_chunks(video_id)',
    );
    _db.execute(
      'CREATE INDEX IF NOT EXISTS idx_videos_created ON videos(created_at DESC)',
    );
    _db.execute(
      'CREATE INDEX IF NOT EXISTS idx_videos_category ON videos(category)',
    );
    _db.execute(
      'CREATE INDEX IF NOT EXISTS idx_videos_source ON videos(source)',
    );
    ensureVecTable(_db);
    _db.execute('PRAGMA user_version = $_schemaVersion');
  }

  /// Runs [action] inside one transaction (BEGIN … COMMIT, ROLLBACK on
  /// error). `package:sqlite3` has no transaction helper, so this is the
  /// app's own. A video is only ever written through this, so a failed
  /// ingest never leaves a half-saved video behind (PRD §2).
  T _tx<T>(T Function() action) {
    _db.execute('BEGIN IMMEDIATE');
    try {
      final result = action();
      _db.execute('COMMIT');
      return result;
    } catch (e) {
      _db.execute('ROLLBACK');
      rethrow;
    }
  }

  void close() => _db.dispose();

  // -- videos -----------------------------------------------------------

  /// True when a video with this dedupe key is already saved (PRD §4:
  /// never save the same video twice — the UI offers to open the existing
  /// one instead).
  bool isSaved(String dedupeKey) {
    final rows = _db.select(
      'SELECT 1 FROM videos WHERE dedupe_key = ? LIMIT 1',
      [dedupeKey],
    );
    return rows.isNotEmpty;
  }

  int? videoIdFor(String dedupeKey) {
    final rows = _db.select(
      'SELECT id FROM videos WHERE dedupe_key = ? LIMIT 1',
      [dedupeKey],
    );
    return rows.isEmpty ? null : rows.first['id'] as int;
  }

  /// Inserts a video + its chunks + FTS rows + vec rows in ONE transaction.
  /// [embeddings] is parallel to [chunks] (one 384-dim vector per chunk);
  /// pass an empty list when the embedding model is unavailable — chunks
  /// are still saved and searchable by FTS, and vectors are backfilled
  /// later by [backfillEmbedding].
  int insertVideo({
    required Video video,
    required List<TranscriptChunk> chunks,
    required List<List<double>> embeddings,
  }) {
    assert(embeddings.isEmpty || embeddings.length == chunks.length);
    return _tx<int>(() {
      _db.execute(
        '''
        INSERT INTO videos
          (dedupe_key, url, title, channel, duration_seconds, source,
           summary, key_points_json, tags_json, category, language, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ''',
        [
          video.dedupeKey,
          video.url,
          video.title,
          video.channel,
          video.durationSeconds,
          video.source,
          video.summary,
          _json(video.keyPoints),
          _json(video.tags),
          video.category,
          video.language,
          video.createdAt.millisecondsSinceEpoch,
        ],
      );
      final videoId = _db.lastInsertRowId;
      _ensureCategory(video.category);
      for (var i = 0; i < chunks.length; i++) {
        final c = chunks[i];
        _db.execute(
          '''
          INSERT INTO transcript_chunks
            (video_id, start_seconds, end_seconds, text)
          VALUES (?, ?, ?, ?)
          ''',
          [videoId, c.startSeconds, c.endSeconds, c.text],
        );
        final chunkId = _db.lastInsertRowId;
        _db.execute(
          'INSERT INTO chunk_fts(rowid, title, text) VALUES (?, ?, ?)',
          [chunkId, video.title, c.text],
        );
        if (embeddings.isNotEmpty) {
          _insertVecRow(chunkId, embeddings[i]);
        }
      }
      // Title/tags/summary as an extra FTS row (PRD §7.1): ASR mis-hears
      // names ("Claude" -> "cloud"), so the correct spelling must be
      // searchable. Negative rowid keeps it out of the chunk rowid space.
      _db.execute(
        'INSERT INTO chunk_fts(rowid, title, text) VALUES (?, ?, ?)',
        [-videoId, video.title, '${video.summary} ${video.tags.join(' ')}'],
      );
      return videoId;
    });
  }

  void _insertVecRow(int chunkId, List<double> embedding) {
    final v = '[${embedding.map((e) => e.toStringAsFixed(6)).join(',')}]';
    _db.execute(
      'INSERT INTO chunk_vectors(rowid, embedding) VALUES (?, ?)',
      [chunkId, v],
    );
  }

  /// Backfills a vector for a chunk that was saved without one (embedding
  /// model was unavailable at ingest time).
  void backfillEmbedding(int chunkId, List<double> embedding) {
    _db.execute('DELETE FROM chunk_vectors WHERE rowid = ?', [chunkId]);
    _insertVecRow(chunkId, embedding);
  }

  void _ensureCategory(String name) {
    _db.execute(
      'INSERT OR IGNORE INTO categories(name, created_at) VALUES (?, ?)',
      [name, DateTime.now().millisecondsSinceEpoch],
    );
  }

  List<String> listCategories() {
    final rows = _db.select(
      'SELECT name FROM categories ORDER BY name COLLATE NOCASE',
    );
    return rows.map((r) => r['name'] as String).toList();
  }

  List<Video> listVideos({
    String? category,
    String? source,
    String? channel,
    int limit = 100,
    int offset = 0,
  }) {
    final where = <String>[];
    final args = <Object?>[];
    if (category != null) {
      where.add('category = ?');
      args.add(category);
    }
    if (source != null) {
      where.add('source = ?');
      args.add(source);
    }
    if (channel != null) {
      where.add('channel = ?');
      args.add(channel);
    }
    final sql =
        'SELECT * FROM videos'
        '${where.isEmpty ? '' : ' WHERE ${where.join(' AND ')}'}'
        ' ORDER BY created_at DESC LIMIT ? OFFSET ?';
    args.addAll([limit, offset]);
    return _db.select(sql, args).map(_rowToVideo).toList();
  }

  Video? getVideo(int id) {
    final rows = _db.select('SELECT * FROM videos WHERE id = ?', [id]);
    return rows.isEmpty ? null : _rowToVideo(rows.first);
  }

  List<TranscriptChunk> getChunks(int videoId) {
    return _db
        .select(
          'SELECT * FROM transcript_chunks WHERE video_id = ? '
          'ORDER BY start_seconds',
          [videoId],
        )
        .map(
          (r) => TranscriptChunk(
            id: r['id'] as int,
            videoId: r['video_id'] as int,
            startSeconds: (r['start_seconds'] as num).toDouble(),
            endSeconds: (r['end_seconds'] as num).toDouble(),
            text: r['text'] as String,
          ),
        )
        .toList();
  }

  /// Deletes a video and everything that belongs to it (chunks, FTS rows,
  /// vectors cascade through the transaction).
  void deleteVideo(int id) {
    _tx(() {
      final chunkIds = _db
          .select('SELECT id FROM transcript_chunks WHERE video_id = ?', [id])
          .map((r) => r['id'] as int)
          .toList();
      for (final cid in chunkIds) {
        _db.execute('DELETE FROM chunk_fts WHERE rowid = ?', [cid]);
        _db.execute('DELETE FROM chunk_vectors WHERE rowid = ?', [cid]);
      }
      _db.execute('DELETE FROM chunk_fts WHERE rowid = ?', [-id]);
      _db.execute('DELETE FROM transcript_chunks WHERE video_id = ?', [id]);
      _db.execute('DELETE FROM videos WHERE id = ?', [id]);
    });
  }

  int countVideos() {
    return _db.select('SELECT COUNT(*) AS n FROM videos').first['n'] as int;
  }

  int countChunks() {
    return _db
        .select('SELECT COUNT(*) AS n FROM transcript_chunks')
        .first['n'] as int;
  }

  // -- export / import (PRD §4, §12) -------------------------------------

  /// Copies the live database file to [destination] (used by Settings →
  /// Export). The caller picks the destination with a file picker.
  Future<void> exportTo(File destination) async {
    final dir = await getApplicationDocumentsDirectory();
    final src = File(p.join(dir.path, 'memorybox.db'));
    if (!await src.exists()) {
      throw StateError('No database file to export yet.');
    }
    await src.copy(destination.path);
  }

  /// Replaces the live database with [source] (Settings → Import). The app
  /// must be restarted afterwards — the caller shows that in the UI.
  Future<void> importFrom(File source) async {
    final dir = await getApplicationDocumentsDirectory();
    final dest = File(p.join(dir.path, 'memorybox.db'));
    _db.dispose();
    await source.copy(dest.path);
  }

  // -- helpers ----------------------------------------------------------

  static String _json(List<String> items) {
    final escaped = items.map((s) => '"${s.replaceAll(r'\', r'\\').replaceAll('"', r'\"')}"');
    return '[${escaped.join(',')}]';
  }

  static List<String> _fromJson(String raw) {
    // Stored as a simple JSON string array; parse tolerantly.
    final out = <String>[];
    final re = RegExp(r'"((?:[^"\\]|\\.)*)"');
    for (final m in re.allMatches(raw)) {
      out.add(m.group(1)!.replaceAll(r'\"', '"').replaceAll(r'\\', r'\'));
    }
    return out;
  }

  static Video _rowToVideo(Row r) {
    return Video(
      id: r['id'] as int,
      dedupeKey: r['dedupe_key'] as String,
      url: r['url'] as String,
      title: r['title'] as String,
      channel: r['channel'] as String,
      durationSeconds: r['duration_seconds'] as int,
      source: r['source'] as String,
      summary: r['summary'] as String,
      keyPoints: _fromJson(r['key_points_json'] as String),
      tags: _fromJson(r['tags_json'] as String),
      category: r['category'] as String,
      language: r['language'] as String,
      createdAt: DateTime.fromMillisecondsSinceEpoch(r['created_at'] as int),
    );
  }
}
