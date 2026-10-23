#!/usr/bin/env python3
"""Guard MPV Flow runtime diagnostics and their app capture path."""
from __future__ import annotations

import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
PATCH = (ROOT / "app/src/main/cpp/mpvflow/mpvflow-vo-gpu-next.patch").read_text(encoding="utf-8")
AIMAGE_PATCH = (ROOT / "app/src/main/cpp/mpvflow/mpvflow-aimagereader-vulkan.patch").read_text(encoding="utf-8")
PREPARE = (ROOT / "scripts/prepare-mpvflow.py").read_text(encoding="utf-8")
MPV_VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")
PLAYER_CONTROLS = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt").read_text(encoding="utf-8")
LOG_REPOSITORY = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/DebugLogRepository.kt").read_text(encoding="utf-8")
DEBUG_LOGS_SCREEN = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/DebugLogsScreen.kt").read_text(encoding="utf-8")
CRASH_ACTIVITY = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/CrashActivity.kt").read_text(encoding="utf-8")
DECODERS_SHEET = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/DecodersSheet.kt").read_text(encoding="utf-8")
MEDIA3_FLOW_SINK = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt").read_text(encoding="utf-8")
MEDIA3_FLOW_CADENCE = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowCadence.kt").read_text(encoding="utf-8")
FLOW_GPU_CORE = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c").read_text(encoding="utf-8")
FLOW_CAPTURE_IO = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_capture_io.c").read_text(encoding="utf-8")
PLAYBACK_SESSION = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlaybackSession.kt").read_text(encoding="utf-8")
QCOM_ADAPTER = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_vulkan_qcom.c").read_text(encoding="utf-8")
MEDIA3_NATIVE = (ROOT / "app/src/main/cpp/media3flow_vulkan_jni.cpp").read_text(encoding="utf-8")
FLOW_CAPTURE_MANAGER = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/FlowDiagnosticsCapture.kt").read_text(encoding="utf-8")
PLAYER_ACTIVITY = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt").read_text(encoding="utf-8")
MEDIA3_FORMAT_POLICY = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowFormatPolicy.kt").read_text(encoding="utf-8")
NATIVE_ENGINE = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/NativeMedia3Engine.kt").read_text(encoding="utf-8")
MORE_SHEET = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt").read_text(encoding="utf-8")

# Capture internals remain available to diagnostics, but are not player settings.
for removed_capture_ui in (
    "Start marked capture",
    "Start paired Soft Blend comparison",
    "startFlowDiagnosticsCapture",
    "stopFlowDiagnosticsCapture",
    "flowDiagnosticsCaptureState",
):
    assert removed_capture_ui not in MORE_SHEET, f"More sheet must not expose {removed_capture_ui}"


def require(source: str, fragment: str, message: str) -> None:
    assert fragment in source, message


def verify_unified_hunk_counts(patch_text: str) -> None:
    lines = patch_text.splitlines()
    index = 0
    hunk_pattern = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")
    while index < len(lines):
        match = hunk_pattern.match(lines[index])
        if not match:
            index += 1
            continue
        expected_old = int(match.group(2) or 1)
        expected_new = int(match.group(4) or 1)
        old_count = new_count = 0
        index += 1
        while index < len(lines) and not lines[index].startswith(("@@ ", "diff --git ")):
            line = lines[index]
            if line.startswith(" "):
                old_count += 1
                new_count += 1
            elif line.startswith("-"):
                old_count += 1
            elif line.startswith("+"):
                new_count += 1
            elif line.startswith("\\"):
                pass
            else:
                raise AssertionError(f"Malformed unified diff line {index + 1}: {line!r}")
            index += 1
        assert old_count == expected_old, (
            f"Unified hunk at line {index} consumes {old_count} old lines, expected {expected_old}"
        )
        assert new_count == expected_new, (
            f"Unified hunk at line {index} produces {new_count} new lines, expected {expected_new}"
        )


verify_unified_hunk_counts(PATCH)
verify_unified_hunk_counts(AIMAGE_PATCH)


