# db — Phase 1 built, Phase 2 completes it

- `vec_native.dart` — FFI binding for sqlite-vec's `sqlite3_vec_init` and
  the `loadSqliteVecExtension()` extension on the bundled `sqlite3`. This
  is the only supported load path: the bundled SQLite is compiled with
  `SQLITE_OMIT_AUTHORIZATION`, so `SELECT load_extension(...)` fails.
- `vec_loader.dart` — one-time extension load, `chunk_vectors` vec0 table
  (384-dim), KNN lookup, L2-distance → cosine conversion.

Phase 2 adds `database.dart` (videos / transcript_chunks / FTS5 /
categories) and `key_store.dart` (Groq key in secure storage).
