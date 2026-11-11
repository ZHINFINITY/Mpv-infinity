#!/usr/bin/env python3
"""Apply app-owned artifact guards to the pinned FongMi motion shader.

The patch is version-guarded and limited to the synthesis and reconstruction
shader bodies in the pinned upstream ``video/out/gpu_next/motion.c``. It keeps
the existing synthesis guards and adds a bounded local motion-softening term to
the source-frame fallback; it does not change motion search or vector generation.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

SOURCE_RELATIVE = Path("video/out/gpu_next/motion.c")
MARKER = "MPVFLOW_ARTIFACT_GUARDS_V4"
PREVIOUS_MARKER = "MPVFLOW_ARTIFACT_GUARDS_V3"
LEGACY_MARKER = "MPVFLOW_ARTIFACT_TRUST_TAPER_V2"
LOCAL_SOFTENING_MARKER = "MPVFLOW_LOCAL_MOTION_SOFTENING_V1"
RECONSTRUCTION_MARKER = "MPVFLOW_RECONSTRUCTION_POSITIVE_EVIDENCE_V1"

RECONSTRUCTION_PATTERN = re.compile(
    r"(?m)^(?P<indent> *)" + re.escape(
        "if (!(evidence > best || (evidence == best && distance < nearest)))"
    )
)
RECONSTRUCTION_BLOCK = """// MPVFLOW_RECONSTRUCTION_POSITIVE_EVIDENCE_V1: only propagate measured motion.
if (!(evidence > 0.0 &&
      (evidence > best || (evidence == best && distance < nearest))))"""

PIXEL_HEADER_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)vec4 synthesize_pixel\(sampler2D source0, sampler2D source1, sampler2D warp0,"
)
OUTPUT_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)color = mix\(mix\(original0, original1, alpha\),\n"
    r"[ \t]*mix\(warped0, warped1, alpha\), clamp\(trust, 0\.0, 1\.0\)\);"
)

NEIGHBORHOOD_HELPER = """float neighborhood_quality(sampler2D field, vec2 uv, vec2 center)
{
    ivec2 size = textureSize(field, 0);
    ivec2 pixel = ivec2(uv * vec2(size));
    ivec2 last = size - ivec2(1);
    vec2 n0 = texelFetch(field, clamp(pixel + ivec2(-2, 0), ivec2(0), last), 0).xy;
    vec2 n1 = texelFetch(field, clamp(pixel + ivec2(0, -2), ivec2(0), last), 0).xy;
    vec2 n2 = texelFetch(field, clamp(pixel + ivec2(0, 2), ivec2(0), last), 0).xy;
    vec2 n3 = texelFetch(field, clamp(pixel + ivec2(2, 0), ivec2(0), last), 0).xy;
    float spread = 0.25 * (length(n0 - center) + length(n1 - center) +
                           length(n2 - center) + length(n3 - center));
    return 1.0 - smoothstep(0.65, 2.8, spread);
}"""

TRUST_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)vec3 conflict = abs\(metric0 - metric1\);\n"
    r"(?P=indent)float color_error = max\(conflict\.r, max\(conflict\.g, conflict\.b\)\);\n"
    r"(?P=indent)// An edge can constrain motion without a unique tangential match\.\n"
    r"(?P=indent)// Recover it only when both directions and full-size colors agree\.\n"
    r"(?P=indent)float reliable = min\(f\.w, r\.w\) \*\n"
    r"(?P=indent)[ \t]+\(1\.0 - smoothstep\(0\.002, 0\.02, color_error\)\);\n"
    r"(?P=indent)float trust = max\(min\(f\.z, r\.z\), reliable\);\n"
    r"(?P=indent)trust \*= 1\.0 - smoothstep\(0\.03, 0\.15,\n"
    r"[ \t]+color_error\);"
)

MOTION_SOFTNESS_CALC = """vec2 local_motion_delta = (r.xy - f.xy) * scale;
float local_motion_length = length(local_motion_delta);
vec2 local_motion_offset = local_motion_delta /
                           max(local_motion_length, 0.0001) *
                           min(0.75, 0.08 * local_motion_length);
