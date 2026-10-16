#!/usr/bin/env python3
"""Regression tests ensuring MPVFlow debug text stays out of the normal OSD."""
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
    assert patched == PINNED_STATS_EXCERPT, "MPVFlow must not modify MPV's standard stats OSD"
    assert "MPVFLOW_OSD_SYNTH_STATS" not in patched
    assert "MPVFLOW_OSD_SYNTH_ROW" not in patched
    assert "MPVFlow Synth:" not in patched
    assert "mpvflow-output-rate" not in patched
    assert PREPARE.patch_mpvflow_stats_lua(patched) == patched

    print("MPVFlow OSD debug-text removal tests passed")


if __name__ == "__main__":
    main()
