#!/usr/bin/env python3
"""Regression tests for FongMi's native motion-interpolation stats row."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PREPARE = (ROOT / "scripts/prepare-mpvflow.py").read_text(encoding="utf-8")
MPV_VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")


def main() -> None:
    assert 'command_path = mpv_dir / "player/command.c"' in PREPARE
    assert '{"motion-interpolation-info", mp_property_motion_info}' in PREPARE
    assert 'mp.get_property_native("motion-interpolation-info")' in PREPARE
    assert 'FONGMI_PATCH = VENDOR / "fongmi/mpv-fongmi-android.patch"' not in PREPARE
    assert "patch_mpvflow_stats_lua" not in PREPARE, "the retired Flow OSD injector must not be staged into MPV"
    assert "mpvFlowOsdStatsEnabled = mpvFlowFrameInterpolationActive" not in MPV_VIEW
    assert "mpvFlowOsdStatsEnabled = false" in MPV_VIEW, "the old MPVFlow-to-app OSD bridge must remain disabled for FongMi MPV"
    print("FongMi native motion-interpolation OSD regression tests passed")


if __name__ == "__main__":
    main()
