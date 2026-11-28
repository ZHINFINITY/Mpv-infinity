#!/usr/bin/env python3
"""Verify the exact Pinned source and 480px integration used by Mpv-infinity."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from mpvrx_motion_source import MPV_REVISION, verify_mpv_source  # noqa: E402
from patch_paired_color_mask import (  # noqa: E402
    has_paired_color_mask,
    transform_source as transform_paired_color_mask,
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", required=True, type=Path)
    parser.add_argument("--require-analysis-downsample-aa", action="store_true")
    parser.add_argument("--require-paired-color-mask", action="store_true")
    args = parser.parse_args()
    try:
        antialias_patched = verify_mpv_source(args.mpv_dir)
    except (OSError, ValueError) as exc:
        parser.exit(1, f"Mpv-infinity Pinned source verification failed: {exc}\n")
    if args.require_analysis_downsample_aa and not antialias_patched:
        parser.exit(1, "Mpv-infinity motion-analysis downsample antialias patch is required but missing\n")
    if args.require_paired_color_mask:
        motion = (args.mpv_dir.resolve() / "video/out/gpu_next/motion.c").read_text(encoding="utf-8")
        if not has_paired_color_mask(motion):
            parser.exit(1, "active paired-color mismatch mask is required but missing\n")
        unchanged, changed = transform_paired_color_mask(motion)
        if changed or unchanged != motion:
            parser.exit(1, "active paired-color mismatch mask is not idempotent\n")
    suffix = " with bounded analysis-downsample antialiasing" if antialias_patched else ""
    if args.require_paired_color_mask:
        suffix += " and active paired-color mismatch mask"
    print(f"Mpv-infinity Pinned motion source {MPV_REVISION}{suffix} and 480px integration verified")


if __name__ == "__main__":
    main()