# Config is a request only; the renderer independently reports initialization and output state.
require(MPV_VIEW, '"mpvflow-target-fps", mpvFlowTargetFps.toString()', "effective target FPS must reach the MPV renderer")
require(MPV_VIEW, 'runtime_state=not_verified', "config logging must not claim runtime activation")
require(PATCH, "event=renderer_init state=initialized backend=%s", "successful Flow context creation must report its effective compute backend")
require(PATCH, "event=renderer_init state=unavailable", "failed or missing GPU initialization must be visible")
require(PATCH, "event=output state=synthesized renderer_api=vulkan compute_api=%s", "only successfully rendered synthesized output may report its renderer and effective compute API")
require(PATCH, "event=output state=passthrough", "source-frame fallback must be distinguishable from synthesized output")
require(PATCH, "target_fps=%d reason=motion_pair", "synthesized output must include target and reason")
require(MPV_VIEW, "event=present_mode state=$presentModeState", "MPV Flow must report its Android Vulkan presentation-mode request")
present_mode_diagnostic = MPV_VIEW.split('"MPVFLOW_DIAGNOSTIC event=present_mode', 1)[1].split("\n      )", 1)[0]
require(present_mode_diagnostic, "fallback=FIFO_if_unsupported", "present-mode diagnostics must disclose the safe fallback")
require(present_mode_diagnostic, "target_fps=$mpvFlowTargetFps", "present-mode diagnostics must preserve the selected rate")
require(PATCH, "target_fps=%d reason=%s status=%d cpu_fallback=no", "pass-through output must expose reason and status")
require(PATCH, "p->flow_bypass_logged_mask & reason_class", "pass-through logging must be deduplicated by outcome class")
require(PATCH, "frame->num_frames > 1 || p->next_opts->mpvflow", "Flow must open the display-synchronized queue gate for one-frame VO batches")
require(PATCH, "event=queue_state state=%s", "MPV Flow must report why the queue lacks a temporal pair or when one becomes available")
require(PATCH, "waiting_for_temporal_pair", "MPV Flow must distinguish an incomplete pair from renderer or dispatch failure")
require(PATCH, "event=runtime_cadence", "MPV Flow must report sustained swap-submit and synthesis cadence")
cadence_format = PATCH.split("MPVFLOW_DIAGNOSTIC event=runtime_cadence", 1)[1].split("\\n\",", 1)[0]
if cadence_format.index("metric=successful_pl_swapchain_submit_not_surfaceflinger_scanout") > cadence_format.index(
    "window_ms=%.1f"
):
    raise AssertionError("the strict OSD metric identity must precede truncation-prone cadence fields")
if cadence_format.count("metric=successful_pl_swapchain_submit_not_surfaceflinger_scanout") != 1:
    raise AssertionError("the cadence record must contain exactly one strict OSD metric identity")
for marker in (
    "render_callback_fps=%.2f", "render_cpu_avg_ms=%.3f", "callback_interval_avg_ms=%.3f",
    "previous_present_budget_skips=%llu",
    "pl_swapchain_start_frame_avg_ms=%.3f", "pl_swapchain_start_frame_max_ms=%.3f",
    "pl_swapchain_start_frame_samples=%llu",
    "frame_repeat_count=%llu", "swap_attempts=%llu", "swap_failures=%llu",
    "pair_analysis_attempts=%llu", "pair_analysis_failures=%llu", "pair_cache_reuses=%llu",
):
    require(PATCH, marker, f"MPV cadence report is missing {marker}")
assert "vsync_slots=%llu" not in PATCH, "num_vsyncs must not be mislabeled as missed display-vsync slots"
require(PATCH, "event=gpu_timing api=Vulkan", "MPV must emit direct retained per-stage Vulkan timing records")
require(PATCH, "sample_scope=lifetime poll=nonblocking", "MPV Vulkan timing records must identify cumulative sample scope and nonblocking query polling")
require(PATCH, "event=motion_geometry state=ready engine=mpv", "MPV must log its active motion-grid geometry directly to Logcat")
require(PATCH, "motion_search_passes_per_pair=6", "MPV diagnostics must confirm all six original direction/level searches remain enabled")
require(PATCH, "search_order=center_out_exact_legacy_tie_break", "MPV diagnostics must identify the exact-preserving candidate order")
require(PATCH, "MPVFLOW_GPU_MOTION_GRID_STEP", "MPV grid telemetry must use the shared core geometry constants")
require(PATCH, "source_sync_pct=%.1f", "MPV Flow must quantify display-synchronized source batches")
require(PATCH, "empty_batch_redraws=%llu", "MPV Flow cadence must separate empty redraw callbacks from unsynchronized source batches")
require(PATCH, "submit_call_avg_ms=%.3f", "MPV Flow must label the nonblocking submit-call duration accurately")
require(PATCH, "event=swapchain_wait stage=pl_swapchain_start_frame", "MPV Flow must name the aggregate swapchain start-frame interval accurately")
for marker in (
    'FLOW_WSI_ACQUIRE, "vkAcquireNextImageKHR"',
    'FLOW_WSI_FENCE_WAIT, "present_fence_wait"',
    'FLOW_WSI_PRESENT, "vkQueuePresentKHR"',
):
    require(PREPARE, marker, f"libplacebo must separately time the WSI stage {marker!r}")
