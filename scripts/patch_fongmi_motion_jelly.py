#!/usr/bin/env python3
"""Apply the opt-in motion-aligned majority jelly test to the exact pinned FongMi shader."""
from __future__ import annotations

import hashlib
from pathlib import Path

from patch_fongmi_motion_downsample import MARKER as DOWNSAMPLE_MARKER

MOTION_ANTIALIAS_SHA256 = "db13147fd268ebe857e235a2ad066c0d34d9c35cab66f6fa93ed5767d61219a2"
MOTION_JELLY_SHA256 = "a21153eec66e0181ecb9631dc51fce95194b60c85621605580ae91f880a41de0"
MOTION_JELLY_PREVIEW_SHA256 = "4db2130a7079363d3c7c4a0d11fd1d1a6fb20ded1c1d8253f330f201c5de4667"
MARKER = "MPVFLOW_JELLY_MOTION_ALIGNED_MAJORITY_V2"
ORIGINAL_ZERO_FLOW = """            if (all(equal(vec4(f.xy, r.xy), vec4(0.0))))
                return original;"""
PATCHED_ZERO_FLOW = """            {
                // MPVFLOW_JELLY_MOTION_ALIGNED_MAJORITY_V2: opt-in local majority fill; default builds never apply this patch.
                vec2 jelly_scale = output_extent / motion_extent;
                vec2 jelly_displacement0 = f.xy * jelly_scale;
                vec2 jelly_displacement1 = r.xy * jelly_scale;
                vec4 jelly_center0 = sample_rgba(source0, vec2(pixel) + jelly_displacement0, source_extent);
                vec4 jelly_center1 = sample_rgba(source1, vec2(pixel) + jelly_displacement1, source_extent);
                vec3 jelly_center_metric0 = hdr != 0 ? synthesis_pq(jelly_center0.rgb) : jelly_center0.rgb;
                vec3 jelly_center_metric1 = hdr != 0 ? synthesis_pq(jelly_center1.rgb) : jelly_center1.rgb;
                vec3 jelly_center_delta = abs(jelly_center_metric0 - jelly_center_metric1);
                float jelly_change = max(jelly_center_delta.r,
                                         max(jelly_center_delta.g, jelly_center_delta.b));
                if (jelly_change > 0.12 && blend > 0.000001 && blend < 0.999999) {
                    vec2 jelly_step = vec2(4.0) / motion_extent;
                    vec4 jelly_sum0 = vec4(0.0), jelly_sum1 = vec4(0.0);
                    float jelly_weight0 = 0.0, jelly_weight1 = 0.0;
                    int jelly_count0 = 0, jelly_count1 = 0;
                    for (int neighbor = 0; neighbor < 8; neighbor++) {
                        vec2 direction = neighbor == 0 ? vec2(1.0, 0.0) :
                                         neighbor == 1 ? vec2(-1.0, 0.0) :
                                         neighbor == 2 ? vec2(0.0, 1.0) :
                                         neighbor == 3 ? vec2(0.0, -1.0) :
                                         neighbor == 4 ? vec2(0.70710678, 0.70710678) :
                                         neighbor == 5 ? vec2(-0.70710678, 0.70710678) :
                                         neighbor == 6 ? vec2(0.70710678, -0.70710678) :
                                                         vec2(-0.70710678, -0.70710678);
                        vec2 neighbor_uv = clamp(uv + direction * jelly_step, vec2(0.0), vec2(1.0));
                        vec4 neighbor_f = textureLod(warp0, neighbor_uv, 0.0);
                        vec4 neighbor_r = textureLod(warp1, neighbor_uv, 0.0);
                        if (!all(lessThanEqual(abs(neighbor_f), finite_limit)) ||
                            !all(lessThanEqual(abs(neighbor_r), finite_limit)) ||
                            min(neighbor_f.w, neighbor_r.w) <= 0.001)
                            continue;
                        vec2 neighbor_pixel = vec2(pixel) +
                            direction * (4.0 * jelly_scale);
                        vec4 jelly0 = sample_rgba(source0,
                            neighbor_pixel + neighbor_f.xy * jelly_scale, source_extent);
                        vec4 jelly1 = sample_rgba(source1,
                            neighbor_pixel + neighbor_r.xy * jelly_scale, source_extent);
                        if (any(isnan(jelly0.rgb)) || any(isinf(jelly0.rgb)) ||
                            any(isnan(jelly1.rgb)) || any(isinf(jelly1.rgb)))
                            continue;
                        vec3 jelly_metric0 = hdr != 0 ? synthesis_pq(jelly0.rgb) : jelly0.rgb;
                        vec3 jelly_metric1 = hdr != 0 ? synthesis_pq(jelly1.rgb) : jelly1.rgb;
                        vec3 jelly_error0 = abs(jelly_metric0 - jelly_center_metric0);
                        vec3 jelly_error1 = abs(jelly_metric1 - jelly_center_metric1);
                        float jelly_e0 = max(jelly_error0.r, max(jelly_error0.g, jelly_error0.b));
                        float jelly_e1 = max(jelly_error1.r, max(jelly_error1.g, jelly_error1.b));
                        float jelly_w0 = 1.0 - smoothstep(0.16, 0.32, jelly_e0);
                        float jelly_w1 = 1.0 - smoothstep(0.16, 0.32, jelly_e1);
                        if (jelly_w0 > 0.5) {
                            jelly_sum0 += jelly_w0 * jelly0;
                            jelly_weight0 += jelly_w0;
                            jelly_count0++;
                        }
                        if (jelly_w1 > 0.5) {
                            jelly_sum1 += jelly_w1 * jelly1;
                            jelly_weight1 += jelly_w1;
                            jelly_count1++;
                        }
                    }
                    bool jelly_support0 = jelly_count0 >= 5 && jelly_weight0 >= 3.0;
                    bool jelly_support1 = jelly_count1 >= 5 && jelly_weight1 >= 3.0;
                    if (jelly_support0 || jelly_support1) {
                        float jelly_phase_weight = (1.0 - blend) * float(jelly_support0) +
                                                   blend * float(jelly_support1);
                        vec4 jelly_region = vec4(0.0);
                        if (jelly_support0)
                            jelly_region += (1.0 - blend) * jelly_sum0 / jelly_weight0;
                        if (jelly_support1)
                            jelly_region += blend * jelly_sum1 / jelly_weight1;
                        jelly_region /= max(jelly_phase_weight, 0.000001);
                        float jelly_support = max(jelly_support0 ? min(1.0, jelly_weight0 / 5.0) : 0.0,
                                                  jelly_support1 ? min(1.0, jelly_weight1 / 5.0) : 0.0);
                        float jelly_phase = 4.0 * blend * (1.0 - blend);
                        float jelly_fill = clamp(0.65 * jelly_support * jelly_phase, 0.0, 0.65);
                        if (jelly_fill > 0.001)
                            return mix(original, jelly_region, jelly_fill);
                    }
                }
                if (all(equal(vec4(f.xy, r.xy), vec4(0.0)))) {
                    return original;
                }
            }"""

