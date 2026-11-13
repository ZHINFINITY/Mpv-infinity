#!/usr/bin/env python3
"""Apply app-owned refinement, motion-grid, and artifact guards to pinned FongMi motion.

The patch is version-guarded and limited to refinement, the analysis grid, and
synthesis/reconstruction/inverse-warp logic in pinned upstream
``video/out/gpu_next/motion.c``. It keeps bilinear field sampling, resolves
aperture ambiguity along the measured gradient normal while preserving
inherited tangential motion, selects the best inverse-warp closure, and retains
whole-frame scene-cut handling.
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
WARP_TRAJECTORY_MARKER = "MPVFLOW_WARP_TRAJECTORY_COHERENCE_V1"
WARP_BEST_RESIDUAL_MARKER = "MPVFLOW_WARP_BEST_INVERSE_RESIDUAL_V1"
REJECTED_WARP_TRUST_MARKER = "MPVFLOW_WARP_TRAJECTORY_TRUST_V2"
SCENE_CUT_MARKER = "MPVFLOW_SCENE_CUT_SPATIAL_V1"
REJECTED_INSHOT_FALLBACK_MARKER = "MPVFLOW_INSHOT_NEAREST_SOURCE_FALLBACK_V1"
FINE_GRID_MARKER = "MPVFLOW_FINE_MOTION_GRID_V1"
FINE_GRID_ASSIGNMENT = "int spacing = (level == LEVELS - 1 || level == 0) ? 4 : 8;"
SPATIAL_TRUST_MARKER = "MPVFLOW_SPATIAL_TRUST_ISLAND_GUARD_V1"
REFINEMENT_APERTURE_NORMAL_MARKER = "MPVFLOW_REFINEMENT_APERTURE_NORMAL_V1"
REFINEMENT_APERTURE_TANGENT_MARKER = "MPVFLOW_REFINEMENT_APERTURE_TANGENT_V1"

RECONSTRUCTION_PATTERN = re.compile(
    r"(?m)^(?P<indent> *)" + re.escape(
        "if (!(evidence > best || (evidence == best && distance < nearest)))"
    )
)
RECONSTRUCTION_BLOCK = """// MPVFLOW_RECONSTRUCTION_POSITIVE_EVIDENCE_V1: only propagate measured motion.
if (!(evidence > 0.0 &&
      (evidence > best || (evidence == best && distance < nearest))))"""

WARP_START_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)vec2 uv = \(vec2\(gl_GlobalInvocationID\.xy\) \+ 0\.5\) / map_size;\n"
    r"(?P=indent)vec2 source = uv;$"
)
WARP_INITIAL_MOTION_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)vec2 initial_motion = textureLod\(forward, uv, 0\.0\)\.xy;$"
)
WARP_VECTOR_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)vec2 displacement = -weight \* f\.xy;$"
)
WARP_RELIABILITY_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)reliable \*= 1\.0 - smoothstep\(0\.1, 0\.5, cycle\);$"
)
WARP_ITERATION_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)for \(int i = 0; i < 4; i\+\+\)\n"
    r"(?P=indent)[ \t]+source = uv - weight \* textureLod\(forward, source, 0\.0\)\.xy / map_size;\n"
    r"(?P=indent)vec4 f = textureLod\(forward, source, 0\.0\);$"
)
WARP_RESIDUAL_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)float residual = length\(\(source \+ weight \* f\.xy - uv\) \* output_size\);$"
)
WARP_BEST_RESIDUAL_BLOCK = """// MPVFLOW_WARP_BEST_INVERSE_RESIDUAL_V1: keep the best closure among the initial and four solved positions.
vec2 best_source = source;
vec4 f = initial_field;
vec4 best_field = f;
float best_residual = 1e20;
float initial_residual = length((source + weight * f.xy / map_size - uv) * output_size);
if (!(any(isnan(f)) || any(isinf(f))) &&
    !isnan(initial_residual) && !isinf(initial_residual))
    best_residual = initial_residual;
for (int i = 0; i < 4; i++) {
    source = uv - weight * f.xy / map_size;
    f = textureLod(forward, source, 0.0);
    float candidate_residual = length((source + weight * f.xy / map_size - uv) * output_size);
    if (!(any(isnan(f)) || any(isinf(f))) &&
        !isnan(candidate_residual) && !isinf(candidate_residual) &&
        candidate_residual < best_residual) {
        best_source = source;
        best_field = f;
        best_residual = candidate_residual;
    }
}
source = best_source;
f = best_field;"""
WARP_TRAJECTORY_BLOCK = """// MPVFLOW_WARP_TRAJECTORY_COHERENCE_V1: gate uncertain recovery on path stability.
float trajectory_error = abs(weight) * length(motion_vector - initial_motion);
float trajectory_quality = 1.0 - smoothstep(0.125, 0.5, trajectory_error);
if (isnan(trajectory_quality) || isinf(trajectory_quality))
    trajectory_quality = 0.0;