float local_motion_softness = 0.24 * (1.0 - clamp(trust, 0.0, 1.0)) *
                              boundary_activation * min(f.w, r.w) *
                              smoothstep(0.45, 0.70, local_quality) *
                              smoothstep(0.75, 3.0, local_motion_length);
if (isnan(local_motion_softness) || isinf(local_motion_softness))
    local_motion_softness = 0.0;"""

TRUST_BLOCK = """vec3 difference = metric0 - metric1;
vec3 conflict = abs(difference);
float color_error = max(conflict.r, max(conflict.g, conflict.b));
float luma_error = abs(dot(metric0, vec3(0.299, 0.587, 0.114)) -
                       dot(metric1, vec3(0.299, 0.587, 0.114)));
float chroma_error = max(abs((metric0.r - metric0.g) - (metric1.r - metric1.g)),
                         abs((metric0.b - metric0.g) - (metric1.b - metric1.g)));
// Keep the old common-support certificate; boundary evidence only attenuates it.
float reliable = min(f.w, r.w) *
                 (1.0 - smoothstep(0.002, 0.02, color_error));
float trust = max(min(f.z, r.z), reliable);
float boundary_trust = 1.0 - smoothstep(0.04, 0.08, luma_error);
boundary_trust *= 1.0 - smoothstep(0.08, 0.12, color_error) *
                  smoothstep(0.03, 0.06, chroma_error);
boundary_trust *= 1.0 - (1.0 - smoothstep(0.04, 0.08, min(f.w, r.w))) *
                  smoothstep(0.06, 0.10, color_error);
float boundary_activation = max(smoothstep(0.04, 0.06, luma_error),
                                smoothstep(0.06, 0.08, color_error));
float region3_local_trust = 1.0;
float local_quality = 1.0;
// Sample local vectors only where photometric disagreement makes a seam likely.
if (boundary_activation > 0.0) {
    local_quality = min(neighborhood_quality(warp0, uv, f.xy),
                        neighborhood_quality(warp1, uv, r.xy));
    boundary_trust *= mix(1.0, smoothstep(0.45, 0.70, local_quality),
                          boundary_activation);
    region3_local_trust = mix(1.0, smoothstep(0.30, 0.75, local_quality),
                              boundary_activation);
}
// Fade toward source-frame crossfade on conflict instead of forming block seams.
// MPVFLOW_ARTIFACT_GUARDS_V4 keeps the color taper continuous through 0.30.
trust *= 1.0 - smoothstep(0.03, 0.30,
                           color_error);
