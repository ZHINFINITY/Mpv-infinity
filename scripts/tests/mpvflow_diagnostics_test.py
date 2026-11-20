#!/usr/bin/env python3
"""Guard native FongMi MPV integration and the restored Native Media3 diagnostics path."""
from __future__ import annotations

import pathlib

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
FLOW_GPU_CORE = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c").read_text(encoding="utf-8")
LOG_REPOSITORY = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/DebugLogRepository.kt").read_text(encoding="utf-8")
CRASH_ACTIVITY = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/CrashActivity.kt").read_text(encoding="utf-8")
PLAYER_CONTROLS = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt").read_text(encoding="utf-8")
MORE_SHEET = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt").read_text(encoding="utf-8")


def require(source: str, fragment: str, message: str) -> None:
    assert fragment in source, message


# The MPV runtime compiles FongMi's pinned native gpu-next implementation. The
# former local patch remains provenance/history only and is not applied/copied.
assert "FONGMI_PATCH" not in PREPARE
assert "mpvflow_gpu.c" not in PREPARE
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
    "startFlowDiagnosticsCapture",
    "stopFlowDiagnosticsCapture",
):
    assert removed_capture_ui not in MORE_SHEET, f"the More sheet must not expose {removed_capture_ui}"

print("Native FongMi MPV and pre-FongMi Native Media3 diagnostic regression tests passed")
