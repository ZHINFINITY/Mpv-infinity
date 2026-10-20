#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <generated-root> <android-ndk-version> <mpv-runtime-aar>" >&2
  exit 2
fi

GENERATED_ROOT="$1"
NDK_VERSION="$2"
RUNTIME_AAR="$3"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NDK_DIR="$(python3 "$SCRIPT_DIR/android_ndk_path.py" "$NDK_VERSION" "$REPO_ROOT")"
FFMPEG_COMMIT="946fcce07b6dcd0331c8cc609192aeff5e1924f8"
FFMPEG_ARCHIVE_SHA256="0aa2b1de2a5698b20a23e93d539a9a8e82ca0117496c5bdf05d198805f42bb3b"
FFMPEG_ARCHIVE="$GENERATED_ROOT/ffmpeg-n9.0.2.tar.gz"
FFMPEG_SOURCE="$GENERATED_ROOT/ffmpeg-source"
RUNTIME_DIR="$GENERATED_ROOT/runtime/arm64-v8a"

[[ -d "$NDK_DIR/toolchains/llvm" ]] || { echo "Android NDK not found: $NDK_DIR" >&2; exit 1; }
[[ -f "$RUNTIME_AAR" ]] || { echo "MPV runtime AAR not found: $RUNTIME_AAR" >&2; exit 1; }

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) HOST_TAG="linux-x86_64" ;;
  Darwin-arm64) HOST_TAG="darwin-arm64" ;;
  Darwin-x86_64) HOST_TAG="darwin-x86_64" ;;
  *) echo "Unsupported host for Android NDK configure: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[[ -x "$TOOLCHAIN/aarch64-linux-android26-clang" ]] || {
  echo "Android arm64 API 26 clang is missing under $TOOLCHAIN" >&2
  exit 1
}
export PATH="$TOOLCHAIN:$PATH"

mkdir -p "$GENERATED_ROOT"
if [[ ! -f "$FFMPEG_SOURCE/config.h" || ! -f "$FFMPEG_SOURCE/config_components.h" || ! -f "$FFMPEG_SOURCE/libavutil/avconfig.h" ]]; then
  rm -rf "$FFMPEG_SOURCE"
  if [[ ! -f "$FFMPEG_ARCHIVE" ]]; then
    curl --fail --location --retry 3 --silent --show-error \
      "https://github.com/FFmpeg/FFmpeg/archive/${FFMPEG_COMMIT}.tar.gz" \
      --output "$FFMPEG_ARCHIVE"
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(sha256sum "$FFMPEG_ARCHIVE" | awk '{print $1}')"
  else
    ACTUAL_SHA256="$(shasum -a 256 "$FFMPEG_ARCHIVE" | awk '{print $1}')"
  fi
  [[ "$ACTUAL_SHA256" == "$FFMPEG_ARCHIVE_SHA256" ]] || {
    rm -f "$FFMPEG_ARCHIVE"
    echo "FFmpeg n9.0 source archive checksum mismatch" >&2
    exit 1
  }
  mkdir -p "$FFMPEG_SOURCE"
  tar -xzf "$FFMPEG_ARCHIVE" -C "$FFMPEG_SOURCE" --strip-components=1

  (
    cd "$FFMPEG_SOURCE"
    ./configure \
      --target-os=android \
      --arch=aarch64 \
      --cpu=armv8-a \
      --enable-cross-compile \
      --cross-prefix="$TOOLCHAIN/aarch64-linux-android-" \
      --cc="$TOOLCHAIN/aarch64-linux-android26-clang" \
      --ar="$TOOLCHAIN/llvm-ar" \
      --nm="$TOOLCHAIN/llvm-nm" \
      --ranlib="$TOOLCHAIN/llvm-ranlib" \
      --strip="$TOOLCHAIN/llvm-strip" \
      --disable-gpl \
      --disable-nonfree \
      --enable-version3 \
      --disable-everything \
      --enable-decoder=dca \
      --enable-swresample \
      --enable-pic \
      --enable-static \
      --disable-shared \
      --disable-programs \
      --disable-doc \
      --disable-network \
      --disable-autodetect
  )
fi

grep -Eq '^#define CONFIG_GPL 0$' "$FFMPEG_SOURCE/config.h" || {
  echo "FFmpeg header config did not record CONFIG_GPL=0" >&2; exit 1;
}
grep -Eq '^#define CONFIG_NONFREE 0$' "$FFMPEG_SOURCE/config.h" || {
  echo "FFmpeg header config did not record CONFIG_NONFREE=0" >&2; exit 1;
}
grep -Eq '^#define CONFIG_DCA_DECODER 1$' "$FFMPEG_SOURCE/config_components.h" || {
  echo "FFmpeg header config did not enable the DCA decoder" >&2; exit 1;
}
grep -Eq '^#define CONFIG_DCA_ENCODER 0$' "$FFMPEG_SOURCE/config_components.h" || {
  echo "FFmpeg header config unexpectedly enabled the DCA encoder" >&2; exit 1;
}
grep -Eq '^#define CONFIG_DCA_PARSER 0$' "$FFMPEG_SOURCE/config_components.h" || {
  echo "FFmpeg header config unexpectedly enabled the DCA parser" >&2; exit 1;
}
grep -Fq '#define FFMPEG_LICENSE "LGPL version 3 or later"' "$FFMPEG_SOURCE/config.h" || {
  echo "FFmpeg header config is not LGPL-3.0-or-later" >&2; exit 1;
}
ENABLED_DECODERS="$(grep -E '^#define CONFIG_.*_DECODER 1$' "$FFMPEG_SOURCE/config_components.h" || true)"
[[ "$ENABLED_DECODERS" == '#define CONFIG_DCA_DECODER 1' ]] || {
  echo "FFmpeg header config enables decoders beyond DCA: $ENABLED_DECODERS" >&2; exit 1;
}
if grep -Eq '^#define CONFIG_.*_(ENCODER|PARSER) 1$' "$FFMPEG_SOURCE/config_components.h"; then
  echo "FFmpeg header config unexpectedly enables an encoder or parser" >&2
  exit 1
fi

python3 "$REPO_ROOT/scripts/verify-media3-dca-runtime.py" "$RUNTIME_AAR" --abis arm64-v8a
mkdir -p "$RUNTIME_DIR"
for library in avcodec avutil swresample; do
  unzip -p "$RUNTIME_AAR" "jni/arm64-v8a/lib${library}.so" > "$RUNTIME_DIR/lib${library}.so"
done

echo "Prepared FFmpeg n9.0 headers and dynamically linked LGPL DCA runtime for arm64-v8a."