require(PATCH, "event=swapchain_wait stage=swap_buffers", "MPV Flow must report slow blocking swap/present calls")
require(PATCH, "metric=cpu_blocking_call_not_scanout", "swapchain waits must not be misrepresented as confirmed scanout")
require(PATCH, "pl_vulkan_swapchain_set_diagnostics", "lower-level Vulkan WSI measurements must be enabled only by the MPV Flow path")
flow_prepare_call = PATCH.index("+            flow_prepare_frame(vo, &mix, &flow_prepared, frame->still,")
flow_prepare_flush = PATCH.index("+            pl_gpu_flush(gpu);", flow_prepare_call)
flow_acquire_call = PATCH.index(
    "+        should_draw && pl_swapchain_start_frame(p->sw, &swframe);",
    flow_prepare_call,
)
assert flow_prepare_call < flow_prepare_flush < flow_acquire_call, (
    "Flow preparation and nonblocking GPU submission must precede Vulkan swapchain acquisition"
)
flow_prepare_body = PATCH.split("+static void flow_prepare_frame(", 1)[1].split(
    "+static bool flow_render_frame(", 1
)[0]
require(flow_prepare_body, "if (p->paused || source_is_still)", "paused and still frames must bypass motion interpolation")
require(flow_prepare_body, "const bool pair_cache_matches = p->flow_pair &&", "MPV must identify a reusable vector pair before applying present-budget backpressure")
require(flow_prepare_body, "if (!pair_cache_matches && !mpvflow_previous_present_budget_available(", "an over-budget present may defer only uncached pair analysis")
require(flow_prepare_body, "p->flow_cadence_pair_cache_reuses++", "cached vectors must remain available for synthesis during present backpressure")
require(flow_prepare_body, "mpvflow_gpu_analyze_pair(", "pre-acquire producer must prepare motion before presentation")
require(flow_prepare_body, "mpvflow_previous_present_budget_available(", "MPV must skip new motion work after a measured over-budget present")
require(flow_prepare_body, "p->flow_cadence_previous_present_budget_skips++", "MPV must count callbacks suppressed by the prior-present budget gate")
assert flow_prepare_body.index("mpvflow_previous_present_budget_available(") < flow_prepare_body.index(
    "mpvflow_gpu_analyze_pair("
), "MPV backpressure admission must happen before native pair analysis"
require(flow_prepare_body, 'prepared->bypass_reason = "previous_frame_over_budget"', "MPV backpressure must preserve an explicit source fallback reason")
require(flow_prepare_body, "mpvflow_gpu_synthesize_with_policy(", "pre-acquire producer must synthesize the offscreen output through the shared motion-blend policy")
require(flow_prepare_body, "if (!allow_synthesis)", "rate limiting may bypass interpolation but must preserve nearest-source rendering")
require(flow_prepare_body, "mpvflow_synthesis_identity_matches", "rate-limited redraws may hold only a bounded, forward sample of the same temporal pair")
require(flow_prepare_body, 'synthesis_rate_limited_cached', "bounded cached synthesis must stay continuous instead of selecting a source endpoint")
require(flow_prepare_body, "temporal_identity_valid = fp0 && fp1", "missing frame metadata must not be dereferenced by cache lookup")
require(flow_prepare_body, "flow_cached_synthesis_policy == synthesis_policy", "held output must not outlive a blend or visibility policy change")
require(PATCH, "The output texture is pair-owned", "cached output must be invalidated with its owning temporal pair")
require(PATCH, "should_draw && pl_swapchain_start_frame(p->sw, &swframe)", "synthesis rate limiting must not suppress source-frame presentation")
require(PATCH, "p->flow_pending_rate_limiter_eligible && flow_synthesized", "only actual synthesized renders may consume target-rate credit")
require(PATCH, "analysis=bt709_bt1886_tonemapped", "HDR motion input must be a separate SDR tone-mapped analysis copy")
require(PATCH, "event=producer state=pre_acquire", "MPV must report the producer/presenter split at runtime")
require(PATCH, "swap_buffers_call_avg_ms=%.3f", "MPV Flow cadence must include the swap_buffers call duration")
require(PATCH, "swap_buffers_call_max_ms=%.3f", "MPV Flow cadence must include the maximum swap_buffers call duration")
require(PATCH, "swap_buffers_call_samples=%llu", "MPV Flow cadence must identify the swap_buffers timing sample count")
require(MPV_VIEW, 'PlaybackSession.setOptionString("swapchain-depth", "2")', "MPV Flow should request triple buffering instead of five minimum swapchain images")
require(MPV_VIEW, 'MpvConfigOverridePolicy.isOwnedByMpvConf("swapchain-depth")', "the Flow depth tuning must preserve explicit mpv.conf ownership")
require((ROOT / "app/src/main/cpp/third_party/libplacebo/include/libplacebo/vulkan.h").read_text(encoding="utf-8"), "pl_vulkan_swapchain_set_diagnostics", "the vendored libplacebo API must match the pinned native library")
require(PATCH, "flow_update_context(vo);", "renderer initialization must be retried from the frame path when options were processed before GPU availability")
assert PATCH.count("flow_update_context(vo);") >= 3, "initialization must be checked during map, render, and renderer-option updates"

