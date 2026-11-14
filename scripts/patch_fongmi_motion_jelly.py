#!/usr/bin/env python3
"""Apply the opt-in jelly-region fallback to the exact MPVRx-pinned FongMi shader."""
from __future__ import annotations

import hashlib
from pathlib import Path

from patch_fongmi_motion_downsample import MARKER as DOWNSAMPLE_MARKER

MOTION_ANTIALIAS_SHA256 = "db13147fd268ebe857e235a2ad066c0d34d9c35cab66f6fa93ed5767d61219a2"
MOTION_JELLY_SHA256 = "973cdf51bb664b6c83b67da8d8a55d5e7691bd135e6c21e733751cffa09d5c80"
MARKER = "MPVFLOW_JELLY_ZERO_MOTION_REGION_V1"
ORIGINAL_ZERO_FLOW = """            if (all(equal(vec4(f.xy, r.xy), vec4(0.0))))
                return original;"""
PATCHED_ZERO_FLOW = """            if (all(equal(vec4(f.xy, r.xy), vec4(0.0)))) {
                // MPVFLOW_JELLY_ZERO_MOTION_REGION_V1: opt-in source-specific fill for old-position fragments.
                vec3 jelly_center0 = hdr != 0 ? synthesis_pq(original0.rgb) : original0.rgb;
                vec3 jelly_center1 = hdr != 0 ? synthesis_pq(original1.rgb) : original1.rgb;
                float jelly_change = max(max(abs(jelly_center0.r - jelly_center1.r),
                                            abs(jelly_center0.g - jelly_center1.g)),
                                        abs(jelly_center0.b - jelly_center1.b));
                if (jelly_change > 0.12 && blend > 0.000001 && blend < 0.999999) {
                    vec2 jelly_step = vec2(4.0) / motion_extent;
                    vec4 jelly_sum0 = vec4(0.0), jelly_sum1 = vec4(0.0);
                    float jelly_weight0 = 0.0, jelly_weight1 = 0.0;
                    int jelly_count0 = 0, jelly_count1 = 0;
                    for (int neighbor = 0; neighbor < 4; neighbor++) {
                        vec2 direction = neighbor == 0 ? vec2(1.0, 0.0) :
                                         neighbor == 1 ? vec2(-1.0, 0.0) :
                                         neighbor == 2 ? vec2(0.0, 1.0) : vec2(0.0, -1.0);
                        vec2 neighbor_uv = clamp(uv + direction * jelly_step, vec2(0.0), vec2(1.0));
                        vec4 neighbor_f = textureLod(warp0, neighbor_uv, 0.0);
                        vec4 neighbor_r = textureLod(warp1, neighbor_uv, 0.0);
                        if (!all(lessThanEqual(abs(neighbor_f), finite_limit)) ||
                            !all(lessThanEqual(abs(neighbor_r), finite_limit)) ||
                            length(neighbor_f.xy) > 0.75 || length(neighbor_r.xy) > 0.75)
                            continue;
                        vec2 neighbor_pixel = clamp(vec2(pixel) +
                            direction * (4.0 * output_extent / motion_extent),
                            vec2(0.0), output_extent - vec2(1.0));
                        vec4 jelly0 = sample_rgba(source0, neighbor_pixel, source_extent);
                        vec4 jelly1 = sample_rgba(source1, neighbor_pixel, source_extent);
                        if (any(isnan(jelly0.rgb)) || any(isinf(jelly0.rgb)) ||
                            any(isnan(jelly1.rgb)) || any(isinf(jelly1.rgb)))
                            continue;
                        vec3 jelly_metric0 = hdr != 0 ? synthesis_pq(jelly0.rgb) : jelly0.rgb;
                        vec3 jelly_metric1 = hdr != 0 ? synthesis_pq(jelly1.rgb) : jelly1.rgb;
                        vec3 jelly_error0 = abs(jelly_metric0 - jelly_center0);
                        vec3 jelly_error1 = abs(jelly_metric1 - jelly_center1);
                        float jelly_e0 = max(jelly_error0.r, max(jelly_error0.g, jelly_error0.b));
                        float jelly_e1 = max(jelly_error1.r, max(jelly_error1.g, jelly_error1.b));
                        float jelly_w0 = 1.0 - smoothstep(0.40, 0.60, jelly_e0);
                        float jelly_w1 = 1.0 - smoothstep(0.40, 0.60, jelly_e1);
                        if (jelly_w0 > 0.05) {
                            jelly_sum0 += jelly_w0 * jelly0;
                            jelly_weight0 += jelly_w0;
                            jelly_count0++;
                        }
                        if (jelly_w1 > 0.05) {
                            jelly_sum1 += jelly_w1 * jelly1;
                            jelly_weight1 += jelly_w1;
                            jelly_count1++;
                        }
                    }
                    bool jelly_support0 = jelly_count0 >= 2 && jelly_weight0 >= 1.5;
                    bool jelly_support1 = jelly_count1 >= 2 && jelly_weight1 >= 1.5;
                    if (jelly_support0 || jelly_support1) {
                        float jelly_phase_weight = (1.0 - blend) * float(jelly_support0) +
                                                   blend * float(jelly_support1);
                        vec4 jelly_region = vec4(0.0);
                        if (jelly_support0)
                            jelly_region += (1.0 - blend) * jelly_sum0 / jelly_weight0;
                        if (jelly_support1)
                            jelly_region += blend * jelly_sum1 / jelly_weight1;
                        jelly_region /= max(jelly_phase_weight, 0.000001);
                        float jelly_support = max(jelly_support0 ? min(1.0, jelly_weight0 / 2.0) : 0.0,
                                                  jelly_support1 ? min(1.0, jelly_weight1 / 2.0) : 0.0);
                        float jelly_phase = 4.0 * blend * (1.0 - blend);
                        float jelly_fill = clamp(0.75 * jelly_support * jelly_phase, 0.0, 0.75);
                        if (jelly_fill > 0.001)
                            return mix(original, jelly_region, jelly_fill);
                    }
                }
                return original;
            }"""


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def has_jelly_region_patch(source: str) -> bool:
    return (
        source.count(MARKER) == 1
        and source.count(PATCHED_ZERO_FLOW) == 1
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


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("motion_source", type=Path)
    parser.add_argument("--disabled", action="store_true", help="verify the regular build has no jelly patch")
    args = parser.parse_args()
    changed = apply_patch(args.motion_source, enabled=not args.disabled)
    print(
        "Applied opt-in MPVRx jelly-region synthesis fallback"
        if changed
        else "MPVRx jelly-region synthesis fallback is already present"
        if not args.disabled
        else "MPVRx jelly-region synthesis fallback is disabled; source unchanged"
    )


if __name__ == "__main__":
    main()
