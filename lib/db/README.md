# db — Phase 2 built

- `vec_native.dart` (Phase 1) — FFI binding for sqlite-vec's
  `sqlite3_vec_init`; the only supported load path for the bundled SQLite.
- `vec_loader.dart` (Phase 1) — one-time extension load, `chunk_vectors`
  vec0 table (384-dim), KNN lookup, L2-distance → cosine conversion.
- `database.dart` — one SQLite file (`memorybox.db`): `videos`,
  `transcript_chunks`, `chunk_fts` (FTS5), `categories` (dynamic only),
  plus vec0. Dedupe by `dedupe_key`, one-transaction video insert,
  export/import of the .db file.
- `key_store.dart` — Groq API key in platform secure storage; never in
  the database, logs, or repo.