# Retain/capture both previously named GPU records and the unified current record prefix.
require(LOG_REPOSITORY, 'message.contains("MPVFLOW_GPU_DIAGNOSTIC", ignoreCase = true)', "log retention must recognize legacy GPU Flow records")
require(CRASH_ACTIVITY, "selectMpvFlowDiagnosticLines(logcat.toString())", "crash export must use the shared Flow diagnostic selector")
require(LOG_REPOSITORY, 'message.contains("MPVFLOW_DIAGNOSTIC", ignoreCase = true)', "log retention must recognize unified runtime records")
require(LOG_REPOSITORY, 'message.contains("event=queue_state", ignoreCase = true)', "the capped viewer must pin Flow temporal-pair readiness records")
require(LOG_REPOSITORY, "isNativeMedia3FlowDiagnostic", "log retention must preserve Native Media3 Flow route and summary records")
require(CRASH_ACTIVITY, '"media3_flow_route"', "crash export must explicitly include Native Media3 route records")
require(CRASH_ACTIVITY, '"flow_summary"', "crash export must explicitly include Native Media3 flow summaries")
require(MEDIA3_FLOW_SINK, "eglSwapReturnFps=", "Native Media3 summary must report successful EGL swap-return cadence")
require(MEDIA3_FLOW_SINK, "eglSwapInterval=", "Native Media3 summary must report whether implicit EGL swap pacing was disabled")
require(MEDIA3_FLOW_SINK, "outputMetric=successful_eglSwapBuffers_return_not_scanout", "Media3 cadence must not be misrepresented as display scanout FPS")
require(PLAYER_CONTROLS, "NativeStatsDisplay.interpolationFps", "Native stats must retain interpolated output FPS")
require(PLAYER_CONTROLS, '"Performance: motion $flowGpuTime', "Native stats must retain compact performance counters")
for verbose_native_stat in (
    "Vulkan/libplacebo timestamp queries",
    "GLES_EXT_disjoint_timer_query",
    "Flow queues:",
    "Inferred output tick gaps",
    "Flow CPU avg/p95/max",
    "Flow pixels (1/8",
    "Static-path vector probe",
):
    assert verbose_native_stat not in PLAYER_CONTROLS, f"Native stats must not expose verbose algorithm detail: {verbose_native_stat}"
for compact_native_field in (
    "capturedFps=",
    "targetFps=",
    "eglSwapReturnFps=",
    "generatedFps=",
    "generatedFrames=",
    "droppedFrames=",
    "submissionFailures=",
):
    require(MEDIA3_FLOW_SINK, compact_native_field, f"Media3 compact summary is missing {compact_native_field}")
for removed_native_field in (
    "vulkanStagesUs=",
    "glesStagesUs=",
    "queueDepth=pendingInput:",
    "inferredSkippedOutputTicksTotal=",
    "tickGapContexts=",
    "outputSubmissionFailureReasons=",
    "presentationPathFailureReasons=",
    "lastNoWritableSlotStates=",
    "motionPairAnalysisTotals=",
):
    assert removed_native_field not in MEDIA3_FLOW_SINK, f"Media3 summary must not retain verbose field {removed_native_field}"
