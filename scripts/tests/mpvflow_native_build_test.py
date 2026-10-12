#!/usr/bin/env python3
"""Guard the Android MPVFlow native compiler and Vulkan build configuration."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PREPARE = ROOT / "scripts/prepare-mpvflow.py"
BUILD = ROOT / "scripts/build-mpvflow-mpv-runtime.sh"
PLAYBACK_SESSION = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlaybackSession.kt"
MPV_VIEW = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt"
APP_BUILD = ROOT / "app/build.gradle.kts"
MPV_CONF_POLICY = ROOT / "app/src/main/java/app/infinity/mpvz/preferences/MpvConfigOverride.kt"
POLICY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/RendererBackendPolicy.kt"
POLICY_TEST = ROOT / "app/src/test/java/app/infinity/mpvz/ui/player/RendererBackendPolicyTest.kt"
DECODER_SCREEN = ROOT / "app/src/main/java/app/infinity/mpvz/ui/preferences/DecoderPreferencesScreen.kt"
STRING_RESOURCES = ROOT / "app/src/main/res/values/strings.xml"

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
assert prepare.enable_android_vulkan_dependency(patched) == patched, "MPV Vulkan preparation must be safe to repeat after dependency compilation"

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

vulkan_context = (
    "before\n"
    "    const char *opt_extensions[] = {\n"
    "        VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME,\n"
    "    };\n"
    "after\n"
)
patched_vulkan_context = prepare.enable_android_external_memory_extensions(vulkan_context)
assert '"VK_ANDROID_external_memory_android_hardware_buffer"' in patched_vulkan_context
assert '"VK_EXT_queue_family_foreign"' in patched_vulkan_context
assert patched_vulkan_context.startswith("before\n") and patched_vulkan_context.endswith("after\n")
assert prepare.enable_android_external_memory_extensions(patched_vulkan_context) == patched_vulkan_context, (
    "Android Vulkan interop extensions must be requested idempotently"
)
try:
    prepare.enable_android_external_memory_extensions("no optional extension list\n")
except SystemExit:
    pass
else:
    raise AssertionError("unexpected MPV Vulkan extension-list changes must fail closed")

placebo_meson = (
    "spirv = cxx.find_library('SPIRV', required: required, static: static, dirs: vulkan_lib_dirs)\n"
    "glslang_deps += spirv\n"
    "glslang_deps += cxx.find_library('glslang', required: required, static: static)\n"
)
patched_placebo_meson = prepare.enable_android_glslang_library_search(placebo_meson)
assert "cxx.find_library('glslang', required: required, static: static)" not in patched_placebo_meson, (
    "Android glslang lookup must not rely on compiler-default library directories"
)
assert "cxx.find_library('glslang', required: required, static: static, dirs: vulkan_lib_dirs)" in patched_placebo_meson, (
    "Android glslang lookup must use the same Vulkan SDK library directory as SPIRV"
)
assert "dirs: vulkan_lib_dirs" in patched_placebo_meson.splitlines()[0], (
    "the existing SPIRV Vulkan SDK lookup must remain intact"
)
assert prepare.enable_android_glslang_library_search(patched_placebo_meson) == patched_placebo_meson, (
    "libplacebo glslang preparation must be safe to repeat after dependency compilation"
)
for bad_placebo_meson in (
    "no glslang lookup here\n",
    placebo_meson + placebo_meson.splitlines()[-1] + "\n",
    placebo_meson.replace(", dirs: vulkan_lib_dirs", ""),
):
    try:
        prepare.enable_android_glslang_library_search(bad_placebo_meson)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected libplacebo compiler lookup changes must fail closed")

timer_source = (
    "// Gives us enough queries for 8 results\n#define QUERY_POOL_SIZE 16\n"
    "struct pl_timer_t { atomic_uint_fast8_t pending; // bitmask of queries that are still running\n};\n"
    "static inline uint_fast8_t timer_bit(int index)\n"
)
patched_timer_source = prepare.enlarge_libplacebo_timer_ring(timer_source)
assert "#define QUERY_POOL_SIZE 64" in patched_timer_source, "the Vulkan timer ring must retain 32 complete results"
assert "atomic_uint_fast32_t pending" in patched_timer_source, "the pending mask must represent all 32 timer slots"
assert "static inline uint_fast32_t timer_bit(int index)" in patched_timer_source, "timer bits must use the widened mask type"
assert prepare.enlarge_libplacebo_timer_ring(patched_timer_source) == patched_timer_source, (
    "libplacebo timer-ring preparation must be safe to repeat"
)
for bad_timer_source in (
    timer_source.replace("QUERY_POOL_SIZE 16", "QUERY_POOL_SIZE 32"),
    timer_source + "// Gives us enough queries for 8 results\n#define QUERY_POOL_SIZE 16\n",
    timer_source.replace("atomic_uint_fast8_t pending", "atomic_uint_fast16_t pending"),
    patched_timer_source.replace("uint_fast32_t timer_bit", "uint_fast16_t timer_bit"),
):
    try:
        prepare.enlarge_libplacebo_timer_ring(bad_timer_source)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected libplacebo timer-ring layout must fail closed")

build = BUILD.read_text(encoding="utf-8")
prepare_source = PREPARE.read_text(encoding="utf-8")
assert 'VO_PATCH = VENDOR / "mpvflow-vo-gpu-next.patch"' in prepare_source, (
    "MPV source preparation must keep the custom VO patch path explicit"
)
assert '"--input", str(VO_PATCH)' in prepare_source, (
    "MPV source preparation must never derive its patch input from the positional native-source list"
)
assert '"--input", str(required[2])' not in prepare_source, (
    "adding OpenCL sources must not silently redirect the mpv patch command"
)
prepare_call = 'python3 "$ROOT/scripts/prepare-mpvflow.py" --mpv-dir "$BUILDSCRIPTS/deps/mpv" --libplacebo-dir "$BUILDSCRIPTS/deps/libplacebo"'
assert prepare_call in build, "the native build must patch pinned libplacebo before compilation"
native_cmake = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
for source in (
    "mpvflow/mpvflow_opencl.c",
    "mpvflow/mpvflow_opencl_vk_bridge.c",
):
    assert source in native_cmake, f"Native Media3 CMake target must compile the real OpenCL backend: {source}"
assert "\n        dl\n" in native_cmake, "Native Media3 must link the runtime dlopen dependency"
vo_patch = (ROOT / "app/src/main/cpp/mpvflow/mpvflow-vo-gpu-next.patch").read_text(encoding="utf-8")
for marker in (
    "event=swapchain_wait stage=acquire",
    "event=swapchain_wait stage=swap_buffers",
    "metric=cpu_blocking_call_not_scanout",
    "submit_call_avg_ms=%.3f",
):
    assert marker in vo_patch, f"MPV Flow must distinguish real blocking swapchain waits from submit time: missing {marker!r}"
assert build.index("./include/ci.sh install") < build.index(prepare_call) < build.index("./include/ci.sh build"), (
    "the libplacebo search-path correction must run after dependency checkout and before native builds"
)
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
    'export MPVFLOW_ROOT="$ROOT"',
    "libSPIRV.a libglslang.a libglslang-default-resource-limits.a",
    'MPV_BUILD_DIR="$BUILDSCRIPTS/deps/mpv/_build-arm64"',
    'MPV_DEPENDENCIES="$MPV_BUILD_DIR/meson-info/intro-dependencies.json"',
    'PLACEBO_PC="$MPV_PREFIX/pkgconfig/libplacebo.pc"',
    'PLACEBO_VERSION="$(sed -n \'s/^Version: *//p\' "$PLACEBO_PC")"',
    'readelf -d "$MPV_LIBRARY"',
    'python3 - "$MPV_DEPENDENCIES" "$PLACEBO_VERSION"',
    'dependency.get("name") == "libplacebo"',
    'actual_version != expected_version',
    'Verified MPV Meson libplacebo dependency version',
)
for fragment in required_fragments:
    assert fragment in build, f"native build must require Android Vulkan/SPIR-V support: missing {fragment!r}"
assert 'strings "$MPV_LIBRARY"' not in build, (
    "the native build must validate MPV's configured dependency, not search libmpv for the player-only version string"
)
version_guard_start = 'python3 - "$MPV_DEPENDENCIES" "$PLACEBO_VERSION" <<\'PY\'\n'
version_guard = build.split(version_guard_start, 1)[1].split("\nPY\n", 1)[0]
with tempfile.TemporaryDirectory(prefix="mpvflow-placebo-version-test-") as temporary:
    dependencies_file = Path(temporary) / "intro-dependencies.json"
    dependencies_file.write_text(
        json.dumps([{"name": "libplacebo", "version": "7.374.0"}]),
        encoding="utf-8",
    )
    matching = subprocess.run(
        [sys.executable, "-", str(dependencies_file), "7.374.0"],
        input=version_guard,
        check=False,
        capture_output=True,
        text=True,
    )
    assert matching.returncode == 0, f"matching MPV/libplacebo metadata must pass: {matching.stderr}"
    mismatching = subprocess.run(
        [sys.executable, "-", str(dependencies_file), "7.371.0"],
        input=version_guard,
        check=False,
        capture_output=True,
        text=True,
    )
    assert mismatching.returncode != 0, "a real MPV/libplacebo Meson version mismatch must fail closed"
    dependencies_file.write_text("[]\n", encoding="utf-8")
    missing = subprocess.run(
        [sys.executable, "-", str(dependencies_file), "7.374.0"],
        input=version_guard,
        check=False,
        capture_output=True,
        text=True,
    )
    assert missing.returncode != 0, "missing MPV libplacebo dependency metadata must fail closed"

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
timing_candidate = init_options.split("val mpvFlowFrameInterpolationCandidate =", 1)[1].split(
    "val flowInterpolationRc", 1
)[0]
assert "!frameInterpolationConfigOwned" in timing_candidate, (
    "MPV Flow timing must not override interpolation or sync options owned by mpv.conf"
)
for marker in (
    'PlaybackSession.setOptionString("display-fps-override", displayRefreshRate.hz.toString())',
    'PlaybackSession.setOptionString("interpolation", "yes")',
    'PlaybackSession.setOptionString("video-sync", "display-resample")',
    "displayRefreshRate != null",
    "flowDisplayFpsOverrideRc == 0",
    "currentDisplayRefreshRate()",
    "val mpvFlowFrameInterpolationEnabled =",
    "mpvFlowFrameInterpolationCandidate && flowTimingOptionsReady",
    'PlaybackSession.setOptionString("interpolation", "no")',
    'PlaybackSession.setOptionString("video-sync", "audio")',
    "fallback=source_passthrough",
):
    assert marker in init_options, f"MPV Flow must establish and safely gate its display cadence: missing {marker!r}"
assert init_options.index('PlaybackSession.setOptionString("interpolation", "yes")') < init_options.index(
    'PlaybackSession.setOptionString("mpvflow",'
), "the GPU frame mixer must be configured before enabling Flow synthesis"
renderer_option_order = (
    'PlaybackSession.setOptionString("gpu-api", backend.gpuApi)',
    'PlaybackSession.setOptionString("gpu-context", backend.gpuContext)',
    "PlaybackSession.setVideoOutput(backend.vo)",
)
renderer_positions = [init_options.index(marker) for marker in renderer_option_order]
assert renderer_positions == sorted(renderer_positions), (
    "MPV Flow must apply gpu-api, gpu-context, then vo during initOptions"
)
display_override_position = init_options.index(
    'PlaybackSession.setOptionString("display-fps-override", displayRefreshRate.hz.toString())'
)
vo_creation_position = init_options.index("PlaybackSession.setVideoOutput(backend.vo)")
assert display_override_position < vo_creation_position, (
    "MPV Flow must provide Android display FPS before VO creation and display-synced frame timing"
)
present_mode_position = init_options.index('PlaybackSession.setOptionString("vulkan-swap-mode", it)')
assert present_mode_position < vo_creation_position, (
    "MPV Flow must request its Vulkan present mode before gpu-next creates the Android swapchain"
)
for marker in (
    'backend.gpuApi == "vulkan"',
    'MpvConfigOverridePolicy.isOwnedByMpvConf("vulkan-swap-mode") -> null',
    'mpvFlowFrameInterpolationEnabled -> "mailbox"',
    'else -> "auto"',
    "fallback=FIFO_if_unsupported",
):
    assert marker in init_options, f"MPV Flow present-mode request must retain {marker!r}"
flow_selector = view_source.split("val flowRequiresVulkan =", 1)[1].split(
    "val gpuNextEnabled =", 1
)[0]
for marker in (
    "BuildConfig.MPV_HAS_MPVFLOW",
    "BuildConfig.MPV_SUPPORTS_VULKAN",
    "!decoderPreferences.rifeFrameInterpolation.get()",
    "!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.FRAME_INTERPOLATION)",
    "PlaybackEngineMode.NATIVE",
):
    assert marker in flow_selector, f"Flow Vulkan selection must retain guard {marker!r}"
for marker in ('vo = "gpu-next"', 'gpuApi = "vulkan"', 'gpuContext = "androidvk"'):
    assert marker in view_source, f"MPV Flow Vulkan backend must configure {marker!r}"

flow_hwdec = init_options.split("val hardwareDecodeMode =", 1)[1].split(
    "val hardwareDecodeOptionResult", 1
)[0]
assert "RendererBackendPolicy.gpuFlowHwdecMode(" in flow_hwdec
assert "BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN" in flow_hwdec, (
    "MPV Flow must not request a direct MediaCodec/Vulkan path absent from the APK flavor"
)
standard_flavor = APP_BUILD.read_text(encoding="utf-8").split('create("standard") {', 1)[1].split(
    'create("noVulkan") {', 1
)[0]
assert 'MPV_SUPPORTS_MEDIACODEC_VULKAN", "false"' in standard_flavor, (
    "the standard ARM64 Flow build must keep its direct-import capability accurately declared"
)
mpv_conf_source = MPV_CONF_POLICY.read_text(encoding="utf-8")
renderer_ownership = mpv_conf_source.split("RENDERER(", 1)[1].split("\n  ),", 1)[0]
assert '"vulkan-swap-mode"' in renderer_ownership, (
    "explicit mpv.conf Vulkan swap-mode configuration must be discoverable and preserved"
)
decoder_screen = DECODER_SCREEN.read_text(encoding="utf-8")
for marker in (
    "val mpvFlowForcesRenderer =",
    "value = gpuNext || mpvFlowForcesRenderer",
    "value = (useVulkan || mpvFlowForcesRenderer) && isVulkanSupported",
    "enabled = !mpvFlowForcesRenderer",
    "R.string.pref_decoder_mpvflow_compute_api_title",
):
    assert marker in decoder_screen, f"the Decoder screen must truthfully distinguish Flow rendering and compute: missing {marker!r}"
compute_title_position = decoder_screen.index("R.string.pref_decoder_mpvflow_compute_api_title")
compute_selector_start = decoder_screen.rfind("ListPreference(", 0, compute_title_position)
compute_selector_end = decoder_screen.index("PreferenceDivider()", compute_selector_start)
compute_selector = decoder_screen[compute_selector_start:compute_selector_end]
for marker in (
    "ListPreference(",
    "onValueChange = preferences.mpvFlowComputeApi::set",
    "values = MpvFlowComputeApi.entries",
    "!mpvFlowComputeApiConfigOwned",
    "MpvFlowComputeApi.OPENCL",
    "MpvFlowComputeApi.VULKAN",
):
    assert marker in compute_selector, f"the compute selector must persist real backend choices: missing {marker!r}"
resource_source = STRING_RESOURCES.read_text(encoding="utf-8")
for marker in (
    "Auto prefers the Qualcomm OpenCL AHardwareBuffer backend",
    "cl_qcom_android_ahardwarebuffer_host_ptr",
    "No Vulkan compute fallback is used for this explicit choice",
    "Force Vulkan/SPIR-V compute",
):
    assert marker in resource_source, f"compute API choice must have accurate user-facing semantics: missing {marker!r}"
assert "Decoder input may use MediaCodec direct mode or mediacodec-copy" in resource_source, (
    "Flow settings must distinguish decoder-input copy mode from interpolation compute"
)
assert "synthesized output stays GPU-side with no CPU pixel readback" in resource_source, (
    "Flow settings must state the actual synthesized-output path"
)

flow_gpu_source = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c").read_text(encoding="utf-8")
opencl_source = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_opencl.c").read_text(encoding="utf-8")
bridge_source = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_opencl_vk_bridge.c").read_text(encoding="utf-8")
media3_jni_source = (ROOT / "app/src/main/cpp/media3flow_vulkan_jni.cpp").read_text(encoding="utf-8")
cmake_source = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
manifest_source = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for marker in (
    "#define MPVFLOW_GRID_STEP 4",
    "int radius = has ? 2 : c->search_radius;",
    "inline float subpixel_offset",
    "sample_coarse(coarse,cgw,cgh",
    "flow_at_edge_aware(forward,backward",
    "flow_at_bilinear(forward,backward",
    "inline float reliability(float4 flow,float cycle,float valid)",
    "cycle_error0",
    "boundary_conflict",
    "(float)best/(64.0f*255.0f),uniqueness",
    "SET_ARG(c->synth_kernel, 10, luma0)",
    "SET_ARG(c->synth_kernel, 15, dstride)",
):
    assert marker in opencl_source, (
        f"OpenCL motion and artifact safeguards must match Vulkan Flow: missing {marker!r}"
    )
for marker in (
    "mpvflow_opencl_create(",
    "mpvflow_opencl_vk_bridge_create(",
    "effective_api=opencl",
    "flow_opencl_analyze_pair(",
    "flow_opencl_synthesize(",
    "requested_api=auto from=opencl-qcom to=vulkan-spirv",
):
    assert marker in flow_gpu_source, f"MPVFlow must dispatch a real OpenCL backend with explicit fallback: missing {marker!r}"
assert 'cl_qcom_android_ahardwarebuffer_host_ptr' in opencl_source, (
    "OpenCL must import shared Android HardwareBuffer storage rather than exposing a disconnected compute toggle"
)
opencl_buffer_creation = opencl_source.split("static enum MPVFlowOpenCLStatus make_buffer(", 1)[1].split(
    "enum MPVFlowOpenCLStatus mpvflow_opencl_import_ahardwarebuffer(", 1
)[0]
assert "flags = CL_MEM_USE_HOST_PTR | CL_MEM_EXT_HOST_PTR_QCOM" in opencl_buffer_creation and \
       "c->clCreateBuffer(c->context, flags, 0, &desc, &err)" in opencl_buffer_creation, (
    "QCOM AHardwareBuffer imports must match the public Adreno host_ptr call shape: size zero and no explicit access qualifier"
)
assert "desc_in->allocation_bytes, &desc, &err" not in opencl_buffer_creation, (
    "the validated BLOB allocation size must not be substituted for the QCOM extension's size argument"
)
assert "*cl_create_error_out = err" in opencl_buffer_creation, (
    "the raw vendor clCreateBuffer error must be preserved for device-side diagnosis"
)
assert "cl_error_available=%d" in bridge_source and \
       "qcom_size_arg=0 qcom_access_flags=default" in bridge_source and \
       "qcom_required_padding_bytes=%zu" in bridge_source and \
       "ahb_allocation_bytes=%zu desc=" in bridge_source, (
    "OpenCL import failures must distinguish the QCOM call arguments from the validated AHardwareBuffer descriptor"
)
assert "CL_DEVICE_EXT_MEM_PADDING_IN_BYTES_QCOM 0x40A0u" in opencl_source and \
       "CL_DEVICE_EXT_MEM_PADDING_IN_BYTES_QCOM" in opencl_source and \
       "qcom_ext_mem_padding_bytes=%zu" in opencl_source and \
       "size_t qcom_ext_mem_padding_bytes" in opencl_source and \
       "size_t qcom_page_size_bytes" in opencl_source, (
    "OpenCL must query and report QCOM padding/page-size values using the extension's size_t query shape"
)
assert "mpvflow_opencl_get_qcom_ext_mem_padding_bytes" in bridge_source and \
       "size_t qcom_padding_bytes" in bridge_source and \
       "ahb_blob_bytes = logical_bytes + (VkDeviceSize)qcom_padding_bytes" in bridge_source and \
       "ahb_desc.width = (uint32_t)ahb_blob_bytes" in bridge_source, (
    "the shared BLOB AHardwareBuffer allocation must include the device-required QCOM tail padding"
)
for marker in (
    "clGetPlatformInfo",
    "CL_PLATFORM_VERSION",
    "CL_DEVICE_VERSION",
    "CL_DRIVER_VERSION",
    "CL_DEVICE_OPENCL_C_VERSION",
    "device_extensions",
    "mpvflow_opencl_get_runtime_info",
    "event=opencl_device",
):
    assert marker in opencl_source or marker in flow_gpu_source, (
        f"OpenCL platform/device/driver/extension identity must be logged: missing {marker!r}"
    )
opencl_synthesis = flow_gpu_source.split("static enum MPVFlowGPUResult flow_opencl_synthesize(", 1)[1].split(
    "enum MPVFlowGPUResult mpvflow_gpu_synthesize(", 1
)[0]
new_output_buffer = opencl_synthesis.split("if (!pair->opencl_output) {", 1)[1].split(
    "for (int i = 0; i < 2; i++)", 1
)[0]
assert "mpvflow_opencl_vk_buffer_create" in new_output_buffer and \
       "mpvflow_opencl_vk_buffer_opencl_finished(pair->opencl_output)" in new_output_buffer, (
    "a newly created OpenCL output buffer must be marked foreign-ready before its first OpenCL access"
)
assert 'cl_qcom_ext_host_ptr' in opencl_source and 'has_extension(exts, MPVFLOW_BASE_EXTENSION)' in opencl_source, (
    "OpenCL must verify the Qualcomm base host-pointer extension required by its AHardwareBuffer import ABI"
)
assert "vkCmdCopyImageToBuffer" in bridge_source and "vkCmdCopyBufferToImage" in bridge_source, (
    "the OpenCL interop path must transfer textures using synchronized GPU-only Vulkan copies"
)
assert "vkCmdFillBuffer" in bridge_source and "mpvflow_opencl_vk_fill_buffer" in bridge_source, (
    "the OpenCL startup smoke test must exercise Vulkan writes into shared storage without CPU uploads"
)
interop_probe = flow_gpu_source.split("static enum MPVFlowOpenCLVkBridgeStatus flow_opencl_interop_probe(", 1)[1].split(
    "MPVFlowGPUContext *mpvflow_gpu_create(", 1
)[0]
for marker in (
    "mpvflow_opencl_analyze_pair",
    "mpvflow_opencl_synthesize",
    "mpvflow_opencl_vk_copy_buffer_to_texture",
    "probe_dimension = 64",
):
    assert marker in interop_probe, (
        f"OpenCL initialization must execute an analyze/synthesize Vulkan round-trip: missing {marker!r}"
    )
assert "probe=kernel_interop_roundtrip" in flow_gpu_source
assert "opencl_status_valid=%d" in interop_probe, (
    "an OpenCL status must not be reported as a returned error when buffer import failed before OpenCL execution"
)
opencl_analyze_wrapper = flow_gpu_source.split("static enum MPVFlowGPUResult flow_opencl_analyze_pair(", 1)[1].split(
    "enum MPVFlowGPUResult mpvflow_gpu_analyze_pair(", 1
)[0]
assert opencl_analyze_wrapper.count("mpvflow_opencl_finish(ctx->opencl)") == 1, (
    "analysis must retain its failure drain but avoid a duplicate finish after successful internal completion"
)
opencl_synthesize_wrapper = flow_gpu_source.split("static enum MPVFlowGPUResult flow_opencl_synthesize(", 1)[1].split(
    "enum MPVFlowGPUResult mpvflow_gpu_synthesize(", 1
)[0]
assert "if (mpvflow_opencl_finish(ctx->opencl) == MPVFLOW_OPENCL_OK)" in opencl_synthesize_wrapper, (
    "failed OpenCL synthesis must still finish before returning shared buffers to Vulkan"
)
assert opencl_synthesize_wrapper.count("mpvflow_opencl_finish(ctx->opencl)") == 2, (
    "synthesis must retain only its geometry/error cleanup finishes and avoid a duplicate success-path finish"
)
pair_cleanup = flow_gpu_source.split("static void destroy_pair_textures(", 1)[1].split("\n}\n", 1)[0]
assert "} else {\n                        *owned[i] = NULL;\n                    }" in pair_cleanup and \
       "\n                *owned[i] = NULL;\n" not in pair_cleanup, (
    "failed OpenCL buffer destruction must retain its handle so runtime fallback cannot mistake live interop resources for cleaned resources"
)
failed_synthesis_context = vo_patch.split(
    'bypass_reason = output ? "synthesis_failed" : "synthesis_output_missing";', 1
)[1].splitlines()[:5]
assert any("flow_release_pair(p);" in line for line in failed_synthesis_context), (
    "a failed synthesis may tear down pair-owned resources; the MPV frame-ID cache must not reuse that pair"
)
for marker in (
    "flow_init_retry_count < 2",
    '"opencl_initialization"',
    "flow_init_retry_after_ns",
    "retry_scheduled=%d",
):
    assert marker in vo_patch, f"explicit OpenCL initialization should use a bounded retry: missing {marker!r}"
media3_sink_source = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt").read_text(
    encoding="utf-8"
)
media3_native_source = (
    ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVulkanNative.kt"
).read_text(encoding="utf-8")
assert "nativeGetComputeApiName" in media3_jni_source and "nativeGetComputeApiName" in media3_native_source
assert "effective_compute_api=${effectiveComputeApiName()}" in media3_sink_source
assert "backend=vulkan-spirv" not in media3_sink_source, (
    "Media3 Flow diagnostics must not claim Vulkan compute when OpenCL was requested or selected"
)
for source in ("mpvflow/mpvflow_opencl.c", "mpvflow/mpvflow_opencl_vk_bridge.c"):
    assert source in cmake_source, f"Native Media3 must compile {source}"
assert "dl" in cmake_source and 'android:name="libOpenCL.so"' in manifest_source and 'android:required="false"' in manifest_source, (
    "OpenCL must be dynamically loaded as an optional Android vendor library"
)
assert "flow_compute_api=%s" in media3_jni_source and "renderer_api=vulkan flow_compute_api=%s" in media3_jni_source, (
    "Native Media3 diagnostics must distinguish Vulkan rendering from the effective Flow compute backend"
)
assert "AHardwareBuffer_lock" not in bridge_source and "clEnqueueReadBuffer" not in opencl_source, (
    "Flow must not add CPU pixel readback to the OpenCL backend"
)
assert "vk->get_proc_addr" in bridge_source
assert '"vkGetPhysicalDeviceExternalBufferPropertiesKHR"' in bridge_source
assert "vkGetPhysicalDeviceExternalBufferProperties(vk->phys_device" not in bridge_source, (
    "Android's Vulkan loader may not export this core entry point for direct linking"
)
frame_interpolation_ownership = mpv_conf_source.split("val FRAME_INTERPOLATION =", 1)[1].split(
    "\n    )", 1
)[0]
for option in ("gpu-api", "gpu-context", "interpolation", "video-sync", "display-fps-override"):
    assert f'"{option}"' in frame_interpolation_ownership, (
        f"mpv.conf-owned {option} must disable MPV Flow rather than override the configured path"
    )
assert '"vulkan-swap-mode"' not in frame_interpolation_ownership, (
    "an explicit Vulkan swap mode must be preserved without disabling MPV Flow"
)
policy_source = POLICY.read_text(encoding="utf-8")
flow_hwdec_policy = policy_source.split("fun gpuFlowHwdecMode(", 1)[1].split(
    "fun preferredHwdecMode(", 1
)[0]
for marker in (
    "buildSupportsMediaCodecVulkan: Boolean",
    "usesVulkan = true",
    "buildSupportsMediaCodecVulkan = buildSupportsMediaCodecVulkan",
    "): String = preferredHwdecMode(",
):
    assert marker in flow_hwdec_policy, f"Flow hwdec policy must honor its Vulkan capability: missing {marker!r}"
policy_tests = POLICY_TEST.read_text(encoding="utf-8")
for marker in (
    '"mediacodec,mediacodec-copy,no"',
    '"mediacodec-copy,no"',
    '"no"',
    "buildSupportsMediaCodecVulkan = true",
    "buildSupportsMediaCodecVulkan = false",
):
    assert marker in policy_tests, f"Flow hwdec tests must cover direct, copy and disabled modes: missing {marker!r}"

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
        "build_prefix() {\n"
        "IN_CI=1 ./include/download-deps.sh\n"
        'msg "Compiling"\n'
        "./buildall.sh --only-deps mpv\n"
        "}\n"
        "./buildall.sh -n mpv\n"
        "./buildall.sh -n\n",
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
    mpv.write_text(
        'unset CC CXX # meson wants these unset\n'
        'meson setup $build --cross-file "$prefix_dir"/crossfile.txt \\\n'
        '\t--default-library shared \\\n'
        '\t-Diconv=disabled -Dlua=enabled \\\n'
        '\t-Dlibmpv=true -Dcplayer=false \\\n'
        '\t-Dmanpage-build=disabled\n',
        encoding="utf-8",
    )
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
    patched_ci = ci.read_text(encoding="utf-8")
    early_prepare = 'python3 "$MPVFLOW_ROOT/scripts/prepare-mpvflow.py" --mpv-dir deps/mpv --libplacebo-dir deps/libplacebo'
    assert early_prepare in patched_ci, "Flow preparation must be injected after source download"
    assert patched_ci.index(early_prepare) < patched_ci.index('msg "Compiling"') < patched_ci.index(
        "./buildall.sh --arch arm64 --only-deps mpv"
    ), "Flow Vulkan/glslang source fixes must run before the dependency prefix builds libplacebo"
    patched_placebo = libplacebo.read_text(encoding="utf-8")
    assert "-Dglslang=enabled" in patched_placebo and "-Dvulkan=enabled" in patched_placebo
    assert "cmake --install" in patched_placebo and "libSPIRV.a" in patched_placebo
    patched_mpv = mpv.read_text(encoding="utf-8")
    assert "\t-Dmanpage-build=disabled -Dvulkan=enabled\n" in patched_mpv, (
        "MPV Vulkan must be enabled in the pinned Meson options block"
    )
    assert "--arch arm64" in ci.read_text(encoding="utf-8"), "native build must remain arm64-only"

print("MPVFlow Android Vulkan/glslang native-build regression tests passed")
