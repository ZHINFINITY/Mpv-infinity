#!/usr/bin/env python3
"""Guard FongMi MPV integration and Media3 interpolation diagnostics."""
from __future__ import annotations

import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
FONGMI_PATCH = (ROOT / "app/src/main/cpp/mpvflow/fongmi/mpv-fongmi-android.patch").read_text(encoding="utf-8")
FONGMI_MOTION = (ROOT / "app/src/main/cpp/mpvflow/fongmi/motion.c").read_text(encoding="utf-8")
FONGMI_GPU_TEST = (ROOT / "app/src/main/cpp/mpvflow/fongmi/test/gpu_motion.c").read_text(encoding="utf-8")
PREPARE = (ROOT / "scripts/prepare-mpvflow.py").read_text(encoding="utf-8")
MPV_VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")
DECODER_PREFERENCES = (ROOT / "app/src/main/java/app/infinity/mpvz/preferences/DecoderPreferences.kt").read_text(encoding="utf-8")
MPV_CONFIG = (ROOT / "app/src/main/java/app/infinity/mpvz/preferences/MpvConfigOverride.kt").read_text(encoding="utf-8")
MEDIA3_SINK = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt").read_text(encoding="utf-8")
MEDIA3_NATIVE = (ROOT / "app/src/main/cpp/media3flow_vulkan_jni.cpp").read_text(encoding="utf-8")
FLOW_GPU_CORE = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c").read_text(encoding="utf-8")
LOG_REPOSITORY = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/DebugLogRepository.kt").read_text(encoding="utf-8")
CRASH_ACTIVITY = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/CrashActivity.kt").read_text(encoding="utf-8")
PLAYER_CONTROLS = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt").read_text(encoding="utf-8")
MORE_SHEET = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt").read_text(encoding="utf-8")


def require(source: str, fragment: str, message: str) -> None:
    assert fragment in source, message


# The pinned FongMi patch is the MPV runtime renderer and includes the Android
# Vulkan mapper. The old MPVFlow VO patch must not be staged into MPV anymore.
patch_parse = subprocess.run(
    ["git", "apply", "--numstat", str(ROOT / "app/src/main/cpp/mpvflow/fongmi/mpv-fongmi-android.patch")],
    cwd=ROOT,
    check=False,
    capture_output=True,
    text=True,
)
assert patch_parse.returncode == 0, f"FongMi patch is malformed: {patch_parse.stdout}{patch_parse.stderr}"
require(PREPARE, 'FONGMI_PATCH = VENDOR / "fongmi/mpv-fongmi-android.patch"', "MPV preparation must use the combined FongMi/Android patch")
require(PREPARE, '"--fuzz=0", "--input", str(FONGMI_PATCH)', "the pinned FongMi patch must apply without fuzz")
require(PREPARE, "motion_option_marker = '{\"motion-interpolation\", OPT_INT(motion_fps), M_RANGE(0, 240)}'", "source preparation must verify the FongMi option")
assert '"mpvflow-vo-gpu-next.patch"' not in PREPARE
assert '"mpvflow-aimagereader-vulkan.patch"' not in PREPARE
assert "MPVFLOW_AIMAGE_VULKAN_MAPPER" in FONGMI_PATCH
assert "mpvflow_gpu.c" not in FONGMI_PATCH, "MPV must not reinstall the retired Flow VO renderer"

# Keep the user's 120-fps default and Soft Blend-off baseline; config ownership
# remains authoritative, and actual MPV timing still follows display resample.
require(DECODER_PREFERENCES, "DEFAULT_MPVFLOW_TARGET_FPS = 120", "the established 120-fps target default must remain unchanged")
require(DECODER_PREFERENCES, 'getBoolean("mpvflow_soft_blend_debug", false)', "Soft Blend must stay off by default")
require(MPV_VIEW, 'PlaybackSession.setOptionString("motion-interpolation", targetFps.toString())', "the selected target must reach FongMi")
require(MPV_VIEW, 'BuildConfig.MPV_HAS_FONGMI_MEMC', "FongMi must be gated by the packaged runtime capability")
require(MPV_VIEW, 'MpvConfigOverridePolicy.isOwnedByMpvConf("motion-interpolation")', "explicit mpv.conf motion settings must remain authoritative")
require(MPV_VIEW, 'if (mpvFlowFrameInterpolationEnabled) "display-resample" else "audio"', "FongMi must retain display-resample synchronization")
require(MPV_CONFIG, '"motion-interpolation"', "the FongMi motion property must be included in interpolation config ownership")
assert 'setOptionString("mpvflow-target-fps"' not in MPV_VIEW, "MPV must no longer pass MPVFlow-only renderer options"

