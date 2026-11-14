#!/usr/bin/env python3
"""Apply a bounded anti-alias filter to the pinned MPVRx FongMi analysis reduction."""
from __future__ import annotations

import hashlib
from pathlib import Path

UPSTREAM_MOTION_SHA256 = "ca7870c21f7a499d03ab1f7d73f1b9bcbccee534398d8a6f01957556e7d61932"
MARKER = "MPVFLOW_MPVRX_ANALYSIS_DOWNSAMPLE_AA_V1"
ORIGINAL_BLOCK = """            vec2 p = (vec2(gl_GlobalInvocationID.xy) + 0.5) * source_scale - 0.5;
            vec3 sampled = sample_color(source, p);
            color = vec4(linear_hdr != 0 ? hdr_proxy(sampled) : sampled, 1.0);"""
PATCHED_BLOCK = """            vec2 p = (vec2(gl_GlobalInvocationID.xy) + 0.5) * source_scale - 0.5;
            // MPVFLOW_MPVRX_ANALYSIS_DOWNSAMPLE_AA_V1: average a bounded footprint before motion matching.
            vec2 tap_count = clamp(ceil(source_scale / 2.0), vec2(1.0), vec2(4.0));
            vec3 sampled = vec3(0.0);
            for (int y = 0; y < 4; y++) {
                if (float(y) >= tap_count.y)
                    continue;
                for (int x = 0; x < 4; x++) {
                    if (float(x) >= tap_count.x)
                        continue;
                    vec2 subpixel = (vec2(x, y) + 0.5) / tap_count - 0.5;
                    sampled += sample_color(source, p + subpixel * source_scale);
                }
            }
            sampled /= tap_count.x * tap_count.y;
            color = vec4(linear_hdr != 0 ? hdr_proxy(sampled) : sampled, 1.0);"""


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def has_antialias_filter(source: str) -> bool:
    return (
        source.count(MARKER) == 1
        and source.count("vec2 tap_count = clamp(ceil(source_scale / 2.0), vec2(1.0), vec2(4.0));") == 1
        and source.count("sampled += sample_color(source, p + subpixel * source_scale);") == 1
        and source.count("sampled /= tap_count.x * tap_count.y;") == 1
        and source.count("vec3 sampled = sample_color(source, p);") == 0
    )


def transform_source(source: str, *, verify_upstream_hash: bool = True) -> tuple[str, bool]:
    """Transform exactly the MPVRx-pinned source; reject drift and partial patches."""
    if MARKER in source:
        if has_antialias_filter(source) and source.count(PATCHED_BLOCK) == 1:
            return source, False
        raise ValueError("FongMi analysis-downsample marker/source mismatch")

    if verify_upstream_hash and _sha256(source.encode("utf-8")) != UPSTREAM_MOTION_SHA256:
        raise ValueError("unexpected FongMi motion.c source; expected exact MPVRx-pinned source")
    if source.count(ORIGINAL_BLOCK) != 1:
        raise ValueError("expected one exact FongMi motion-analysis downsample block")

    patched = source.replace(ORIGINAL_BLOCK, PATCHED_BLOCK, 1)
    if not has_antialias_filter(patched) or patched.count(PATCHED_BLOCK) != 1:
        raise ValueError("FongMi analysis-downsample patch failed its postconditions")
    return patched, True


def apply_patch(path: Path, *, enabled: bool = True) -> bool:
    """Patch in place when enabled; otherwise require the untouched upstream source."""
    source = path.read_text(encoding="utf-8")
    if not enabled:
        if MARKER in source:
            raise ValueError("app-owned downsample patch found without explicit test-workflow opt-in")
        if _sha256(source.encode("utf-8")) != UPSTREAM_MOTION_SHA256:
            raise ValueError("unmodified MPVRx-pinned FongMi source required when antialiasing is disabled")
        return False
    patched, changed = transform_source(source)
    if changed:
        path.write_text(patched, encoding="utf-8")
    return changed


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("motion_source", type=Path)
    args = parser.parse_args()
    changed = apply_patch(args.motion_source)
    print(
        "Applied bounded MPVRx motion-analysis downsample antialiasing"
        if changed
        else "Bounded MPVRx motion-analysis downsample antialiasing is already applied"
    )


if __name__ == "__main__":
    main()