require(MPV_VIEW, "pixelArtifactQuality=unmeasured_native_pixel_coverage_unavailable", "MPV must not imply pixel quality without a coverage measurement")
media3_analysis_body = MEDIA3_FLOW_SINK.split("private fun analyzeNewestPair()", 1)[1].split(
    "private fun renderAtPosition(", 1
)[0]
require(media3_analysis_body, "analyzeDeferredMotionPair()", "Media3 must admit deferred pair analysis independently of EGL swap timing")
assert "shouldAnalyzeMotionPairAfterEglSwap" not in media3_analysis_body, "Media3 pair analysis must not be gated by prior EGL swap duration"
require(media3_analysis_body, "deferredMotionPairs.enqueue", "Media3 must retain a pair when analysis cannot run immediately")
require(media3_analysis_body, "takeNextRetainedAdjacent", "Media3 must retry only still-adjacent retained source pairs")
require(media3_analysis_body, "preferredPair = preferredPair", "Media3 must prioritize the exact retained bracket for the next output tick")
assert '"egl_swap_backpressure_analysis_deferred"' not in media3_analysis_body, "Media3 deferred-pair analysis must not restore the removed EGL-swap backpressure path"
require(media3_analysis_body, '"motion_pair_queue_full"', "Media3 must keep bounded slot-pressure diagnostics when a deferred pair cannot run")
require(MEDIA3_FLOW_SINK, "private fun preferredMotionPairForNextOutput", "Media3 pair priority must derive from the display cadence")
require(MEDIA3_FLOW_SINK, "nextRenderableOutputTick(dueTick, lastOutputTick)", "Media3 pair priority must use the next renderable cadence tick without replaying missed intervals")
require(MEDIA3_FLOW_CADENCE, "fun nextRenderableOutputTick", "Media3 next-tick selection must remain independently testable")
require(MEDIA3_FLOW_SINK, "for (offset in 0L..1L)", "Media3 must consider the next tick when the due tick lands exactly on a source PTS")
require(FLOW_GPU_CORE, "queue_timestamp_valid_bits=%u", "Vulkan timer setup must log timestamp capability for the compute queue")
require(FLOW_GPU_CORE, "VK_QUEUE_COMPUTE_BIT", "timestamp capability must be queried from the actual compute queue family")
require(FLOW_GPU_CORE, "vkGetPhysicalDeviceProperties(vk->phys_device, &device_properties)", "timestamp support must inspect physical-device limits")
require(FLOW_GPU_CORE, "device_properties.limits.timestampPeriod", "timestamp conversion resolution must be retained")
require(FLOW_GPU_CORE, "device_properties.limits.timestampComputeAndGraphics", "compute/graphics timestamp support must be inspected")
require(FLOW_GPU_CORE, "compute_timestamps_supported", "timer creation and polling must use the combined Vulkan capability predicate")
require(FLOW_GPU_CORE, "timestamp_period_ns=%.6f", "initial and per-stage diagnostics must log timestamp period")
require(FLOW_GPU_CORE, "timestamp_compute_and_graphics=%d", "initial and per-stage diagnostics must log compute/graphics timestamp support")
require(FLOW_GPU_CORE, "compute_queue_timestamp_valid_bits_zero", "unsupported Vulkan timestamps must be explicitly reported")
require(FLOW_GPU_CORE, "event=gpu_timing api=Vulkan timer_source=%s", "Vulkan timing must emit direct per-stage records with an explicit source")
require(FLOW_GPU_CORE, "dispatches_total=%llu", "Vulkan stage diagnostics must count issued compute work even without timers")
require(FLOW_GPU_CORE, "unresolved_dispatches_total=%llu", "Vulkan stage diagnostics must expose unresolved or unavailable timer results")
require(FLOW_GPU_CORE, "event=motion_geometry state=ready", "MPV and Media3 must expose the actual allocated Vulkan motion grid")
require(FLOW_GPU_CORE, "motion_search_passes_per_pair=6", "the shared Vulkan core must retain every original motion pass")
require(FLOW_GPU_CORE, "event=qcom_block_match_capability", "QCOM block-match support and format usage must be queried at runtime")
require(FLOW_GPU_CORE, "shader_execution=optional_qcom2_custom_pass_initialization_follows", "QCOM capability reporting must distinguish capability queries from adapter initialization")
require(FLOW_GPU_CORE, "event=qcom_block_match_dispatch state=fallback", "QCOM setup or dispatch failure must retain baseline SAD matching")
require(FLOW_GPU_CORE, "fallback=full_baseline_sad_search", "QCOM adapter dispatch must report a safe full-search fallback")
require(FLOW_GPU_CORE, "event=qcom_seed_backend engine=%s state=%s", "QCOM readiness must be logged after optional shader initialization and attributed to its engine")
require(FLOW_GPU_CORE, "qcom_pair_attempts++", "QCOM runtime diagnostics must count actual per-pair attempts")
require(FLOW_GPU_CORE, "qcom_dispatches++", "QCOM runtime diagnostics must count successful dispatches separately from readiness")
require(FLOW_GPU_CORE, "qcom_fallbacks++", "QCOM runtime diagnostics must expose baseline-SAD fallbacks")
require(PATCH, "event=qcom_runtime", "MPV must periodically report QCOM runtime counters under its own diagnostic path")
require(PATCH, "engine=mpv state=%s adapter_ready=%d luma_pass_ready=%d", "MPV QCOM runtime records must identify readiness independently of dispatch")
require(PATCH, "pair_attempts=%llu dispatches=%llu fallbacks=%llu", "MPV QCOM runtime records must distinguish attempted, dispatched, and fallback pairs")
require(PATCH, ".diagnostic_engine = \"mpv\"", "MPV must label shared-core QCOM diagnostics with its engine identity")
require(MEDIA3_NATIVE, "config.diagnostic_engine = \"media3\";", "Media3 must label shared-core QCOM diagnostics separately from MPV")
require(QCOM_ADAPTER, "refinement=full_baseline_sad_search", "successful QCOM dispatch must still be refined and checked by baseline SAD")
require(QCOM_ADAPTER, "event=qcom_block_match_dispatch state=active", "QCOM active diagnostics must represent an actual submitted dispatch")
assert "base_r8_block_match_ready" not in FLOW_GPU_CORE, "QCOM capability queries alone must not imply an active dispatch"
require(MEDIA3_NATIVE, "event=queue_capability", "Media3 must report whether the device exposes a dedicated compute family")
require(MEDIA3_NATIVE, "async_queue_disabled_reason=exclusive_ahb_images_require_verified_queue_ownership", "Media3 must explain why its current AHardwareBuffer path remains single-family")
for stage in (
    "motion_search_forward_level_0", "motion_search_backward_level_0",
    "motion_search_forward_level_1", "motion_search_backward_level_1",
    "motion_search_forward_level_2", "motion_search_backward_level_2",
):
    require(FLOW_GPU_CORE, stage, f"Vulkan timer dispatch classification is missing {stage}")
