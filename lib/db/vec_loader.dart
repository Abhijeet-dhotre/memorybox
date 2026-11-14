// Loads sqlite-vec into the app's bundled SQLite and creates the vector
// table the PRD requires (one 384-dim vector per chunk, §10/§11.2).
//
// The extension is bundled by hook/build.dart as a native code asset:
// desktop targets download the official v0.1.6 release at build time,
// Android uses the NDK-built libvec0.so from
// android/app/src/main/jniLibs/<abi>/ (see
// spikes/sqlitevec_spike/android/build_vec0.sh). Loading happens once, at
// app start, before any database is opened; [ensureVecTable] is then called
// on every database open so the `vec0` table exists.

import 'package:sqlite3/sqlite3.dart';

import 'vec_native.dart';

/// The vector dimension for the whole app (PRD §11: quantized
/// multilingual-e5-small, 384 dimensions). Changing this requires a
/// re-embed of every chunk and re-runs the §7.2 test set.
const vecDimensions = 384;

/// Loads sqlite-vec into the process-wide bundled SQLite instance.
/// Safe to call more than once; later calls are no-ops.
void ensureSqliteVecLoaded() {
  _loaded ??= () {
    sqlite3.loadSqliteVecExtension();
    return true;
  }();
}

bool? _loaded;

/// Creates the vec0 vector table on [db] if it does not exist yet.
/// The table holds one row per chunk (rowid == transcript_chunks.id) and
/// one 384-dim float vector per row. Call after [ensureSqliteVecLoaded].
void ensureVecTable(Database db) {
  db.execute(
    'CREATE VIRTUAL TABLE IF NOT EXISTS chunk_vectors '
    'USING vec0(embedding float[$vecDimensions])',
  );
}

/// Runs a nearest-neighbour lookup for [queryEmbedding] (already
/// L2-normalised, 384 floats) and returns up to [limit] (chunk rowid,
/// L2 distance) pairs, nearest first. Callers convert distance to cosine
/// similarity with `1 - distance^2 / 2` (valid for normalised vectors).
List<(int, double)> knn(
  Database db,
  List<double> queryEmbedding, {
  int limit = 10,
}) {
  final q = '[${queryEmbedding.map((e) => e.toStringAsFixed(6)).join(',')}]';
  final rows = db.select(
    'SELECT rowid, distance FROM chunk_vectors '
    'WHERE embedding MATCH ? ORDER BY distance LIMIT ?',
    [q, limit],
  );
  return rows
      .map((r) => (r['rowid'] as int, (r['distance'] as num).toDouble()))
      .toList();
}

/// Cosine similarity between the query and a chunk, from the L2 distance
/// sqlite-vec returns. Only valid when both vectors are L2-normalised,
/// which the embedding service guarantees.
double cosineFromDistance(double distance) =>
    (1.0 - (distance * distance) / 2.0).clamp(-1.0, 1.0);
