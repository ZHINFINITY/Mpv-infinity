#!/usr/bin/env python3
"""Taper FongMi's paired-color recovery and gate conflicting warp lanes locally."""
from __future__ import annotations

from pathlib import Path

MARKER = "MPVRX_PAIRED_COLOR_EVIDENCE_MASK_V1"
LEGACY_ARTIFACT_STRENGTH_MARKER = "MPVRX_CONFLICT_PIXEL_STRENGTH_V1"
PREVIOUS_ARTIFACT_STRENGTH_MARKER = "MPV_INFINITY_MOTION_CONFLICT_FADE_V2"
UNCERTAIN_ARTIFACT_STRENGTH_MARKER = "MPV_INFINITY_UNCERTAIN_PIXEL_FALLBACK_V3"
ARTIFACT_STRENGTH_MARKER = "MPV_INFINITY_DIRECTIONAL_WARP_EVIDENCE_V4"
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
ARTIFACT_STRENGTH_V2_BLOCK = (
    "                    // MPV_INFINITY_MOTION_CONFLICT_FADE_V2: add a small bounded fade only for conflicting fast motion.\n"
    "                    float local_motion_length = length(displacement1 - displacement0);\n"
    "                    float rapid_motion_weight = smoothstep(0.5, 2.0, local_motion_length);\n"
    "                    float high_motion_opacity = mix(0.35, 0.28, rapid_motion_weight);\n"
    "                    visibility *= mix(1.0, high_motion_opacity, conflict_weight);\n"
)
ARTIFACT_STRENGTH_V3_BLOCK = (
    "                    // MPV_INFINITY_UNCERTAIN_PIXEL_FALLBACK_V3: reject conflicting warp where opposing paths strongly disagree.\n"
    "                    float path_disagreement = length(displacement1 - displacement0);\n"
    "                    float uncertainty_weight = smoothstep(0.5, 2.0, path_disagreement);\n"
    "                    float uncertain_path_opacity = mix(0.35, 0.0, uncertainty_weight);\n"
    "                    visibility *= mix(1.0, uncertain_path_opacity, conflict_weight);\n"
)
ARTIFACT_STRENGTH_BLOCK = (
    "                    // MPV_INFINITY_DIRECTIONAL_WARP_EVIDENCE_V4: attenuate only the conflicting source lane lacking paired and cycle support.\n"
    "                    vec2 local_warp_support = evidence * vec2(f.w, r.w);\n"
    "                    if (any(isnan(local_warp_support)) || any(isinf(local_warp_support)))\n"
    "                        local_warp_support = vec2(0.0);\n"
    "                    local_warp_support = clamp(local_warp_support, vec2(0.0), vec2(1.0));\n"
    "                    vec2 local_warp_keep = smoothstep(vec2(0.10), vec2(0.60), local_warp_support);\n"
    "                    visibility *= mix(vec2(1.0), local_warp_keep, conflict_weight);\n"
)
ARTIFACT_STRENGTH_VERSIONS = (
    (ARTIFACT_STRENGTH_MARKER, ARTIFACT_STRENGTH_BLOCK),
    (UNCERTAIN_ARTIFACT_STRENGTH_MARKER, ARTIFACT_STRENGTH_V3_BLOCK),
    (PREVIOUS_ARTIFACT_STRENGTH_MARKER, ARTIFACT_STRENGTH_V2_BLOCK),
    (LEGACY_ARTIFACT_STRENGTH_MARKER, ARTIFACT_STRENGTH_V1_BLOCK),
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
    """Recognize the paired-color mask before any conflict-opacity taper."""
    return (
        _has_paired_color_support(source)
        and source.count(VISIBILITY_ANCHOR) == 1
        and not any(marker in source or block in source for marker, block in ARTIFACT_STRENGTH_VERSIONS)
    )


def has_paired_color_mask(source: str) -> bool:
    """Validate the current directional gate or one supported prior version."""
    if not _has_paired_color_support(source) or source.count(VISIBILITY_ANCHOR) != 1:
        return False
    active = [
        (marker, block)
        for marker, block in ARTIFACT_STRENGTH_VERSIONS
        if source.count(marker) == 1 and source.count(block) == 1
    ]
    if len(active) != 1:
        return False
    active_pair = active[0]
    return all(
        (source.count(marker) == 1 and source.count(block) == 1)
        if (marker, block) == active_pair
        else (marker not in source and block not in source)
        for marker, block in ARTIFACT_STRENGTH_VERSIONS
    )


def transform_source(source: str) -> tuple[str, bool]:
    """Apply exact mask edits; upgrade supported older masks or reject unknown states."""
    if MARKER in source:
        if has_paired_color_mask(source):
            if ARTIFACT_STRENGTH_MARKER in source:
                return source, False
            prior_block = next(
                (block for marker, block in ARTIFACT_STRENGTH_VERSIONS[1:] if marker in source),
                None,
            )
            if prior_block is None:
                raise ValueError("Mpv-infinity directional warp gate upgrade has no recognized prior block")
            patched = source.replace(prior_block, ARTIFACT_STRENGTH_BLOCK, 1)
            if not has_paired_color_mask(patched) or ARTIFACT_STRENGTH_MARKER not in patched:
                raise ValueError("Mpv-infinity directional warp gate upgrade failed its postconditions")
            return patched, True
        if not has_legacy_paired_color_mask(source):
            raise ValueError("Mpv-infinity paired-color mask marker/source mismatch")
        patched = source.replace(VISIBILITY_ANCHOR, VISIBILITY_ANCHOR + ARTIFACT_STRENGTH_BLOCK, 1)
        if not has_paired_color_mask(patched):
            raise ValueError("Mpv-infinity directional warp gate patch failed its postconditions")
        return patched, True

    if (
        PATCHED_SUPPORT in source
        or any(marker in source or block in source for marker, block in ARTIFACT_STRENGTH_VERSIONS)
    ):
        raise ValueError("Mpv-infinity paired-color mask thresholds found without their marker")
    if source.count(CONFLICT_WEIGHT) != 1 or source.count(PAIRED_ERROR_ANCHOR) != 1:
        raise ValueError("expected the exact active FongMi conflict-weight and paired-error mask")
    if source.count(ORIGINAL_BLOCK) != 1 or source.count(VISIBILITY_ANCHOR) != 1:
        raise ValueError("expected one exact FongMi paired-color support and visibility block")

    patched = source.replace(ORIGINAL_BLOCK, PATCHED_BLOCK, 1)
    patched = patched.replace(VISIBILITY_ANCHOR, VISIBILITY_ANCHOR + ARTIFACT_STRENGTH_BLOCK, 1)
    if not has_paired_color_mask(patched):
        raise ValueError("Mpv-infinity paired-color mask patch failed its postconditions")
    return patched, True


def unpatch_source(source: str) -> str:
    """Return the exact pre-mask source after validating any supported mask version."""
    if has_paired_color_mask(source):
        strength_block = next(block for marker, block in ARTIFACT_STRENGTH_VERSIONS if marker in source)
        source = source.replace(strength_block, "", 1)
    elif not has_legacy_paired_color_mask(source):
        raise ValueError("Mpv-infinity paired-color mask marker/source mismatch")
    unpatched = source.replace(PATCHED_BLOCK, ORIGINAL_BLOCK, 1)
    if (
        MARKER in unpatched
        or PATCHED_SUPPORT in unpatched
        or any(marker in unpatched or block in unpatched for marker, block in ARTIFACT_STRENGTH_VERSIONS)
        or unpatched.count(ORIGINAL_BLOCK) != 1
    ):
        raise ValueError("Mpv-infinity paired-color mask could not be reversed cleanly")
    return unpatched


def strip_patch(path: Path) -> bool:
    """Temporarily remove only a valid mask so earlier source patchers can rerun."""
    source = path.read_text(encoding="utf-8")
    if MARKER not in source and PATCHED_SUPPORT not in source:
        if any(marker in source or block in source for marker, block in ARTIFACT_STRENGTH_VERSIONS):
            raise ValueError("Mpv-infinity conflict-pixel strength marker/source mismatch")
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
    print("Applied Mpv∞ directional warp evidence gate" if changed else "Mpv∞ directional warp evidence gate is already applied")
