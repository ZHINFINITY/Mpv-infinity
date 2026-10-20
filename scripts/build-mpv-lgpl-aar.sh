#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SOURCE_URL="https://github.com/Riteshp2001/mpvlibAndroid.git"
STANDARD_SOURCE_COMMIT="45b206be0e8d199fbc3a68f9adfc02ed6afccb71"
NO_VULKAN_SOURCE_COMMIT="108a82cc09eb38bd6e717b318117408b19a70f8f"
MPV_COMMIT="f5bcfb195412e0ca733eac2e850879cd3b1ded18"
LIBPLACEBO_COMMIT="0d043c7f6f79cd3687c023454bdacbe615e4d96f"
DAV1D_COMMIT="aa09a630ef57ee7d9482ffb7ef355a903dbb5302"
LIBASS_COMMIT="b2fe9d8770678a7b5271387d38c20657ebf3429a"
DEFAULT_ABIS="arm64-v8a,armeabi-v7a,x86,x86_64"
ABIS="${MPV_LGPL_ABIS:-$DEFAULT_ABIS}"
IFS=',' read -r -a ABI_LIST <<< "$ABIS"
ARCHES=()
declare -A ABI_TO_ARCH=(
  ["arm64-v8a"]="arm64"
  ["armeabi-v7a"]="armv7l"
  ["x86"]="x86"
  ["x86_64"]="x86_64"
)
declare -A SEEN_ABIS=()
for abi in "${ABI_LIST[@]}"; do
  if [[ -z "$abi" ]]; then
    echo "MPV_LGPL_ABIS contains an empty ABI entry." >&2
    exit 1
  fi
  if [[ -n "${SEEN_ABIS[$abi]:-}" ]]; then
    echo "MPV_LGPL_ABIS contains duplicate ABI: $abi" >&2
    exit 1
  fi
  SEEN_ABIS["$abi"]=1
  arch="${ABI_TO_ARCH[$abi]:-}"
  if [[ -z "$arch" ]]; then
    echo "Unsupported MPV_LGPL_ABIS entry: $abi" >&2
    exit 1
  fi
  ARCHES+=("$arch")
done
if [[ "${#ARCHES[@]}" -eq 0 ]]; then
  echo "MPV_LGPL_ABIS must select at least one ABI." >&2
  exit 1
fi
ABIS="$(IFS=,; printf '%s' "${ABI_LIST[*]}")"
VARIANTS=(mpvlib.aar mpvlib-no-vulkun.aar)
LEGACY_VARIANT=mpvlib-fongmi.aar
RESUME_BUILD="${MPV_LGPL_RESUME:-0}"

if [[ "$RESUME_BUILD" == "1" && -z "${MPV_LGPL_BUILD_ROOT:-}" ]]; then
  echo "MPV_LGPL_RESUME=1 requires an explicit MPV_LGPL_BUILD_ROOT." >&2
  exit 1
fi