require(FLOW_GPU_CORE, "motion_grid=%ux%u grid_step=%d fine_block_size=%d coarse_block_size=%d", "motion geometry records must disambiguate fine and coarse vector support")
require(FLOW_GPU_CORE, "poll_policy=nonblocking_frame_operation_boundary", "Vulkan timestamp polling must stay nonblocking and outside the per-dispatch hot path")
run_pass_body = FLOW_GPU_CORE.split("static bool run_pass(", 1)[1].split("static bool allocate_pair_textures(", 1)[0]
assert "flow_timer_poll_and_report" not in run_pass_body, "timer-result polling must not add CPU/Vulkan query overhead to every compute dispatch"
require(MPV_VIEW, '"MPVFLOW_DIAGNOSTIC event=renderer_config', "startup must record the applied MPV renderer settings")
require(MPV_VIEW, "option_order=gpu-api>gpu-context>vo", "startup must record the actual Vulkan renderer-option order")
require(MPV_VIEW, 'PlaybackSession.setOptionString("display-fps-override", displayRefreshRate.hz.toString())', "Flow must provide MPV with the Android display refresh rate")
require(MPV_VIEW, "display_refresh_source=", "MPV Flow logs must identify where the display refresh rate was resolved")
require(DECODERS_SHEET, "HW+ is unavailable in this Vulkan build", "the decoder sheet must explain the standard build's supported HW-copy route")
require(DEBUG_LOGS_SCREEN, 'ActivityResultContracts.OpenDocumentTree()', "debug logs must let users select a destination folder")
require(DEBUG_LOGS_SCREEN, 'Save all accessible Logcat to folder', "debug logs must expose an unfiltered all-buffer folder export")
require(DEBUG_LOGS_SCREEN, 'Save visible logs to folder', "debug logs must expose a visible-filter folder export")
require(DEBUG_LOGS_SCREEN, "DocumentsContract.createDocument", "folder exports must write a real text document to the selected tree")
require(LOG_REPOSITORY, 'listOf("logcat", "-b", "all", "-d", "-v", "threadtime")', "all-buffer export must use raw Logcat rather than the PID-filtered viewer snapshot")
require(LOG_REPOSITORY, "capture.lines.forEach", "all-buffer export must preserve every raw line without the viewer retention cap")

# Ensure renderer tuning is written before turning the option on so init logs its actual target.
target_index = MPV_VIEW.index('"mpvflow-target-fps", mpvFlowTargetFps.toString()')
enable_index = MPV_VIEW.index('"mpvflow", if (mpvFlowFrameInterpolationEnabled) "yes" else "no"')
assert target_index < enable_index, "target FPS must be configured before enabling the renderer"
gpu_api_index = MPV_VIEW.index('PlaybackSession.setOptionString("gpu-api", backend.gpuApi)')
gpu_context_index = MPV_VIEW.index('PlaybackSession.setOptionString("gpu-context", backend.gpuContext)')
renderer_index = MPV_VIEW.index('PlaybackSession.setVideoOutput(backend.vo)')
assert gpu_api_index < gpu_context_index < renderer_index, "Vulkan API/context must be applied before creating the selected VO"

