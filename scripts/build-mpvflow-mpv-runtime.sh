#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
MPV_BUILDER_DIR="${MPV_BUILDER_DIR:-$ROOT/native-builder}"
MPV_SOURCE_DIR="${MPV_SOURCE_DIR:-$ROOT/mpv-source}"
RIFE_SOURCE_DIR="${RIFE_SOURCE_DIR:-$ROOT/rife-project}"
MPVFLOW_INCLUDE_RIFE="${MPVFLOW_INCLUDE_RIFE:-0}"
BUILDSCRIPTS="$MPV_BUILDER_DIR/buildscripts"

[[ -d "$BUILDSCRIPTS" ]] || { echo "Missing pinned Android MPV build tools: $BUILDSCRIPTS" >&2; exit 1; }
[[ -d "$MPV_SOURCE_DIR/.git" ]] || { echo "Missing pinned MPV checkout: $MPV_SOURCE_DIR" >&2; exit 1; }
[[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] || { echo "ANDROID_HOME/ANDROID_SDK_ROOT is required" >&2; exit 1; }
[[ "$MPVFLOW_INCLUDE_RIFE" == "0" || "$MPVFLOW_INCLUDE_RIFE" == "1" ]] || {
  echo "MPVFLOW_INCLUDE_RIFE must be 0 or 1" >&2
  exit 1
}
if [[ "$MPVFLOW_INCLUDE_RIFE" == "1" ]]; then
  [[ -d "$RIFE_SOURCE_DIR/src" && -f "$RIFE_SOURCE_DIR/src/rife.cpp" ]] || {
    echo "Missing pinned RIFE source: $RIFE_SOURCE_DIR" >&2
    exit 1
  }
  export RIFE_SOURCE_DIR
fi

export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export MPV_SOURCE_DIR
export MPVFLOW_ROOT="$ROOT"
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
  "$BUILDSCRIPTS/scripts/mpv.sh" \
  "$BUILDSCRIPTS/scripts/ffmpeg.sh" \
  "$BUILDSCRIPTS/scripts/libbluray.sh" <<'PY'
from pathlib import Path
import sys

ci_path, depinfo_path, download_path, libplacebo_path, android_mk_path, mpv_script_path, ffmpeg_script_path, libbluray_script_path = map(Path, sys.argv[1:])
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
flow_prepare = 'python3 "$MPVFLOW_ROOT/scripts/prepare-mpvflow.py" --mpv-dir deps/mpv --libplacebo-dir deps/libplacebo\n'
ci = ci.replace(download_marker, download_marker + symver_patch + flow_prepare, 1)
ci_path.write_text(ci)
print("Prepared the pinned Android MPV builder, FFmpeg probe, and MPVFlow sources before dependency compilation")

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

ffmpeg_script = ffmpeg_script_path.read_text()
ffmpeg_gpl_switch = "--disable-static --enable-shared --enable-{gpl,version3}"
if ffmpeg_script.count(ffmpeg_gpl_switch) != 1:
    raise SystemExit(f"Expected one FFmpeg GPL configure switch in {ffmpeg_script_path}")
ffmpeg_script_path.write_text(ffmpeg_script.replace(
    ffmpeg_gpl_switch,
    "--disable-static --enable-shared --disable-gpl --enable-version3",
    1,
))
print("Configured FFmpeg as LGPLv3-or-later with GPL disabled to retain Apache-2.0 mbedTLS")

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
mpv_vulkan_option = "\t-Dmanpage-build=disabled\n"
if mpv_script.count(mpv_vulkan_option) != 1:
    raise SystemExit(f"Expected one MPV Meson options block in {mpv_script_path}")
mpv_script_path.write_text(mpv_script.replace(
    mpv_vulkan_option,
    mpv_vulkan_option.rstrip("\n") + " -Dgpl=false -Dvulkan=enabled -Dlibbluray=enabled -Ddvdnav=disabled\n",
    1,
))
print("Disabled MPV's GPL/DVD-navigation features; required Vulkan and LGPL Blu-ray support")

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
\ninclude $(CLEAR_VARS)
LOCAL_MODULE := libudfread
LOCAL_SRC_FILES := $(PREFIX)/lib/libudfread.so
include $(PREBUILT_SHARED_LIBRARY)
""",
    1,
)
mpv_player_dependencies = "LOCAL_SHARED_LIBRARIES := swscale avcodec mpv"
if android_mk.count(mpv_player_dependencies) != 1:
    raise SystemExit(f"Expected exactly one libplayer dependency list in {android_mk_path}")
android_mk_path.write_text(android_mk.replace(
    mpv_player_dependencies,
    "LOCAL_SHARED_LIBRARIES := swscale avcodec mpv udfread placebo",
    1,
))
print("Registered libplacebo.so and libudfread.so in ndk-build so the runtime APK stages both dependencies")

depinfo = depinfo_path.read_text()
qairt_dependency = "dep_mpv=(ffmpeg libass lua libplacebo qairt)"
if depinfo.count(qairt_dependency) != 1:
    raise SystemExit(f"Expected one MPV dependency list containing QAIRT in {depinfo_path}")
depinfo = depinfo.replace(qairt_dependency, "dep_mpv=(ffmpeg libass lua libplacebo libbluray)", 1)
libxml2_version = "v_libxml2=2.15.2\n"
if depinfo.count(libxml2_version) != 1 or "v_libbluray=" in depinfo:
    raise SystemExit(f"Unexpected libbluray or libxml2 version metadata in {depinfo_path}")
depinfo = depinfo.replace(libxml2_version, libxml2_version + "v_libbluray=1.4.1\n", 1)
placebo_dependency = "dep_libplacebo=()\n"
if depinfo.count(placebo_dependency) != 1 or "dep_libbluray=" in depinfo:
    raise SystemExit(f"Unexpected libbluray dependency metadata in {depinfo_path}")
depinfo = depinfo.replace(placebo_dependency, placebo_dependency + "dep_libbluray=()\n", 1)
if depinfo.count("v_ci_ffmpeg=n8.0.1") != 1:
    raise SystemExit(f"Expected the pinned FFmpeg 8.0.1 line in {depinfo_path}")
depinfo = depinfo.replace("v_ci_ffmpeg=n8.0.1", "v_ci_ffmpeg=n9.0.2", 1)
cache_marker = "-libxml2-${v_libxml2}-fontconfig-"
if depinfo.count(cache_marker) != 1:
    raise SystemExit(f"Expected one dependency-cache key marker in {depinfo_path}")
depinfo_path.write_text(depinfo.replace(
    cache_marker,
    "-libxml2-${v_libxml2}-libbluray-${v_libbluray}-fontconfig-",
    1,
))
print("Excluded unused QAIRT; pinned FFmpeg 9.0.2 and LGPL libbluray 1.4.1 for the arm64 AAR ABI")

libbluray_build_script = '''#!/bin/bash -e
. ../../include/path.sh
build=_build$ndk_suffix
if [ "$1" == "build" ]; then
    true
elif [ "$1" == "clean" ]; then
    rm -rf "$build"
    exit 0
else
    exit 255
fi
unset CC CXX
meson setup "$build" --cross-file "$prefix_dir/crossfile.txt" -Ddefault_library=static \\
    -Denable_tools=false \\
    -Denable_docs=false \\
    -Denable_devtools=false \\
    -Denable_examples=false \\
    -Dbdj_jar=disabled \\
    -Dembed_udfread=false \\
    -Dfontconfig=disabled \\
    -Dfreetype=disabled \\
    -Dlibxml2=enabled
ninja -C "$build" -j"$cores"
DESTDIR="$prefix_dir" ninja -C "$build" install
pc="$prefix_dir/lib/pkgconfig/libbluray.pc"
if ! grep -q -- "-ludfread" "$pc"; then
    "${SED:-sed}" -i.bak '/^Libs:/ s/$/ -ludfread/' "$pc"
    rm -f "$pc.bak"
fi
'''
if libbluray_script_path.exists() and libbluray_script_path.read_text() != libbluray_build_script:
    raise SystemExit(f"Refusing to overwrite an unexpected libbluray builder: {libbluray_script_path}")
libbluray_script_path.write_text(libbluray_build_script)
libbluray_script_path.chmod(0o755)
print(f"Prepared the pinned libbluray 1.4.1 Meson build with separate LGPL libudfread: {libbluray_script_path}")
PY

LIBBLURAY_VERSION=1.4.1
LIBBLURAY_SHA256=76b5dc40097f28dca4ebb009c98ed51321b2927453f75cc72cf74acd09b9f449
LIBBLURAY_SOURCE="$BUILDSCRIPTS/deps/libbluray"
LIBBLURAY_ARCHIVE="$BUILDSCRIPTS/deps/libbluray-$LIBBLURAY_VERSION.tar.xz"
[[ ! -e "$LIBBLURAY_SOURCE" ]] || { echo "Refusing to reuse an existing libbluray source tree: $LIBBLURAY_SOURCE" >&2; exit 1; }
mkdir -p "$BUILDSCRIPTS/deps" "$LIBBLURAY_SOURCE"
curl -fsSL "https://downloads.videolan.org/pub/videolan/libbluray/$LIBBLURAY_VERSION/libbluray-$LIBBLURAY_VERSION.tar.xz" -o "$LIBBLURAY_ARCHIVE"
echo "$LIBBLURAY_SHA256  $LIBBLURAY_ARCHIVE" | sha256sum -c -
tar -xJf "$LIBBLURAY_ARCHIVE" -C "$LIBBLURAY_SOURCE" --strip-components=1
rm -f "$LIBBLURAY_ARCHIVE"

cd "$BUILDSCRIPTS"
./include/ci.sh install

if [[ "$MPVFLOW_INCLUDE_RIFE" == "1" ]]; then
  cd "$ROOT"
  RIFE_PREFIX="$BUILDSCRIPTS/prefix/arm64" ./scripts/build-rife-native.sh
  python3 "$ROOT/scripts/prepare-rife-mpv.py" \
    --mpv-dir "$BUILDSCRIPTS/deps/mpv" \
    --prefix-dir "$BUILDSCRIPTS/prefix/arm64"
fi

cd "$ROOT"
python3 "$ROOT/scripts/prepare-mpvflow.py" --mpv-dir "$BUILDSCRIPTS/deps/mpv" --libplacebo-dir "$BUILDSCRIPTS/deps/libplacebo"

cd "$BUILDSCRIPTS"
./include/ci.sh build
MPV_PREFIX="$BUILDSCRIPTS/prefix/arm64/usr/local/lib"
MPV_LIBRARY="$MPV_PREFIX/libmpv.so"
MPV_BUILD_DIR="$BUILDSCRIPTS/deps/mpv/_build-arm64"
MPV_DEPENDENCIES="$MPV_BUILD_DIR/meson-info/intro-dependencies.json"
PLACEBO_LIBRARY="$MPV_PREFIX/libplacebo.so"
PLACEBO_PC="$MPV_PREFIX/pkgconfig/libplacebo.pc"
[[ -f "$MPV_LIBRARY" && -f "$PLACEBO_LIBRARY" && -f "$PLACEBO_PC" ]] || {
  echo "MPVFlow native prefix is missing libmpv, libplacebo, or libplacebo.pc" >&2
  exit 1
}
[[ -f "$MPV_DEPENDENCIES" ]] || {
  echo "MPVFlow MPV Meson dependency metadata is missing: $MPV_DEPENDENCIES" >&2
  exit 1
}
PLACEBO_VERSION="$(sed -n 's/^Version: *//p' "$PLACEBO_PC")"
[[ -n "$PLACEBO_VERSION" ]] || { echo "Could not read the installed libplacebo version" >&2; exit 1; }
if ! readelf -d "$MPV_LIBRARY" | grep -Eq 'Shared library: \[libplacebo\.so([.][0-9]+)*\]'; then
  echo "MPVFlow libmpv.so must dynamically use the shared libplacebo.so runtime" >&2
  exit 1
fi
if ! readelf -d "$MPV_LIBRARY" | grep -Fq 'Shared library: [libudfread.so]'; then
  echo "MPVFlow libmpv.so must retain its LGPL libudfread dependency through libbluray" >&2
  exit 1
fi
if readelf -d "$MPV_LIBRARY" | grep -Eq 'Shared library: \[libbluray\.so([.][0-9]+)*\]'; then
  echo "MPVFlow libmpv.so must statically link libbluray; the AAR does not bundle libbluray.so" >&2
  exit 1
fi
if ! strings -a "$MPV_LIBRARY" | grep -Fq 'libbluray'; then
  echo "MPVFlow libmpv.so is missing the required LGPL Blu-ray feature" >&2
  exit 1
fi
python3 - "$MPV_DEPENDENCIES" "$PLACEBO_VERSION" <<'PY'
import json
import sys

metadata_path, expected_version = sys.argv[1:]
with open(metadata_path, encoding="utf-8") as metadata_file:
    dependencies = json.load(metadata_file)
placebo_dependencies = [dependency for dependency in dependencies if dependency.get("name") == "libplacebo"]
if len(placebo_dependencies) != 1:
    raise SystemExit(
        "MPVFlow MPV Meson metadata must report exactly one libplacebo dependency; "
        f"found {len(placebo_dependencies)} in {metadata_path}"
    )
actual_version = placebo_dependencies[0].get("version")
if actual_version != expected_version:
    raise SystemExit(
        "MPVFlow MPV was configured against libplacebo "
        f"{actual_version}, but the shared runtime is {expected_version}"
    )
print(f"Verified MPV Meson libplacebo dependency version {actual_version}")
PY
echo "Verified libmpv.so dynamically uses shared libplacebo $PLACEBO_VERSION, matching Native Media3 Flow"
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
