#!/usr/bin/env python3
"""Verify the exact FongMi MEMC source pair used by the Mpv-infinity 645 test."""
from __future__ import annotations

import hashlib
import subprocess
from pathlib import Path

from patch_fongmi_motion_downsample import has_antialias_filter
from patch_fongmi_paired_color_mask import (
    MARKER as PAIRED_COLOR_MASK_MARKER,
    PATCHED_SUPPORT as PAIRED_COLOR_MASK_SUPPORT,
    has_paired_color_mask,
    unpatch_source as unpatch_paired_color_mask,
)

MPV_REVISION = "645f8ecdd3c94379048e9ee3e7014e174de0bd02"
MOTION_SHA256 = "756c1da75d274d81c2e84f988bae1785aa6b6a9a813d16d3b47d9656b31016d0"
MOTION_ANTIALIAS_SHA256 = "793ac79e8243727bb58286ca6bb6a6b6e539ba3e8abae34c4dae7b6503c5cb2e"
MOTION_HEADER_SHA256 = "12fedd08063a540b69667a51e146d539ebae509b63c07964f573410a7d0ae427"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_mpv_source(mpv_dir: Path) -> bool:
    """Fail closed unless source matches the target pin or its exact downsample patch.

    Return True when the bounded analysis-downsample antialias patch is present.
    The paired-color conflict mask is validated separately and stripped before
    source hashing, so its existing behavior remains an independent local layer.
    """
    root = mpv_dir.resolve()
    motion_path = root / "video/out/gpu_next/motion.c"
    header_path = root / "video/out/gpu_next/motion.h"
    vo_path = root / "video/out/vo_gpu_next.c"
    android_context_path = root / "video/out/vulkan/context_android.c"
    required = (motion_path, header_path, vo_path, android_context_path)
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise ValueError("Mpv-infinity FongMi source is missing required files: " + ", ".join(missing))

    if (root / ".git").exists():
        result = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "HEAD"],
            check=False,
            capture_output=True,
            text=True,
        )
        revision = result.stdout.strip() if result.returncode == 0 else ""
        if revision != MPV_REVISION:
            raise ValueError(
                f"Unexpected FongMi MPV revision: expected {MPV_REVISION}, got {revision or 'unavailable'}"
            )

    motion = motion_path.read_text(encoding="utf-8")
    if PAIRED_COLOR_MASK_MARKER in motion or PAIRED_COLOR_MASK_SUPPORT in motion:
        if not has_paired_color_mask(motion):
            raise ValueError("Mpv-infinity paired-color mask marker/source mismatch")
        motion = unpatch_paired_color_mask(motion)
    motion_hash = hashlib.sha256(motion.encode("utf-8")).hexdigest()
    if motion_hash == MOTION_SHA256:
        antialias_patched = False
    elif motion_hash == MOTION_ANTIALIAS_SHA256 and has_antialias_filter(motion):
        antialias_patched = True
    else:
        raise ValueError(f"Unexpected Mpv-infinity FongMi motion source hash for {motion_path.name}: {motion_hash}")

    header_hash = _sha256(header_path)
    if header_hash != MOTION_HEADER_SHA256:
        raise ValueError(f"Unexpected FongMi motion source hash for {header_path.name}: {header_hash}")

    header = header_path.read_text(encoding="utf-8")
    vo = vo_path.read_text(encoding="utf-8")
    android_context = android_context_path.read_text(encoding="utf-8")
    contracts = (
        ("prepared compute dispatch", "MOTION_PASS_PREPARE" in motion and "pl_dispatch_prepare_compute" in motion),
        ("capture/render ownership API", "mp_motion_capture_supported" in motion and "mp_motion_render_owned" in motion),
        ("motion API declaration", "mp_motion_create(pl_gpu gpu, int max_dimension)" in header),
        ("480px analysis cap", "mp_motion_create(p->gpu, 480);" in vo),
        ("FongMi motion option", '{"motion-interpolation", OPT_INT(motion_fps), M_RANGE(0, 240)}' in vo),
        ("Android display-time presentation", "info->queue_display_time_only = true;" in android_context),
    )
    failed = [name for name, passed in contracts if not passed]
    if failed:
        raise ValueError("FongMi motion integration contract is incomplete: " + ", ".join(failed))
    return antialias_patched