# Capture is explicitly opt-in, follows successful synthesis, and cleans up bounded auto-stopped sessions.
assert "static pl_pass make_pass(pl_gpu gpu, const char *glsl,\nstatic bool ensure_capture_passes" not in FLOW_GPU_CORE, (
    "capture helpers must not leave an incomplete C declaration before the pass initializer"
)
synthesis_success_index = PATCH.index("+                if (status == MPVFLOW_GPU_OK && output) {")
capture_start_index = PATCH.index("+                (void) flow_capture_start_for_pair(")
paired_synthesis_index = PATCH.index("+                    status = mpvflow_gpu_capture_paired_sample(", capture_start_index)
capture_sample_index = PATCH.index("+                        const enum MPVFlowGPUResult capture_status = mpvflow_gpu_capture_sample(", synthesis_success_index)
assert capture_start_index < paired_synthesis_index < synthesis_success_index < capture_sample_index, (
    "MPV must use the live pair for paired capture and keep standard capture after production synthesis succeeds"
)
require(
    MEDIA3_FLOW_SINK,
    "check(!Media3FlowFormatPolicy.isDolbyVision(format.sampleMimeType.orEmpty(), format.codecs.orEmpty()))",
    "Media3 diagnostics capture must refuse Dolby Vision even when color transfer appears SDR",
)
require(MEDIA3_FORMAT_POLICY, "fun isDolbyVision(mimeType: String, codecs: String): Boolean", "capture policy must use the shared Dolby Vision detector")
require(FLOW_CAPTURE_MANAGER, "if (!canStartFlowDiagnosticsCapture(_state.value, sessionId))", "an unresolved native session must block a second capture")
require(FLOW_CAPTURE_MANAGER, "val retainedPhase = previousPhase.takeIf", "failed stops must preserve an active capture phase")
require(FLOW_CAPTURE_MANAGER, "message = \"Stop request failed; capture may still be active. Retry Stop:", "failed stops must remain retryable rather than becoming a fresh-start state")
terminal_cleanup_index = FLOW_CAPTURE_MANAGER.index("if (status.terminal) {")
terminal_archive_index = FLOW_CAPTURE_MANAGER.index("createAndSaveArchive(session, engine, id)", terminal_cleanup_index)
require(FLOW_CAPTURE_MANAGER[terminal_cleanup_index:terminal_archive_index], "val cleanup = stopRequest", "native auto-completion must clear the engine-side session before archive completion")
require(MPV_VIEW, "val activeSession = mpvFlowCaptureSessionId ?: return null", "MPV stop cleanup must be idempotent after auto-completion")
require(MEDIA3_FLOW_SINK, "val activeSession = diagnosticCaptureSession ?: return@runOnGlThreadSync null", "Media3 stop cleanup must be idempotent after auto-completion")
require(MEDIA3_FLOW_SINK, "if (result == null) diagnosticCaptureSession = null", "Media3 must retain its session ID when native stop fails")
require(FLOW_CAPTURE_MANAGER, "while (currentCoroutineContext().isActive)", "the suspend capture monitor must check cancellation through its coroutine context")
require(PLAYER_ACTIVITY, 'PlaybackEngineMode.AUTO -> "active_engine_unresolved"', "diagnostics must fail closed rather than guess a renderer if AUTO remains unresolved")
mapper_hunk = AIMAGE_PATCH.split("@@ -1611,0 +1677,9", 1)[1].split("\n@@", 1)[0]
require(mapper_hunk, "struct priv *mapper_priv = fp->vo->priv;", "AImage unmap cleanup must declare its own mapper context after line shifts")
require(mapper_hunk, "mapper_priv->hwdec.mapper", "AImage unmap cleanup must use its locally declared mapper context")
require(mapper_hunk, "mapper_priv->el_hwdec.mapper", "AImage enhancement-layer cleanup must use its locally declared mapper context")
assert "p->hwdec.mapper" not in mapper_hunk, "AImage cleanup must not depend on an out-of-scope p variable"
require(PREPARE, 'VENDOR / "mpvflow_capture_io.c"', "pinned MPV preparation must require the capture writer implementation")
require(PREPARE, 'VENDOR / "mpvflow_capture_io.h"', "pinned MPV preparation must require the capture writer header")
require(PREPARE, 'shutil.copy2(VENDOR / "mpvflow_capture_io.c", video_out_dir / "mpvflow_capture_io.c")', "pinned MPV preparation must stage the capture writer implementation beside the VO")
require(PREPARE, 'shutil.copy2(VENDOR / "mpvflow_capture_io.h", video_out_dir / "mpvflow_capture_io.h")', "pinned MPV preparation must stage the capture writer header beside the VO")
require(PREPARE, 'capture_io_source = "video/out/mpvflow_capture_io.c"', "pinned MPV Meson must register the capture writer translation unit")
require(PREPARE, "capture_io_source, *opencl_sources", "existing MPVFlow Meson marker validation must require capture writer registration")

