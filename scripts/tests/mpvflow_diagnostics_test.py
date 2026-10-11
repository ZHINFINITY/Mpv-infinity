#!/usr/bin/env python3
"""Guard MPV Flow runtime diagnostics and their app capture path."""
from __future__ import annotations

import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
PATCH = (ROOT / "app/src/main/cpp/mpvflow/mpvflow-vo-gpu-next.patch").read_text(encoding="utf-8")
MPV_VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")
LOG_REPOSITORY = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/DebugLogRepository.kt").read_text(encoding="utf-8")
DEBUG_LOGS_SCREEN = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/DebugLogsScreen.kt").read_text(encoding="utf-8")
CRASH_ACTIVITY = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/CrashActivity.kt").read_text(encoding="utf-8")
DECODERS_SHEET = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/DecodersSheet.kt").read_text(encoding="utf-8")
MEDIA3_FLOW_SINK = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt").read_text(encoding="utf-8")
FLOW_GPU_CORE = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c").read_text(encoding="utf-8")


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


# Config is a request only; the renderer independently reports initialization and output state.
require(MPV_VIEW, '"mpvflow-target-fps", mpvFlowTargetFps.toString()', "effective target FPS must reach the MPV renderer")
require(MPV_VIEW, 'runtime_state=not_verified', "config logging must not claim runtime activation")
require(PATCH, "event=renderer_init state=initialized backend=vulkan-spirv", "successful Vulkan context creation must be explicit")
require(PATCH, "event=renderer_init state=unavailable", "failed or missing GPU initialization must be visible")
require(PATCH, "event=output state=synthesized backend=vulkan-spirv", "only a successfully rendered synthesized output may report synthesized state")
require(PATCH, "event=output state=passthrough", "source-frame fallback must be distinguishable from synthesized output")
require(PATCH, "target_fps=%d reason=motion_pair", "synthesized output must include target and reason")
require(MPV_VIEW, "event=present_mode state=$presentModeState", "MPV Flow must report its Android Vulkan presentation-mode request")
require(MPV_VIEW, "fallback=FIFO_if_unsupported target_fps=$mpvFlowTargetFps", "present-mode diagnostics must preserve the selected rate and disclose the safe fallback")
require(PATCH, "target_fps=%d reason=%s status=%d cpu_fallback=no", "pass-through output must expose reason and status")
require(PATCH, "p->flow_bypass_logged_mask & reason_class", "pass-through logging must be deduplicated by outcome class")
require(PATCH, "frame->num_frames > 1 || p->next_opts->mpvflow", "Flow must open the display-synchronized queue gate for one-frame VO batches")
require(PATCH, "event=queue_state state=%s", "MPV Flow must report why the queue lacks a temporal pair or when one becomes available")
require(PATCH, "waiting_for_temporal_pair", "MPV Flow must distinguish an incomplete pair from renderer or dispatch failure")
require(PATCH, "event=runtime_cadence", "MPV Flow must report sustained swap-submit and synthesis cadence")
for marker in (
    "render_callback_fps=%.2f", "render_cpu_avg_ms=%.3f", "callback_interval_avg_ms=%.3f",
    "vsync_slots=%llu", "swap_attempts=%llu", "swap_failures=%llu",
    "pair_analysis_attempts=%llu", "pair_analysis_failures=%llu", "pair_cache_reuses=%llu",
):
    require(PATCH, marker, f"MPV cadence report is missing {marker}")
require(PATCH, "event=gpu_timing api=Vulkan", "MPV must emit direct retained per-stage Vulkan timing records")
require(PATCH, "sample_scope=lifetime poll=nonblocking", "MPV Vulkan timing records must identify cumulative sample scope and nonblocking query polling")
require(PATCH, "event=motion_geometry state=ready engine=mpv", "MPV must log its active motion-grid geometry directly to Logcat")
require(PATCH, "motion_search_passes_per_pair=6", "MPV diagnostics must confirm all six original direction/level searches remain enabled")
require(PATCH, "search_order=center_out_exact_legacy_tie_break", "MPV diagnostics must identify the exact-preserving candidate order")
require(PATCH, "MPVFLOW_GPU_MOTION_GRID_STEP", "MPV grid telemetry must use the shared core geometry constants")
require(PATCH, "source_sync_pct=%.1f", "MPV Flow must quantify display-synchronized source batches")
require(PATCH, "empty_batch_redraws=%llu", "MPV Flow cadence must separate empty redraw callbacks from unsynchronized source batches")
require(PATCH, "submit_call_avg_ms=%.3f", "MPV Flow must label the nonblocking submit-call duration accurately")
require(PATCH, "event=swapchain_wait stage=acquire", "MPV Flow must report slow swapchain image acquisition")
require(PATCH, "event=swapchain_wait stage=swap_buffers", "MPV Flow must report slow blocking swap/present calls")
require(PATCH, "metric=cpu_blocking_call_not_scanout", "swapchain waits must not be misrepresented as confirmed scanout")
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
require(MEDIA3_FLOW_SINK, "vulkanStagesUs=", "Media3 must report nonblocking Vulkan timestamp-query times by compute stage")
require(MEDIA3_FLOW_SINK, "computeTimestampValidBits=", "Media3 must report exact compute-queue timestamp capability")
require(MEDIA3_FLOW_SINK, "glesStagesUs=", "GLES presentation timing must remain separately labeled from Vulkan compute")
require(MEDIA3_FLOW_SINK, "queueDepth=pendingInput:", "Media3 must report input/source/pair queue depth for backlog diagnosis")
require(MEDIA3_FLOW_SINK, "oldestPendingInputMs=", "Media3 must report the age of the oldest queued decoder input")
require(MEDIA3_FLOW_SINK, "pairPtsGapMs=", "Media3 must report the source PTS gap and capture age of the selected pair")
require(MEDIA3_FLOW_SINK, "deadlineMissReasons=", "Media3 must categorize missed output tick contexts")
require(MEDIA3_FLOW_SINK, "outputCallbackLatenessMs=", "Media3 must report Choreographer-to-GL callback lateness")
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
for stage in (
    "motion_search_forward_level_0", "motion_search_backward_level_0",
    "motion_search_forward_level_1", "motion_search_backward_level_1",
    "motion_search_forward_level_2", "motion_search_backward_level_2",
):
    require(FLOW_GPU_CORE, stage, f"Vulkan timer dispatch classification is missing {stage}")
require(FLOW_GPU_CORE, "motion_grid=%ux%u grid_step=%d block_size=%d", "motion geometry records must disambiguate processing pixels from vector-grid cells")
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
print("MPV Flow runtime diagnostics and capture regression tests passed")
