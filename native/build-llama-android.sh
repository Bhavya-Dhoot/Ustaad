#!/usr/bin/env bash
# Cross-compile llama.cpp (llama-server, rpc-server, llama-bench, llama-cli)
# for Android arm64-v8a with the NDK, static ggml/llama, RPC backend on.
#
# Usage:  native/build-llama-android.sh [-v VARIANT] [-b BUILD_DIR] [-j JOBS] [-- extra cmake args]
#   VARIANT  default | dot | i8mm | sve2 | kleidi | opencl   (see case below; default = no -march, plain armv8-a)
#   BUILD_DIR  default: <repo>/third_party/build/android-<VARIANT>   (build/ is gitignored)
# Env overrides: NDK, CMAKE, NINJA, LLAMA_SRC, ANDROID_PLATFORM
# Output: <BUILD_DIR>/bin/{llama-server,ggml-rpc-server|rpc-server,llama-bench,llama-cli}
# Run native/package-llama-android.sh <BUILD_DIR> to strip and copy into native/prebuilt/arm64-v8a/.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/c/Users/DELL/AppData/Local/Android/Sdk}"
NDK="${NDK:-$SDK/ndk/28.2.13676358}"
CMAKE="${CMAKE:-/c/Program Files/CMake/bin/cmake.exe}"
NINJA="${NINJA:-$SDK/cmake/3.22.1/bin/ninja.exe}"
LLAMA_SRC="${LLAMA_SRC:-$ROOT/third_party/llama.cpp}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-31}"
VARIANT=default
JOBS="$(nproc 2>/dev/null || echo 8)"
BUILD_DIR=""

while getopts "v:b:j:" opt; do
  case "$opt" in
    v) VARIANT="$OPTARG" ;;
    b) BUILD_DIR="$OPTARG" ;;
    j) JOBS="$OPTARG" ;;
    *) exit 2 ;;
  esac
done
shift $((OPTIND - 1))
[ "${1:-}" = "--" ] && shift
EXTRA=("$@")

[ -z "$BUILD_DIR" ] && BUILD_DIR="$ROOT/third_party/build/android-$VARIANT"

# CPU flags. Snapdragon 8 Elite Gen 5 (SM8850): armv9.2, dotprod, i8mm, bf16, sve2, sme.
ARCH_ARGS=()
case "$VARIANT" in
  default) ;;                                                           # upstream default: baseline armv8-a
  dot)     ARCH_ARGS=(-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16) ;;
  i8mm)    ARCH_ARGS=(-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+fp16+i8mm) ;;
  sve2)    ARCH_ARGS=(-DGGML_CPU_ARM_ARCH=armv9-a+dotprod+fp16+i8mm+bf16+sve2) ;;
  kleidi)  ARCH_ARGS=(-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+fp16+i8mm -DGGML_CPU_KLEIDIAI=ON) ;;
  opencl)  ARCH_ARGS=(-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+fp16+i8mm
                      -DGGML_OPENCL=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON
                      -DGGML_OPENCL_EMBED_KERNELS=ON) ;;
  *) echo "unknown variant $VARIANT" >&2; exit 2 ;;
esac

W() { cygpath -m "$1"; }   # Git Bash path -> mixed Windows path for native cmake/ninja
mkdir -p "$BUILD_DIR"

"$CMAKE" -G Ninja \
  -S "$(W "$LLAMA_SRC")" -B "$(W "$BUILD_DIR")" \
  -DCMAKE_MAKE_PROGRAM="$(W "$NINJA")" \
  -DCMAKE_TOOLCHAIN_FILE="$(W "$NDK")/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="$ANDROID_PLATFORM" -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
  -DGGML_RPC=ON \
  -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF \
  -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_TOOLS=ON \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_APP=OFF \
  -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF \
  "${ARCH_ARGS[@]}" "${EXTRA[@]}"

"$CMAKE" --build "$(W "$BUILD_DIR")" -j "$JOBS" \
  --target llama-server ggml-rpc-server llama-bench llama-cli

echo "built variant=$VARIANT in $BUILD_DIR"
ls -l "$BUILD_DIR/bin" 2>/dev/null || true