reliable *= trajectory_quality;"""


SCENE_INIT_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)vec3 mean1 = vec3\(0\.0\);\n"
    r"(?P=indent)float residual = 0\.0;$"
)
SCENE_SAMPLE_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)mean0 \+= a;\n"
    r"(?P=indent)mean1 \+= b;$"
)
SCENE_CUT_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)float cut = max\(histogram_distance / 512\.0,\n"
    r"(?P=indent)[ \t]+max\(mean_distance\.r, max\(mean_distance\.g, mean_distance\.b\)\)\);$"
)
SCENE_TILE_INIT_BLOCK = """float tile_luma_delta[16];
for (int i = 0; i < 16; i++)
    tile_luma_delta[i] = 0.0;"""
SCENE_CUT_BLOCK = """// MPVFLOW_SCENE_CUT_SPATIAL_V1: detect spatially rearranged cuts with shared colors.
float global_luma_delta = dot((mean0 - mean1) / 256.0,
                              vec3(0.2126, 0.7152, 0.0722));
float spatial_cut = 0.0;
for (int i = 0; i < 16; i++) {
    float local_luma_delta = abs(tile_luma_delta[i] / 16.0 - global_luma_delta);
    if (local_luma_delta > 0.15)
        spatial_cut += 1.0 / 16.0;
}
cut = max(cut, spatial_cut);"""

FINE_GRID_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)int spacing = level == LEVELS - 1 \? 4 : 8;$"
)
FINE_GRID_BLOCK = """// MPVFLOW_FINE_MOTION_GRID_V1: halve finest-level vector-cell pitch
// without enlarging the analysis image.
int spacing = (level == LEVELS - 1 || level == 0) ? 4 : 8;"""

REFINEMENT_OUTER_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)if \(any\(notEqual\(vector, vec2\(0\)\)\) \|\| residual != 0\.0\) \{$"
)
REFINEMENT_TENSOR_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)float trace = xx \+ yy;\n"
    r"(?P=indent)if \(trace > 0\.000001\) \{$"
)
REFINEMENT_AXIS_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)vec2 axis = xx > yy \? vec2\(1, 0\) : vec2\(0, 1\);\n"
    r"(?P=indent)float projected = xx > yy \? vector\.x \+ xy / xx \* vector\.y\n"
    r"(?P=indent)[ \t]+: vector\.y \+ xy / yy \* vector\.x;$"
)
REFINEMENT_CANDIDATE_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)vec2 candidate = clamp\(axis \* \(round\(projected\) \+ float\(offset\)\),\n"
    r"(?P=indent)[ \t]+-p, size - 1\.0 - p\);$"
)
REFINEMENT_DELTA_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)float delta = abs\(dot\(candidate, axis\) - projected\);$"
)
REFINEMENT_PROJECTED_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)float projected = dot\(vector, aperture_normal\);$"
)
LEGACY_REFINEMENT_CANDIDATE_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)vec2 candidate = clamp\(aperture_normal \* \(round\(projected\) \+ float\(offset\)\),\n"
    r"(?P=indent)[ \t]+-p, size - 1\.0 - p\);$"
)
LEGACY_DENSE_CANDIDATE_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)vec2 candidate = clamp\(aperture_normal \* float\(offset\), -p, size - 1\.0 - p\);$"
)
DENSE_CANONICALIZATION_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)if \(all\(equal\(motion_scale, vec2\(1\)\)\) && any\(notEqual\(vector, vec2\(0\)\)\)\) \{$"
)
DENSE_AXIS_LOOP_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)for \(int index = 0; index < 2 \* \(2 \* settings\.z \+ 1\); index\+\+\) \{\n"
    r"(?P=indent)[ \t]+int offset = index % \(2 \* settings\.z \+ 1\) - settings\.z;\n"
    r"(?P=indent)[ \t]+vec2 axis = index < 2 \* settings\.z \+ 1 \? vec2\(1, 0\) : vec2\(0, 1\);\n"
    r"(?P=indent)[ \t]+vec2 candidate = clamp\(axis \* float\(offset\), -p, size - 1\.0 - p\);$"
)

PIXEL_HEADER_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)vec4 synthesize_pixel\(sampler2D source0, sampler2D source1, sampler2D warp0,"
)
LEGACY_OUTPUT_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)color = mix\(mix\(original0, original1, alpha\),\n"
    r"[ \t]*mix\(warped0, warped1, alpha\), clamp\(trust, 0\.0, 1\.0\)\);"
)
PINNED_OUTPUT_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)return mix\(mix\(original0, original1, blend\),\n"
    r"[ \t]*mix\(warped0, warped1, blend\), clamp\(trust, 0\.0, 1\.0\)\);"
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

NEIGHBORHOOD_END_PATTERN = re.compile(
    r"(?m)^[ \t]*return 1\.0 - smoothstep\(0\.65, 2\.8, spread\);\n"
    r"(?P<indent>[ \t]*)}$"
)

SPATIAL_TRUST_HELPER = """float spatial_trust_consensus(sampler2D warp0, sampler2D warp1,
                              vec2 uv, vec4 f, vec4 r)
{
    vec2 texel_step = 1.0 / vec2(textureSize(warp0, 0));
    vec4 f0 = textureLod(warp0, uv + vec2(-texel_step.x, 0.0), 0.0);
    vec4 r0 = textureLod(warp1, uv + vec2(-texel_step.x, 0.0), 0.0);
    vec4 f1 = textureLod(warp0, uv + vec2( texel_step.x, 0.0), 0.0);
    vec4 r1 = textureLod(warp1, uv + vec2( texel_step.x, 0.0), 0.0);
    vec4 f2 = textureLod(warp0, uv + vec2(0.0, -texel_step.y), 0.0);
    vec4 r2 = textureLod(warp1, uv + vec2(0.0, -texel_step.y), 0.0);
    vec4 f3 = textureLod(warp0, uv + vec2(0.0,  texel_step.y), 0.0);
    vec4 r3 = textureLod(warp1, uv + vec2(0.0,  texel_step.y), 0.0);
    float w0 = (any(isnan(f0)) || any(isinf(f0)) || any(isnan(r0)) || any(isinf(r0))) ? 0.0 :
        1.0 - smoothstep(0.65, 2.8,
        0.5 * (length(f0.xy - f.xy) + length(r0.xy - r.xy)));
    float w1 = (any(isnan(f1)) || any(isinf(f1)) || any(isnan(r1)) || any(isinf(r1))) ? 0.0 :
        1.0 - smoothstep(0.65, 2.8,
        0.5 * (length(f1.xy - f.xy) + length(r1.xy - r.xy)));
    float w2 = (any(isnan(f2)) || any(isinf(f2)) || any(isnan(r2)) || any(isinf(r2))) ? 0.0 :
        1.0 - smoothstep(0.65, 2.8,
        0.5 * (length(f2.xy - f.xy) + length(r2.xy - r.xy)));
    float w3 = (any(isnan(f3)) || any(isinf(f3)) || any(isnan(r3)) || any(isinf(r3))) ? 0.0 :
        1.0 - smoothstep(0.65, 2.8,
        0.5 * (length(f3.xy - f.xy) + length(r3.xy - r.xy)));
    float s0 = 0.0;
    float s1 = 0.0;
    float s2 = 0.0;
    float s3 = 0.0;
    if (!(any(isnan(f0)) || any(isinf(f0)) || any(isnan(r0)) || any(isinf(r0))))
        s0 = max(min(f0.z, r0.z), min(f0.w, r0.w));
    if (!(any(isnan(f1)) || any(isinf(f1)) || any(isnan(r1)) || any(isinf(r1))))
        s1 = max(min(f1.z, r1.z), min(f1.w, r1.w));
    if (!(any(isnan(f2)) || any(isinf(f2)) || any(isnan(r2)) || any(isinf(r2))))
        s2 = max(min(f2.z, r2.z), min(f2.w, r2.w));
    if (!(any(isnan(f3)) || any(isinf(f3)) || any(isnan(r3)) || any(isinf(r3))))
        s3 = max(min(f3.z, r3.z), min(f3.w, r3.w));
    float total = w0 + w1 + w2 + w3;
    if (total < 0.25)
        return clamp(max(min(f.z, r.z), min(f.w, r.w)), 0.0, 1.0);
    return clamp((s0 * w0 + s1 * w1 + s2 * w2 + s3 * w3) / total,
                 0.0, 1.0);
}"""

SPATIAL_TRUST_PATTERN = re.compile(
    r"(?m)^(?P<indent>[ \t]*)trust \*= boundary_trust \* region3_local_trust;$"
)

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

LEGACY_SOFTENED_OUTPUT_BLOCK_V4 = """// MPVFLOW_LOCAL_MOTION_SOFTENING_V1: two bounded directional taps soften only supported motion fallback.
vec4 source_blend = mix(original0, original1, alpha);
if (local_motion_softness > 0.001) {
    vec4 soft0 = sample_rgba(first, source_position + f.xy * scale + local_motion_offset);
    vec4 soft1 = sample_rgba(second, source_position + r.xy * scale - local_motion_offset);
    vec4 motion_blend = mix(mix(warped0, soft0, 0.5),
                            mix(warped1, soft1, 0.5), alpha);
    source_blend = mix(source_blend, motion_blend, local_motion_softness);
}
color = mix(source_blend, mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));"""
LEGACY_SOFTENED_OUTPUT_BLOCK = LEGACY_SOFTENED_OUTPUT_BLOCK_V4

PINNED_SOFTENED_OUTPUT_BLOCK_V4 = """// MPVFLOW_LOCAL_MOTION_SOFTENING_V1: two bounded directional taps soften only supported motion fallback.
vec4 source_blend = mix(original0, original1, blend);
if (local_motion_softness > 0.001) {
    vec4 soft0 = sample_rgba(source0, vec2(pixel) + f.xy * scale + local_motion_offset);
    vec4 soft1 = sample_rgba(source1, vec2(pixel) + r.xy * scale - local_motion_offset);
    vec4 motion_blend = mix(mix(warped0, soft0, 0.5),
                            mix(warped1, soft1, 0.5), blend);
    source_blend = mix(source_blend, motion_blend, local_motion_softness);
}
return mix(source_blend, mix(warped0, warped1, blend), clamp(trust, 0.0, 1.0));"""
PINNED_SOFTENED_OUTPUT_BLOCK = PINNED_SOFTENED_OUTPUT_BLOCK_V4

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


def _output_matches(source: str):
    return [
        (pattern, block, match)
        for pattern, block in (
            (LEGACY_OUTPUT_PATTERN, LEGACY_SOFTENED_OUTPUT_BLOCK),
            (PINNED_OUTPUT_PATTERN, PINNED_SOFTENED_OUTPUT_BLOCK),
        )
        for match in pattern.finditer(source)
    ]


def _replace_output(source: str, label: str) -> str:
    matches = _output_matches(source)
    if len(matches) != 1:
        raise ValueError(f"Pinned FongMi {label} anchor count is {len(matches)}; refusing an unverified patch")
    pattern, block, _ = matches[0]
    return pattern.sub(lambda match: _indent_block(block, match.group("indent")), source, count=1)


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
        and len(_output_matches(source)) == 1
        and LEGACY_MARKER not in source
        and LOCAL_SOFTENING_MARKER not in source
        and "float motion_length =" not in source
    )


def _is_synthesis_patched_core(source: str, require_spatial: bool = True) -> bool:
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
        and (not require_spatial or _has_spatial_trust_guard(source))
        and source.count("vec2 local_motion_delta = (r.xy - f.xy) * scale;") == 1
        and source.count("float local_motion_softness = 0.24 * (1.0 - clamp(trust, 0.0, 1.0)) *") == 1
        and (
            (
                source.count("sample_rgba(first, source_position + f.xy * scale + local_motion_offset)") == 1
                and source.count("sample_rgba(second, source_position + r.xy * scale - local_motion_offset)") == 1
                and source.count("color = mix(source_blend, mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));") == 1
            )
            or (
                source.count("sample_rgba(source0, vec2(pixel) + f.xy * scale + local_motion_offset)") == 1
                and source.count("sample_rgba(source1, vec2(pixel) + r.xy * scale - local_motion_offset)") == 1
                and source.count("return mix(source_blend, mix(warped0, warped1, blend), clamp(trust, 0.0, 1.0));") == 1
            )
        )
        and len(_output_matches(source)) == 0
        and REJECTED_WARP_TRUST_MARKER not in source
        and "trust *= trajectory_quality;" not in source
        and REJECTED_INSHOT_FALLBACK_MARKER not in source
        and "nearest_frame_weight" not in source
        and "vec4 nearest_frame =" not in source
        and LEGACY_MARKER not in source
        and PREVIOUS_MARKER not in source
    )


def _has_spatial_trust_guard(source: str) -> bool:
    return (
        source.count(SPATIAL_TRUST_MARKER) == 1
        and source.count("float spatial_trust_consensus(sampler2D warp0, sampler2D warp1,") == 1
        and source.count("spatial_trust_consensus(warp0, warp1, uv, f, r)") == 1
        and source.count("float spatial_island = max(0.0, clamp(trust, 0.0, 1.0) - spatial_consensus);") == 1
        and source.count("trust = mix(trust, min(trust, spatial_consensus),") == 1
        and source.count("0.75 * boundary_activation *") == 1
        and source.count("float s0 = 0.0;") == 1
        and source.count("float s1 = 0.0;") == 1
        and source.count("float s2 = 0.0;") == 1
        and source.count("float s3 = 0.0;") == 1
        and all(
            source.count(
                f"if (!(any(isnan(f{i})) || any(isinf(f{i})) || any(isnan(r{i})) || any(isinf(r{i}))))"
            ) == 1
            for i in range(4)
        )
        and re.search(
            r"(?m)^[ \t]*if \(boundary_activation > 0\.0\) \{\n"
            r"[ \t]+float spatial_consensus = spatial_trust_consensus\(warp0, warp1, uv, f, r\);",
            source,
        ) is not None
    )


def _patch_spatial_trust(source: str) -> str:
    if SPATIAL_TRUST_MARKER in source:
        if not _has_spatial_trust_guard(source):
            raise ValueError("FongMi spatial trust marker/source mismatch")
        return source
    if any(token in source for token in (
        "float spatial_trust_consensus(",
        "float spatial_island =",
        "spatial_trust_consensus(warp0, warp1, uv, f, r)",
    )):
        raise ValueError("FongMi spatial trust source contains a partial guard")
    source = _replace_one(
        NEIGHBORHOOD_END_PATTERN,
        source,
        lambda match: (
            match.group(0)
            + "\n\n"
            + _indent_block(SPATIAL_TRUST_HELPER, match.group("indent"))
        ),
        "spatial trust consensus helper",
    )
    source = _replace_one(
        SPATIAL_TRUST_PATTERN,
        source,
        lambda match: (
            match.group(0)
            + "\n"
            + _indent_block(
                "// " + SPATIAL_TRUST_MARKER + ": suppress isolated high-trust warp islands only across coherent motion.\n"
                "if (boundary_activation > 0.0) {\n"
                "    float spatial_consensus = spatial_trust_consensus(warp0, warp1, uv, f, r);\n"
                "    float spatial_island = max(0.0, clamp(trust, 0.0, 1.0) - spatial_consensus);\n"
                "    float spatial_guard_weight = 0.75 * boundary_activation *\n"
                "                                 smoothstep(0.08, 0.35, spatial_island);\n"
                "    trust = mix(trust, min(trust, spatial_consensus),\n"
                "                clamp(spatial_guard_weight, 0.0, 0.75));\n"
                "}",
                match.group("indent"),
            )
        ),
        "spatial trust transition cap",
    )
    if not _has_spatial_trust_guard(source):
        raise ValueError("FongMi spatial trust island guard failed its postconditions")
    return source


def _is_synthesis_patched(source: str) -> bool:
    return _is_synthesis_patched_core(source)

def _has_reconstruction_guard(source: str) -> bool:
    return (
        source.count(RECONSTRUCTION_MARKER) == 1
        and source.count("if (!(evidence > 0.0 &&") == 1
        and source.count("(evidence > best || (evidence == best && distance < nearest))))") == 1
        and RECONSTRUCTION_PATTERN.search(source) is None
    )


def _has_warp_trajectory_v1_guard(source: str) -> bool:
    return (
        source.count(WARP_TRAJECTORY_MARKER) == 1
        and (
            source.count("vec2 initial_motion = textureLod(forward, uv, 0.0).xy;") == 1
            or source.count("vec2 initial_motion = initial_field.xy;") == 1
        )
        and source.count("vec2 motion_vector = f.xy;") == 1
        and source.count("float trajectory_error = abs(weight) * length(motion_vector - initial_motion);") == 1
        and source.count("1.0 - smoothstep(0.125, 0.5, trajectory_error)") == 1
        and source.count("reliable *= trajectory_quality;") == 1
    )


def _has_warp_trajectory_guard(source: str) -> bool:
    return (
        _has_warp_trajectory_v1_guard(source)
        and REJECTED_WARP_TRUST_MARKER not in source
        and "trust *= trajectory_quality;" not in source
    )


def _has_warp_best_residual_guard(source: str) -> bool:
    return (
        source.count(WARP_BEST_RESIDUAL_MARKER) == 1
        and source.count("vec4 initial_field = textureLod(forward, uv, 0.0);") == 1
        and source.count("vec2 best_source = source;") == 1
        and source.count("vec4 best_field = f;") == 1
        and source.count("float best_residual = 1e20;") == 1
        and source.count("float candidate_residual = length((source + weight * f.xy / map_size - uv) * output_size);") == 1
        and source.count("candidate_residual < best_residual") == 1
        and source.count("source = best_source;") == 1
        and source.count("f = best_field;") == 1
        and source.count("float residual = best_residual;") == 1
        and WARP_ITERATION_PATTERN.search(source) is None
        and WARP_RESIDUAL_PATTERN.search(source) is None
    )


def _has_scene_cut_guard(source: str) -> bool:
    return (
        source.count(SCENE_CUT_MARKER) == 1
        and source.count("float tile_luma_delta[16];") == 1
        and source.count("int tile_index = (y / 4) * 4 + x / 4;") == 1
        and source.count(
            "tile_luma_delta[tile_index] += dot(a - b, vec3(0.2126, 0.7152, 0.0722));"
        ) == 1
        and source.count("float spatial_cut = 0.0;") == 1
        and source.count("float local_luma_delta = abs(tile_luma_delta[i] / 16.0 - global_luma_delta);") == 1
        and source.count("if (local_luma_delta > 0.15)") == 1
        and source.count("cut = max(cut, spatial_cut);") == 1
    )


def _has_fine_motion_grid(source: str) -> bool:
    return (
        source.count(FINE_GRID_MARKER) == 1
        and source.count(FINE_GRID_ASSIGNMENT) == 1
        and "int spacing = level == LEVELS - 1 ? 4 : 8;" not in source
    )


def _has_refinement_aperture_normal(source: str, require_tangent: bool = True) -> bool:
    common = (
        source.count(REFINEMENT_APERTURE_NORMAL_MARKER) == 1
        and source.count("bool aperture_ambiguous = false;") == 1
        and source.count("vec2 aperture_normal = vec2(0.0);") == 1
        and source.count("float tensor_discriminant = sqrt(max(") == 1
        and source.count("float lambda_min = max(0.5 * (trace - tensor_discriminant), 0.0);") == 1
        and source.count("float lambda_max = max(0.5 * (trace + tensor_discriminant), 0.0);") == 1
        and source.count("lambda_min < 0.12 * lambda_max;") == 1
        and source.count("aperture_normal = abs(xy) > 0.000001") == 1
        and source.count("float projected = dot(vector, aperture_normal);") == 1
        and source.count("float delta = abs(dot(candidate, aperture_normal) - projected);") == 1
        and source.count(
            "if (all(equal(motion_scale, vec2(1))) && any(notEqual(vector, vec2(0))) && aperture_ambiguous) {"
        ) == 1
        and source.count("for (int offset = -settings.z; offset <= settings.z; offset++) {") == 1
        and source.count("if (aperture_ambiguous) {") == 1
        and REFINEMENT_TENSOR_PATTERN.search(source) is None
        and REFINEMENT_AXIS_PATTERN.search(source) is None
        and REFINEMENT_CANDIDATE_PATTERN.search(source) is None
        and REFINEMENT_DELTA_PATTERN.search(source) is None
        and DENSE_CANONICALIZATION_PATTERN.search(source) is None
        and DENSE_AXIS_LOOP_PATTERN.search(source) is None
    )
    if not common:
        return False
    if require_tangent:
        return (
            source.count(REFINEMENT_APERTURE_TANGENT_MARKER) == 1
            and source.count("vec2 candidate = clamp(vector + aperture_normal *\n") == 2
            and source.count("(round(projected) + float(offset) - projected),") == 1
            and source.count("(float(offset) - dot(vector, aperture_normal)),") == 1
            and "aperture_normal * (round(projected) + float(offset))" not in source
            and "aperture_normal * float(offset), -p" not in source
        )
    return (
        REFINEMENT_APERTURE_TANGENT_MARKER not in source
        and source.count("vec2 candidate = clamp(aperture_normal * (round(projected) + float(offset)),") == 1
        and source.count("vec2 candidate = clamp(aperture_normal * float(offset), -p, size - 1.0 - p);") == 1
    )


def _is_patched(source: str) -> bool:
    return (
        _is_synthesis_patched(source)
        and _has_reconstruction_guard(source)
        and _has_warp_trajectory_guard(source)
        and _has_warp_best_residual_guard(source)
        and _has_scene_cut_guard(source)
        and _has_fine_motion_grid(source)
        and _has_refinement_aperture_normal(source)
    )


def _patch_refinement_aperture_normal(source: str) -> str:
    if REFINEMENT_APERTURE_NORMAL_MARKER in source:
        if _has_refinement_aperture_normal(source):
            return source
        if not _has_refinement_aperture_normal(source, require_tangent=False):
            raise ValueError("FongMi refinement aperture-normal marker/source mismatch")
        source = _replace_one(
            REFINEMENT_PROJECTED_PATTERN,
            source,
            lambda match: (
                match.group("indent") + "// " + REFINEMENT_APERTURE_TANGENT_MARKER
                + ": preserve inherited tangential flow; refine only its normal projection.\n"
                + match.group(0)
            ),
            "legacy aperture tangential-flow preservation marker",
        )
        source = _replace_one(
            LEGACY_REFINEMENT_CANDIDATE_PATTERN,
            source,
            lambda match: (
                match.group("indent") + "vec2 candidate = clamp(vector + aperture_normal *\n"
                + match.group("indent") + "                       (round(projected) + float(offset) - projected),\n"
                + match.group("indent") + "                       -p, size - 1.0 - p);"
            ),
            "legacy normal-only refinement candidate",
        )
        source = _replace_one(
            LEGACY_DENSE_CANDIDATE_PATTERN,
            source,
            lambda match: (
                match.group("indent") + "vec2 candidate = clamp(vector + aperture_normal *\n"
                + match.group("indent") + "                       (float(offset) - dot(vector, aperture_normal)),\n"
                + match.group("indent") + "                       -p, size - 1.0 - p);"
            ),
            "legacy normal-only dense candidate",
        )
        if not _has_refinement_aperture_normal(source):
            raise ValueError("FongMi tangent-preserving aperture upgrade failed its postconditions")
        return source
    if any(token in source for token in (
        "bool aperture_ambiguous = false;",
        "float tensor_discriminant = sqrt(max(",
        "aperture_normal = abs(xy) > 0.000001",
    )):
        raise ValueError("FongMi refinement source contains a partial aperture-normal guard")

    source = _replace_one(
        REFINEMENT_OUTER_PATTERN,
        source,
        lambda match: (
            match.group("indent") + "bool aperture_ambiguous = false;\n"
            + match.group("indent") + "vec2 aperture_normal = vec2(0.0);\n"
            + match.group(0)
        ),
        "refinement aperture state",
    )
    source = _replace_one(
        REFINEMENT_TENSOR_PATTERN,
        source,
        lambda match: (
            match.group("indent") + "float trace = xx + yy;\n"
            + match.group("indent") + "// " + REFINEMENT_APERTURE_NORMAL_MARKER
            + ": canonicalize only edge-ambiguous flow along the principal gradient normal.\n"
            + match.group("indent")
            + "float tensor_discriminant = sqrt(max((xx - yy) * (xx - yy) + 4.0 * xy * xy, 0.0));\n"
            + match.group("indent") + "float lambda_min = max(0.5 * (trace - tensor_discriminant), 0.0);\n"
            + match.group("indent") + "float lambda_max = max(0.5 * (trace + tensor_discriminant), 0.0);\n"
            + match.group("indent") + "aperture_ambiguous = trace > 0.000001 &&\n"
            + match.group("indent") + "                     lambda_max > 0.000001 &&\n"
            + match.group("indent") + "                     lambda_min < 0.12 * lambda_max;\n"
            + match.group("indent") + "if (aperture_ambiguous) {\n"
            + match.group("indent") + "    aperture_normal = abs(xy) > 0.000001\n"
            + match.group("indent") + "        ? normalize(vec2(lambda_max - yy, xy))\n"
            + match.group("indent") + "        : (xx >= yy ? vec2(1, 0) : vec2(0, 1));"
        ),
        "refinement tensor eigenvector",
    )
    source = _replace_one(
        REFINEMENT_AXIS_PATTERN,
        source,
        lambda match: (
            match.group("indent") + "// " + REFINEMENT_APERTURE_TANGENT_MARKER
            + ": preserve inherited tangential flow; refine only its normal projection.\n"
            + match.group("indent") + "float projected = dot(vector, aperture_normal);"
        ),
        "refinement aperture projection",
    )
    source = _replace_one(
        REFINEMENT_CANDIDATE_PATTERN,
        source,
        lambda match: (
            match.group("indent")
            + "vec2 candidate = clamp(vector + aperture_normal *\n"
            + match.group("indent") + "                       (round(projected) + float(offset) - projected),\n"
            + match.group("indent") + "                       -p, size - 1.0 - p);"
        ),
        "refinement normal-lattice candidate",
    )
    source = _replace_one(
        REFINEMENT_DELTA_PATTERN,
        source,
        lambda match: match.group("indent")
        + "float delta = abs(dot(candidate, aperture_normal) - projected);",
        "refinement normal-projection ranking",
    )
    source = _replace_one(
        DENSE_CANONICALIZATION_PATTERN,
        source,
        lambda match: (
            match.group("indent")
            + "if (all(equal(motion_scale, vec2(1))) && any(notEqual(vector, vec2(0))) && aperture_ambiguous) {"
        ),
        "dense aperture-normal guard",
    )
    source = _replace_one(
        DENSE_AXIS_LOOP_PATTERN,
        source,
        lambda match: (
            match.group("indent")
            + "for (int offset = -settings.z; offset <= settings.z; offset++) {\n"
            + match.group("indent")
            + "    vec2 candidate = clamp(vector + aperture_normal *\n"
            + match.group("indent")
            + "                           (float(offset) - dot(vector, aperture_normal)),\n"
            + match.group("indent")
            + "                           -p, size - 1.0 - p);"
        ),
        "dense normal-lattice candidate search",
    )
    if not _has_refinement_aperture_normal(source):
        raise ValueError("FongMi aperture-normal refinement patch failed its postconditions")
    return source


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


def _patch_warp_trajectory(source: str) -> str:
    if REJECTED_WARP_TRUST_MARKER in source or "trust *= trajectory_quality;" in source:
        raise ValueError("FongMi source contains the rejected primary trajectory-trust multiplier")
    if WARP_TRAJECTORY_MARKER in source:
        if not _has_warp_trajectory_v1_guard(source):
            raise ValueError("FongMi warp trajectory V1 marker/source mismatch")
        return source
    source = _replace_one(
        WARP_START_PATTERN,
        source,
        lambda match: (
            match.group("indent") + "vec2 uv = (vec2(gl_GlobalInvocationID.xy) + 0.5) / map_size;\n"
            + match.group("indent") + "vec4 initial_field = textureLod(forward, uv, 0.0);\n"
            + match.group("indent") + "vec2 initial_motion = initial_field.xy;\n"
            + match.group("indent") + "vec2 source = uv;"
        ),
        "inverse-warp start",
    )
    source = _replace_one(
        WARP_VECTOR_PATTERN,
        source,
        lambda match: match.group("indent") + "vec2 motion_vector = f.xy;\n" + match.group(0),
        "inverse-warp solved vector",
    )
    source = _replace_one(
        WARP_RELIABILITY_PATTERN,
        source,
        lambda match: match.group(0) + "\n" + _indent_block(WARP_TRAJECTORY_BLOCK, match.group("indent")),
        "inverse-warp trajectory gate",
    )
    if not _has_warp_trajectory_v1_guard(source):
        raise ValueError("FongMi inverse-path-only trajectory patch failed its postconditions")
    return source


def _patch_warp_best_residual(source: str) -> str:
    if WARP_BEST_RESIDUAL_MARKER in source:
        if not _has_warp_best_residual_guard(source):
            raise ValueError("FongMi best inverse-residual marker/source mismatch")
        return source
    if any(token in source for token in (
        "vec2 best_source = source;",
        "vec4 best_field = f;",
        "candidate_residual < best_residual",
    )):
        raise ValueError("FongMi inverse-warp source contains a partial best-residual guard")
    if "vec4 initial_field = textureLod(forward, uv, 0.0);" not in source:
        source = _replace_one(
            WARP_INITIAL_MOTION_PATTERN,
            source,
            lambda match: (
                match.group("indent") + "vec4 initial_field = textureLod(forward, uv, 0.0);\n"
                + match.group("indent") + "vec2 initial_motion = initial_field.xy;"
            ),
            "initial inverse-warp field reuse",
        )
    source = _replace_one(
        WARP_ITERATION_PATTERN,
        source,
        lambda match: _indent_block(WARP_BEST_RESIDUAL_BLOCK, match.group("indent")),
        "best inverse-warp iterate selector",
    )
    source = _replace_one(
        WARP_RESIDUAL_PATTERN,
        source,
        lambda match: match.group("indent") + "float residual = best_residual;",
        "selected inverse-warp closure residual",
    )
    if not _has_warp_best_residual_guard(source):
        raise ValueError("FongMi best inverse-residual warp patch failed its postconditions")
    return source

def _patch_scene_cut(source: str) -> str:
    if SCENE_CUT_MARKER in source:
        if not _has_scene_cut_guard(source):
            raise ValueError("FongMi spatial scene-cut marker/source mismatch")
        return source
    if any(token in source for token in (
        "float tile_luma_delta[16];",
        "tile_luma_delta[tile_index] +=",
        "float spatial_cut = 0.0;",
    )):
        raise ValueError("FongMi spatial scene-cut source contains a partial guard")
    source = _replace_one(
        SCENE_INIT_PATTERN,
        source,
        lambda match: (
            match.group(0).splitlines()[0] + "\n"
            + _indent_block(SCENE_TILE_INIT_BLOCK, match.group("indent")) + "\n"
            + match.group("indent") + "float residual = 0.0;"
        ),
        "scene detector tile accumulator",
    )
    source = _replace_one(
        SCENE_SAMPLE_PATTERN,
        source,
        lambda match: (
            match.group(0) + "\n"
            + match.group("indent") + "int tile_index = (y / 4) * 4 + x / 4;\n"
            + match.group("indent")
            + "tile_luma_delta[tile_index] += dot(a - b, vec3(0.2126, 0.7152, 0.0722));"
        ),
        "scene detector spatial samples",
    )
    source = _replace_one(
        SCENE_CUT_PATTERN,
        source,
        lambda match: (
            match.group(0) + "\n"
            + _indent_block(SCENE_CUT_BLOCK, match.group("indent"))
        ),
        "scene detector spatial cut score",
    )
    if not _has_scene_cut_guard(source):
        raise ValueError("FongMi spatial scene-cut patch failed its postconditions")
    return source


def _patch_fine_motion_grid(source: str) -> str:
    if FINE_GRID_MARKER in source:
        if not _has_fine_motion_grid(source):
            raise ValueError("FongMi fine motion-grid marker/source mismatch")
        return source
    if FINE_GRID_ASSIGNMENT in source:
        raise ValueError("FongMi fine motion-grid assignment is missing its marker")
    source = _replace_one(
        FINE_GRID_PATTERN,
        source,
        lambda match: _indent_block(FINE_GRID_BLOCK, match.group("indent")),
        "finest motion-vector grid spacing",
    )
    if not _has_fine_motion_grid(source):
        raise ValueError("FongMi fine motion-grid patch failed its postconditions")
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
    source = _replace_output(source, "V3 synthesis output")
    source = _patch_spatial_trust(source)
    if not _is_synthesis_patched(source):
        raise ValueError("FongMi motion artifact V3-to-V4 upgrade failed its postconditions")
    return source


def transform_source(source: str) -> tuple[str, bool]:
    """Apply version-guarded aperture, grid, evidence, inverse-path, and scene-cut safeguards."""
    original = source
    if MARKER in source:
        if _is_synthesis_patched(source):
            patched = source
        elif _is_synthesis_patched_core(source, require_spatial=False):
            patched = _patch_spatial_trust(source)
        else:
            raise ValueError("FongMi motion artifact V4 marker/source mismatch or rejected pixelwise output behavior")
        patched = _patch_reconstruction(patched)
        patched = _patch_warp_trajectory(patched)
        patched = _patch_warp_best_residual(patched)
        patched = _patch_scene_cut(patched)
        patched = _patch_fine_motion_grid(patched)
        patched = _patch_refinement_aperture_normal(patched)
        if not _is_patched(patched):
            raise ValueError("FongMi motion artifact postconditions failed")
        return patched, patched != original

    if PREVIOUS_MARKER in source:
        source = _patch_v3_synthesis(source)
        source = _patch_reconstruction(source)
        source = _patch_warp_trajectory(source)
        source = _patch_warp_best_residual(source)
        source = _patch_scene_cut(source)
        source = _patch_fine_motion_grid(source)
        source = _patch_refinement_aperture_normal(source)
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
    source = _replace_output(source, "synthesis output")
    source = _patch_spatial_trust(source)
    source = _patch_reconstruction(source)
    source = _patch_warp_trajectory(source)
    source = _patch_warp_best_residual(source)
    source = _patch_scene_cut(source)
    source = _patch_fine_motion_grid(source)
    source = _patch_refinement_aperture_normal(source)
    if not _is_patched(source):
        raise ValueError("FongMi motion artifact V4 patch failed its postconditions")
    return source, True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mpv_dir", type=Path, help="extracted, pinned FongMi MPV source tree")
    args = parser.parse_args()
    source_path = args.mpv_dir / SOURCE_RELATIVE
    if not source_path.is_file():
        raise SystemExit(f"Pinned FongMi motion source is missing: {source_path}")
    source = source_path.read_text(encoding="utf-8")
    try:
        patched, changed = transform_source(source)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    if changed:
        source_path.write_text(patched, encoding="utf-8")
        print("Applied MPVFlow FongMi aperture-normal refinement, fine-grid, synthesis, inverse-warp, and scene-cut guards")
    else:
        print("MPVFlow FongMi aperture-normal refinement, fine-grid, synthesis, inverse-warp, and scene-cut guards are already applied")


if __name__ == "__main__":
    main()
