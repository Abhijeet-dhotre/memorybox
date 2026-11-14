// sqlite-vec native binding (asg017 sqlite-vec v0.1.6).
//
// Why this exists: the bundled SQLite in `package:sqlite3` v3 is compiled
// with SQLITE_OMIT_AUTHORIZATION, so the SQL function `load_extension()`
// always fails with "not authorized". The supported way to load an
// extension is the FFI entry point below, exactly as the sqlite3 package's
// own `example/custom_extension` does (it uses sqlite-vec as its example).
// Do NOT switch this to `SELECT load_extension(...)` — it cannot work with
// the bundled build. Do NOT switch to the `sqlite_vector` pub package
// (sqlite.ai) — that is a different extension with a different SQL API;
// the PRD locks sqlite-vec / vec0.
//
// The `sqlite3_vec_init` symbol is provided by the code asset bundled by
// hook/build.dart (desktop: downloaded release; Android: NDK-built
// libvec0.so from android/app/src/main/jniLibs/<abi>/). The binding is
// resolved lazily on first call, so importing this file is safe on every
// platform; only calling [loadSqliteVecExtension] touches the native code.

// ignore_for_file: non_constant_identifier_names

import 'dart:ffi';

import 'package:sqlite3/sqlite3.dart';

@Native<Int Function(Pointer<Void>, Pointer<Void>, Pointer<Void>)>()
external int sqlite3_vec_init(
  Pointer<Void> db,
  Pointer<Void> pzErrMsg,
  Pointer<Void> pApi,
);

/// Registers sqlite-vec on the [Sqlite3] instance, after which `vec0`
/// virtual tables can be created on any database it opens.
extension LoadSqliteVecExtension on Sqlite3 {
  void loadSqliteVecExtension() {
    ensureExtensionLoaded(
      SqliteExtension(
        Native.addressOf<
              NativeFunction<
                Int Function(Pointer<Void>, Pointer<Void>, Pointer<Void>)
              >
            >(sqlite3_vec_init)
            .cast(),
      ),
    );
  }
}
