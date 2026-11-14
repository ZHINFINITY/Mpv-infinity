#!/usr/bin/env python3
"""Verify the exact MPVRx-provenance FongMi source and its 480px integration."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from mpvrx_motion_source import MPV_REVISION, verify_mpv_source  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", required=True, type=Path)
    parser.add_argument("--require-analysis-downsample-aa", action="store_true")
    args = parser.parse_args()
    try:
        antialias_patched = verify_mpv_source(args.mpv_dir)
    except (OSError, ValueError) as exc:
        parser.exit(1, f"MPVRx FongMi source verification failed: {exc}\n")
    if args.require_analysis_downsample_aa and not antialias_patched:
        parser.exit(1, "MPVRx motion-analysis downsample antialias patch is required but missing\n")
    suffix = " with bounded analysis-downsample antialiasing" if antialias_patched else ""
    print(f"MPVRx FongMi motion source {MPV_REVISION}{suffix} and 480px integration verified")


if __name__ == "__main__":
    main()
