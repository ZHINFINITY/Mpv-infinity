#!/usr/bin/env python3
"""Guard native FongMi MPV integration and the restored Native Media3 diagnostics path."""
from __future__ import annotations

import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
PREPARE = (ROOT / "scripts/prepare-mpvflow.py").read_text(encoding="utf-8")
FONGMI_MOTION = (ROOT / "app/src/main/cpp/mpvflow/fongmi/motion.c").read_text(encoding="utf-8")
FONGMI_GPU_TEST = (ROOT / "app/src/main/cpp/mpvflow/fongmi/test/gpu_motion.c").read_text(encoding="utf-8")
MPV_VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")
DECODER_PREFERENCES = (ROOT / "app/src/main/java/app/infinity/mpvz/preferences/DecoderPreferences.kt").read_text(encoding="utf-8")
MPV_CONFIG = (ROOT / "app/src/main/java/app/infinity/mpvz/preferences/MpvConfigOverride.kt").read_text(encoding="utf-8")
MEDIA3_SINK = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt").read_text(encoding="utf-8")
MEDIA3_NATIVE = (ROOT / "app/src/main/cpp/media3flow_vulkan_jni.cpp").read_text(encoding="utf-8")
CMAKE = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
BUILD_GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
FLOW_GPU_CORE = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c").read_text(encoding="utf-8")
LOG_REPOSITORY = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/DebugLogRepository.kt").read_text(encoding="utf-8")
CRASH_ACTIVITY = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/CrashActivity.kt").read_text(encoding="utf-8")
PLAYER_CONTROLS = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt").read_text(encoding="utf-8")
MORE_SHEET = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt").read_text(encoding="utf-8")
VO_CAPTURE_PATCH = (ROOT / "scripts/patch_fongmi_vo_diagnostics.py").read_text(encoding="utf-8")
DIAGNOSTICS_GUIDE = (ROOT / "docs/mpvflow-diagnostics.md").read_text(encoding="utf-8")
FONGMI_HELPER_README = (ROOT / "app/src/main/cpp/mpvflow/fongmi/README.md").read_text(encoding="utf-8")
LEGACY_PATCHER = (ROOT / "scripts/patch_fongmi_motion_artifact.py").read_text(encoding="utf-8")
MOTION_POLICY_TEST = (ROOT / "scripts/tests/fongmi_motion_artifact_policy_test.py").read_text(encoding="utf-8")


def require(source: str, fragment: str, message: str) -> None:
    assert fragment in source, message


# The MPV runtime uses the pinned FongMi gpu-next implementation. The retained
# local helper and app-owned shader patcher are not copied into that runtime.
assert "FONGMI_PATCH" not in PREPARE
assert "mpvflow/fongmi/motion.c" not in PREPARE
assert "patch_fongmi_motion_artifact.py" not in PREPARE
for marker in (
    '"video/out/gpu_next/motion.c"',
    '"motion-interpolation", OPT_INT(motion_fps)',
    '"video/out/hwdec/hwdec_aimagereader_vk_direct.c"',
    "mp_motion_prepare_view",
    "mp_motion_render_views",
    '"motion-interpolation-info", mp_property_motion_info',
    'mp.get_property_native("motion-interpolation-info")',
    "validate_fongmi_frame_priv_members(vo)",
    '"bool retry = result == RA_HWDEC_MAP_RETRY;"',
):
    require(PREPARE, marker, f"native FongMi source contract missing {marker!r}")

# Keep the user's 120-fps target and Soft Blend-off baseline, with mpv.conf
# ownership and display-resample synchronization matching MPV-RX's controller.
require(DECODER_PREFERENCES, "DEFAULT_MPVFLOW_TARGET_FPS = 120", "the established 120-fps target default must remain unchanged")
require(DECODER_PREFERENCES, 'getBoolean("mpvflow_soft_blend_debug", false)', "Soft Blend must stay off by default")
require(MPV_VIEW, 'PlaybackSession.setOptionString("motion-interpolation", targetFps.toString())', "the selected target must reach native FongMi")
require(MPV_VIEW, "BuildConfig.MPV_HAS_FONGMI_MEMC", "FongMi must be gated by the packaged runtime capability")
require(MPV_VIEW, 'MpvConfigOverridePolicy.isOwnedByMpvConf("motion-interpolation")', "explicit mpv.conf motion settings must remain authoritative")
require(MPV_VIEW, 'if (mpvFlowFrameInterpolationEnabled) "display-resample" else "audio"', "FongMi must retain display-resample synchronization")
require(MPV_CONFIG, '"motion-interpolation"', "the FongMi motion property must be included in interpolation config ownership")
assert 'setOptionString("mpvflow-target-fps"' not in MPV_VIEW, "MPV must not pass MPVFlow-only renderer options"