# FongMi keeps its bounded motion resolution, render lifecycle, display cadence,
# and stats property. Media3 reuses its GPU core but synthesizes from the full-
# resolution source textures into the caller-owned output when possible.
for marker in (
    '"motion-interpolation", OPT_INT(motion_fps), M_RANGE(0, 240)',
    'p->motion = mp_motion_create(p->gpu, 480);',
    'p->motion_fps * frame->vsync_interval',
    'mp_motion_render(p->motion, fp0->motion_texture, fp1->motion_texture,',
    'mp_motion_reset(p->motion);',
    'mp_motion_destroy(&p->motion);',
    'motion-interpolation-info',
    'analysis<=480 pixels, synthesis=source resolution',
):
    require(FONGMI_PATCH, marker, f"FongMi MPV runtime missing {marker!r}")
require(FONGMI_MOTION, 'pl_var_vec2("source_size")', "synthesis must scale source sampling to the output size")
require(FONGMI_MOTION, 'pl_var_vec2("motion_size")', "motion sampling must retain its lower analysis resolution")
require(FONGMI_MOTION, "mp_motion_render_sources_to(", "Media3 must be able to write directly into its output texture")
require(FONGMI_GPU_TEST, "static void scaled_source_pair(", "the GPU suite must test separate analysis and source dimensions")
require(FONGMI_GPU_TEST, "assert(mp_motion_render_sources_to(", "the GPU suite must exercise direct output-target rendering")
require(FONGMI_GPU_TEST, "assert(error < 0.025);", "scaled motion output must meet its pixel-error threshold")

# Media3 keeps queue/pair work bounded and nonblocking; only adjacent retained
# pairs are retried, while the native FongMi path falls back safely to Flow.
require(MEDIA3_SINK, "MAX_PRESENTATION_BUFFERS = 3", "Media3 presentation must keep its bounded buffer count")
require(MEDIA3_SINK, "deferredMotionPairs.takeNextRetainedAdjacent(", "deferred analysis must retry only still-adjacent source pairs")
require(MEDIA3_SINK, "preferredPair = preferredPair", "the next display-cadence bracket must receive analysis priority")
require(MEDIA3_SINK, '"motion_pair_queue_full"', "slot pressure must remain visible and bounded")
assert "shouldAnalyzeMotionPairAfterEglSwap" not in MEDIA3_SINK, "pair analysis must not be gated by a previous EGL swap duration"
require(MEDIA3_NATIVE, "mp_motion_create(vulkan->gpu, config.max_dimension)", "Media3 must initialize the FongMi motion core")
require(MEDIA3_NATIVE, "mp_motion_analyze_pair(context->motion", "Media3 must reuse cached GPU motion analysis")
require(MEDIA3_NATIVE, "mp_motion_render_sources_to(", "Media3 must attempt full-resolution direct-to-target synthesis")
require(MEDIA3_NATIVE, "mp_motion_render_sources(", "Media3 must retain the FongMi internal-output fallback")
require(MEDIA3_NATIVE, "fallback=mpvflow", "unsupported FongMi input must preserve a safe synthesis fallback")
require(MEDIA3_NATIVE, "mp_motion_destroy(&context->motion)", "FongMi GPU resources must be released with the Media3 context")
require(FLOW_GPU_CORE, "poll_policy=nonblocking_frame_operation_boundary", "Vulkan timing must not add per-dispatch CPU waits")

# Preserve useful crash/diagnostic export and compact user-facing performance
# counters for both native Media3 and the FongMi MPV route.
require(LOG_REPOSITORY, 'message.contains("MPVFLOW_DIAGNOSTIC", ignoreCase = true)', "debug logs must retain renderer diagnostic records")
require(LOG_REPOSITORY, "isNativeMedia3FlowDiagnostic", "debug logs must retain native Media3 records")
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

print("FongMi MPV and Media3 Flow diagnostic regression tests passed")
