#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MPV_SOURCE_DIR="${1:?usage: $0 <patched-mpv-source> <libplacebo-source>}"
LIBPLACEBO_SOURCE_DIR="${2:?usage: $0 <patched-mpv-source> <libplacebo-source>}"
MPV_SOURCE_DIR="$(realpath "$MPV_SOURCE_DIR")"
LIBPLACEBO_SOURCE_DIR="$(realpath "$LIBPLACEBO_SOURCE_DIR")"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rife-mpv-options.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
PREFIX="$WORK_DIR/prefix"
PLACEBO_BUILD="$WORK_DIR/libplacebo-build"
MPV_BUILD="$WORK_DIR/mpv-build"

# The option registry is platform-independent; build the pinned source as a
# small host libmpv so the test can call the same mpv_set_option_string API that
# the Android app calls before mpv_initialize().
meson setup "$PLACEBO_BUILD" "$LIBPLACEBO_SOURCE_DIR" \
  --prefix "$PREFIX" --libdir lib --default-library shared \
  -Ddemos=false -Dtests=false -Dbench=false -Dfuzz=false \
  -Dvulkan=disabled -Dopengl=disabled -Dglslang=disabled -Dshaderc=disabled \
  -Dlcms=disabled -Ddovi=disabled -Dlibdovi=disabled \
  -Dunwind=disabled -Dxxhash=disabled
meson compile -C "$PLACEBO_BUILD" -j2
meson install -C "$PLACEBO_BUILD"

export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
export LD_LIBRARY_PATH="$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
meson setup "$MPV_BUILD" "$MPV_SOURCE_DIR" \
  --prefix "$PREFIX" --libdir lib --default-library shared \
  -Dlibmpv=true -Dcplayer=false -Dbuild-date=false -Dmanpage-build=disabled \
  -Dgl=disabled -Dvulkan=disabled
meson compile -C "$MPV_BUILD" -j2

LIBMPV="$MPV_BUILD/libmpv.so"
if [[ ! -f "$LIBMPV" ]]; then
  echo "Expected the host libmpv at $LIBMPV" >&2
  exit 1
fi
cc -std=c11 -Wall -Wextra -Werror \
  -I"$MPV_SOURCE_DIR/include" \
  "$ROOT/scripts/tests/rife_mpv_option_runtime_test.c" "$LIBMPV" \
  -Wl,-rpath,"$MPV_BUILD" \
  -o "$WORK_DIR/rife-mpv-option-runtime-test"
"$WORK_DIR/rife-mpv-option-runtime-test"
