#!/usr/bin/env python3
"""Apply the controlled 960px FongMi motion-resolution experiment.

The extra pyramid level keeps the coarsest analysis image and native-pixel
search radius unchanged while increasing the finest field resolution. This is
an experimental runtime patch, not a claim that the larger workload is free.
"""
from __future__ import annotations

import argparse
from pathlib import Path

CAP_MARKER = "MPVFLOW_ANALYSIS_RESOLUTION_V1"
PYRAMID_MARKER = "MPVFLOW_ANALYSIS_PYRAMID_LEVELS_V1"

OLD_CALL = "p->motion = mp_motion_create(p->gpu, 480);"
NEW_CALL = "p->motion = mp_motion_create(p->gpu, 960);"
OLD_LOG = '"analysis<=480 pixels, synthesis=source resolution.\\n", fps);'
NEW_LOG = '"analysis<=960 pixels, synthesis=source resolution.\\n", fps);'

OLD_LEVELS = "#define LEVELS 3\n#define MATCH_RADIUS 6"
NEW_LEVELS = (
    "/* MPVFLOW_ANALYSIS_PYRAMID_LEVELS_V1: retain the 480-cap coarse search range. */\n"
    "#define LEVELS 4\n#define MATCH_RADIUS 6"
)
GRID_CONDITION = "if (level == 0 && scale < 0.666667)"


def replace_exact(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise ValueError(f"Expected exactly one {label}, found {count}")
    return source.replace(old, new, 1)


def transform_vo(source: str) -> tuple[str, bool]:
    if CAP_MARKER in source:
        if (
            source.count(CAP_MARKER) == 1
            and source.count(NEW_CALL) == 1
            and source.count(NEW_LOG) == 1
            and OLD_CALL not in source
            and OLD_LOG not in source
        ):
            return source, False
        raise ValueError("FongMi motion-resolution marker/source mismatch in vo_gpu_next.c")

    if source.count(OLD_CALL) != 1 or source.count(OLD_LOG) != 1:
        raise ValueError("Pinned FongMi 480px motion caller did not match exactly")
    source = replace_exact(
        source,
        OLD_CALL,
        f"/* {CAP_MARKER}: controlled 960px analysis-resolution test. */\n        {NEW_CALL}",
        "480px motion caller",
    )
    source = replace_exact(source, OLD_LOG, NEW_LOG, "motion-resolution log")
    if source.count(CAP_MARKER) != 1 or source.count(NEW_CALL) != 1 or source.count(NEW_LOG) != 1:
        raise ValueError("960px FongMi motion caller failed its postconditions")
    return source, True


def transform_motion(source: str) -> tuple[str, bool]:
    if PYRAMID_MARKER in source:
        if (
            source.count(PYRAMID_MARKER) == 1
            and source.count("#define LEVELS 4") == 1
            and source.count("#define MATCH_RADIUS 6") == 1
            and source.count(GRID_CONDITION) == 1
            and source.count("spacing = 3;") == 1
            and OLD_LEVELS not in source
        ):
            return source, False
        raise ValueError("FongMi four-level analysis marker/source mismatch in motion.c")

    if source.count(OLD_LEVELS) != 1:
        raise ValueError("Pinned FongMi three-level pyramid did not match exactly")
    if source.count(GRID_CONDITION) != 1 or source.count("spacing = 3;") != 1:
        raise ValueError("Expected the artifact patcher's 960-cap fine-grid condition")
    source = replace_exact(source, OLD_LEVELS, NEW_LEVELS, "three-level pyramid")
    if (
        source.count(PYRAMID_MARKER) != 1
        or source.count("#define LEVELS 4") != 1
        or source.count("#define MATCH_RADIUS 6") != 1
        or source.count(GRID_CONDITION) != 1
    ):
        raise ValueError("Four-level FongMi analysis pyramid failed its postconditions")
    return source, True


def patch_tree(root: Path) -> None:
    vo_path = root / "video/out/vo_gpu_next.c"
    motion_path = root / "video/out/gpu_next/motion.c"
    if not vo_path.is_file() or not motion_path.is_file():
        raise SystemExit(f"Pinned FongMi MPV source is incomplete under {root}")
    vo, vo_changed = transform_vo(vo_path.read_text(encoding="utf-8"))
    motion, motion_changed = transform_motion(motion_path.read_text(encoding="utf-8"))
    if vo_changed:
        vo_path.write_text(vo, encoding="utf-8")
    if motion_changed:
        motion_path.write_text(motion, encoding="utf-8")
    print(
        "Applied FongMi 960px motion-analysis experiment "
        f"(caller_changed={int(vo_changed)}, pyramid_changed={int(motion_changed)})"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mpv_dir", type=Path, help="root of the pinned FongMi/mpv checkout")
    args = parser.parse_args()
    patch_tree(args.mpv_dir)


if __name__ == "__main__":
    main()
