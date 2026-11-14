// Memory Box — native-assets hook: bundle sqlite-vec (asg017) as a code
// asset so `package:sqlite3` can load it through `ensureExtensionLoaded`
// (see lib/db/vec_native.dart). This follows the pattern in the sqlite3
// package's own `example/custom_extension` (which uses sqlite-vec itself
// as the example extension).
//
// Desktop (Linux / Windows / macOS): downloads the official v0.1.6 loadable
// release from GitHub at build time (the same file the Phase 1 spike
// verified). Network access at build time is required for these targets.
//
// Android: there is no official sqlite-vec release binary for Android, so
// the hook copies the NDK-built `libvec0.so` from
// `android/app/src/main/jniLibs/<abi>/`. Build it first with
// `spikes/sqlitevec_spike/android/build_vec0.sh` (needs the Android NDK);
// the hook fails with a clear message if it is missing.
//
// The hook runs at build time only; it is never executed by the app.

import 'dart:io';

import 'package:code_assets/code_assets.dart';
import 'package:hooks/hooks.dart';
import 'package:http/http.dart';
import 'package:tar/tar.dart';

const _version = '0.1.6';

void main(List<String> args) async {
  await build(args, (input, output) async {
    if (!input.config.buildCodeAssets) return;

    final targetOS = input.config.code.targetOS;
    final arch = input.config.code.targetArchitecture;

    if (targetOS == OS.android) {
      final abi = switch (arch) {
        Architecture.arm64 => 'arm64-v8a',
        Architecture.arm => 'armeabi-v7a',
        Architecture.x64 => 'x86_64',
        _ => throw UnsupportedError('Unsupported Android ABI $arch'),
      };
      final prebuilt = File(
        'android/app/src/main/jniLibs/$abi/libvec0.so',
      );
      if (!prebuilt.existsSync()) {
        throw StateError(
          'Missing NDK-built sqlite-vec for $abi: ${prebuilt.path} does not '
          'exist. Run spikes/sqlitevec_spike/android/build_vec0.sh first '
          '(needs ANDROID_NDK_HOME set).',
        );
      }
      final target = input.outputDirectory.resolve('libvec0.so');
      await prebuilt.copy(target.toFilePath());
      output.assets.code.add(
        CodeAsset(
          package: 'memorybox',
          name: 'sqlite_vec.dart',
          file: target,
          linkMode: DynamicLoadingBundled(),
        ),
      );
      return;
    }

    final (osName, fileName) = switch (targetOS) {
      OS.linux => ('linux', 'vec0.so'),
      OS.macOS => ('macos', 'vec0.dylib'),
      OS.windows => ('windows', 'vec0.dll'),
      _ => throw UnsupportedError('Unsupported target OS $targetOS'),
    };
    final archName = switch (arch) {
      Architecture.x64 => 'x86_64',
      Architecture.arm64 => 'aarch64',
      _ => throw UnsupportedError('Unsupported target architecture $arch'),
    };

    final client = Client();
    final uri = Uri.parse(
      'https://github.com/asg017/sqlite-vec/releases/download/v$_version/'
      'sqlite-vec-$_version-loadable-$osName-$archName.tar.gz',
    );
    final response = await client.send(
      Request('GET', uri)..followRedirects = true,
    );
    if (response.statusCode != 200) {
      throw StateError(
        'Unexpected status code ${response.statusCode} for $uri',
      );
    }

    final reader = TarReader(response.stream.transform(gzip.decoder));
    var found = false;
    while (await reader.moveNext()) {
      final current = reader.current;
      if (current.name == fileName) {
        final targetFilePath = input.outputDirectory.resolve(fileName);
        await current.contents.pipe(
          File(targetFilePath.toFilePath()).openWrite(),
        );
        output.assets.code.add(
          CodeAsset(
            package: 'memorybox',
            name: 'sqlite_vec.dart',
            file: targetFilePath,
            linkMode: DynamicLoadingBundled(),
          ),
        );
        found = true;
        break;
      }
    }
    await reader.cancel();
    client.close();
    if (!found) throw StateError('Could not find $fileName in $uri');
  });
}