# Resolved Vulkan is named vulkan-spirv; it must not be rejected by the readback gate.
require(FLOW_GPU_CORE, "mpvflow_capture_io_backend_supports_vulkan_readback(backend)", "capture must classify resolved Vulkan aliases before refusing readback")
require(FLOW_CAPTURE_IO, 'strcmp(backend, "vulkan-spirv")', "the shared classifier must recognize the exact Vulkan compute API label")
require(FLOW_CAPTURE_IO, 'strcmp(backend, "opencl-qcom")', "the shared classifier must recognize the exact QCOM OpenCL label")
require(FLOW_GPU_CORE, "reason=capture_readback_requires_vulkan compute_api=%s", "a real non-Vulkan readback refusal must log its resolved backend")
require(PATCH, "mpvflow_gpu_capture_start(", "MPV must use the shared Vulkan-alias-aware capture start gate")
require(MEDIA3_NATIVE, "mpvflow_gpu_capture_start(", "Media3 must use the same Vulkan-alias-aware capture start gate")
require(PATCH, "START_PAIRED\\t", "MPV paired capture must use a distinct explicit private control command")
require(PATCH, "mpvflow_gpu_capture_paired_sample(", "MPV must rerender a paired comparison from the live source pair")
require(MEDIA3_NATIVE, "mpvflow_gpu_capture_paired_sample(", "Media3 must rerender a paired comparison under its existing synchronization")
require(FLOW_GPU_CORE, "const bool blend_variants[MPVFLOW_CAPTURE_PAIRED_SAMPLES_PER_PAIR] = {\n        false, true,", "shared capture must emit Soft Blend off then on")
require(FLOW_GPU_CORE, "false, soft_blend, preserve_motion_blend_on_conflict, &variant_output", "paired variants must keep the conflict-preservation parameter identical")
require(FLOW_GPU_CORE, "source0, source1, variant_output, capture_timestep", "each diagnostic variant must reuse both source textures and the fixed midpoint")
require(FLOW_GPU_CORE, "const float capture_timestep = 0.5f;", "decision capture must use a repeatable interpolation midpoint")
require(FLOW_GPU_CORE, "source_pts0 + (source_pts1 - source_pts0) * 0.5", "diagnostic output PTS must be the source-pair midpoint")
require(FLOW_CAPTURE_IO, "same_analyzed_motion_field", "the manifest must declare reuse of the cached motion field")
assert 'if (!backend || strcmp(backend, "vulkan"))' not in FLOW_GPU_CORE, "the capture gate must not compare Vulkan against the wrong literal alias"

# The private control file is an integration prerequisite; it must survive core reuse and mpv.conf ownership.
assert 'MpvConfigOverridePolicy.isOwnedByMpvConf("mpvflow-diagnostic-control-file")' not in MPV_VIEW, (
    "a broad mpv.conf ownership selection must not suppress the private debug control channel"
)
control_setup = MPV_VIEW.split("private fun configureMpvFlowCaptureControl", 1)[1].split(
    "/** Arms the MPV VO", 1
)[0]
require(control_setup, "PlaybackSession.setIntegrationOptionString(", "the private MPV control option must use the integration setter")
require(control_setup, "event=control_option source=$source state=$state option_rc=$result", "control-option failures must include their exact native return code")
integration_setter = PLAYBACK_SESSION.split("fun setIntegrationOptionString(", 1)[1].split("fun getPropertyInt(", 1)[0]
require(integration_setter, "if (!nativeCoreReady)", "fresh MPV cores must set the integration option before initialization")
require(integration_setter, "MPVLib.setOptionString(name, value)", "fresh-core MPV options must use the pre-initialization setter")
require(integration_setter, 'val property = "options/$name"', "reused MPV cores must use the documented runtime options property")
require(integration_setter, "MPVLib.setPropertyString(property, value)", "reused MPV cores must apply the private path through the runtime property setter")
require(integration_setter, "MPVLib.getPropertyString(property) == value", "runtime control-option setup must fail closed unless readback verifies the exact value")
require(MPV_VIEW, 'configureMpvFlowCaptureControl("after_initialize")', "a reused MPV core must configure the new view's control path after initialize returns")
control_start = MPV_VIEW.split("fun startFlowDiagnosticCapture", 1)[1].split("fun stopFlowDiagnosticCapture", 1)[0]
require(control_start, 'configureMpvFlowCaptureControl("capture_start")', "capture start must retry the MPV control option instead of failing on a stale readiness flag")
require(control_start, "if (!mpvFlowCaptureControlConfigured)", "MPV must avoid resetting a ready control option at every capture tap")
require(control_start, 'return "mpvflow_capture_control_unavailable"', "the start path must still fail closed if the integration option genuinely cannot be set")
media3_synthesize = MEDIA3_NATIVE.split(
    "Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeSynthesize(", 1
)[1].split(
    "Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeReleasePair(", 1
)[0]
require(
    media3_synthesize,
    "context->flow, pair, source0->texture, source1->texture, timestep,",
    "Media3 synthesis must use the full-resolution decoder textures",
)
require(
    media3_synthesize,
    "context->flow, pair, source0->texture, source1->texture, synthesized,",
    "Media3 capture must use the exact full-resolution source textures consumed by synthesis",
)
require(
    FLOW_GPU_CORE,
    "capture_source_pair_dimensions_mismatch source0=%dx%d source1=%dx%d synthesized=%dx%d",
    "source-pair dimension failures must report the actual texture sizes",
)
assert "output_w != source_w" not in FLOW_GPU_CORE, (
    "diagnostic capture must not reject valid synthesized outputs solely because output dimensions differ"
)
require(FLOW_GPU_CORE, "source_tile_w * 2 + output_tile_w", "capture atlases must account for independently scaled output tiles")
require(FLOW_GPU_CORE, "int output_tile_w = 0, output_tile_h = 0;", "capture must size synthesized images independently from source images")
print("MPV Flow runtime diagnostics and capture regression tests passed")