SYNTHESIS_RETURN = "return mix(original, jelly_region, jelly_fill);"
PHASE_RAMP = "float jelly_phase = 4.0 * blend * (1.0 - blend);"
PREVIEW_PHASE_RAMP = (
    "float jelly_phase_base = 4.0 * blend * (1.0 - blend);\n"
    "                        float jelly_phase = jelly_phase_base * jelly_phase_base;"
)
PREVIEW_MARKER = "MPVFLOW_JELLY_BLEND_TEST_V1"
PREVIEW_RETURN = (
    "// MPVFLOW_JELLY_BLEND_TEST_V1: retain the confirmed mask; use the phase-smoothed Jelly blend.\n"
    "                            return mix(original, jelly_region, jelly_fill);"
)
PATCHED_ZERO_FLOW_PREVIEW = (
    PATCHED_ZERO_FLOW.replace(PHASE_RAMP, PREVIEW_PHASE_RAMP, 1)
    .replace(SYNTHESIS_RETURN, PREVIEW_RETURN, 1)
)


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def has_jelly_region_patch(source: str) -> bool:
    return (
        source.count(MARKER) == 1
        and source.count(PATCHED_ZERO_FLOW) == 1
        and source.count(ORIGINAL_ZERO_FLOW) == 0
        and source.count(DOWNSAMPLE_MARKER) == 1
    )


def has_jelly_preview_patch(source: str) -> bool:
    return (
        source.count(MARKER) == 1
        and source.count(PREVIEW_MARKER) == 1
        and source.count(PATCHED_ZERO_FLOW_PREVIEW) == 1
        and source.count(PATCHED_ZERO_FLOW) == 0
        and source.count(ORIGINAL_ZERO_FLOW) == 0
        and source.count(DOWNSAMPLE_MARKER) == 1
    )