# Native Media3 is restored to its pre-FongMi MPVFlow implementation. FongMi's
# separately retained helper core must not be linked into the Native JNI target.
require(MEDIA3_SINK, "MAX_PRESENTATION_BUFFERS = 3", "Media3 presentation must keep its bounded buffer count")
require(MEDIA3_SINK, "deferredMotionPairs.takeNextRetainedAdjacent(", "deferred analysis must retry only still-adjacent source pairs")
require(MEDIA3_SINK, "preferredPair = preferredPair", "the next display-cadence bracket must receive analysis priority")
require(MEDIA3_SINK, '"motion_pair_queue_full"', "slot pressure must remain visible and bounded")
assert "shouldAnalyzeMotionPairAfterEglSwap" not in MEDIA3_SINK, "pair analysis must not be gated by a previous EGL swap duration"
assert "mp_motion_" not in MEDIA3_NATIVE, "Native Media3 must not use the MPV-only FongMi core"
require(MEDIA3_NATIVE, "mpvflow_gpu_synthesize_with_policy(", "Native Media3 must retain its MPVFlow synthesis path")
require(MEDIA3_NATIVE, "mpvflow_gpu_synthesize_with_policy_to_texture(", "Native Media3 must retain its baseline direct-output path")
assert "mpvflow/fongmi/motion.c" not in CMAKE, "FongMi motion must not be linked into Native Media3"
assert "pl_shader_custom" in CMAKE, "Native MPVFlow must retain its generic renderer-hook shader API"
require(FLOW_GPU_CORE, "poll_policy=nonblocking_frame_operation_boundary", "Vulkan timing must not add per-dispatch CPU waits")
require(FONGMI_MOTION, "mp_motion_render_sources_to(", "the separately retained FongMi helper core must remain testable")
require(FONGMI_GPU_TEST, "scaled_source_pair(", "the standalone FongMi GPU fixtures must retain scaled-source coverage")

# Preserve useful crash/diagnostic export and compact user-facing performance
# counters for Native Media3.
require(LOG_REPOSITORY, 'message.contains("MPVFLOW_DIAGNOSTIC", ignoreCase = true)', "debug logs must retain renderer diagnostic records")
require(LOG_REPOSITORY, "isNativeMedia3FlowDiagnostic", "debug logs must retain Native Media3 records")
require(CRASH_ACTIVITY, '"media3_flow_route"', "crash exports must include Media3 route records")
require(MEDIA3_SINK, "outputMetric=successful_eglSwapBuffers_return_not_scanout", "Media3 cadence must not be presented as confirmed scanout FPS")
require(PLAYER_CONTROLS, "NativeStatsDisplay.interpolationFps", "the player must keep displaying interpolated output FPS")
for removed_capture_ui in (
    "Start marked capture",
    "Start paired Soft Blend comparison",
):
    assert removed_capture_ui not in MORE_SHEET, f"the More sheet must not expose {removed_capture_ui}"
