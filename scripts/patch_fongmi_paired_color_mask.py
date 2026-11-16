#!/usr/bin/env python3
"""Apply FongMi paired-color support and local contour ownership fallback."""
from __future__ import annotations

from pathlib import Path

MARKER = "MPVRX_PAIRED_COLOR_EVIDENCE_MASK_V1"
LEGACY_ARTIFACT_STRENGTH_MARKER = "MPVRX_CONFLICT_PIXEL_STRENGTH_V1"
PREVIOUS_ARTIFACT_STRENGTH_MARKER = "MPV_INFINITY_MOTION_CONFLICT_FADE_V2"
UNCERTAIN_ARTIFACT_STRENGTH_MARKER = "MPV_INFINITY_UNCERTAIN_PIXEL_FALLBACK_V3"
DIRECTIONAL_ARTIFACT_STRENGTH_MARKER = "MPV_INFINITY_DIRECTIONAL_WARP_EVIDENCE_V4"
ARTIFACT_STRENGTH_MARKER = "MPV_INFINITY_LOCAL_CONTOUR_OWNERSHIP_V1"
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
ARTIFACT_STRENGTH_V4_BLOCK = (
    "                    // MPV_INFINITY_DIRECTIONAL_WARP_EVIDENCE_V4: attenuate only the conflicting source lane lacking paired and cycle support.\n"
    "                    vec2 local_warp_support = evidence * vec2(f.w, r.w);\n"
    "                    if (any(isnan(local_warp_support)) || any(isinf(local_warp_support)))\n"
    "                        local_warp_support = vec2(0.0);\n"
    "                    local_warp_support = clamp(local_warp_support, vec2(0.0), vec2(1.0));\n"
    "                    vec2 local_warp_keep = smoothstep(vec2(0.10), vec2(0.60), local_warp_support);\n"
    "                    visibility *= mix(vec2(1.0), local_warp_keep, conflict_weight);\n"
)
FINAL_BLEND_ANCHOR = (
    "            return original * (1.0 - visibility.x - visibility.y) +\n"
    "                   warped0 * visibility.x + warped1 * visibility.y;\n"
)
ARTIFACT_STRENGTH_BLOCK = (
    "            // MPV_INFINITY_LOCAL_CONTOUR_OWNERSHIP_V1: resolve ambiguous inverse paths with local patch evidence.\n"
    "            vec4 blended_output = original * (1.0 - visibility.x - visibility.y) +\n"
    "                                  warped0 * visibility.x + warped1 * visibility.y;\n"
    "            vec2 path_error0 = displacement1 + displacement0 * projection.x;\n"
    "            vec2 path_error1 = displacement0 + displacement1 * projection.y;\n"
    "            float path_disagreement = max(length(path_error0), length(path_error1));\n"
    "            float path_resolve = smoothstep(0.5, 1.5, path_disagreement);\n"
    "            if (path_resolve > 0.0) {\n"
    "                vec2 paired1_position = vec2(pixel) - displacement0 * projection.x;\n"
    "                vec2 paired0_position = vec2(pixel) - displacement1 * projection.y;\n"
    "                vec2 texel_size = 1.0 / output_extent;\n"
    "                vec2 patch_axis = length(path_error0) >= length(path_error1) ? path_error0 : path_error1;\n"
    "                patch_axis /= max(length(patch_axis), 0.000001);\n"
    "                const float patch_tap[3] = float[3](0.0, 1.0, -1.0);\n"
    "                float patch_error0 = 0.0;\n"
    "                float patch_error1 = 0.0;\n"
    "                for (int tap = 0; tap < 3; tap++) {\n"
    "                    vec2 offset = patch_axis * patch_tap[tap];\n"
    "                    vec3 a0 = sample_witness(source0, p0 + offset, output_extent, texel_size);\n"
    "                    vec3 b0 = sample_witness(source1, paired1_position + offset, output_extent, texel_size);\n"
    "                    vec3 a1 = sample_witness(source0, paired0_position + offset, output_extent, texel_size);\n"
    "                    vec3 b1 = sample_witness(source1, p1 + offset, output_extent, texel_size);\n"
    "                    if (hdr != 0) {\n"
    "                        a0 = synthesis_pq(a0); b0 = synthesis_pq(b0);\n"
    "                        a1 = synthesis_pq(a1); b1 = synthesis_pq(b1);\n"
    "                    }\n"
    "                    patch_error0 += max(max(abs(a0.r - b0.r), abs(a0.g - b0.g)), abs(a0.b - b0.b));\n"
    "                    patch_error1 += max(max(abs(a1.r - b1.r), abs(a1.g - b1.g)), abs(a1.b - b1.b));\n"
    "                }\n"
    "                vec2 patch_support = vec2(1.0) - smoothstep(vec2(0.02), vec2(0.08),\n"
    "                                                       vec2(patch_error0, patch_error1) / 3.0);\n"
    "                vec2 ownership = patch_support * vec2(f.w, r.w);\n"
    "                if (any(isnan(ownership)) || any(isinf(ownership)))\n"
    "                    ownership = vec2(0.0);\n"
    "                float directional_evidence = smoothstep(0.01, 0.10, abs(ownership.x - ownership.y));\n"
    "                float unsupported = 1.0 - max(patch_support.x, patch_support.y);\n"
    "                float local_resolve = path_resolve * max(directional_evidence, unsupported);\n"
    "                if (local_resolve > 0.0 && max(ownership.x, ownership.y) > 0.001) {\n"
    "                    int owner = ownership.x > ownership.y ? 0 :\n"
    "                        (ownership.y > ownership.x ? 1 : (blend < 0.5 ? 0 : 1));\n"
    "                    vec4 local_owner = owner == 0 ? warped0 : warped1;\n"
    "                    return mix(blended_output, local_owner, local_resolve);\n"
    "                }\n"
    "                if (local_resolve > 0.0) {\n"
    "                    vec4 endpoint = blend < 0.5 ? original0 : original1;\n"
    "                    return mix(blended_output, endpoint, local_resolve);\n"
    "                }\n"
    "                return blended_output;\n"
    "            }\n"
    "            return blended_output;\n"
)
ARTIFACT_STRENGTH_VERSIONS = (
    (ARTIFACT_STRENGTH_MARKER, ARTIFACT_STRENGTH_BLOCK),
    (DIRECTIONAL_ARTIFACT_STRENGTH_MARKER, ARTIFACT_STRENGTH_V4_BLOCK),
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
    """Recognize the paired-color mask before any local strength or ownership edit."""
    return (
        _has_paired_color_support(source)
        and source.count(VISIBILITY_ANCHOR) == 1
        and source.count(FINAL_BLEND_ANCHOR) == 1
        and not any(marker in source or block in source for marker, block in ARTIFACT_STRENGTH_VERSIONS)
    )


def has_paired_color_mask(source: str) -> bool:
    """Validate the local ownership resolver or one supported prior mask version."""
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
    if not all(
        (source.count(marker) == 1 and source.count(block) == 1)
        if (marker, block) == active_pair
        else (marker not in source and block not in source)
        for marker, block in ARTIFACT_STRENGTH_VERSIONS
    ):
        return False
    if active_pair == (ARTIFACT_STRENGTH_MARKER, ARTIFACT_STRENGTH_BLOCK):
        return source.count(FINAL_BLEND_ANCHOR) == 0
    return source.count(FINAL_BLEND_ANCHOR) == 1


def transform_source(source: str) -> tuple[str, bool]:
    """Apply the local ownership edit, upgrading supported older masks exactly."""
    if MARKER in source:
        if ARTIFACT_STRENGTH_MARKER in source and has_paired_color_mask(source):
            return source, False
        if has_paired_color_mask(source) or has_legacy_paired_color_mask(source):
            source = unpatch_source(source)
        else:
            raise ValueError("Mpv-infinity paired-color mask marker/source mismatch")

    if (
        PATCHED_SUPPORT in source
        or any(marker in source or block in source for marker, block in ARTIFACT_STRENGTH_VERSIONS)
    ):
        raise ValueError("Mpv-infinity paired-color mask found without a valid ownership marker")
    if source.count(CONFLICT_WEIGHT) != 1 or source.count(PAIRED_ERROR_ANCHOR) != 1:
        raise ValueError("expected the exact active FongMi conflict-weight and paired-error mask")
    if (
        source.count(ORIGINAL_BLOCK) != 1
        or source.count(VISIBILITY_ANCHOR) != 1
        or source.count(FINAL_BLEND_ANCHOR) != 1
    ):
        raise ValueError("expected one exact FongMi paired-color support, visibility, and final blend block")

    patched = source.replace(ORIGINAL_BLOCK, PATCHED_BLOCK, 1)
    patched = patched.replace(FINAL_BLEND_ANCHOR, ARTIFACT_STRENGTH_BLOCK, 1)
    if not has_paired_color_mask(patched):
        raise ValueError("Mpv-infinity local contour ownership patch failed its postconditions")
    return patched, True


def unpatch_source(source: str) -> str:
    """Return the exact pre-mask source after validating any supported mask version."""
    if has_paired_color_mask(source):
        marker, strength_block = next(
            pair for pair in ARTIFACT_STRENGTH_VERSIONS if pair[0] in source
        )
        replacement = FINAL_BLEND_ANCHOR if marker == ARTIFACT_STRENGTH_MARKER else ""
        source = source.replace(strength_block, replacement, 1)
    elif not has_legacy_paired_color_mask(source):
        raise ValueError("Mpv-infinity paired-color mask marker/source mismatch")
    unpatched = source.replace(PATCHED_BLOCK, ORIGINAL_BLOCK, 1)
    if (
        MARKER in unpatched
        or PATCHED_SUPPORT in unpatched
        or any(marker in unpatched or block in unpatched for marker, block in ARTIFACT_STRENGTH_VERSIONS)
        or unpatched.count(ORIGINAL_BLOCK) != 1
        or unpatched.count(FINAL_BLEND_ANCHOR) != 1
    ):
        raise ValueError("Mpv-infinity paired-color mask could not be reversed cleanly")
    return unpatched


def strip_patch(path: Path) -> bool:
    """Temporarily remove only a valid mask so earlier source patchers can rerun."""
    source = path.read_text(encoding="utf-8")
    if MARKER not in source and PATCHED_SUPPORT not in source:
        if any(marker in source or block in source for marker, block in ARTIFACT_STRENGTH_VERSIONS):
            raise ValueError("Mpv-infinity local contour ownership marker/source mismatch")
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
    print(
        "Applied Mpv-infinity local contour ownership resolver"
        if changed
        else "Mpv-infinity local contour ownership resolver is already applied"
    )
