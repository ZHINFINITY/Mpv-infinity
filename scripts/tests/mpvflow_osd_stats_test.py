#!/usr/bin/env python3
"""Regression tests for FongMi's native motion-interpolation stats row."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATCH = (ROOT / "app/src/main/cpp/mpvflow/fongmi/mpv-fongmi-android.patch").read_text(encoding="utf-8")
PREPARE = (ROOT / "scripts/prepare-mpvflow.py").read_text(encoding="utf-8")
MPV_VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")


def main() -> None:
    assert '"motion-interpolation-info", mp_property_motion_info' in PATCH
    assert 'mp.get_property_native("motion-interpolation-info")' in PATCH
    assert 'append(s, info.state, {prefix="MEMC:"})' in PATCH
    assert 'append(s, format("%.1f/s, synthesis %.1f/s"' in PATCH
    assert 'append(s, format("%dx%d ➜ %dx%d"' in PATCH
    assert "add_motion(stats)" in PATCH
    assert "motion-interpolation-info" not in PREPARE, "the vendor patch, not a second preparation-time injector, owns FongMi stats"
    assert "patch_mpvflow_stats_lua" not in PREPARE, "the retired Flow OSD injector must not be staged into MPV"
    assert "mpvFlowOsdStatsEnabled = mpvFlowFrameInterpolationActive" not in MPV_VIEW
    assert "mpvFlowOsdStatsEnabled = false" in MPV_VIEW, "the old MPVFlow-to-app OSD bridge must remain disabled for the FongMi MPV path"
    print("FongMi motion interpolation OSD regression tests passed")


if __name__ == "__main__":
    main()