trust *= boundary_trust * region3_local_trust;""" + "\n" + MOTION_SOFTNESS_CALC

SOFTENED_OUTPUT_BLOCK = """// MPVFLOW_LOCAL_MOTION_SOFTENING_V1: two bounded directional taps soften only supported motion fallback.
vec4 source_blend = mix(original0, original1, alpha);
if (local_motion_softness > 0.001) {
    vec4 soft0 = sample_rgba(first, source_position + f.xy * scale + local_motion_offset);
    vec4 soft1 = sample_rgba(second, source_position + r.xy * scale - local_motion_offset);
    vec4 motion_blend = mix(mix(warped0, soft0, 0.5),
                            mix(warped1, soft1, 0.5), alpha);
    source_blend = mix(source_blend, motion_blend, local_motion_softness);
}
color = mix(source_blend, mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));"""

LEGACY_TAPER_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)// MPVFLOW_ARTIFACT_TRUST_TAPER_V2: soften color-conflict fallback only when motion trust remains\n"
    r"(?P=indent)trust \*= 1\.0 - smoothstep\(0\.03, 0\.30,\n"
    r"[ \t]+color_error\);"
)

V3_REGION_QUALITY_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)float region3_local_trust = 1\.0;$"
)
V3_NEIGHBOR_QUALITY_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)float local_quality = min\(neighborhood_quality\((?P<forward>warp0|forward), uv, f\.xy\),\n"
    r"[ \t]*neighborhood_quality\((?P<backward>warp1|backward), uv, r\.xy\)\);$"
)
V3_TRUST_END_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)trust \*= boundary_trust \* region3_local_trust;$"
)


def _indent_block(block: str, indent: str) -> str:
    return "\n".join(indent + line if line else "" for line in block.splitlines())


def _replace_one(pattern: re.Pattern[str], source: str, replacement, label: str) -> str:
    matches = list(pattern.finditer(source))
    if len(matches) != 1:
        raise ValueError(f"Pinned FongMi {label} anchor count is {len(matches)}; refusing an unverified patch")
    return pattern.sub(replacement, source, count=1)


def _is_synthesis_patched_v3(source: str) -> bool:
    return (
        source.count(PREVIOUS_MARKER) == 1
        and source.count("float neighborhood_quality(sampler2D field, vec2 uv, vec2 center)") == 1
        and (
            source.count("float luma_error = abs(dot(metric0,") == 1
            or source.count("float luma_error = abs(dot(warped0.rgb,") == 1
        )
        and (
            source.count("float chroma_error = max(abs((metric0.r - metric0.g)") == 1
            or source.count("float chroma_error = max(abs((warped0.r - warped0.g)") == 1
        )
        and source.count("float boundary_trust = 1.0 - smoothstep(0.04, 0.08, luma_error);") == 1
        and source.count("trust *= 1.0 - smoothstep(0.03, 0.30,") == 1
        and source.count("trust *= boundary_trust * region3_local_trust;") == 1
        and source.count("float local_quality = min(neighborhood_quality(") == 1
        and len(list(OUTPUT_PATTERN.finditer(source))) == 1
        and LEGACY_MARKER not in source
        and LOCAL_SOFTENING_MARKER not in source
        and "float motion_length =" not in source
    )


def _is_synthesis_patched(source: str) -> bool:
    return (
        source.count(MARKER) == 1
        and source.count(LOCAL_SOFTENING_MARKER) == 1
        and source.count("float neighborhood_quality(sampler2D field, vec2 uv, vec2 center)") == 1
        and (
            source.count("float luma_error = abs(dot(metric0,") == 1
            or source.count("float luma_error = abs(dot(warped0.rgb,") == 1
        )
        and (
            source.count("float chroma_error = max(abs((metric0.r - metric0.g)") == 1
            or source.count("float chroma_error = max(abs((warped0.r - warped0.g)") == 1
        )
        and source.count("float boundary_trust = 1.0 - smoothstep(0.04, 0.08, luma_error);") == 1
        and source.count("trust *= 1.0 - smoothstep(0.03, 0.30,") == 1
        and source.count("trust *= boundary_trust * region3_local_trust;") == 1
        and source.count("vec2 local_motion_delta = (r.xy - f.xy) * scale;") == 1
        and source.count("float local_motion_softness = 0.24 * (1.0 - clamp(trust, 0.0, 1.0)) *") == 1
        and source.count("sample_rgba(first, source_position + f.xy * scale + local_motion_offset)") == 1
        and source.count("sample_rgba(second, source_position + r.xy * scale - local_motion_offset)") == 1
        and source.count("color = mix(source_blend, mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));") == 1
        and len(list(OUTPUT_PATTERN.finditer(source))) == 0
        and LEGACY_MARKER not in source
        and PREVIOUS_MARKER not in source
    )


def _has_reconstruction_guard(source: str) -> bool:
    return (
        source.count(RECONSTRUCTION_MARKER) == 1
        and source.count("if (!(evidence > 0.0 &&") == 1
        and source.count("(evidence > best || (evidence == best && distance < nearest))))") == 1
        and RECONSTRUCTION_PATTERN.search(source) is None
    )


def _is_patched(source: str) -> bool:
    return _is_synthesis_patched(source) and _has_reconstruction_guard(source)


def _patch_reconstruction(source: str) -> str:
    if RECONSTRUCTION_MARKER in source:
        if not _has_reconstruction_guard(source):
            raise ValueError("FongMi reconstruction evidence marker/source mismatch")
        return source
    source = _replace_one(
        RECONSTRUCTION_PATTERN,
        source,
        lambda match: _indent_block(RECONSTRUCTION_BLOCK, match.group("indent")),
        "reconstruction evidence selector",
    )
    if not _has_reconstruction_guard(source):
        raise ValueError("FongMi reconstruction evidence patch failed its postconditions")
    return source


def _patch_v3_synthesis(source: str) -> str:
    """Upgrade an already-patched V3 tree without reapplying the raw-source patch."""
    if not _is_synthesis_patched_v3(source):
        raise ValueError("FongMi motion artifact V3 marker/source mismatch")
    source = source.replace(PREVIOUS_MARKER, MARKER, 1)
    source = _replace_one(
        V3_REGION_QUALITY_PATTERN,
        source,
        lambda match: match.group("indent") + "float region3_local_trust = 1.0;\n" +
                      match.group("indent") + "float local_quality = 1.0;",
        "V3 local-quality scope",
    )
    source = _replace_one(
        V3_NEIGHBOR_QUALITY_PATTERN,
        source,
        lambda match: (
            match.group("indent") + f"local_quality = min(neighborhood_quality({match.group('forward')}, uv, f.xy),\n" +
            match.group("indent") + f"                    neighborhood_quality({match.group('backward')}, uv, r.xy));"
        ),
        "V3 neighborhood-quality scope",
    )
    source = _replace_one(
        V3_TRUST_END_PATTERN,
        source,
        lambda match: match.group(0) + "\n" + _indent_block(MOTION_SOFTNESS_CALC, match.group("indent")),
        "V3 local-softening insertion",
    )
    source = _replace_one(
        OUTPUT_PATTERN,
        source,
        lambda match: _indent_block(SOFTENED_OUTPUT_BLOCK, match.group("indent")),
        "V3 synthesis output",
    )
    if not _is_synthesis_patched(source):
        raise ValueError("FongMi motion artifact V3-to-V4 upgrade failed its postconditions")
    return source


def transform_source(source: str) -> tuple[str, bool]:
    """Apply the version-guarded synthesis guards and local softening."""
    original = source
    if MARKER in source:
        if not _is_synthesis_patched(source):
            raise ValueError("FongMi motion artifact V4 marker/source mismatch")
        patched = _patch_reconstruction(source)
        return patched, patched != original

    if PREVIOUS_MARKER in source:
        source = _patch_v3_synthesis(source)
        source = _patch_reconstruction(source)
        if not _is_patched(source):
            raise ValueError("FongMi motion artifact V3-to-V4 patch failed its postconditions")
        return source, source != original

    if LEGACY_MARKER in source:
        source = _replace_one(
            LEGACY_TAPER_PATTERN,
            source,
            lambda match: (
                match.group("indent")
                + "trust *= 1.0 - smoothstep(0.03, 0.15,\n"
                + match.group("indent")
                + "    color_error);"
            ),
            "V2 taper",
        )
    else:
        taper_pattern = re.compile(
            r"(?m)^(?P<indent>[ \t]*)trust \*= 1\.0 - smoothstep\(0\.03, 0\.15,\n"
            r"[ \t]+color_error\);"
        )
        if len(list(taper_pattern.finditer(source))) != 1:
            raise ValueError("Pinned FongMi synthesis taper anchor changed; refusing an unverified patch")

    source = _replace_one(
        PIXEL_HEADER_PATTERN,
        source,
        lambda match: (
            _indent_block(NEIGHBORHOOD_HELPER, match.group("indent"))
            + "\n\n"
            + match.group(0)
        ),
        "synthesis pixel header",
    )
    source = _replace_one(
        TRUST_PATTERN,
        source,
        lambda match: _indent_block(TRUST_BLOCK, match.group("indent")),
        "synthesis trust",
    )
    source = _replace_one(
        OUTPUT_PATTERN,
        source,
        lambda match: _indent_block(SOFTENED_OUTPUT_BLOCK, match.group("indent")),
        "synthesis output",
    )
    source = _patch_reconstruction(source)
    if not _is_patched(source):
        raise ValueError("FongMi motion artifact V4 patch failed its postconditions")
    return source, True


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
        print("Applied MPVFlow FongMi V4 artifact guards and bounded local motion softening")
    else:
        print("MPVFlow FongMi V4 artifact guards and local motion softening are already applied")


if __name__ == "__main__":
    main()
