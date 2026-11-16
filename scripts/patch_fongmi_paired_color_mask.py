#!/usr/bin/env python3
"""Taper FongMi's paired-color recovery and add a bounded high-motion conflict fade."""
from __future__ import annotations

from pathlib import Path

MARKER = "MPVRX_PAIRED_COLOR_EVIDENCE_MASK_V1"
LEGACY_ARTIFACT_STRENGTH_MARKER = "MPVRX_CONFLICT_PIXEL_STRENGTH_V1"
ARTIFACT_STRENGTH_MARKER = "MPV_INFINITY_MOTION_CONFLICT_FADE_V2"
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
VISIBILITY_ANCHOR = (
    "                    visibility += conflict_weight * phase * vec2(f.z, r.z) * evidence;\n"
)
ARTIFACT_STRENGTH_V1_BLOCK = (
    "                    // MPVRX_CONFLICT_PIXEL_STRENGTH_V1: fade only locally conflicting warp colors.\n"
    "                    visibility *= mix(1.0, 0.35, conflict_weight);\n"
)
ARTIFACT_STRENGTH_BLOCK = (
    "                    // MPV_INFINITY_MOTION_CONFLICT_FADE_V2: add a small bounded fade only for conflicting fast motion.\n"
    "                    float local_motion_length = length(displacement1 - displacement0);\n"
    "                    float rapid_motion_weight = smoothstep(0.5, 2.0, local_motion_length);\n"
    "                    float high_motion_opacity = mix(0.35, 0.28, rapid_motion_weight);\n"
    "                    visibility *= mix(1.0, high_motion_opacity, conflict_weight);\n"
)


def _has_paired_color_support(source: str) -> bool:
    return (
        source.count(MARKER) == 1
        and source.count(PATCHED_BLOCK) == 1
        and source.count(ORIGINAL_BLOCK) == 0
        and source.count(CONFLICT_WEIGHT) == 1
        and source.count(PAIRED_ERROR_ANCHOR) == 1
    )


def has_legacy_paired_color_mask(source: str) -> bool:
    """Recognize the paired-color mask before either conflict-opacity taper."""
    return (
        _has_paired_color_support(source)
        and source.count(VISIBILITY_ANCHOR) == 1
        and ARTIFACT_STRENGTH_MARKER not in source
        and LEGACY_ARTIFACT_STRENGTH_MARKER not in source
        and ARTIFACT_STRENGTH_BLOCK not in source
        and ARTIFACT_STRENGTH_V1_BLOCK not in source
    )


def has_paired_color_mask(source: str) -> bool:
    """Validate the current mask or its immediately preceding supported version."""
    return (
        _has_paired_color_support(source)
        and source.count(VISIBILITY_ANCHOR) == 1
        and (
            (
                source.count(ARTIFACT_STRENGTH_MARKER) == 1
                and source.count(ARTIFACT_STRENGTH_BLOCK) == 1
                and LEGACY_ARTIFACT_STRENGTH_MARKER not in source
                and ARTIFACT_STRENGTH_V1_BLOCK not in source
            )
            or (
                source.count(LEGACY_ARTIFACT_STRENGTH_MARKER) == 1
                and source.count(ARTIFACT_STRENGTH_V1_BLOCK) == 1
                and ARTIFACT_STRENGTH_MARKER not in source
                and ARTIFACT_STRENGTH_BLOCK not in source
            )
        )
    )


def transform_source(source: str) -> tuple[str, bool]:
    """Apply exact mask edits; upgrade supported older masks or reject unknown states."""
    if MARKER in source:
        if has_paired_color_mask(source):
            if ARTIFACT_STRENGTH_MARKER in source:
                return source, False
            patched = source.replace(ARTIFACT_STRENGTH_V1_BLOCK, ARTIFACT_STRENGTH_BLOCK, 1)
            if not has_paired_color_mask(patched) or ARTIFACT_STRENGTH_MARKER not in patched:
                raise ValueError("MPVRx motion-aware conflict fade upgrade failed its postconditions")
            return patched, True
        if not has_legacy_paired_color_mask(source):
            raise ValueError("MPVRx paired-color mask marker/source mismatch")
        patched = source.replace(VISIBILITY_ANCHOR, VISIBILITY_ANCHOR + ARTIFACT_STRENGTH_BLOCK, 1)
        if not has_paired_color_mask(patched):
            raise ValueError("MPVRx motion-aware conflict fade patch failed its postconditions")
        return patched, True

    if (
        PATCHED_SUPPORT in source
        or ARTIFACT_STRENGTH_MARKER in source
        or LEGACY_ARTIFACT_STRENGTH_MARKER in source
        or ARTIFACT_STRENGTH_BLOCK in source
        or ARTIFACT_STRENGTH_V1_BLOCK in source
    ):
        raise ValueError("MPVRx paired-color mask thresholds found without their marker")
    if source.count(CONFLICT_WEIGHT) != 1 or source.count(PAIRED_ERROR_ANCHOR) != 1:
        raise ValueError("expected the exact active FongMi conflict-weight and paired-error mask")
    if source.count(ORIGINAL_BLOCK) != 1 or source.count(VISIBILITY_ANCHOR) != 1:
        raise ValueError("expected one exact FongMi paired-color support and visibility block")

    patched = source.replace(ORIGINAL_BLOCK, PATCHED_BLOCK, 1)
    patched = patched.replace(VISIBILITY_ANCHOR, VISIBILITY_ANCHOR + ARTIFACT_STRENGTH_BLOCK, 1)
    if not has_paired_color_mask(patched):
        raise ValueError("MPVRx paired-color mask patch failed its postconditions")
    return patched, True


def unpatch_source(source: str) -> str:
    """Return the exact pre-mask source after validating either supported mask version."""
    if has_paired_color_mask(source):
        strength_block = (
            ARTIFACT_STRENGTH_BLOCK
            if ARTIFACT_STRENGTH_MARKER in source
            else ARTIFACT_STRENGTH_V1_BLOCK
        )
        source = source.replace(strength_block, "", 1)
    elif not has_legacy_paired_color_mask(source):
        raise ValueError("MPVRx paired-color mask marker/source mismatch")
    unpatched = source.replace(PATCHED_BLOCK, ORIGINAL_BLOCK, 1)
    if (
        MARKER in unpatched
        or ARTIFACT_STRENGTH_MARKER in unpatched
        or LEGACY_ARTIFACT_STRENGTH_MARKER in unpatched
        or PATCHED_SUPPORT in unpatched
        or ARTIFACT_STRENGTH_BLOCK in unpatched
        or ARTIFACT_STRENGTH_V1_BLOCK in unpatched
        or unpatched.count(ORIGINAL_BLOCK) != 1
    ):
        raise ValueError("MPVRx paired-color mask could not be reversed cleanly")
    return unpatched


def strip_patch(path: Path) -> bool:
    """Temporarily remove only a valid mask so earlier source patchers can rerun."""
    source = path.read_text(encoding="utf-8")
    if MARKER not in source and PATCHED_SUPPORT not in source:
        if (
            ARTIFACT_STRENGTH_MARKER in source
            or LEGACY_ARTIFACT_STRENGTH_MARKER in source
            or ARTIFACT_STRENGTH_BLOCK in source
            or ARTIFACT_STRENGTH_V1_BLOCK in source
        ):
            raise ValueError("MPVRx conflict-pixel strength marker/source mismatch")
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
    print("Applied Mpv∞ motion-aware conflict fade" if changed else "Mpv∞ motion-aware conflict fade is already applied")