require(MORE_SHEET, "One-shot FongMi diagnostic", "the FongMi one-shot action must be explicitly labeled")
require(MORE_SHEET, "collectFlowAsState()", "capture state must use Compose's StateFlow collector")
assert re.search(
    r"if\s*\(\s*BuildConfig\.DEBUG\s*&&\s*BuildConfig\.MPV_HAS_FONGMI_DIAGNOSTICS"
    r"\s*&&\s*activeEngine\s*==\s*PlaybackEngineMode\.MPV\s*&&\s*activity\s*!=\s*null\s*\)",
    MORE_SHEET,
    re.S,
), "the capture control must be debug-only, diagnostic-build-only, and MPV-only"
require(MORE_SHEET, "activity.startFlowDiagnosticsCapture(singleOutput = true)", "the UI must request the bounded one-output mode")
require(MORE_SHEET, "activity.stopFlowDiagnosticsCapture()", "the user must be able to stop an armed capture")
require(MORE_SHEET, "nothing is uploaded", "the UI must state that captures stay local")
require(MORE_SHEET, "Saved: $location", "the UI must show where the local diagnostic ZIP was exported")
assert MORE_SHEET.count("startFlowDiagnosticsCapture(") == 1
assert re.search(
    r"fongmiDiagnosticCaptureEnabled\s*=\s*BuildConfig\.DEBUG\s*&&"
    r"\s*BuildConfig\.MPV_HAS_FONGMI_DIAGNOSTICS\s*&&\s*mpvFlowFrameInterpolationActive",
    MPV_VIEW,
), "the capture option must be diagnostic-build-only and follow active FongMi interpolation"
require(MPV_VIEW, 'PlaybackSession.setIntegrationOptionString("fongmi-diagnostic-control-file", optionValue)', "the native VO must receive the dedicated FongMi control file")
capture_config = MPV_VIEW[MPV_VIEW.index("private fun configureMpvFlowCaptureControl"):MPV_VIEW.index("/** Arms the MPV VO")]
assert "!BuildConfig.MPV_HAS_MPVFLOW" not in capture_config, "FongMi capture must not require the separate MPVFlow renderer"
require(BUILD_GRADLE, "val includeMpvFlowFongmiDiagnostics = false", "the pinned runtime must not advertise uninstrumented FongMi capture controls")
assert "mpvFlowFongmiDiagnosticCapture" not in BUILD_GRADLE, "an independent Gradle request must not enable unsupported native capture"
require(PREPARE, "MPVFLOW_DEBUG_CAPTURE is unsupported with the MPVRx-pinned FongMi 95ec motion core", "capture support must remain fail-closed until the pinned runtime is instrumented")
require(VO_CAPTURE_PATCH, r"%80[^\t\r\n]", "STOP IDs must not absorb LF or CRLF line endings")
require(VO_CAPTURE_PATCH, "p->fongmi_capture_output_pts = mpi0->pts + alpha * (mpi1->pts - mpi0->pts);", "capture PTS must remain in the source-media time domain")
require(VO_CAPTURE_PATCH, r'\"resolution_limit\":[1920,1920]', "capture limits must apply independently to both axes")
assert VO_CAPTURE_PATCH.count(r'\"capture_pixel_limit\":2073600') == 2, "successful and terminal settings must both report the area cap"
assert abs((10.0 + 0.5 * (10.04 - 10.0)) - 10.02) < 1e-12
warp_body = FONGMI_MOTION.split("static const char warp_body[] = GLSL(", 1)[1].split("\n);", 1)[0]
require(warp_body, "vec2 displacement = (best_source - uv) * map_size;", "warp output must represent the source coordinate used by its residual")
synthesis_body = FONGMI_MOTION.split("static const char synthesis_body[] = GLSL(", 1)[1].split("\n);", 1)[0]
require(synthesis_body, "spatial_trust_consensus(forward, backward, uv, f, r)", "synthesis must use dispatch-bound sampler names")
require(synthesis_body, "motion_field_medoid(forward, backward, uv, f, r, coherent0, coherent1, coherent_support)", "medoid fallback must use dispatch-bound sampler names")
assert not re.search(r"\b(?:spatial_trust_consensus|motion_field_medoid)\(warp[01]\b", synthesis_body)
scene_body = FONGMI_MOTION.split("static const char scene_body[] = GLSL(", 1)[1].split("\n);", 1)[0]
require(scene_body, "cut = max(cut, min(spatial_cut, 0.44));", "spatial-only disagreement must not independently cross the 0.45 hard-hold threshold")
require(DIAGNOSTICS_GUIDE, "does **not** expose FongMi one-shot capture", "the guide must accurately describe the currently disabled capture path")
require(DIAGNOSTICS_GUIDE, "unavailable in the current build", "older capture instructions must not be presented as a current feature")
require(LEGACY_PATCHER, "no legacy upstream/runtime revision is supported", "the legacy patcher must remain test-only without claiming support for an upstream runtime revision")
require(LEGACY_PATCHER, "does not support active MPVRx runtime revision", "the legacy patcher warning must explicitly reject the active runtime pin")
require(LEGACY_PATCHER, "95ec8e929f8ad2f97c8415c486971493a0b408df", "the legacy patcher warning must name the exact unsupported runtime pin")
require(FONGMI_HELPER_README, "There is no supported legacy upstream/runtime revision", "the legacy patcher support boundary must be documented alongside runtime provenance")
require(MOTION_POLICY_TEST, "exact MPVRx-pinned runtime motion.c", "the optional runtime source check must name its exact pin contract")
source_mode = MOTION_POLICY_TEST.split("if args.source:", 1)[1].split('print(\n    "FongMi artifact policy passed', 1)[0]
require(source_mode, "SOURCE_VERIFIER.verify_mpv_source(mpv_dir)", "the runtime source option must verify the exact source revision and hashes")
assert not re.search(r"\bPATCHER\.transform_source\(active_source\)", source_mode), "the active runtime source must not be routed through the legacy transformer"
print("Native FongMi MPV and pre-FongMi Native Media3 diagnostic regression tests passed")
