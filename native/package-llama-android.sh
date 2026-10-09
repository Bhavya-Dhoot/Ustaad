#!/usr/bin/env bash
# Strip the Android binaries from a build dir and copy them to native/prebuilt/arm64-v8a/ under
# jniLibs-friendly names (they are executables, the lib*.so names only let the APK ship them).
# Usage: native/package-llama-android.sh <BUILD_DIR> [OUT_DIR]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="${1:?build dir}"; OUT="${2:-$ROOT/native/prebuilt/arm64-v8a}"
SDK="${ANDROID_SDK_ROOT:-/c/Users/DELL/AppData/Local/Android/Sdk}"
STRIP="${NDK:-$SDK/ndk/28.2.13676358}/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
mkdir -p "$OUT"
cp_strip() { cp "$BUILD/bin/$1" "$OUT/$2"; "$STRIP" "$OUT/$2"; ls -l "$OUT/$2" | awk '{print $5, $9}'; }
cp_strip llama-server    libllama-server.so
cp_strip ggml-rpc-server librpc-server.so
cp_strip llama-bench     libllama-bench.so
cp_strip llama-cli       libllama-cli.so
