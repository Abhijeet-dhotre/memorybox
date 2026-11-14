// Groq API key storage (PRD §11, §12).
//
// The key is the user's own Groq free-tier key, entered once in Settings.
// It lives in the platform secure storage (Keychain on iOS/macOS, Keystore
// on Android, Credential Manager on Windows via flutter_secure_storage)
// and is NEVER written to the database, logs, or the repo. There is no
// default key and no hardcoded key anywhere in this app.

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class GroqKeyStore {
  static const _key = 'groq_api_key';

  static const _storage = FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
  );

  Future<String?> read() => _storage.read(key: _key);

  Future<bool> hasKey() async => (await read())?.isNotEmpty == true;

  Future<void> save(String key) => _storage.write(key: _key, value: key.trim());

  Future<void> clear() => _storage.delete(key: _key);
}
