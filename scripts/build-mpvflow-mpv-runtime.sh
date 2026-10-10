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

python3 - \
  "$BUILDSCRIPTS/include/ci.sh" \
  "$BUILDSCRIPTS/include/depinfo.sh" \
  "$BUILDSCRIPTS/include/download-deps.sh" \
  "$BUILDSCRIPTS/scripts/libplacebo.sh" \
  "$MPV_BUILDER_DIR/app/src/main/jni/Android.mk" \
  "$BUILDSCRIPTS/scripts/mpv.sh" <<'PY'
from pathlib import Path
import sys

ci_path, depinfo_path, download_path, libplacebo_path, android_mk_path, mpv_script_path = map(Path, sys.argv[1:])
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

download = download_path.read_text()
libplacebo_clone = "[ ! -d libplacebo ] && git clone --recursive https://github.com/haasn/libplacebo"
if download.count(libplacebo_clone) != 1:
    raise SystemExit(f"Expected one unpinned libplacebo clone in {download_path}")
libplacebo_revision = "0d043c7f6f79cd3687c023454bdacbe615e4d96f"
download = download.replace(
    libplacebo_clone,
    libplacebo_clone + "\n" +
    f"git -C libplacebo checkout --detach {libplacebo_revision}\n" +
    "git -C libplacebo submodule update --init --recursive",
    1,
)
print(f"Pinned libplacebo {libplacebo_revision} to satisfy MPV and GPU Flow APIs")

glslang_revision = "1062752a891c95b2bfeed9e356562d88f9df84ac"
glslang_insert_after = "git -C libplacebo submodule update --init --recursive\n"
if download.count(glslang_insert_after) != 1:
    raise SystemExit(f"Expected one pinned libplacebo submodule update in {download_path}")
glslang_download = (
    "if [ ! -d glslang/.git ]; then\n"
    "\tgit init glslang\n"
    "\tgit -C glslang remote add origin https://github.com/KhronosGroup/glslang.git\n"
    "fi\n"
    f"git -C glslang fetch --depth 1 origin {glslang_revision}\n"
    "git -C glslang checkout --detach FETCH_HEAD\n"
    "git -C glslang submodule update --init --recursive --depth 1\n"
)
download = download.replace(glslang_insert_after, glslang_insert_after + glslang_download, 1)
download_path.write_text(download)
print(f"Pinned Android SPIR-V compiler glslang {glslang_revision}")

libplacebo_script = libplacebo_path.read_text()
libplacebo_setup = "unset CC CXX\nmeson setup $build --cross-file \"$prefix_dir\"/crossfile.txt \\\n\t-Dvulkan=enabled -Ddemos=false\n"
libplacebo_setup_replacement = '''unset CC CXX
glslang_build="$DIR/deps/glslang/_build$ndk_suffix"
cmake -S "$DIR/deps/glslang" -B "$glslang_build" -G Ninja \\
\t-DCMAKE_TOOLCHAIN_FILE="$DIR/sdk/android-ndk-${v_ndk}/build/cmake/android.toolchain.cmake" \\
\t-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared \\
\t-DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix_dir" \\
\t-DCMAKE_INSTALL_LIBDIR=lib -DCMAKE_INSTALL_INCLUDEDIR=include \\
\t-DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF \\
\t-DGLSLANG_ENABLE_INSTALL=ON -DGLSLANG_TESTS=OFF \\
\t-DENABLE_GLSLANG_BINARIES=OFF -DENABLE_SPIRV=ON -DENABLE_SPVREMAPPER=OFF \\
\t-DENABLE_HLSL=OFF -DENABLE_OPT=OFF -DENABLE_PCH=OFF
cmake --build "$glslang_build" --parallel "$cores"
cmake --install "$glslang_build"
for library in libSPIRV.a libglslang.a libglslang-default-resource-limits.a; do
\t[ -f "$prefix_dir/lib/$library" ] || { echo "Missing Android glslang library: $prefix_dir/lib/$library" >&2; exit 1; }
done
export CPPFLAGS="${CPPFLAGS:-} -I$prefix_dir/include"
export LDFLAGS="${LDFLAGS:-} -L$prefix_dir/lib -lc++"
meson setup $build --cross-file "$prefix_dir"/crossfile.txt \\
\t-Dvulkan=enabled -Dglslang=enabled -Dvulkan-sdk="$prefix_dir" \\
\t-Dopengl=enabled -Ddefault_library=shared -Ddemos=false
'''
if libplacebo_script.count(libplacebo_setup) != 1:
    raise SystemExit(f"Expected one Vulkan-enabled libplacebo setup in {libplacebo_path}")
