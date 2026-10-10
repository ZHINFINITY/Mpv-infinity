#!/usr/bin/env python3
"""Guard the Android MPVFlow native compiler and Vulkan build configuration."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PREPARE = ROOT / "scripts/prepare-mpvflow.py"
BUILD = ROOT / "scripts/build-mpvflow-mpv-runtime.sh"
PLAYBACK_SESSION = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlaybackSession.kt"
MPV_VIEW = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt"

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("prepare_mpvflow", PREPARE)
assert spec and spec.loader, "could not load MPVFlow preparation module"
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)

old_dependency = "vulkan = dependency('vulkan', version: '>= 1.3.238', required: vulkan_opt)"
new_dependency = "vulkan = dependency('vulkan', required: vulkan_opt)"
header_check = "cc.has_header_symbol('vulkan/vulkan_core.h', 'VK_VERSION_1_3', dependencies: vulkan)"
meson = f"before\n{old_dependency}\n{header_check}\nafter\n"
patched = prepare.enable_android_vulkan_dependency(meson)
assert old_dependency not in patched, "Android build must not require missing Vulkan pkg-config version metadata"
assert new_dependency in patched, "Android Vulkan dependency must remain required and validate NDK headers"
assert header_check in patched, "Android build must retain the Vulkan 1.3 header-symbol requirement"
assert patched.startswith("before\n") and patched.endswith("after\n"), "dependency patch must preserve adjacent MPV Meson content"

for bad_meson in (
    "no Vulkan dependency here\n",
    f"{old_dependency}\n{old_dependency}\n",
    f"{old_dependency}\n",
):
    try:
        prepare.enable_android_vulkan_dependency(bad_meson)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected MPV Vulkan source changes must fail closed")

build = BUILD.read_text(encoding="utf-8")
required_fragments = (
    "1062752a891c95b2bfeed9e356562d88f9df84ac",
    "-DCMAKE_TOOLCHAIN_FILE=",
    "-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28",
    "-DGLSLANG_ENABLE_INSTALL=ON -DGLSLANG_TESTS=OFF",
    "-DENABLE_GLSLANG_BINARIES=OFF -DENABLE_SPIRV=ON -DENABLE_SPVREMAPPER=OFF",
    "-DENABLE_HLSL=OFF -DENABLE_OPT=OFF -DENABLE_PCH=OFF",
    'export CPPFLAGS="${CPPFLAGS:-} -I$prefix_dir/include"',
    'export LDFLAGS="${LDFLAGS:-} -L$prefix_dir/lib -lc++"',
    "-Dglslang=enabled -Dvulkan-sdk=",
    "-Dvulkan=enabled",
    "libSPIRV.a libglslang.a libglslang-default-resource-limits.a",
    'PLACEBO_PC="$MPV_PREFIX/pkgconfig/libplacebo.pc"',
    'PLACEBO_VERSION="$(sed -n \'s/^Version: *//p\' "$PLACEBO_PC")"',
    'readelf -d "$MPV_LIBRARY"',
    'strings "$MPV_LIBRARY"',
)
for fragment in required_fragments:
    assert fragment in build, f"native build must require Android Vulkan/SPIR-V support: missing {fragment!r}"

session_source = PLAYBACK_SESSION.read_text(encoding="utf-8")
initialize_order = (
    "MPVLib.create(context.applicationContext)",
    "initOptions()",
    "MPVLib.init()",
    "postInitOptions()",
)
initialize_positions = [session_source.index(marker) for marker in initialize_order]
assert initialize_positions == sorted(initialize_positions), (
    "GPU renderer options must be applied after mpv_create and before mpv_initialize"
)
option_setter = session_source.split("fun setOptionString(", 1)[1].split("\n  }", 1)[0]
assert "withCore(-1, allowInitializing = true)" in option_setter, (
    "PlaybackSession must admit renderer option writes during core initialization"
)
core_gate = session_source.split("private inline fun <T> withCore(", 1)[1].split(
    "private inline fun <T> withReadyCore(", 1
)[0]
assert "_state.value.phase == PlaybackPhase.INITIALIZING" in core_gate, (
    "withCore must permit libmpv option writes in the INITIALIZING phase"
)

view_source = MPV_VIEW.read_text(encoding="utf-8")
init_options = view_source.split("override fun initOptions()", 1)[1].split(
    "override fun observeProperties()", 1
)[0]
renderer_option_order = (
    'PlaybackSession.setOptionString("gpu-api", backend.gpuApi)',
    'PlaybackSession.setOptionString("gpu-context", backend.gpuContext)',
    "PlaybackSession.setVideoOutput(backend.vo)",
)
renderer_positions = [init_options.index(marker) for marker in renderer_option_order]
assert renderer_positions == sorted(renderer_positions), (
    "MPV Flow must apply gpu-api, gpu-context, then vo during initOptions"
)
flow_selector = view_source.split("val flowRequiresVulkan =", 1)[1].split(
    "val gpuNextEnabled =", 1
)[0]
for marker in (
    "BuildConfig.MPV_HAS_MPVFLOW",
    "BuildConfig.MPV_SUPPORTS_VULKAN",
    "!decoderPreferences.rifeFrameInterpolation.get()",
    "PlaybackEngineMode.NATIVE",
):
    assert marker in flow_selector, f"Flow Vulkan selection must retain guard {marker!r}"
for marker in ('vo = "gpu-next"', 'gpuApi = "vulkan"', 'gpuContext = "androidvk"'):
    assert marker in view_source, f"MPV Flow Vulkan backend must configure {marker!r}"

start_marker = "<<'PY'\n"
start = build.index(start_marker) + len(start_marker)
end = build.index("\nPY\n", start)
builder_patcher = build[start:end]
with tempfile.TemporaryDirectory(prefix="mpvflow-builder-patch-test-") as temporary:
    fixture = Path(temporary)
    ci = fixture / "ci.sh"
    depinfo = fixture / "depinfo.sh"
    downloads = fixture / "download-deps.sh"
    libplacebo = fixture / "libplacebo.sh"
    android_mk = fixture / "Android.mk"
    mpv = fixture / "mpv.sh"
    ci.write_text(
        'msg "Fetching mpv"\n'
        "mkdir -p deps/mpv\n"
        "$WGET https://github.com/mpv-player/mpv/archive/master.tar.gz -O master.tgz\n"
        "tar -xzf master.tgz -C deps/mpv --strip-components=1\n"
        "rm master.tgz\n"
        "./buildall.sh --only-deps mpv\n"
        "./buildall.sh -n mpv\n"
        "./buildall.sh -n\n"
        "IN_CI=1 ./include/download-deps.sh\n",
        encoding="utf-8",
    )
    depinfo.write_text("dep_mpv=(ffmpeg libass lua libplacebo qairt)\nv_ci_ffmpeg=n8.0.1\n", encoding="utf-8")
    downloads.write_text(
        "[ ! -d libplacebo ] && git clone --recursive https://github.com/haasn/libplacebo\n# mpv\n",
        encoding="utf-8",
    )
    libplacebo.write_text(
        'unset CC CXX\n'
        'meson setup $build --cross-file "$prefix_dir"/crossfile.txt \\\n'
        '\t-Dvulkan=enabled -Ddemos=false\n',
        encoding="utf-8",
    )
    android_mk.write_text(
        "include $(CLEAR_VARS)\n"
        "LOCAL_MODULE := libmpv\n"
        "LOCAL_SRC_FILES := $(PREFIX)/lib/libmpv.so\n"
        "LOCAL_EXPORT_C_INCLUDES := $(PREFIX)/include\n"
        "include $(PREBUILT_SHARED_LIBRARY)\n"
        "LOCAL_SHARED_LIBRARIES := swscale avcodec mpv\n",
        encoding="utf-8",
    )
    mpv.write_text("\t\t-Dmanpage-build=disabled\n", encoding="utf-8")
    result = subprocess.run(
        [sys.executable, "-", str(ci), str(depinfo), str(downloads), str(libplacebo), str(android_mk), str(mpv)],
        input=builder_patcher,
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, f"builder patcher fixture failed:\n{result.stdout}{result.stderr}"
    patched_downloads = downloads.read_text(encoding="utf-8")
    assert "0d043c7f6f79cd3687c023454bdacbe615e4d96f" in patched_downloads, "libplacebo pin was lost"
    assert "git -C glslang fetch --depth 1 origin 1062752a891c95b2bfeed9e356562d88f9df84ac" in patched_downloads
    patched_placebo = libplacebo.read_text(encoding="utf-8")
    assert "-Dglslang=enabled" in patched_placebo and "-Dvulkan=enabled" in patched_placebo
    assert "cmake --install" in patched_placebo and "libSPIRV.a" in patched_placebo
    assert "-Dvulkan=enabled" in mpv.read_text(encoding="utf-8"), "MPV must fail the build if Vulkan is unavailable"
    assert "--arch arm64" in ci.read_text(encoding="utf-8"), "native build must remain arm64-only"

print("MPVFlow Android Vulkan/glslang native-build regression tests passed")