def transform_source(source: str, *, verify_upstream_hash: bool = True) -> tuple[str, bool]:
    """Patch only the exact AA-prepared source; reject drift and partial edits."""
    if MARKER in source:
        if has_jelly_region_patch(source):
            return source, False
        raise ValueError("MPVRx jelly-region marker/source mismatch")
    if verify_upstream_hash and _sha256(source.encode("utf-8")) != MOTION_ANTIALIAS_SHA256:
        raise ValueError("unexpected MPVRx motion source; jelly test requires the exact AA-prepared 95ec8e9 source")
    if source.count(DOWNSAMPLE_MARKER) != 1:
        raise ValueError("jelly test requires bounded MPVRx analysis-downsample antialiasing")
    if source.count(ORIGINAL_ZERO_FLOW) != 1:
        raise ValueError("expected one exact FongMi zero-motion synthesis early return")

    patched = source.replace(ORIGINAL_ZERO_FLOW, PATCHED_ZERO_FLOW, 1)
    if not has_jelly_region_patch(patched):
        raise ValueError("MPVRx jelly-region patch failed its postconditions")
    return patched, True


def transform_preview_source(source: str, *, verify_upstream_hash: bool = True) -> tuple[str, bool]:
    """Apply the unchanged Jelly mask and synthesize only its selected regions."""
    if PREVIEW_MARKER in source:
        if has_jelly_preview_patch(source):
            return source, False
        raise ValueError("MPVRx Jelly blend-test marker/source mismatch")

    jelly_source, _ = transform_source(source, verify_upstream_hash=verify_upstream_hash)
    if jelly_source.count(PATCHED_ZERO_FLOW) != 1:
        raise ValueError("Jelly blend test requires exactly one verified applied-mask shader")
    preview_source = jelly_source.replace(PATCHED_ZERO_FLOW, PATCHED_ZERO_FLOW_PREVIEW, 1)
    if not has_jelly_preview_patch(preview_source):
        raise ValueError("MPVRx Jelly blend-test patch failed its postconditions")
    return preview_source, True


def apply_patch(path: Path, *, enabled: bool = True) -> bool:
    """Apply only when explicitly enabled; regular builds retain the AA baseline."""
    source = path.read_text(encoding="utf-8")
    if not enabled:
        if MARKER in source:
            raise ValueError("jelly-region patch found without explicit test-workflow opt-in")
        return False
    patched, changed = transform_source(source)
    if changed:
        path.write_text(patched, encoding="utf-8")
    return changed


def apply_preview_patch(path: Path, *, enabled: bool = True) -> bool:
    """Apply only for the separately opted-in Jelly blend-test APK."""
    source = path.read_text(encoding="utf-8")
    if not enabled:
        if PREVIEW_MARKER in source:
            raise ValueError("Jelly blend-test patch found without explicit diagnostic-workflow opt-in")
        return False
    patched, changed = transform_preview_source(source)
    if changed:
        path.write_text(patched, encoding="utf-8")
    return changed


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("motion_source", type=Path)
    parser.add_argument("--disabled", action="store_true", help="verify the regular build has no jelly patch")
    parser.add_argument("--preview-only", action="store_true", help="compile the mask-gated Jelly blend test shader")
    args = parser.parse_args()
    if args.disabled and args.preview_only:
        parser.error("--disabled and --preview-only cannot be combined")
    changed = (
        apply_preview_patch(args.motion_source, enabled=True)
        if args.preview_only
        else apply_patch(args.motion_source, enabled=not args.disabled)
    )
    print(
        "Applied opt-in MPVRx Jelly blend test; synthesis is limited to the confirmed mask"
        if changed and args.preview_only
        else "Opt-in MPVRx Jelly blend test is already present; synthesis remains limited to the confirmed mask"
        if args.preview_only
        else
        "Applied opt-in MPVRx motion-aligned majority jelly-region test"
        if changed
        else "Opt-in MPVRx motion-aligned majority jelly-region test is already present"
        if not args.disabled
        else "Opt-in MPVRx motion-aligned majority jelly-region test disabled; source unchanged"
    )


if __name__ == "__main__":
    main()
