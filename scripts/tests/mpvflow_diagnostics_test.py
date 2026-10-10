#!/usr/bin/env python3
"""Guard MPV Flow runtime diagnostics and their app capture path."""
from __future__ import annotations

import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
PATCH = (ROOT / "app/src/main/cpp/mpvflow/mpvflow-vo-gpu-next.patch").read_text(encoding="utf-8")
MPV_VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")
LOG_REPOSITORY = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/DebugLogRepository.kt").read_text(encoding="utf-8")
CRASH_ACTIVITY = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/crash/CrashActivity.kt").read_text(encoding="utf-8")


def require(source: str, fragment: str, message: str) -> None:
    assert fragment in source, message


# Config is a request only; the renderer independently reports initialization and output state.
require(MPV_VIEW, '"mpvflow-target-fps", mpvFlowTargetFps.toString()', "effective target FPS must reach the MPV renderer")
require(MPV_VIEW, 'runtime_state=not_verified', "config logging must not claim runtime activation")
require(PATCH, "event=renderer_init state=initialized backend=vulkan-spirv", "successful Vulkan context creation must be explicit")
require(PATCH, "event=renderer_init state=unavailable", "failed or missing GPU initialization must be visible")
require(PATCH, "event=output state=synthesized backend=vulkan-spirv", "only a successfully rendered synthesized output may report synthesized state")
require(PATCH, "event=output state=passthrough", "source-frame fallback must be distinguishable from synthesized output")
require(PATCH, "target_fps=%d reason=motion_pair", "synthesized output must include target and reason")
require(PATCH, "target_fps=%d reason=%s status=%d cpu_fallback=no", "pass-through output must expose reason and status")
require(PATCH, "p->flow_bypass_logged_mask & reason_class", "pass-through logging must be deduplicated by outcome class")
require(PATCH, "flow_update_context(vo);", "renderer initialization must be retried from the frame path when options were processed before GPU availability")
assert PATCH.count("flow_update_context(vo);") >= 3, "initialization must be checked during map, render, and renderer-option updates"

# Retain/capture both previously named GPU records and the unified current record prefix.
require(LOG_REPOSITORY, 'message.contains("MPVFLOW_GPU_DIAGNOSTIC", ignoreCase = true)', "log retention must recognize legacy GPU Flow records")
require(CRASH_ACTIVITY, 'line.contains("MPVFLOW_GPU_DIAGNOSTIC", ignoreCase = true)', "crash export must include legacy GPU Flow records")
require(LOG_REPOSITORY, 'message.contains("MPVFLOW_DIAGNOSTIC", ignoreCase = true)', "log retention must recognize unified runtime records")
require(LOG_REPOSITORY, "isNativeMedia3FlowDiagnostic", "log retention must preserve Native Media3 Flow route and summary records")
require(CRASH_ACTIVITY, '"media3_flow_route"', "crash export must explicitly include Native Media3 route records")
require(CRASH_ACTIVITY, '"flow_summary"', "crash export must explicitly include Native Media3 flow summaries")

# Ensure renderer tuning is written before turning the option on so init logs its actual target.
target_index = MPV_VIEW.index('"mpvflow-target-fps", mpvFlowTargetFps.toString()')
enable_index = MPV_VIEW.index('"mpvflow", if (mpvFlowFrameInterpolationEnabled) "yes" else "no"')
assert target_index < enable_index, "target FPS must be configured before enabling the renderer"
print("MPV Flow runtime diagnostics and capture regression tests passed")
