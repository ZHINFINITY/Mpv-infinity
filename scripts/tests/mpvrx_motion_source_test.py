#!/usr/bin/env python3
"""Verify the exact MPVRx-provenance FongMi source and its 480px integration."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from mpvrx_motion_source import MPV_REVISION, verify_mpv_source  # noqa: E402
from patch_fongmi_motion_jelly import (  # noqa: E402
    has_jelly_preview_patch,
    has_jelly_region_patch,
)
from patch_fongmi_motion_jelly_probe import MARKER as JELLY_PROBE_MARKER  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", required=True, type=Path)
    parser.add_argument("--require-analysis-downsample-aa", action="store_true")
    parser.add_argument("--require-jelly-experimental", action="store_true")
    parser.add_argument("--require-jelly-preview", action="store_true")
    parser.add_argument("--require-jelly-probe", action="store_true")
    args = parser.parse_args()
    if args.require_jelly_experimental and args.require_jelly_preview:
        parser.error("Jelly synthesis and blend test cannot both be required")
    if args.require_jelly_probe and not args.require_jelly_experimental:
        parser.error("Jelly probe requires Jelly experimental synthesis")
    try:
        antialias_patched = verify_mpv_source(args.mpv_dir)
    except (OSError, ValueError) as exc:
        parser.exit(1, f"MPVRx FongMi source verification failed: {exc}\n")
    if args.require_analysis_downsample_aa and not antialias_patched:
        parser.exit(1, "MPVRx motion-analysis downsample antialias patch is required but missing\n")
    if args.require_jelly_experimental and not args.require_jelly_probe:
        motion = (args.mpv_dir.resolve() / "video/out/gpu_next/motion.c").read_text(encoding="utf-8")
        if not has_jelly_region_patch(motion):
            parser.exit(1, "opt-in MPVRx jelly-region patch is required but missing\n")
    if args.require_jelly_preview:
        motion = (args.mpv_dir.resolve() / "video/out/gpu_next/motion.c").read_text(encoding="utf-8")
        if not has_jelly_preview_patch(motion):
            parser.exit(1, "opt-in MPVRx Jelly blend-test patch is required but missing\n")
    if args.require_jelly_probe:
        root = args.mpv_dir.resolve()
        motion = (root / "video/out/gpu_next/motion.c").read_text(encoding="utf-8")
        motion_header = (root / "video/out/gpu_next/motion.h").read_text(encoding="utf-8")
        vo = (root / "video/out/vo_gpu_next.c").read_text(encoding="utf-8")
        meson = (root / "meson.build").read_text(encoding="utf-8")
        if not all(marker in motion for marker in (JELLY_PROBE_MARKER, "synthesis_probe_header", "mp_motion_render_frames_probe")):
            parser.exit(1, "opt-in motion Jelly probe patch is incomplete\n")
        if not all(marker in motion_header for marker in (JELLY_PROBE_MARKER, "struct mp_motion_probe_request", "pl_buf buffer;")):
            parser.exit(1, "opt-in motion Jelly probe API header is incomplete\n")
        if not all(marker in vo for marker in (
            "MPVFLOW_FONGMI_VO_JELLY_PROBE_V1",
            "pl_buf_poll(p->gpu, p->jelly_probe_buf, 0)",
            "mpvrx_jelly_probe_record_is_activation",
            "fallback_probe_pair = !valid_probe_pts || probe_pts_min > 1.63",
            "jelly_probe_last_record_valid",
            "max_mapped_vram",
            "mp_motion_render_frames_probe",
        )):
            parser.exit(1, "opt-in VO Jelly probe/readback integration is incomplete\n")
        if "OPT_BOOL(fongmi_jelly_probe_arm)" not in vo or "OPT_FLAG(fongmi_jelly_probe_arm)" in vo:
            parser.exit(1, "Jelly probe boolean option must use the pinned MPV OPT_BOOL macro\n")
        if "video/out/mpvrx_jelly_probe_writer.c" not in meson:
            parser.exit(1, "opt-in Jelly probe writer is not included in the native build\n")
    suffix = " with bounded analysis-downsample antialiasing" if antialias_patched else ""
    if args.require_jelly_experimental:
        suffix += " and opt-in jelly-region synthesis"
    if args.require_jelly_preview:
        suffix += " and opt-in Jelly blend test (confirmed mask retained)"
    if args.require_jelly_probe:
        suffix += " and the isolated one-shot Jelly probe"
    print(f"MPVRx FongMi motion source {MPV_REVISION}{suffix} and 480px integration verified")


if __name__ == "__main__":
    main()
