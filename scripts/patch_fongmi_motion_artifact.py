#!/usr/bin/env python3
"""Apply the pinned, narrowly scoped FongMi motion artifact taper adjustment.

This edits only the active upstream synthesis shader source in the temporary
pinned MPV build tree. It does not copy or replace FongMi source files.
"""
from __future__ import annotations

import argparse
from pathlib import Path

SOURCE_RELATIVE = Path("video/out/gpu_next/motion.c")
MARKER = "MPVFLOW_ARTIFACT_TRUST_TAPER_V2"
ORIGINAL_TAPER = (
    "            trust *= 1.0 - smoothstep(0.03, 0.15,\n"
    "                                      color_error);"
)
PATCHED_TAPER = (
    "            // " + MARKER + ": soften color-conflict fallback only when motion trust remains\n"
    "            trust *= 1.0 - smoothstep(0.03, 0.30,\n"
    "                                      color_error);"
)


def transform_source(source: str) -> tuple[str, bool]:
    """Apply the exact one-site edit; return (source, changed)."""
    if MARKER in source:
        if source.count(MARKER) != 1 or source.count(PATCHED_TAPER) != 1 or ORIGINAL_TAPER in source:
            raise ValueError("FongMi motion artifact taper marker/source mismatch")
        return source, False
    if source.count(ORIGINAL_TAPER) != 1:
        raise ValueError("Pinned FongMi synthesis taper anchor changed; refusing an unverified patch")
    return source.replace(ORIGINAL_TAPER, PATCHED_TAPER, 1), True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mpv_dir", type=Path, help="extracted, pinned FongMi MPV source tree")
    args = parser.parse_args()
    source_path = args.mpv_dir / SOURCE_RELATIVE
    if not source_path.is_file():
        raise SystemExit(f"Pinned FongMi synthesis source is missing: {source_path}")
    source = source_path.read_text(encoding="utf-8")
    try:
        patched, changed = transform_source(source)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    if changed:
        source_path.write_text(patched, encoding="utf-8")
        print("Applied MPVFlow's narrow FongMi color-trust taper adjustment")
    else:
        print("MPVFlow FongMi color-trust taper adjustment is already applied")


if __name__ == "__main__":
    main()
