#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
MPV_BUILDER_DIR="${MPV_BUILDER_DIR:-$ROOT/native-builder}"
MPV_SOURCE_DIR="${MPV_SOURCE_DIR:-$ROOT/mpv-source}"
BUILDSCRIPTS="$MPV_BUILDER_DIR/buildscripts"

[[ -d "$BUILDSCRIPTS" ]] || { echo "Missing pinned Android MPV build tools: $BUILDSCRIPTS" >&2; exit 1; }
[[ -d "$MPV_SOURCE_DIR/.git" ]] || { echo "Missing pinned MPV checkout: $MPV_SOURCE_DIR" >&2; exit 1; }
[[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] || { echo "ANDROID_HOME/ANDROID_SDK_ROOT is required" >&2; exit 1; }

export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export MPV_SOURCE_DIR
export DONT_BUILD_RELEASE=1
export CACHE_MODE=none
export cores="${MPVFLOW_MPV_BUILD_CORES:-2}"
export NDK_LIBS_OUT="$MPV_BUILDER_DIR/app/src/main/libs"
mkdir -p "$NDK_LIBS_OUT"

python3 - "$BUILDSCRIPTS/include/ci.sh" "$BUILDSCRIPTS/include/depinfo.sh" <<'PY'
from pathlib import Path
import sys

ci_path, depinfo_path = map(Path, sys.argv[1:])
lines = ci_path.read_text().splitlines()
expected = [
    'msg "Fetching mpv"',
    "mkdir -p deps/mpv",
    "$WGET https://github.com/mpv-player/mpv/archive/master.tar.gz -O master.tgz",
    "tar -xzf master.tgz -C deps/mpv --strip-components=1",
    "rm master.tgz",
]
starts = [i for i, line in enumerate(lines) if line.strip() == expected[0]]
if len(starts) != 1 or [line.strip() for line in lines[starts[0]:starts[0] + len(expected)]] != expected:
    raise SystemExit(f"Expected one pristine-mpv download block in {ci_path}; refusing unpinned build")
start = starts[0]
indent = lines[start][:len(lines[start]) - len(lines[start].lstrip())]
replacement = [
    f'{indent}msg "Using pinned MPV source checkout"',
    f'{indent}rm -rf deps/mpv',
    f'{indent}mkdir -p deps/mpv',
    f'{indent}git -C "$MPV_SOURCE_DIR" archive HEAD | tar -x -C deps/mpv',
]
lines[start:start + len(expected)] = replacement
ci = "\n".join(lines) + "\n"
for old, new in (
    ("./buildall.sh --only-deps mpv", "./buildall.sh --arch arm64 --only-deps mpv"),
    ("./buildall.sh -n mpv", "./buildall.sh --arch arm64 -n mpv"),
    ("./buildall.sh -n\n", "./buildall.sh --arch arm64 -n\n"),
):
    if ci.count(old) != 1:
        raise SystemExit(f"Expected exactly one {old!r} in {ci_path}, found {ci.count(old)}")
    ci = ci.replace(old, new, 1)
download_marker = "IN_CI=1 ./include/download-deps.sh\n"
if ci.count(download_marker) != 1:
    raise SystemExit(f"Expected one dependency download step in {ci_path}; refusing unsafe patch")
symver_patch = 'python3 "$GITHUB_WORKSPACE/scripts/patch-ffmpeg-symver.py" deps/ffmpeg/configure\n'
ci = ci.replace(download_marker, download_marker + symver_patch, 1)
ci_path.write_text(ci)
print("Prepared the pinned Android MPV builder and restored FFmpeg's Android symbol-version probe")

depinfo = depinfo_path.read_text()
qairt_dependency = "dep_mpv=(ffmpeg libass lua libplacebo qairt)"
if depinfo.count(qairt_dependency) != 1:
    raise SystemExit(f"Expected one MPV dependency list containing QAIRT in {depinfo_path}")
depinfo = depinfo.replace(qairt_dependency, "dep_mpv=(ffmpeg libass lua libplacebo)", 1)
if depinfo.count("v_ci_ffmpeg=n8.0.1") != 1:
    raise SystemExit(f"Expected the pinned FFmpeg 8.0.1 line in {depinfo_path}")
depinfo_path.write_text(depinfo.replace("v_ci_ffmpeg=n8.0.1", "v_ci_ffmpeg=n9.0.2", 1))
print("Excluded unused QAIRT and pinned FFmpeg 9.0.2 to match the MPV∞ arm64 AAR ABI")
PY

cd "$BUILDSCRIPTS"
./include/ci.sh install

cd "$ROOT"
python3 "$ROOT/scripts/prepare-mpvflow.py" --mpv-dir "$BUILDSCRIPTS/deps/mpv"

cd "$BUILDSCRIPTS"
./include/ci.sh build
APK="$(find "$MPV_BUILDER_DIR/app/build/outputs/apk" -type f -path '*/debug/*' -name '*arm64-v8a-debug*.apk' -print -quit)"
if [[ -z "$APK" ]]; then
  echo "Android MPVFlow runtime build finished but no arm64-v8a debug APK was found" >&2
  find "$MPV_BUILDER_DIR/app/build/outputs/apk" -maxdepth 5 -type f -print >&2 || true
  exit 1
fi

if [[ -n "${GITHUB_ENV:-}" ]]; then
  echo "MPVFLOW_RUNTIME_APK=$APK" >> "$GITHUB_ENV"
fi
echo "Built MPVFlow-only arm64 MPV runtime package: $APK"
