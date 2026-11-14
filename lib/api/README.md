# api — Phase 2 built

- `groq.dart` — Groq client (plain `dart:io`, no wrapper package):
  Whisper `whisper-large-v3-turbo` for Path B transcription, chat
  `llama-3.3-70b-versatile` for summary / key points / tags / dynamic
  category. Key comes from `lib/db/key_store.dart`, never hardcoded.
- `tokenizer.dart` — WordPiece tokenizer for multilingual-e5-small,
  loaded from `assets/models/tokenizer.json`.
- `embeddings.dart` — on-device ONNX Runtime embeddings (quantized
  multilingual-e5-small, 384-dim, `passage:`/`query:` prefixes, mean
  pooling, L2-normalised). Model files are NOT in the repo — see the
  header of `embeddings.dart` for where to get them. When missing,
  ingest saves chunks without vectors and search falls back to text.