if [[ -n "${MPV_LGPL_BUILD_ROOT:-}" ]]; then
  BUILD_TMP="$MPV_LGPL_BUILD_ROOT"
  if [[ -e "$BUILD_TMP" ]] && [[ -n "$(find "$BUILD_TMP" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]] && [[ "$RESUME_BUILD" != "1" ]]; then
    echo "MPV_LGPL_BUILD_ROOT must be empty: $BUILD_TMP" >&2
    exit 1
  fi
  mkdir -p "$BUILD_TMP"
  AUTO_BUILD_ROOT=0
  if [[ "$RESUME_BUILD" == "1" ]] && { [[ ! -d "$BUILD_TMP/source-standard/buildscripts" ]] || [[ ! -f "$BUILD_TMP/prefix-cache/data.tgz" ]]; }; then
    echo "Existing build root lacks the retained Standard source or cached prefix: $BUILD_TMP" >&2
    exit 1
  fi
else
  BUILD_TMP="$(mktemp -d /tmp/mpv-lgpl-aar.XXXXXX)"
  AUTO_BUILD_ROOT=1
fi

INSTALL_STARTED=0
BUILD_COMPLETE=0
cleanup() {
  local status=$?
  set +e
  if [[ "$INSTALL_STARTED" == "1" && "$BUILD_COMPLETE" != "1" ]]; then
    echo "AAR installation did not complete; restoring the original native archives." >&2
    for variant in "${VARIANTS[@]}" "$LEGACY_VARIANT"; do
      rm -f "$ROOT/app/libs/$variant.new"
      if [[ -f "$BUILD_TMP/original/$variant" ]]; then
        cp "$BUILD_TMP/original/$variant" "$ROOT/app/libs/$variant"
      fi
    done
  fi
  if [[ "$AUTO_BUILD_ROOT" == "1" && "$status" == "0" && "${MPV_LGPL_KEEP_BUILD:-0}" != "1" ]]; then
    rm -rf "$BUILD_TMP"
  else
    printf 'Native build workspace retained at: %s\n' "$BUILD_TMP" >&2
  fi
  exit "$status"
}
trap cleanup EXIT

for command in git python3 meson ninja cmake make autoconf autoreconf libtoolize pkg-config javac nm readelf strings unzip; do
  command -v "$command" >/dev/null || { echo "Required native build tool is missing: $command" >&2; exit 1; }
done

mkdir -p "$BUILD_TMP/original" "$BUILD_TMP/rebuilt"
for variant in "${VARIANTS[@]}"; do
  [[ -f "$ROOT/app/libs/$variant" ]] || { echo "Missing original AAR: $ROOT/app/libs/$variant" >&2; exit 1; }
  if [[ ! -f "$BUILD_TMP/original/$variant" ]]; then
    cp "$ROOT/app/libs/$variant" "$BUILD_TMP/original/$variant"
  fi
done
if [[ -f "$ROOT/app/libs/$LEGACY_VARIANT" && ! -f "$BUILD_TMP/original/$LEGACY_VARIANT" ]]; then
  cp "$ROOT/app/libs/$LEGACY_VARIANT" "$BUILD_TMP/original/$LEGACY_VARIANT"
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" ]]; then
  for candidate in /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-1.17.0-openjdk-amd64; do
    if [[ -x "$candidate/bin/javac" ]]; then
      JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
  export PATH="$JAVA_HOME/bin:$HOME/.local/bin:$PATH"
else
  echo "A full JDK with javac is required for the Gradle AAR packaging step." >&2
  exit 1
fi
export cores="${MPV_LGPL_BUILD_CORES:-4}"
export CACHE_MODE=folder
export CACHE_FOLDER="$BUILD_TMP/prefix-cache"
export IN_CI=1
export ANDROID_HOME="${ANDROID_HOME:-$BUILD_TMP/source-standard/buildscripts/sdk/android-sdk-linux}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
mkdir -p "$CACHE_FOLDER"

prepare_source() {
  local name="$1" commit="$2" dir="$BUILD_TMP/source-$1"
  if [[ "$RESUME_BUILD" == "1" && -d "$dir/.git" ]]; then
    local current_commit
    current_commit="$(git -C "$dir" rev-parse HEAD)"
    if [[ "$current_commit" != "$commit" ]]; then
      echo "Retained source $dir is at $current_commit, expected $commit." >&2
      exit 1
    fi
    printf '%s\n' "$dir"
    return 0
  fi
  if [[ -e "$dir" ]]; then
    echo "Refusing to overwrite an existing partial source tree: $dir" >&2
    exit 1
  fi
  git clone --quiet --filter=blob:none --no-checkout "$SOURCE_URL" "$dir"
  git -C "$dir" fetch --quiet --depth=1 origin "$commit"
  git -C "$dir" checkout --quiet --detach FETCH_HEAD
  printf '%s\n' "$dir"
}

patch_profile() {
  local dir="$1"
  if [[ -f "$dir/.mpv-lgpl-profile-patched" ]]; then
    echo "Reusing verified LGPL profile patches in $dir."
    return 0
  fi
  python3 - \
    "$dir/buildscripts/include/ci.sh" \
    "$dir/buildscripts/include/depinfo.sh" \
    "$dir/buildscripts/include/download-deps.sh" \
    "$dir/buildscripts/include/build_config.sh" \
    "$dir/buildscripts/scripts/ffmpeg.sh" \
    "$dir/buildscripts/scripts/mpv.sh" \
    "$dir/buildscripts/scripts/write_versions.sh" \
    "$MPV_COMMIT" "$LIBPLACEBO_COMMIT" "$DAV1D_COMMIT" "$LIBASS_COMMIT" <<'PY'
from pathlib import Path
import re
import sys

ci_path, depinfo_path, download_path, config_path, ffmpeg_path, mpv_path, versions_path = map(Path, sys.argv[1:8])
mpv_revision, placebo_revision, dav1d_revision, libass_revision = sys.argv[8:]

def replace_once(path: Path, text: str, old: str, new: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one exact build marker in {path}; found {count}: {old!r}")
    return text.replace(old, new, 1)

def replace_range_once(path: Path, text: str, start: str, end: str, replacement: str) -> str:
    if text.count(start) != 1 or text.count(end) != 1:
        raise SystemExit(f"Expected one version-generation range in {path}; markers were not unique")
    start_index = text.index(start)
    end_index = text.index(end, start_index + len(start))
    if end_index <= start_index:
        raise SystemExit(f"Version-generation range is reversed in {path}")
    return text[:start_index] + replacement + text[end_index:]

# The Sep 2026 MPV profile used moving git branches for these four native
# sources. Pin them to the last upstream commits before the imported AARs.
ci = ci_path.read_text()
old_mpv = (
    '\tmsg "Fetching mpv"\n'
    '\tif [ ! -d deps/mpv ]; then\n'
    '\t\tgit clone --depth 1 https://github.com/mpv-player/mpv deps/mpv\n'
    '\telse\n'
    '\t\tgit -C deps/mpv fetch --depth 1 origin master\n'
    '\t\tgit -C deps/mpv reset --hard origin/master\n'
    '\tfi\n'
    '\tgit -C deps/mpv apply ../../patches/mpv_video_shaders.patch\n'
)
new_mpv = (
    '\tmsg "Fetching pinned MPV source"\n'
    '\tif [ ! -d deps/mpv/.git ]; then\n'
    '\t\tgit clone --no-checkout https://github.com/mpv-player/mpv deps/mpv\n'
    '\tfi\n'
    f'\tgit -C deps/mpv fetch --depth 1 origin {mpv_revision}\n'
    '\tgit -C deps/mpv checkout --detach FETCH_HEAD\n'
    '\tgit -C deps/mpv apply ../../patches/mpv_video_shaders.patch\n'
)
ci = replace_once(ci_path, ci, old_mpv, new_mpv)
old_ci_arches = (
    'ci_build_arches=(\n'
    '\tarmv7l\n'
    '\tarm64\n'
    ')\n'
    '[ "$ENABLE_X86_ARCH" = "true" ] && ci_build_arches+=(\n'
    '\tx86\n'
    '\tx86_64\n'
    ')\n'
)
new_ci_arches = (
    'read -r -a ci_build_arches <<< "${MPV_LGPL_ARCHES:-armv7l arm64 x86 x86_64}"\n'
)
ci = replace_once(ci_path, ci, old_ci_arches, new_ci_arches)
ci_path.write_text(ci)

depinfo = depinfo_path.read_text()
depinfo = replace_once(depinfo_path, depinfo, 'v_rubberband=4.0.0\n', '')
depinfo = replace_once(depinfo_path, depinfo, 'dep_rubberband=()\n', '')
depinfo = replace_once(depinfo_path, depinfo, ' rubberband ', ' ')
depinfo = replace_once(depinfo_path, depinfo, '-rubberband-${v_rubberband}', '')
if 'v_ci_ffmpeg=n9.0\n' not in depinfo:
    raise SystemExit(f"Expected FFmpeg n9.0 in {depinfo_path}")
depinfo_path.write_text(depinfo)

# Download exact snapshots for sources whose upstream build script used master.
download = download_path.read_text()
old_dav1d = '[ ! -d dav1d ] && git clone https://github.com/videolan/dav1d\n'
new_dav1d = (
    'if [ ! -d dav1d/.git ]; then git clone https://github.com/videolan/dav1d dav1d; fi\n'
    f'git -C dav1d fetch --depth 1 origin {dav1d_revision}\n'
    'git -C dav1d checkout --detach FETCH_HEAD\n'
)
download = replace_once(download_path, download, old_dav1d, new_dav1d)
old_libass = '[ ! -d libass ] && git clone https://github.com/libass/libass\n'
new_libass = (
    'if [ ! -d libass/.git ]; then git clone https://github.com/libass/libass libass; fi\n'
    f'git -C libass fetch --depth 1 origin {libass_revision}\n'
    'git -C libass checkout --detach FETCH_HEAD\n'
)
download = replace_once(download_path, download, old_libass, new_libass)
old_placebo = '[ ! -d libplacebo ] && git clone --recursive https://github.com/haasn/libplacebo\n'
new_placebo = (
    'if [ ! -d libplacebo/.git ]; then git clone --recursive https://github.com/haasn/libplacebo libplacebo; fi\n'
    f'git -C libplacebo fetch --depth 1 origin {placebo_revision}\n'
    'git -C libplacebo checkout --detach FETCH_HEAD\n'
    'git -C libplacebo submodule update --init --recursive\n'
)
download = replace_once(download_path, download, old_placebo, new_placebo)
rubberband_block = (
    '# rubberband\n'
    'if [ ! -d rubberband ]; then\n'
    '\tmkdir rubberband\n'
    '\t$WGET https://github.com/breakfastquay/rubberband/archive/refs/tags/v${v_rubberband}.tar.gz -O - | \\\n'
    '\t\ttar -xz -C rubberband --strip-components=1\n'
    'fi\n\n'
)
download = replace_once(download_path, download, rubberband_block, '')
download_path.write_text(download)

config = config_path.read_text()
if 'ENABLE_X86_ARCH=true\n' not in config:
    raise SystemExit(f"The source profile no longer builds x86 ABIs: {config_path}")

ffmpeg = ffmpeg_path.read_text()
ffmpeg = replace_once(
    ffmpeg_path,
    ffmpeg,
    '--disable-static --enable-shared --enable-{gpl,version3}',
    '--disable-static --enable-shared --disable-gpl --disable-nonfree --enable-version3',
)
ffmpeg_path.write_text(ffmpeg)

mpv = mpv_path.read_text()
mpv = replace_once(mpv_path, mpv, '-D{lua,libcurl,rubberband}=enabled', '-D{lua,libcurl}=enabled')
mpv = replace_once(mpv_path, mpv, '-D{libarchive,dvdnav}=enabled', '-Dlibarchive=enabled -Ddvdnav=disabled')
mpv = replace_once(
    mpv_path,
    mpv,
    '-Dlibmpv=true -Dcplayer=false \\\n',
    '-Dgpl=false \\\n\t-Dlibmpv=true -Dcplayer=false \\\n',
)
if '-Dvulkan=enabled' not in mpv and '-Dvulkan=disabled' not in mpv:
    raise SystemExit(f"Could not verify the existing Vulkan profile in {mpv_path}")
mpv_path.write_text(mpv)

versions = versions_path.read_text()
versions = replace_range_once(
    versions_path,
    versions,
    '# Meson writes the version used by the compiled library to this generated header.\n',
    '# Installed pkg-config files survive CI prefix cache restores and describe the\n',
    '''# Read all generated MPV version headers, not only the NDK-suffix path: the
# upstream suffix can be empty for ARMv7 and may otherwise fall back to a stale
# MPV_VERSION file on ARM64. Fail closed if the selected ABI outputs disagree.
mapfile -t mpv_version_headers < <(find buildscripts/deps/mpv -path '*/common/version.h' -type f -print | sort)
mpv_versions=()
for header in "${mpv_version_headers[@]}"; do
    version=$(sed -n 's/^#define VERSION "\\(.*\\)"/\\1/p' "$header" | head -n 1)
    [ -n "$version" ] && mpv_versions+=("${version#v}")
done
if [ "${#mpv_versions[@]}" -gt 0 ]; then
    mapfile -t unique_mpv_versions < <(printf '%s\\n' "${mpv_versions[@]}" | sort -u)
    if [ "${#unique_mpv_versions[@]}" -ne 1 ]; then
        printf 'MPV version differs between generated ABI headers: %s\\n' "${unique_mpv_versions[*]}" >&2
        exit 1
    fi
    MPV_VERSION="${unique_mpv_versions[0]}"
fi
if [ -z "$MPV_VERSION" ] && [ -f buildscripts/deps/mpv/MPV_VERSION ]; then
    MPV_VERSION=$(sed 's/-UNKNOWN$//' buildscripts/deps/mpv/MPV_VERSION)
fi

''',
)
versions = replace_range_once(
    versions_path,
    versions,
    "# Get the build date from mpv's compiled object file.\n",
    'versions_file=app/src/main/java/is/xyz/mpv/Utils.kt\n',
    '''# Build-date extraction from readelf's human-readable section columns is
# architecture-dependent and can embed binary bytes for 32-bit ARM. This field
# is informational, so report the explicit fallback consistently for every ABI.
DATE=unknown

''',
)
versions_path.write_text(versions)
print("Pinned source dependencies; disabled FFmpeg GPL/nonfree and MPV GPL, Rubber Band, and DVDnav features.")
PY
  touch "$dir/.mpv-lgpl-profile-patched"
}

build_profile() {
  local profile="$1" commit="$2" aar_name="$3" mode="$4"
  local source_dir
  source_dir="$(prepare_source "$profile" "$commit")"
  local buildscripts="$source_dir/buildscripts"
  patch_profile "$source_dir"

  printf '\n=== Building %s MPV runtime (%s) ===\n' "$profile" "$ABIS"
  (
    cd "$buildscripts"
    chmod 755 ./include/*.sh ./scripts/*.sh ./buildall.sh
    export MPV_LGPL_ARCHES="${ARCHES[*]}"
    if [[ "$RESUME_BUILD" == "1" && "$profile" == "standard" ]]; then
      for arch in "${ARCHES[@]}"; do
        [[ -d "prefix/$arch" ]] || { echo "Retained prefix is missing $arch." >&2; exit 1; }
      done
      echo "Reusing retained Standard SDK, source, and selected-ABI dependency prefix."
    else
      IN_CI=1 ./include/ci.sh install
    fi
    if [[ "$profile" == "no-vulkan" ]]; then
      IN_CI=1 ./include/download-deps.sh
      for arch in "${ARCHES[@]}"; do
        ./buildall.sh --arch "$arch" --clean -n libplacebo
      done
    fi
    for arch in "${ARCHES[@]}"; do
      ./buildall.sh --arch "$arch" --clean -n mpv
    done
    rm -rf "$source_dir/app/build"
    ./buildall.sh -n mpv-android
  )

  local built_aar="$source_dir/app/build/outputs/aar/app-release.aar"
  [[ -f "$built_aar" ]] || {
    echo "Expected rebuilt AAR is missing: $built_aar" >&2
    find "$source_dir/app/build/outputs" -maxdepth 4 -type f -print >&2 || true
    exit 1
  }

  local staged_aar="$BUILD_TMP/rebuilt/$aar_name"
  cp "$built_aar" "$staged_aar"
  python3 "$ROOT/scripts/package-mpv-aar-udfread.py" "$staged_aar" "$buildscripts/prefix" \
    --abis "$ABIS"
  local profile_args=()
  if [[ "$mode" == "vulkan" ]]; then
    profile_args+=(--require-vulkan)
  else
    profile_args+=(--forbid-vulkan)
  fi
  python3 "$ROOT/scripts/verify-mpv-aar-lgpl.py" "$staged_aar" \
    --abis "$ABIS" --only-selected-abis "${profile_args[@]}"
  sha256sum "$staged_aar"
}

build_profile standard "$STANDARD_SOURCE_COMMIT" mpvlib.aar vulkan
build_profile no-vulkan "$NO_VULKAN_SOURCE_COMMIT" mpvlib-no-vulkun.aar no-vulkan

# Do not replace a user binary until both rebuilt profiles pass all ABI, license,
# and Vulkan-profile checks. Preserve all originals for rollback on any failure.
INSTALL_STARTED=1
for variant in mpvlib.aar mpvlib-no-vulkun.aar; do
  staged="$ROOT/app/libs/$variant.new"
  cp "$BUILD_TMP/rebuilt/$variant" "$staged"
  mv "$staged" "$ROOT/app/libs/$variant"
done
rm -f "$ROOT/app/libs/$LEGACY_VARIANT"
BUILD_COMPLETE=1
printf '\nInstalled GPL-free Standard and No-Vulkan AARs for: %s\nRemoved the legacy FongMi AAR from the next build.\n' "$ABIS"
