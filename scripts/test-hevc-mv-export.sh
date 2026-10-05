#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FFMPEG_VERSION="n9.0.2"
FFMPEG_ARCHIVE="$WORK/ffmpeg.tar.gz"
FFMPEG_SOURCE="$WORK/ffmpeg"
INSTALL_PREFIX="$WORK/install"
CLIP="$WORK/moving-bframes.hevc"

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "The hosted HEVC export test needs the ffmpeg CLI to create its deterministic fixture" >&2
  exit 1
fi
ENCODERS="$(ffmpeg -hide_banner -encoders 2>/dev/null)"
if ! grep -q 'libx265' <<<"$ENCODERS"; then
  echo "The hosted ffmpeg package must include its libx265 encoder for the synthetic B-frame fixture" >&2
  exit 1
fi

curl -fsSL "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/${FFMPEG_VERSION}.tar.gz" -o "$FFMPEG_ARCHIVE"
mkdir -p "$FFMPEG_SOURCE"
tar -xzf "$FFMPEG_ARCHIVE" -C "$FFMPEG_SOURCE" --strip-components=1
python3 "$ROOT/scripts/patch-ffmpeg-hevc-mvs.py" "$FFMPEG_SOURCE"

ffmpeg -hide_banner -loglevel error \
  -f lavfi -i 'testsrc2=size=320x180:rate=24' -frames:v 96 -an \
  -c:v libx265 -preset ultrafast \
  -x265-params 'keyint=24:min-keyint=24:bframes=3:scenecut=0:repeat-headers=1:pools=1:frame-threads=1' \
  -f hevc "$CLIP"

cd "$FFMPEG_SOURCE"
./configure \
  --prefix="$INSTALL_PREFIX" \
  --libdir="$INSTALL_PREFIX/lib" \
  --disable-programs \
  --disable-doc \
  --disable-x86asm \
  --disable-everything \
  --enable-decoder=hevc \
  --enable-parser=hevc \
  --enable-demuxer=hevc \
  --enable-protocol=file
make -j2
make install

PKG_CONFIG_PATH="$INSTALL_PREFIX/lib/pkgconfig" pkg-config --exists libavformat libavcodec libavutil
cc -O2 -Wall -Wextra \
  $(PKG_CONFIG_PATH="$INSTALL_PREFIX/lib/pkgconfig" pkg-config --cflags libavformat libavcodec libavutil) \
  "$ROOT/scripts/test-hevc-mv-export.c" \
  -o "$WORK/test-hevc-mv-export" \
  $(PKG_CONFIG_PATH="$INSTALL_PREFIX/lib/pkgconfig" pkg-config --libs libavformat libavcodec libavutil)
LD_LIBRARY_PATH="$INSTALL_PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  "$WORK/test-hevc-mv-export" "$CLIP"
