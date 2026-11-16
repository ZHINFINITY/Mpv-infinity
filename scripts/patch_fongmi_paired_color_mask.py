#!/usr/bin/env python3
"""Tighten only FongMi's paired-color evidence mask for conflicting motion candidates."""
from __future__ import annotations

from pathlib import Path

MARKER = "MPVRX_PAIRED_COLOR_EVIDENCE_MASK_V1"
CONFLICT_WEIGHT = "float conflict_weight = smoothstep(0.03, 0.15, color_error);"
PAIRED_ERROR_ANCHOR = "vec2 paired_error = vec2(max(error0.r, max(error0.g, error0.b)),"
ORIGINAL_BLOCK = (
    "                    vec2 support = vec2(1.0) - "
    "smoothstep(vec2(0.03), vec2(0.15), paired_error);\n"
)
PATCHED_BLOCK = (
    "                    // MPVRX_PAIRED_COLOR_EVIDENCE_MASK_V1: keep clean matches; "
    "taper color-mismatched witness recovery sooner.\n"
    "                    vec2 support = vec2(1.0) - "
    "smoothstep(vec2(0.02), vec2(0.08), paired_error);\n"
)
PATCHED_SUPPORT = (
    "vec2 support = vec2(1.0) - smoothstep(vec2(0.02), vec2(0.08), paired_error);"
)


def has_paired_color_mask(source: str) -> bool:
    return (
        source.count(MARKER) == 1
        and source.count(PATCHED_BLOCK) == 1
        and source.count(ORIGINAL_BLOCK) == 0
        and source.count(CONFLICT_WEIGHT) == 1
        and source.count(PAIRED_ERROR_ANCHOR) == 1
    )


def transform_source(source: str) -> tuple[str, bool]:
    """Apply exactly one paired-evidence threshold edit; reject partial or unknown states."""
    if MARKER in source:
        if has_paired_color_mask(source):
            return source, False
        raise ValueError("MPVRx paired-color mask marker/source mismatch")
    if PATCHED_SUPPORT in source:
        raise ValueError("MPVRx paired-color mask thresholds found without their marker")
    if source.count(CONFLICT_WEIGHT) != 1 or source.count(PAIRED_ERROR_ANCHOR) != 1:
        raise ValueError("expected the exact active FongMi conflict-weight and paired-error mask")
    if source.count(ORIGINAL_BLOCK) != 1:
        raise ValueError("expected one exact FongMi paired-color support mask")

    patched = source.replace(ORIGINAL_BLOCK, PATCHED_BLOCK, 1)
    if not has_paired_color_mask(patched):
        raise ValueError("MPVRx paired-color mask patch failed its postconditions")
    return patched, True


def unpatch_source(source: str) -> str:
    """Return the exact pre-mask source after validating the one supported patch."""
    if not has_paired_color_mask(source):
        raise ValueError("MPVRx paired-color mask marker/source mismatch")
    unpatched = source.replace(PATCHED_BLOCK, ORIGINAL_BLOCK, 1)
    if MARKER in unpatched or unpatched.count(ORIGINAL_BLOCK) != 1:
        raise ValueError("MPVRx paired-color mask could not be reversed cleanly")
    return unpatched


def strip_patch(path: Path) -> bool:
    """Temporarily remove only a valid mask so earlier source patchers can rerun."""
    source = path.read_text(encoding="utf-8")
    if MARKER not in source and PATCHED_SUPPORT not in source:
        return False
    path.write_text(unpatch_source(source), encoding="utf-8")
    return True


def apply_patch(path: Path) -> bool:
    """Patch the verified active motion source in place; return whether it changed."""
    source = path.read_text(encoding="utf-8")
    patched, changed = transform_source(source)
    if changed:
        path.write_text(patched, encoding="utf-8")
    return changed


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("motion_source", type=Path)
    args = parser.parse_args()
    changed = apply_patch(args.motion_source)
    print("Applied paired-color mismatch mask" if changed else "Paired-color mismatch mask is already applied")
