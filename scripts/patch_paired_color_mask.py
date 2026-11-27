#!/usr/bin/env python3
"""Taper Pinned's paired-color recovery and gate conflicting warp lanes locally."""
from __future__ import annotations

from pathlib import Path

MARKER = "MPVRX_PAIRED_COLOR_EVIDENCE_MASK_V1"
LEGACY_ARTIFACT_STRENGTH_MARKER = "MPVRX_CONFLICT_PIXEL_STRENGTH_V1"
PREVIOUS_ARTIFACT_STRENGTH_MARKER = "MPV_INFINITY_MOTION_CONFLICT_FADE_V2"
UNCERTAIN_ARTIFACT_STRENGTH_MARKER = "MPV_INFINITY_UNCERTAIN_PIXEL_FALLBACK_V3"
DIRECTIONAL_ARTIFACT_STRENGTH_MARKER = "MPV_INFINITY_DIRECTIONAL_WARP_EVIDENCE_V4"
SELECTIVE_CONTOUR_V1_MARKER = "MPV_INFINITY_SELECTIVE_CONTOUR_RESOLVER_V1"
ARTIFACT_STRENGTH_MARKER = "MPV_INFINITY_SELECTIVE_CONTOUR_RESOLVER_V2"
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
FINAL_BLEND_ANCHOR = (
    "            return original * (1.0 - visibility.x - visibility.y) +\n"
    "                   warped0 * visibility.x + warped1 * visibility.y;\n"
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
RESOLVER_CENTER_ERROR_START = 0.10
RESOLVER_CENTER_ERROR_FULL = 0.15
RESOLVER_CENTER_WEIGHT_MIN = 0.50
RESOLVER_SOURCE_CHANGE_START = 0.06
RESOLVER_SOURCE_CHANGE_FULL = 0.16
RESOLVER_PATH_ERROR_START = 1.5
RESOLVER_PATH_ERROR_FULL = 3.0
RESOLVER_SUPPORT_GOOD = 0.85
RESOLVER_SUPPORT_BAD = 0.15
SELECTIVE_CONTOUR_V1_BLOCK = (
    "            // MPV_INFINITY_SELECTIVE_CONTOUR_RESOLVER_V1: suppress one locally unsupported warp only under strong center conflict and trajectory separation.\n"
    "            vec2 resolved_visibility = visibility;\n"
    "            vec2 path_error0 = displacement1 + displacement0 * projection.x;\n"
    "            vec2 path_error1 = displacement0 + displacement1 * projection.y;\n"
    "            float path_disagreement = max(length(path_error0), length(path_error1));\n"
    "            if (!fixed_weights && path_disagreement > 1.5) {\n"
    "                vec3 center_metric0 = hdr != 0 ? synthesis_pq(warped0.rgb) : warped0.rgb;\n"
    "                vec3 center_metric1 = hdr != 0 ? synthesis_pq(warped1.rgb) : warped1.rgb;\n"
    "                vec3 center_delta = abs(center_metric0 - center_metric1);\n"
    "                float center_color_error = max(center_delta.r, max(center_delta.g, center_delta.b));\n"
    "                float center_conflict_weight = smoothstep(0.10, 0.15, center_color_error);\n"
    "                if (center_conflict_weight >= 0.50) {\n"
    "                    vec2 paired1_position = vec2(pixel) - displacement0 * projection.x;\n"
    "                    vec2 paired0_position = vec2(pixel) - displacement1 * projection.y;\n"
    "                    vec2 texel_size = 1.0 / output_extent;\n"
    "                    vec2 patch_axis = length(path_error0) >= length(path_error1) ? path_error0 : path_error1;\n"
    "                    patch_axis /= max(length(patch_axis), 0.000001);\n"
    "                    const float patch_tap[3] = float[3](0.0, 1.0, -1.0);\n"
    "                    float patch_error0 = 0.0;\n"
    "                    float patch_error1 = 0.0;\n"
    "                    for (int tap = 0; tap < 3; tap++) {\n"
    "                        vec2 offset = patch_axis * patch_tap[tap];\n"
    "                        vec3 a0 = sample_witness(source0, p0 + offset, output_extent, texel_size);\n"
    "                        vec3 b0 = sample_witness(source1, paired1_position + offset, output_extent, texel_size);\n"
    "                        vec3 a1 = sample_witness(source0, paired0_position + offset, output_extent, texel_size);\n"
    "                        vec3 b1 = sample_witness(source1, p1 + offset, output_extent, texel_size);\n"
    "                        if (hdr != 0) {\n"
    "                            a0 = synthesis_pq(a0); b0 = synthesis_pq(b0);\n"
    "                            a1 = synthesis_pq(a1); b1 = synthesis_pq(b1);\n"
    "                        }\n"
    "                        patch_error0 += max(max(abs(a0.r - b0.r), abs(a0.g - b0.g)), abs(a0.b - b0.b));\n"
    "                        patch_error1 += max(max(abs(a1.r - b1.r), abs(a1.g - b1.g)), abs(a1.b - b1.b));\n"
    "                    }\n"
    "                    vec2 patch_support = vec2(1.0) - smoothstep(vec2(0.02), vec2(0.08),\n"
    "                                                           vec2(patch_error0, patch_error1) / 3.0);\n"
    "                    if (any(isnan(patch_support)) || any(isinf(patch_support)))\n"
    "                        patch_support = vec2(0.0);\n"
    "                    float best_support = max(patch_support.x, patch_support.y);\n"
    "                    float worst_support = min(patch_support.x, patch_support.y);\n"
    "                    if (best_support >= 0.85 && worst_support <= 0.15) {\n"
    "                        float trajectory_weight = smoothstep(1.5, 3.0, path_disagreement);\n"
    "                        float reject_weight = center_conflict_weight * trajectory_weight;\n"
    "                        if (patch_support.x > patch_support.y)\n"
    "                            resolved_visibility.y *= 1.0 - reject_weight;\n"
    "                        else\n"
    "                            resolved_visibility.x *= 1.0 - reject_weight;\n"
    "                    }\n"
    "                }\n"
    "            }\n"
    "            return original * (1.0 - resolved_visibility.x - resolved_visibility.y) +\n"
    "                   warped0 * resolved_visibility.x + warped1 * resolved_visibility.y;\n"
)
ARTIFACT_STRENGTH_BLOCK = (
    "            // MPV_INFINITY_SELECTIVE_CONTOUR_RESOLVER_V2: require source change, separated trajectories and a decisive paired-support split.\n"
    "            vec2 resolved_visibility = visibility;\n"
    "            vec2 path_error0 = displacement1 + displacement0 * projection.x;\n"
    "            vec2 path_error1 = displacement0 + displacement1 * projection.y;\n"
    "            float path_disagreement = max(length(path_error0), length(path_error1));\n"
    "            if (!fixed_weights && path_disagreement > 1.5) {\n"
    "                vec3 center_metric0 = hdr != 0 ? synthesis_pq(warped0.rgb) : warped0.rgb;\n"
    "                vec3 center_metric1 = hdr != 0 ? synthesis_pq(warped1.rgb) : warped1.rgb;\n"
    "                vec3 center_delta = abs(center_metric0 - center_metric1);\n"
    "                float center_color_error = max(center_delta.r, max(center_delta.g, center_delta.b));\n"
    "                float center_conflict_weight = smoothstep(0.10, 0.15, center_color_error);\n"
    "                vec3 source_metric0 = hdr != 0 ? synthesis_pq(original0.rgb) : original0.rgb;\n"
    "                vec3 source_metric1 = hdr != 0 ? synthesis_pq(original1.rgb) : original1.rgb;\n"
    "                vec3 source_delta = abs(source_metric0 - source_metric1);\n"
    "                float source_change_error = max(source_delta.r, max(source_delta.g, source_delta.b));\n"
    "                float source_change_weight = smoothstep(0.06, 0.16, source_change_error);\n"
    "                float resolver_weight = max(center_conflict_weight, source_change_weight);\n"
    "                if (resolver_weight >= 0.50) {\n"
    "                    vec2 paired1_position = vec2(pixel) - displacement0 * projection.x;\n"
    "                    vec2 paired0_position = vec2(pixel) - displacement1 * projection.y;\n"
    "                    vec2 texel_size = 1.0 / output_extent;\n"
    "                    vec2 patch_axis = length(path_error0) >= length(path_error1) ? path_error0 : path_error1;\n"
    "                    patch_axis /= max(length(patch_axis), 0.000001);\n"
    "                    const float patch_tap[3] = float[3](0.0, 1.0, -1.0);\n"
    "                    float patch_error0 = 0.0;\n"
    "                    float patch_error1 = 0.0;\n"
    "                    for (int tap = 0; tap < 3; tap++) {\n"
    "                        vec2 offset = patch_axis * patch_tap[tap];\n"
    "                        vec3 a0 = sample_witness(source0, p0 + offset, output_extent, texel_size);\n"
    "                        vec3 b0 = sample_witness(source1, paired1_position + offset, output_extent, texel_size);\n"
    "                        vec3 a1 = sample_witness(source0, paired0_position + offset, output_extent, texel_size);\n"
    "                        vec3 b1 = sample_witness(source1, p1 + offset, output_extent, texel_size);\n"
    "                        if (hdr != 0) {\n"
    "                            a0 = synthesis_pq(a0); b0 = synthesis_pq(b0);\n"
    "                            a1 = synthesis_pq(a1); b1 = synthesis_pq(b1);\n"
    "                        }\n"
    "                        patch_error0 += max(max(abs(a0.r - b0.r), abs(a0.g - b0.g)), abs(a0.b - b0.b));\n"
    "                        patch_error1 += max(max(abs(a1.r - b1.r), abs(a1.g - b1.g)), abs(a1.b - b1.b));\n"
    "                    }\n"
    "                    vec2 patch_support = vec2(1.0) - smoothstep(vec2(0.02), vec2(0.08),\n"
    "                                                           vec2(patch_error0, patch_error1) / 3.0);\n"
    "                    if (any(isnan(patch_support)) || any(isinf(patch_support)))\n"
    "                        patch_support = vec2(0.0);\n"
    "                    float best_support = max(patch_support.x, patch_support.y);\n"
    "                    float worst_support = min(patch_support.x, patch_support.y);\n"
    "                    if (best_support >= 0.85 && worst_support <= 0.15) {\n"
    "                        float trajectory_weight = smoothstep(1.5, 3.0, path_disagreement);\n"
    "                        float reject_weight = resolver_weight * trajectory_weight;\n"
    "                        if (patch_support.x > patch_support.y)\n"
    "                            resolved_visibility.y *= 1.0 - reject_weight;\n"
    "                        else\n"
    "                            resolved_visibility.x *= 1.0 - reject_weight;\n"
    "                    }\n"
    "                }\n"
    "            }\n"
    "            return original * (1.0 - resolved_visibility.x - resolved_visibility.y) +\n"
    "                   warped0 * resolved_visibility.x + warped1 * resolved_visibility.y;\n"
)
ARTIFACT_STRENGTH_VERSIONS = (
    (ARTIFACT_STRENGTH_MARKER, ARTIFACT_STRENGTH_BLOCK),
    (SELECTIVE_CONTOUR_V1_MARKER, SELECTIVE_CONTOUR_V1_BLOCK),
    (DIRECTIONAL_ARTIFACT_STRENGTH_MARKER, ARTIFACT_STRENGTH_V4_BLOCK),
    (UNCERTAIN_ARTIFACT_STRENGTH_MARKER, ARTIFACT_STRENGTH_V3_BLOCK),
    (PREVIOUS_ARTIFACT_STRENGTH_MARKER, ARTIFACT_STRENGTH_V2_BLOCK),
    (LEGACY_ARTIFACT_STRENGTH_MARKER, ARTIFACT_STRENGTH_V1_BLOCK),
)
RESOLVER_ARTIFACT_STRENGTH_MARKERS = (
    ARTIFACT_STRENGTH_MARKER,
    SELECTIVE_CONTOUR_V1_MARKER,
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
    """Recognize the paired-color mask before any local resolver."""
    return (
        _has_paired_color_support(source)
        and source.count(VISIBILITY_ANCHOR) == 1
        and source.count(FINAL_BLEND_ANCHOR) == 1
        and not any(marker in source or block in source for marker, block in ARTIFACT_STRENGTH_VERSIONS)
    )


def has_paired_color_mask(source: str) -> bool:
    """Validate the selective resolver or one supported prior mask version."""
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
    if active_pair[0] in RESOLVER_ARTIFACT_STRENGTH_MARKERS:
        return source.count(FINAL_BLEND_ANCHOR) == 0
    return source.count(FINAL_BLEND_ANCHOR) == 1


def transform_source(source: str) -> tuple[str, bool]:
    """Apply the selective resolver, upgrade known prior masks, or reject unknown states."""
    if MARKER in source:
        if has_paired_color_mask(source):
            if ARTIFACT_STRENGTH_MARKER in source:
                return source, False
            source = unpatch_source(source)
        elif has_legacy_paired_color_mask(source):
            source = unpatch_source(source)
        else:
            raise ValueError("Mpv-infinity paired-color mask marker/source mismatch")

    if (
        PATCHED_SUPPORT in source
        or any(marker in source or block in source for marker, block in ARTIFACT_STRENGTH_VERSIONS)
    ):
        raise ValueError("Mpv-infinity paired-color mask found without a valid resolver marker")
    if source.count(CONFLICT_WEIGHT) != 1 or source.count(PAIRED_ERROR_ANCHOR) != 1:
        raise ValueError("expected the exact active Pinned conflict-weight and paired-error mask")
    if (
        source.count(ORIGINAL_BLOCK) != 1
        or source.count(VISIBILITY_ANCHOR) != 1
        or source.count(FINAL_BLEND_ANCHOR) != 1
    ):
        raise ValueError("expected one exact Pinned paired-color support, visibility, and final blend block")

    patched = source.replace(ORIGINAL_BLOCK, PATCHED_BLOCK, 1)
    patched = patched.replace(FINAL_BLEND_ANCHOR, ARTIFACT_STRENGTH_BLOCK, 1)
    if not has_paired_color_mask(patched):
        raise ValueError("Mpv-infinity selective contour resolver patch failed its postconditions")
    return patched, True


def unpatch_source(source: str) -> str:
    """Return the exact pre-mask source after validating any supported mask version."""
    if has_paired_color_mask(source):
        marker, strength_block = next(
            pair for pair in ARTIFACT_STRENGTH_VERSIONS if pair[0] in source
        )
        replacement = FINAL_BLEND_ANCHOR if marker in RESOLVER_ARTIFACT_STRENGTH_MARKERS else ""
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
            raise ValueError("Mpv-infinity local contour resolver marker/source mismatch")
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
        "Applied Mpv-infinity selective contour resolver"
        if changed
        else "Mpv-infinity selective contour resolver is already applied"
    )
