#!/usr/bin/env bash
# Memory Box — build sqlite-vec (asg017, v0.1.6) for Android from source.
#
# Why from source: there is no official sqlite-vec release binary for
# Android, and the PRD locks sqlite-vec / vec0 (do NOT substitute the
# sqlite_vector pub package — different extension, different SQL API).
#
# Output: android/app/src/main/jniLibs/<abi>/libvec0.so for
# arm64-v8a, armeabi-v7a, x86_64. hook/build.dart copies these into the app
# as code assets at build time.
#
# Flags that matter:
#   -Wl,-z,max-page-size=16384 — Android 15+ (16 KB page size) requirement;
#   without it the .so is rejected at install/load time on 16 KB devices.
#   -fPIC / -shared — a loadable shared library, not a static archive.
#   API level 24 — matches the app's minSdk (see build.gradle.kts).
#
# Usage (from the repo root):
#   ANDROID_NDK_HOME=$HOME/workspace/android-sdk/ndk/android-ndk-r28c \
#     bash spikes/sqlitevec_spike/android/build_vec0.sh
# The script downloads the v0.1.6 source tarball once into
# spikes/sqlitevec_spike/android/src/ and reuses it on later runs.

set -euo pipefail
cd "$(dirname "$0")"

VERSION=0.1.6
NDK="${ANDROID_NDK_HOME:-$HOME/workspace/android-sdk/ndk/android-ndk-r28c}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SRC_DIR="$PWD/src/sqlite-vec-$VERSION"
OUT_DIR="$PWD/../../../android/app/src/main/jniLibs"

if [ ! -d "$TOOLCHAIN" ]; then
  echo "ERROR: NDK toolchain not found at $TOOLCHAIN" >&2
  echo "Set ANDROID_NDK_HOME to the NDK root (e.g. .../android-sdk/ndk/android-ndk-r28c)." >&2
  exit 1
fi

if [ ! -d "$SRC_DIR" ]; then
  mkdir -p "$PWD/src"
  TARBALL="$PWD/src/sqlite-vec-$VERSION.tar.gz"
  if [ ! -f "$TARBALL" ]; then
    echo "Downloading sqlite-vec v$VERSION source..."
    curl -fL -o "$TARBALL" \
      "https://github.com/asg017/sqlite-vec/releases/download/v$VERSION/sqlite-vec-$VERSION.tar.gz"
  fi
  tar -xzf "$TARBALL" -C "$PWD/src"
fi

# sqlite-vec.c includes sqlite-vec.h, which is generated at build time
# from sqlite-vec.h.tmpl (see the upstream Makefile rule).
if [ ! -f "$SRC_DIR/sqlite-vec.h" ]; then
  echo "Generating sqlite-vec.h from sqlite-vec.h.tmpl..."
  VERSION_FULL=$(cat "$SRC_DIR/VERSION")
  if command -v envsubst >/dev/null 2>&1; then
    VERSION="$VERSION_FULL" \
    DATE="$(date -r "$SRC_DIR/VERSION" +'%FT%TZ%z')" \
    SOURCE="release-v$VERSION_FULL" \
    VERSION_MAJOR="$(echo "$VERSION_FULL" | cut -d. -f1)" \
    VERSION_MINOR="$(echo "$VERSION_FULL" | cut -d. -f2)" \
    VERSION_PATCH="$(echo "$VERSION_FULL" | cut -d. -f3 | cut -d- -f1)" \
    envsubst < "$SRC_DIR/sqlite-vec.h.tmpl" > "$SRC_DIR/sqlite-vec.h"
  else
    python3 - "$SRC_DIR" "$VERSION_FULL" <<'PYEOF'
import sys, re
from datetime import datetime, timezone
from pathlib import Path
d, v = Path(sys.argv[1]), sys.argv[2]
parts = v.split('.')
tmpl = (d / 'sqlite-vec.h.tmpl').read_text()
out = tmpl
out = out.replace('${VERSION}', v).replace('$VERSION', v)
out = out.replace('${DATE}', datetime.fromtimestamp((d / 'VERSION').stat().st_mtime, timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ%z')).replace('$DATE', '')
out = out.replace('${SOURCE}', 'release-v' + v).replace('$SOURCE', 'release-v' + v)
out = out.replace('${VERSION_MAJOR}', parts[0]).replace('${VERSION_MINOR}', parts[1]).replace('${VERSION_PATCH}', parts[2].split('-')[0])
out = re.sub(r'\$\{?VERSION_(MAJOR|MINOR|PATCH)\}?', lambda m: {'MAJOR': parts[0], 'MINOR': parts[1], 'PATCH': parts[2].split('-')[0]}[m.group(1)], out)
(d / 'sqlite-vec.h').write_text(out)
PYEOF
  fi
fi

# sqlite-vec.c also needs sqlite3ext.h, which upstream vendors from the
# SQLite amalgamation (scripts/vendor.sh). Fetch it once into src/vendor/.
if [ ! -f "$SRC_DIR/vendor/sqlite3ext.h" ]; then
  echo "Vendoring SQLite amalgamation (for sqlite3ext.h)..."
  mkdir -p "$SRC_DIR/vendor"
  AMAL="$PWD/src/sqlite-amalgamation-3450300.zip"
  if [ ! -f "$AMAL" ]; then
    curl -fL -o "$AMAL" \
      "https://www.sqlite.org/2024/sqlite-amalgamation-3450300.zip"
  fi
  unzip -o -q "$AMAL" -d "$PWD/src"
  cp "$PWD/src/sqlite-amalgamation-3450300/sqlite3ext.h" "$SRC_DIR/vendor/"
  cp "$PWD/src/sqlite-amalgamation-3450300/sqlite3.h" "$SRC_DIR/vendor/" 2>/dev/null || true
fi

# sqlite-vec ships a single amalgamated C source for the loadable extension.
C_SOURCE="$SRC_DIR/sqlite-vec.c"
if [ ! -f "$C_SOURCE" ]; then
  echo "ERROR: $C_SOURCE not found in the v$VERSION source tree." >&2
  exit 1
fi
echo "Extension source: $C_SOURCE"

build_abi() {
  local abi="$1" target="$2" clang="$3"
  local out="$OUT_DIR/$abi"
  mkdir -p "$out"
  echo "--- $abi ($target) ---"
  "$TOOLCHAIN/bin/$clang" \
    -fPIC -shared -O2 \
    -Wl,-z,max-page-size=16384 \
    -I"$SRC_DIR" -I"$SRC_DIR/vendor" \
    "$C_SOURCE" \
    -o "$out/libvec0.so"
  ls -l "$out/libvec0.so"
  "$TOOLCHAIN/bin/llvm-readelf" -l "$out/libvec0.so" | grep -i 'LOAD' | head -3
}

build_abi arm64-v8a   aarch64-linux-android24  aarch64-linux-android24-clang
build_abi armeabi-v7a armv7a-linux-androideabi24 armv7a-linux-androideabi24-clang
build_abi x86_64      x86_64-linux-android24   x86_64-linux-android24-clang

echo "Done. libvec0.so built for arm64-v8a, armeabi-v7a, x86_64 in $OUT_DIR"
