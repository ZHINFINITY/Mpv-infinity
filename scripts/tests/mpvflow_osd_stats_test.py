#!/usr/bin/env python3
"""Regression tests for MPVFlow's minimal synthesized-FPS statistics row."""
from __future__ import annotations

import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("prepare_mpvflow", ROOT / "scripts/prepare-mpvflow.py")
if SPEC is None or SPEC.loader is None:
    raise SystemExit("Could not import MPVFlow source preparation")
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)
MPV_VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")
PINNED_STATS_EXCERPT = '''local mp = require 'mp'
local format = string.format

local function append(s, str, attr)
    s[#s + 1] = str
end

local function append_fps(s, prop, eprop)
end

local function add_video_out(s)
    append_fps(s, "display-fps", "estimated-display-fps")
    append(s, "dropped", {prefix="Dropped Frames:"})
end
'''


def main() -> None:
    patched = PREPARE.patch_mpvflow_stats_lua(PINNED_STATS_EXCERPT)
    assert patched.count("-- MPVFLOW_OSD_SYNTH_STATS") == 1
    assert patched.count("    -- MPVFLOW_OSD_SYNTH_ROW") == 1
    assert 'mp.register_script_message("mpvflow-output-rate"' in patched
    assert 'append(s, mpvflow_output_summary, {prefix="MPVFlow Synth:"})' in patched
    assert 'mpvflow_output_summary = format("%.1f fps", synthesized)' in patched
    assert patched.index('append_fps(s, "display-fps"') < patched.index("-- MPVFLOW_OSD_SYNTH_ROW")
    for removed_debug_text in ("target ", "all swaps", "successful submits; not scanout", "interpolation_alpha"):
        assert removed_debug_text not in patched, f"unexpected debug/explanation text returned: {removed_debug_text}"
    assert PREPARE.patch_mpvflow_stats_lua(patched) == patched

    try:
        PREPARE.patch_mpvflow_stats_lua("local function add_video_out(s) end\n")
    except SystemExit as error:
        assert "layout changed" in str(error)
    else:
        raise AssertionError("Unexpected stats.lua layout must fail closed")

    assert "mpvFlowOsdStatsEnabled = mpvFlowFrameInterpolationActive" in MPV_VIEW
    assert "if (mpvFlowOsdStatsEnabled) sendMpvFlowOsdStats(sample)" in MPV_VIEW
    bridge = MPV_VIEW.split("private fun sendMpvFlowOsdStats(", 1)[1].split("private fun mpvFlowPlayerHealthFields(", 1)[0]
    assert "sample?.synthesizedSubmitFps" in bridge
    assert '"script-message-to"' in bridge and '"mpvflow-output-rate"' in bridge
    assert "swapSubmitFps" not in bridge and "targetFps" not in bridge
    assert "sendMpvFlowOsdStats(null)" in MPV_VIEW, "stale FPS must be cleared when the overlay bridge stops"
    print("MPVFlow Synth FPS statistics OSD regression tests passed")


if __name__ == "__main__":
    main()