libplacebo_path.write_text(libplacebo_script.replace(libplacebo_setup, libplacebo_setup_replacement, 1))
print("Cross-built static Android glslang and required Vulkan/SPIR-V compilation in shared libplacebo; retained OpenGL")

mpv_script = mpv_script_path.read_text()
mpv_vulkan_option = "\t\t-Dmanpage-build=disabled\n"
if mpv_script.count(mpv_vulkan_option) != 1:
    raise SystemExit(f"Expected one MPV Meson options block in {mpv_script_path}")
mpv_script_path.write_text(mpv_script.replace(
    mpv_vulkan_option,
    mpv_vulkan_option.rstrip("\n") + " -Dvulkan=enabled\n",
    1,
))
print("Required MPV's Android Vulkan backend instead of accepting an OpenGL-only native package")

android_mk = android_mk_path.read_text()
mpv_block = """include $(CLEAR_VARS)
LOCAL_MODULE := libmpv
LOCAL_SRC_FILES := $(PREFIX)/lib/libmpv.so
LOCAL_EXPORT_C_INCLUDES := $(PREFIX)/include
include $(PREBUILT_SHARED_LIBRARY)
"""
if android_mk.count(mpv_block) != 1:
    raise SystemExit(f"Expected exactly one libmpv prebuilt module in {android_mk_path}")
android_mk = android_mk.replace(
    mpv_block,
    mpv_block + "\n" + """include $(CLEAR_VARS)
LOCAL_MODULE := libplacebo
LOCAL_SRC_FILES := $(PREFIX)/lib/libplacebo.so
include $(PREBUILT_SHARED_LIBRARY)
""",
    1,
)
mpv_player_dependencies = "LOCAL_SHARED_LIBRARIES := swscale avcodec mpv"
if android_mk.count(mpv_player_dependencies) != 1:
    raise SystemExit(f"Expected exactly one libplayer dependency list in {android_mk_path}")
android_mk_path.write_text(android_mk.replace(
    mpv_player_dependencies,
    "LOCAL_SHARED_LIBRARIES := swscale avcodec mpv placebo",
    1,
))
print("Registered libplacebo.so in ndk-build and linked it into libplayer so the runtime APK stages it")

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
MPV_PREFIX="$BUILDSCRIPTS/prefix/arm64/usr/local/lib"
MPV_LIBRARY="$MPV_PREFIX/libmpv.so"
PLACEBO_LIBRARY="$MPV_PREFIX/libplacebo.so"
PLACEBO_PC="$MPV_PREFIX/pkgconfig/libplacebo.pc"
[[ -f "$MPV_LIBRARY" && -f "$PLACEBO_LIBRARY" && -f "$PLACEBO_PC" ]] || {
  echo "MPVFlow native prefix is missing libmpv, libplacebo, or libplacebo.pc" >&2
  exit 1
}
PLACEBO_VERSION="$(sed -n 's/^Version: *//p' "$PLACEBO_PC")"
[[ -n "$PLACEBO_VERSION" ]] || { echo "Could not read the installed libplacebo version" >&2; exit 1; }
if ! readelf -d "$MPV_LIBRARY" | grep -Eq 'Shared library: \[libplacebo\.so([.][0-9]+)*\]'; then
  echo "MPVFlow libmpv.so must dynamically use the shared libplacebo.so runtime" >&2
  exit 1
fi
if ! strings "$MPV_LIBRARY" | grep -Fq "$PLACEBO_VERSION"; then
  echo "MPVFlow libmpv.so compile-time libplacebo version does not match libplacebo.so ($PLACEBO_VERSION)" >&2
  exit 1
fi
echo "Verified libmpv.so uses shared libplacebo $PLACEBO_VERSION, matching Native Media3 Flow"
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
