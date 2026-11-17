#!/usr/bin/env python3
"""Verify active FongMi MPV output cadence and phase against the pinned runtime."""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXPECTED_REVISION = "645f8ecdd3c94379048e9ee3e7014e174de0bd02"
EXPECTED_TARGET_FPS = (48, 60, 72, 90, 96, 120, 144)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", required=True, type=Path,
                        help="pinned FongMi MPV source used by the runtime build")
    args = parser.parse_args()
    mpv_dir = args.mpv_dir.resolve()
    vo_path = mpv_dir / "video/out/vo_gpu_next.c"
    motion_path = mpv_dir / "video/out/gpu_next/motion.c"
    clock_header = mpv_dir / "video/out/gpu_next/motion_clock.h"
    required = (vo_path, motion_path, clock_header)
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        parser.error("pinned FongMi source is missing: " + ", ".join(missing))

    if (mpv_dir / ".git").exists():
        revision = subprocess.run(
            ["git", "-C", str(mpv_dir), "rev-parse", "HEAD"],
            check=True, capture_output=True, text=True,
        ).stdout.strip()
        if revision != EXPECTED_REVISION:
            parser.error(f"expected pinned FongMi revision {EXPECTED_REVISION}, got {revision}")

    preferences_path = ROOT / "app/src/main/java/app/infinity/mpvz/preferences/DecoderPreferences.kt"
    sheet_path = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/FrameInterpolationFpsSheet.kt"
    player_path = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt"
    preferences = preferences_path.read_text(encoding="utf-8")
    sheet = sheet_path.read_text(encoding="utf-8")
    player = player_path.read_text(encoding="utf-8")
    option_match = re.search(r"MPVFLOW_TARGET_FPS_OPTIONS\s*=\s*listOf\(([^)]*)\)", preferences)
    if not option_match:
        parser.error("could not find the app's interpolation target FPS choices")
    app_target_fps = tuple(int(value.strip()) for value in option_match.group(1).split(","))
    if app_target_fps != EXPECTED_TARGET_FPS:
        parser.error(f"update the cadence matrix for app target choices {app_target_fps}")
    if "DEFAULT_MPVFLOW_TARGET_FPS = 120" not in preferences:
        parser.error("the existing 120-fps default changed unexpectedly")
    if "MPVFLOW_TARGET_FPS_OPTIONS.forEach" not in sheet:
        parser.error("the interpolation FPS sheet is no longer driven by the checked option list")
    if 'setOptionString("motion-interpolation", targetFps.toString())' not in player:
        parser.error("the selected target FPS is no longer passed into MPV motion-interpolation")

    vo = " ".join(vo_path.read_text(encoding="utf-8").split())
    motion = " ".join(motion_path.read_text(encoding="utf-8").split())
    header = clock_header.read_text(encoding="utf-8")
    contracts = (
        ("vsync threshold", "return !(p->motion_fps * frame->vsync_interval >= 1.0 - 1e-6);" in vo),
        ("target clock", "return mp_motion_clock_sample(&p->motion_clock, pts, speed, p->motion_fps);" in vo),
        ("draw-frame PTS use", "output_pts = motion_output_pts(p, frame, output_pts);" in vo),
        ("motion clock implementation", "mp_motion_clock_sample" in header and "floor(clock->elapsed * fps + 1e-7)" in header),
        ("FongMi phase weighting", "vec2 phase = vec2(1.0 - blend, blend);" in motion),
        ("paired inverse-warp phase", "float weight = direction ? 1.0f - alpha : alpha;" in motion),
        ("VO forwards pair alpha", "mp_motion_render_views(mix->motion, &images->frames[0], &images->frames[1], mix->alpha, &output);" in vo),
    )
    failed = [name for name, ok in contracts if not ok]
    if failed:
        parser.error("active FongMi cadence/phase source contract failed: " + ", ".join(failed))

    temp_parent = os.environ.get("TMPDIR")
    with tempfile.TemporaryDirectory(prefix="fongmi-motion-phase-", dir=temp_parent) as tmp:
        binary = Path(tmp) / "fongmi_motion_phase_test"
        test_source = ROOT / "scripts/tests/fongmi_motion_phase_test.c"
        subprocess.run(
            ["cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
             "-I", str(mpv_dir), str(test_source), "-lm", "-o", str(binary)],
            check=True,
        )
        subprocess.run([str(binary)], check=True)

    print("Verified all app target FPS options on the active pinned FongMi cadence and shader-phase path")


if __name__ == "__main__":
    main()
