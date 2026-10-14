#!/usr/bin/env python3
"""Regression tests for the built-in MPVFlow synthesized-FPS OSD patch."""
from __future__ import annotations

import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("prepare_mpvflow", ROOT / "scripts/prepare-mpvflow.py")
if SPEC is None or SPEC.loader is None:
    raise SystemExit("Could not import MPVFlow source preparation")
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)

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
    assert patched.index('append_fps(s, "display-fps"') < patched.index("-- MPVFLOW_OSD_SYNTH_ROW")
    assert "successful submits; not scanout" in patched
    assert PREPARE.patch_mpvflow_stats_lua(patched) == patched

    try:
        PREPARE.patch_mpvflow_stats_lua("local function add_video_out(s) end\n")
    except SystemExit as error:
        assert "layout changed" in str(error)
    else:
        raise AssertionError("Unexpected stats.lua layout must fail closed")

    print("MPVFlow built-in OSD source patch tests passed")


if __name__ == "__main__":
    main()
