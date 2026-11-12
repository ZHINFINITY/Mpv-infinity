#!/usr/bin/env python3
"""Tests for the app-owned safeguards applied to the pinned FongMi shader."""
from __future__ import annotations

import argparse
import importlib.util
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATCHER_PATH = ROOT / "scripts/patch_fongmi_motion_artifact.py"
SPEC = importlib.util.spec_from_file_location("patch_fongmi_motion_artifact", PATCHER_PATH)
assert SPEC and SPEC.loader
PATCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCHER)
MOTION_CAPTURE_PATCHER_PATH = ROOT / "scripts/patch_fongmi_motion_diagnostics.py"
MOTION_CAPTURE_SPEC = importlib.util.spec_from_file_location(
    "patch_fongmi_motion_diagnostics", MOTION_CAPTURE_PATCHER_PATH
)
assert MOTION_CAPTURE_SPEC and MOTION_CAPTURE_SPEC.loader
MOTION_CAPTURE_PATCHER = importlib.util.module_from_spec(MOTION_CAPTURE_SPEC)
MOTION_CAPTURE_SPEC.loader.exec_module(MOTION_CAPTURE_PATCHER)
VO_CAPTURE_PATCHER_PATH = ROOT / "scripts/patch_fongmi_vo_diagnostics.py"
VO_CAPTURE_SPEC = importlib.util.spec_from_file_location(
    "patch_fongmi_vo_diagnostics", VO_CAPTURE_PATCHER_PATH
)
assert VO_CAPTURE_SPEC and VO_CAPTURE_SPEC.loader
VO_CAPTURE_PATCHER = importlib.util.module_from_spec(VO_CAPTURE_SPEC)
VO_CAPTURE_SPEC.loader.exec_module(VO_CAPTURE_PATCHER)
parser = argparse.ArgumentParser()
parser.add_argument("--source", type=Path, help="active pinned FongMi motion.c built by the runtime workflow")
args = parser.parse_args()

BASE_PIXEL_HEADER = """    vec4 synthesize_pixel(sampler2D source0, sampler2D source1, sampler2D warp0,
                          sampler2D warp1, sampler2D scene_map, ivec2 pixel,
                          vec2 output_extent, vec2 motion_extent, float blend, int hdr)
    {"""
BASE_TRUST = """            vec3 conflict = abs(metric0 - metric1);
            float color_error = max(conflict.r, max(conflict.g, conflict.b));
            // An edge can constrain motion without a unique tangential match.
            // Recover it only when both directions and full-size colors agree.
            float reliable = min(f.w, r.w) *
                             (1.0 - smoothstep(0.002, 0.02, color_error));
            float trust = max(min(f.z, r.z), reliable);
            trust *= 1.0 - smoothstep(0.03, 0.15,
                                      color_error);
            if (isnan(trust) || isinf(trust))
                trust = 0.0;"""
BASE_OUTPUT = """color = mix(mix(original0, original1, alpha),
             mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));"""
PINNED_OUTPUT = """return mix(mix(original0, original1, blend),
           mix(warped0, warped1, blend), clamp(trust, 0.0, 1.0));"""
LEGACY_TRUST = BASE_TRUST.replace(
    "            trust *= 1.0 - smoothstep(0.03, 0.15,\n                                      color_error);",
    "            // MPVFLOW_ARTIFACT_TRUST_TAPER_V2: soften color-conflict fallback only when motion trust remains\n"
    "            trust *= 1.0 - smoothstep(0.03, 0.30,\n                                      color_error);",
)

BASE_RECONSTRUCTION = """    if (!(evidence > best || (evidence == best && distance < nearest)))
        continue;"""

BASE_WARP = """static const char warp_body[] = GLSL(
    vec2 uv = (vec2(gl_GlobalInvocationID.xy) + 0.5) / map_size;
    vec2 source = uv;
    for (int i = 0; i < 4; i++)
        source = uv - weight * textureLod(forward, source, 0.0).xy / map_size;
    vec4 f = textureLod(forward, source, 0.0);
    vec2 displacement = -weight * f.xy;
    f.xy /= map_size;
    vec4 reverse = textureLod(backward, source + f.xy, 0.0);
    reverse.xy /= map_size;
    float cycle = length((f.xy + reverse.xy) * map_size);
    float reliable = 1.0 - smoothstep(0.001, 0.01, max(f.z, reverse.z));
    reliable *= 1.0 - smoothstep(0.1, 0.5, cycle);
    float trust = quality(f);
    float geometry = 1.0;
    color = vec4(displacement, trust * geometry, reliable * geometry);
);"""

BASE_GRID = """static void fixture_analyze(int level) {
        int spacing = level == LEVELS - 1 ? 4 : 8;
}"""

BASE_SCENE = """static const char scene_body[] = GLSL(
    float hist0[8];
    float hist1[8];
    for (int i = 0; i < 8; i++) {
        hist0[i] = 0.0;
        hist1[i] = 0.0;
    }
    vec3 mean0 = vec3(0.0);
    vec3 mean1 = vec3(0.0);
    float residual = 0.0;
    for (int y = 0; y < 16; y++) {
        for (int x = 0; x < 16; x++) {
            vec2 uv = (vec2(x, y) + 0.5) / 16.0;
            vec3 a = textureLod(first, uv, 0.0).rgb;
            vec3 b = textureLod(second, uv, 0.0).rgb;
            int ia = clamp(int(dot(a, vec3(0.2126, 0.7152, 0.0722)) * 8.0), 0, 7);
            int ib = clamp(int(dot(b, vec3(0.2126, 0.7152, 0.0722)) * 8.0), 0, 7);
            hist0[ia] += 1.0;
            hist1[ib] += 1.0;
            mean0 += a;
            mean1 += b;
            residual += 0.5 * (textureLod(forward, uv, 0.0).z +
                               textureLod(backward, uv, 0.0).z);
        }
    }
    float histogram_distance = 0.0;
    for (int i = 0; i < 8; i++)
        histogram_distance += abs(hist0[i] - hist1[i]);
    vec3 mean_distance = abs(mean0 - mean1) / 256.0;
    float cut = max(histogram_distance / 512.0,
                    max(mean_distance.r, max(mean_distance.g, mean_distance.b)));
    float brightness_error = dot(mean_distance, vec3(0.25)) +
        0.25 * abs(dot((mean0 - mean1) / 256.0, vec3(0.2126, 0.7152, 0.0722)));
    color = vec4(cut, max(0.0, residual / 256.0 - brightness_error), 0.0, 1.0);
);"""


def fixture(trust: str = BASE_TRUST, output: str = BASE_OUTPUT, output_indent: str = "    ") -> str:
    return (
        BASE_GRID
        + "\n"
        + BASE_WARP
        + "\nFongMi reconstruction prefix\n"
        "static const char reconstruction_body[] = GLSL(\n"
        + BASE_RECONSTRUCTION
        + "\n);\n"
        + BASE_SCENE
        + "\n"
        "FongMi synthesis prefix\n"
        "static const char synthesis_pixel_header[] = GLSL(\n"
        + BASE_PIXEL_HEADER
        + "\n"
        + trust
        + "\n"
        + PATCHER._indent_block(output, output_indent)
        + "\n);\n"
        "FongMi synthesis suffix\n"
    )


def vector_grid_shape(width: int, height: int, spacing: int) -> tuple[int, int]:
    return ((width + spacing - 1) // spacing, (height + spacing - 1) // spacing)


def bilinear_mixed_flow_band_pixels(
    analysis_width: int, source_width: int, spacing: int
) -> float:
    cells = (analysis_width + spacing - 1) // spacing
    centers = [(i + 0.5) * analysis_width / cells for i in range(cells)]
    boundary = analysis_width / 2.0
    left = max(center for center in centers if center < boundary)
    right = min(center for center in centers if center >= boundary)
    return (right - left) * source_width / analysis_width


def smoothstep(edge0: float, edge1: float, value: float) -> float:
    t = min(1.0, max(0.0, (value - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


def taper_weight(color_error: float) -> float:
    return 1.0 - smoothstep(0.03, 0.30, color_error)


def boundary_weight(
    luma_error: float,
    color_error: float,
    chroma_error: float,
    forward_quality: float = 1.0,
    backward_quality: float = 1.0,
    neighborhood_spread: float = 0.0,
) -> float:
    boundary = 1.0 - smoothstep(0.04, 0.08, luma_error)
    boundary *= 1.0 - smoothstep(0.08, 0.12, color_error) * smoothstep(0.03, 0.06, chroma_error)
    boundary *= 1.0 - (1.0 - smoothstep(0.04, 0.08, min(forward_quality, backward_quality))) * smoothstep(0.06, 0.10, color_error)
    activation = max(smoothstep(0.04, 0.06, luma_error), smoothstep(0.06, 0.08, color_error))
    if activation > 0.0:
        local_quality = 1.0 - smoothstep(0.65, 2.8, neighborhood_spread)
        boundary *= (1.0 - activation) + activation * smoothstep(0.45, 0.70, local_quality)
        boundary *= (1.0 - activation) + activation * smoothstep(0.30, 0.75, local_quality)
    return boundary


def local_motion_softness(
    trust: float,
    boundary_activation: float,
    bidirectional_support: float,
    local_quality: float,
    motion_length: float,
) -> float:
    return (
        0.24
        * (1.0 - min(1.0, max(0.0, trust)))
        * min(1.0, max(0.0, boundary_activation))
        * min(1.0, max(0.0, bidirectional_support))
        * smoothstep(0.45, 0.70, local_quality)
        * smoothstep(0.75, 3.0, motion_length)
    )




def trajectory_weight(
    initial: tuple[float, float], solved: tuple[float, float], blend: float
) -> float:
    error = abs(blend) * math.dist(initial, solved)
    return 1.0 - smoothstep(0.125, 0.5, error)


def global_scene_cut_score(first: list[list[float]], second: list[list[float]]) -> float:
    hist0 = [0] * 8
    hist1 = [0] * 8
    sum0 = 0.0
    sum1 = 0.0
    for y in range(16):
        for x in range(16):
            a = first[y][x]
            b = second[y][x]
            hist0[min(7, int(a * 8.0))] += 1
            hist1[min(7, int(b * 8.0))] += 1
            sum0 += a
            sum1 += b
    histogram_distance = sum(abs(a - b) for a, b in zip(hist0, hist1)) / 512.0
    mean_distance = abs(sum0 - sum1) / 256.0
    return max(histogram_distance, mean_distance)


def spatial_scene_cut_score(first: list[list[float]], second: list[list[float]]) -> float:
    global_luma_delta = sum(
        first[y][x] - second[y][x] for y in range(16) for x in range(16)
    ) / 256.0
    changed_tiles = 0
    for tile_y in range(4):
        for tile_x in range(4):
            tile_delta = sum(
                first[y][x] - second[y][x]
                for y in range(tile_y * 4, tile_y * 4 + 4)
                for x in range(tile_x * 4, tile_x * 4 + 4)
            ) / 16.0
            if abs(tile_delta - global_luma_delta) > 0.15:
                changed_tiles += 1
    return changed_tiles / 16.0


original = fixture()
patched, changed = PATCHER.transform_source(original)
assert changed
assert PATCHER.MARKER in patched
assert PATCHER.SPATIAL_TRUST_MARKER in patched
assert PATCHER.LOCAL_SOFTENING_MARKER in patched
assert PATCHER.RECONSTRUCTION_MARKER in patched
assert PATCHER.WARP_TRAJECTORY_MARKER in patched
assert PATCHER.FINE_GRID_MARKER in patched
assert PATCHER.FINE_GRID_ASSIGNMENT in patched
assert PATCHER.REJECTED_WARP_TRUST_MARKER not in patched
assert PATCHER.SCENE_CUT_MARKER in patched
assert PATCHER.REJECTED_INSHOT_FALLBACK_MARKER not in patched
assert PATCHER._has_scene_cut_guard(patched)
assert "int tile_index = (y / 4) * 4 + x / 4;" in patched
assert "tile_luma_delta[tile_index] += dot(a - b, vec3(0.2126, 0.7152, 0.0722));" in patched
assert "cut = max(cut, spatial_cut);" in patched
assert "vec2 initial_motion = textureLod(forward, uv, 0.0).xy;" in patched
assert "vec2 motion_vector = f.xy;" in patched
assert "float trajectory_error = abs(weight) * length(motion_vector - initial_motion);" in patched
assert "reliable *= trajectory_quality;" in patched
assert "trust *= trajectory_quality;" not in patched, "path coherence only gates uncertain recovery"
assert "vec2 displacement = -weight * f.xy;" in patched
assert "color = vec4(displacement, trust * geometry, reliable * geometry);" in patched
assert "source = uv - weight * textureLod(forward, source, 0.0).xy / map_size;" in patched
assert "if (!(evidence > 0.0 &&" in patched
assert "if (!(evidence > best || (evidence == best && distance < nearest)))" not in patched
assert "neighborhood_quality(sampler2D field, vec2 uv, vec2 center)" in patched
assert "spatial_trust_consensus(sampler2D warp0, sampler2D warp1," in patched
assert "1.0 / vec2(textureSize(warp0, 0))" in patched
assert "0.75 * boundary_activation *" in patched
assert "float luma_error = abs(dot(metric0," in patched
assert "float chroma_error = max(abs((metric0.r - metric0.g)" in patched
assert "smoothstep(0.03, 0.30," in patched
assert "smoothstep(0.03, 0.15," not in patched
assert "neighborhood_quality(warp0, uv, f.xy)" in patched
assert "neighborhood_quality(warp1, uv, r.xy)" in patched
assert "boundary_trust *= mix(1.0, smoothstep(0.45, 0.70, local_quality)" in patched
assert "region3_local_trust = mix(1.0, smoothstep(0.30, 0.75, local_quality)" in patched
assert "trust *= boundary_trust * region3_local_trust;" in patched
assert "vec2 local_motion_delta = (r.xy - f.xy) * scale;" in patched
assert "min(0.75, 0.08 * local_motion_length)" in patched
assert "float local_motion_softness = 0.24 *" in patched
assert "smoothstep(0.45, 0.70, local_quality)" in patched
assert "smoothstep(0.75, 3.0, local_motion_length)" in patched
assert "sample_rgba(first, source_position + f.xy * scale + local_motion_offset)" in patched
assert "sample_rgba(second, source_position + r.xy * scale - local_motion_offset)" in patched
assert "vec4 source_blend = mix(original0, original1, alpha);" in patched
assert "nearest_frame_weight" not in patched, "output must not switch temporal source independently per pixel"
assert "vec4 nearest_frame =" not in patched
assert "source_blend = mix(source_blend, nearest_frame," not in patched
assert "color = mix(source_blend, mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));" in patched
assert "FongMi synthesis prefix\n" in patched and "FongMi synthesis suffix\n" in patched
coarse_grid = vector_grid_shape(480, 221, 8)
fine_grid = vector_grid_shape(480, 221, 4)
coarse_mix_band = bilinear_mixed_flow_band_pixels(480, 1920, 8)
fine_mix_band = bilinear_mixed_flow_band_pixels(480, 1920, 4)
assert coarse_grid == (60, 28)
assert fine_grid == (120, 56)
assert coarse_mix_band == 32.0 and fine_mix_band == 16.0
assert fine_mix_band * 2 == coarse_mix_band
broken_grid = patched.replace(PATCHER.FINE_GRID_ASSIGNMENT, "int spacing = 8;", 1)
try:
    PATCHER.transform_source(broken_grid)
except ValueError:
    pass
else:
    raise AssertionError("fine-grid marker must reject a mismatched assignment")
again, changed_again = PATCHER.transform_source(patched)
assert not changed_again and again == patched

pinned_patched, pinned_changed = PATCHER.transform_source(
    fixture(output=PINNED_OUTPUT, output_indent="            ")
)
assert pinned_changed and PATCHER._is_patched(pinned_patched)
assert PATCHER.FINE_GRID_MARKER in pinned_patched
assert "sample_rgba(source0, vec2(pixel) + f.xy * scale + local_motion_offset)" in pinned_patched
assert "sample_rgba(source1, vec2(pixel) + r.xy * scale - local_motion_offset)" in pinned_patched
assert "return mix(source_blend, mix(warped0, warped1, blend), clamp(trust, 0.0, 1.0));" in pinned_patched
assert "nearest_frame_weight" not in pinned_patched

# An existing V3 synthesis patch is upgraded in place, then remains idempotent.
old_reconstruction = PATCHER._indent_block(PATCHER.RECONSTRUCTION_BLOCK, "    ")
legacy_v3 = patched.replace(
    old_reconstruction,
    "    if (!(evidence > best || (evidence == best && distance < nearest)))",
    1,
)
legacy_v3 = legacy_v3.replace(PATCHER.MARKER, PATCHER.PREVIOUS_MARKER, 1)
legacy_v3 = legacy_v3.replace(
    PATCHER._indent_block(PATCHER.LEGACY_SOFTENED_OUTPUT_BLOCK, "    "),
    PATCHER._indent_block(BASE_OUTPUT, "    "),
    1,
)
legacy_v3 = legacy_v3.replace(
    PATCHER._indent_block(PATCHER.MOTION_SOFTNESS_CALC, "            ") + "\n",
    "",
    1,
)
legacy_v3 = legacy_v3.replace("            float local_quality = 1.0;\n", "", 1)
legacy_v3 = legacy_v3.replace("            local_quality = min(", "            float local_quality = min(", 1)
assert PATCHER._is_synthesis_patched_v3(legacy_v3)
upgraded_v3, upgraded_v3_changed = PATCHER.transform_source(legacy_v3)
assert upgraded_v3_changed and PATCHER._is_patched(upgraded_v3)
upgraded_v3_again, upgraded_v3_changed_again = PATCHER.transform_source(upgraded_v3)
assert not upgraded_v3_changed_again and upgraded_v3_again == upgraded_v3

# Existing V3 output in the pinned return-expression form upgrades as well.
pinned_v3 = pinned_patched.replace(
    PATCHER._indent_block(PATCHER.PINNED_SOFTENED_OUTPUT_BLOCK, "            "),
    PATCHER._indent_block(PINNED_OUTPUT, "            "),
    1,
)
pinned_v3 = pinned_v3.replace(PATCHER.MARKER, PATCHER.PREVIOUS_MARKER, 1)
pinned_v3 = pinned_v3.replace(
    PATCHER._indent_block(PATCHER.MOTION_SOFTNESS_CALC, "            ") + "\n",
    "",
    1,
)
pinned_v3 = pinned_v3.replace("            float local_quality = 1.0;\n", "", 1)
pinned_v3 = pinned_v3.replace("            local_quality = min(", "            float local_quality = min(", 1)
assert PATCHER._is_synthesis_patched_v3(pinned_v3)
pinned_upgraded, pinned_upgraded_changed = PATCHER.transform_source(pinned_v3)
assert pinned_upgraded_changed and PATCHER._is_patched(pinned_upgraded)
assert "return mix(source_blend, mix(warped0, warped1, blend), clamp(trust, 0.0, 1.0));" in pinned_upgraded

# An existing V2-only build tree is upgraded, not mistaken for stock source.
upgraded_v2, upgraded_v2_changed = PATCHER.transform_source(fixture(LEGACY_TRUST))
assert upgraded_v2_changed and PATCHER.MARKER in upgraded_v2
assert PATCHER.LEGACY_MARKER not in upgraded_v2
assert PATCHER.LOCAL_SOFTENING_MARKER in upgraded_v2

regressive_primary_trust = patched.replace(
    "reliable *= trajectory_quality;",
    "reliable *= trajectory_quality;\n    // MPVFLOW_WARP_TRAJECTORY_TRUST_V2: forbidden\n    trust *= trajectory_quality;",
    1,
)
regressive_pixel_fallback = patched.replace(
    "color = mix(source_blend, mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));",
    "// MPVFLOW_INSHOT_NEAREST_SOURCE_FALLBACK_V1: forbidden\nfloat nearest_frame_weight = 1.0;\ncolor = mix(source_blend, mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));",
    1,
)
assert regressive_primary_trust != patched
assert regressive_pixel_fallback != patched

for invalid in (
    "unrecognized FongMi source",
    fixture() + fixture(),
    fixture(LEGACY_TRUST.replace("color-conflict fallback only when motion trust remains", "unexpected")),
    patched.replace("float chroma_error =", "float chroma_error_missing ="),
    patched.replace(PATCHER.LOCAL_SOFTENING_MARKER, "unexpected-local-softening-marker"),
    patched.replace("reliable *= trajectory_quality;", "reliable *= 0.0;"),
    regressive_primary_trust,
    regressive_pixel_fallback,
    patched.replace("cut = max(cut, spatial_cut);", "cut = max(cut, cut);"),
):
    try:
        PATCHER.transform_source(invalid)
    except ValueError:
        pass
    else:
        raise AssertionError("the patcher must reject unknown, ambiguous, or inconsistent source")

# Shader-policy proxies only: they verify monotonic safeguards, not device-side visual results.
errors = [i / 255.0 for i in range(256)]
old = [1.0 - smoothstep(0.03, 0.22, value) for value in errors]
new = [taper_weight(value) for value in errors]
assert all(a >= b for a, b in zip(old, old[1:]))
assert all(a >= b for a, b in zip(new, new[1:]))
old_step = max(abs(a - b) for a, b in zip(old, old[1:]))
new_step = max(abs(a - b) for a, b in zip(new, new[1:]))
assert new_step < old_step, (old_step, new_step)
assert math.isclose(taper_weight(0.03), 1.0, abs_tol=1e-9)
assert taper_weight(0.30) == 0.0
assert taper_weight(0.15) > 1.0 - smoothstep(0.03, 0.22, 0.15)

assert math.isclose(boundary_weight(0.0, 0.0, 0.0), 1.0, abs_tol=1e-9)
assert boundary_weight(0.09, 0.09, 0.0) == 0.0, "strong luma boundary must fall back to source crossfade"
assert boundary_weight(0.0, 0.15, 0.09) == 0.0, "strong chroma conflict must fall back to source crossfade"
coherent = boundary_weight(0.05, 0.07, 0.0, neighborhood_spread=0.0)
divergent = boundary_weight(0.05, 0.07, 0.0, neighborhood_spread=2.8)
assert 0.0 <= divergent < coherent <= 1.0, (coherent, divergent)
assert math.isclose(taper_weight(0.15), 0.5829903978052126, abs_tol=1e-9)

assert local_motion_softness(0.0, 1.0, 1.0, 1.0, 20.0) == 0.24
assert local_motion_softness(0.5, 1.0, 1.0, 1.0, 20.0) == 0.12
assert local_motion_softness(1.0, 1.0, 1.0, 1.0, 20.0) == 0.0
assert local_motion_softness(0.0, 1.0, 1.0, 1.0, 0.0) == 0.0, "static content must not soften"
assert local_motion_softness(0.0, 0.0, 1.0, 1.0, 20.0) == 0.0, "clean regions must not soften"
assert local_motion_softness(0.0, 1.0, 0.0, 1.0, 20.0) == 0.0, "unsupported vectors must retain source fallback"
assert local_motion_softness(0.0, 1.0, 1.0, 0.0, 20.0) == 0.0, "incoherent neighborhoods must not smear"
for quality in (i / 100.0 for i in range(101)):
    for motion in (i / 10.0 for i in range(51)):
        weight = local_motion_softness(0.0, 1.0, 1.0, quality, motion)
        assert 0.0 <= weight <= 0.24

# Regression: a spatially varying confidence field made the retired temporal
# source selector jump between different frames on neighboring pixels.
def retired_nearest_source_mask(boundary_activation: float, trust: float, motion_length: float) -> float:
    return (
        boundary_activation
        * (1.0 - min(1.0, max(0.0, trust)))
        * smoothstep(0.75, 3.0, motion_length)
    )


def mean_neighbor_variation(field: list[list[float]]) -> float:
    jumps = [
        abs(field[y][x] - field[y][x + 1])
        for y in range(len(field))
        for x in range(len(field[0]) - 1)
    ]
    jumps.extend(
        abs(field[y][x] - field[y + 1][x])
        for y in range(len(field) - 1)
        for x in range(len(field[0]))
    )
    return sum(jumps) / len(jumps)


trust_field = [
    [0.95 if ((x // 2 + y // 2) % 2 == 0) else 0.05 for x in range(8)]
    for y in range(8)
]
retired_selector = [
    [retired_nearest_source_mask(1.0, trust, 4.0) for trust in row]
    for row in trust_field
]
candidate_selector = [[0.0 for _ in row] for row in trust_field]
alpha = 0.45
retired_phase = [[alpha * (1.0 - weight) for weight in row] for row in retired_selector]
candidate_phase = [[(1.0 - trust) * alpha + trust * alpha for trust in row] for row in trust_field]
assert mean_neighbor_variation(retired_selector) > 0.35, "stress map must expose fragmented nearest-source selection"
assert mean_neighbor_variation(candidate_selector) == 0.0, "candidate must use no spatially varying nearest-source mask"
assert mean_neighbor_variation(retired_phase) > 0.15, "retired selector must create blocky frame-time islands"
assert mean_neighbor_variation(candidate_phase) < 1e-12
assert all(abs(value - alpha) < 1e-12 for row in candidate_phase for value in row)
assert "nearest_frame_weight" not in patched
assert "source_blend = mix(original0, original1, alpha);" in patched
assert "color = mix(source_blend, mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));" in patched

# Regression: the warp-to-fallback cap suppresses isolated high-trust islands
# only; it never raises uncertain trust or changes the shared temporal alpha.
def spatial_trust_guard(center: float, consensus: float, boundary_activation: float) -> float:
    island = max(0.0, min(1.0, center) - consensus)
    guard = 0.75 * min(1.0, max(0.0, boundary_activation)) * smoothstep(0.08, 0.35, island)
    return center + (min(center, consensus) - center) * min(0.75, max(0.0, guard))


def cardinal_support_mean(field: list[list[float]], x: int, y: int) -> float:
    height, width = len(field), len(field[0])
    neighbors = (
        field[y][max(0, x - 1)],
        field[y][min(width - 1, x + 1)],
        field[max(0, y - 1)][x],
        field[min(height - 1, y + 1)][x],
    )
    return sum(neighbors) / 4.0


island_trust = [
    [0.95 if (x + y) % 2 == 0 else 0.05 for x in range(16)]
    for y in range(16)
]
guarded_trust = [
    [spatial_trust_guard(value, cardinal_support_mean(island_trust, x, y), 1.0)
     for x, value in enumerate(row)]
    for y, row in enumerate(island_trust)
]
interior_original = [row[2:14] for row in island_trust[2:14]]
interior_guarded = [row[2:14] for row in guarded_trust[2:14]]
assert mean_neighbor_variation(interior_guarded) < 0.30 * mean_neighbor_variation(interior_original)
assert all(after <= before for old_row, new_row in zip(island_trust, guarded_trust)
           for before, after in zip(old_row, new_row)), "the guard must never promote uncertain warp trust"
assert min(value for row in guarded_trust for value in row) > 0.0
assert max(value for row in guarded_trust for value in row) > 0.20
assert math.isclose(spatial_trust_guard(0.7, 0.7, 1.0), 0.7)
assert math.isclose(spatial_trust_guard(0.7, 0.95, 1.0), 0.7), "incoherent neighbors must not force trust upward"
island_phase = [[alpha + 0.4 * trust for trust in row] for row in island_trust]
guarded_phase = [[alpha + 0.4 * trust for trust in row] for row in guarded_trust]
assert mean_neighbor_variation(guarded_phase[2:14]) < 0.30 * mean_neighbor_variation(island_phase[2:14])
assert min(value for row in guarded_phase for value in row) > alpha
assert max(value for row in guarded_phase for value in row) < 1.0
assert "trust = mix(trust, min(trust, spatial_consensus)," in patched
assert "trust = mix(trust, spatial_consensus," not in patched

# Path coherence remains limited to uncertain-motion recovery; primary match trust is unchanged.
assert trajectory_weight((8.0, -2.0), (8.0, -2.0), 0.75) == 1.0
assert trajectory_weight((8.0, -2.0), (8.1, -2.0), 1.0) == 1.0
assert trajectory_weight((0.0, 0.0), (0.125, 0.0), 1.0) == 1.0
assert trajectory_weight((0.0, 0.0), (0.5, 0.0), 1.0) == 0.0
assert trajectory_weight((0.0, 0.0), (0.5, 0.0), -1.0) == 0.0
assert math.isclose(trajectory_weight((0.0, 0.0), (0.3125, 0.0), 1.0), 0.5, abs_tol=1e-9)
assert 0.0 < trajectory_weight((8.0, 0.0), (8.3, 0.0), 1.0) < 1.0
assert trajectory_weight((12.0, 0.0), (-4.0, 0.0), 0.5) == 0.0
base_trusted_support = 0.8 * 0.9
base_uncertain_support = 0.7 * 0.9
stable_path = trajectory_weight((8.0, 0.0), (8.1, 0.0), 1.0)
boundary_path = trajectory_weight((12.0, 0.0), (-4.0, 0.0), 0.5)
gated_uncertain_support = base_uncertain_support * boundary_path
assert math.isclose(base_trusted_support, 0.72, abs_tol=1e-12)
assert math.isclose(stable_path, 1.0, abs_tol=1e-12)
assert boundary_path == 0.0
assert gated_uncertain_support == 0.0
trajectory_values = [
    trajectory_weight((0.0, 0.0), (delta, 0.0), 1.0)
    for delta in (i / 100.0 for i in range(101))
]
assert all(a >= b for a, b in zip(trajectory_values, trajectory_values[1:]))

# In the uploaded comparison, the close-up cut changes 10/16 spatial tiles
# after global-brightness correction, while adjacent ordinary motion changes 0/16.
# Same-palette spatial rearrangement must trigger; one-tile motion must not.
checker = [
    [0.1 if ((y // 4 + x // 4) % 2 == 0) else 0.9 for x in range(16)]
    for y in range(16)
]
inverted_checker = [[1.0 - value for value in row] for row in checker]
assert global_scene_cut_score(checker, inverted_checker) == 0.0
assert spatial_scene_cut_score(checker, inverted_checker) == 1.0
assert max(global_scene_cut_score(checker, inverted_checker), spatial_scene_cut_score(checker, inverted_checker)) > 0.45
one_tile_changed = [row[:] for row in checker]
for y in range(4):
    for x in range(4):
        one_tile_changed[y][x] = inverted_checker[y][x]
assert max(global_scene_cut_score(checker, one_tile_changed), spatial_scene_cut_score(checker, one_tile_changed)) < 0.45
uniform_dark = [[0.2 for _ in range(16)] for _ in range(16)]
uniform_bright = [[0.35 for _ in range(16)] for _ in range(16)]
assert spatial_scene_cut_score(uniform_dark, uniform_bright) == 0.0

retained_source = (ROOT / "app/src/main/cpp/mpvflow/fongmi/motion.c").read_text(encoding="utf-8")
retained_result, retained_changed = PATCHER.transform_source(retained_source)
assert not retained_changed and retained_result == retained_source, "retained snapshot should mirror active grid, synthesis, reconstruction, warp, and scene-cut safeguards"
assert "vec2 scene_change = texelFetch(scene, ivec2(0), 0).rg;" in retained_source
assert "if (scene_change.r > 0.45 || scene_change.g > 0.08)" in retained_source
assert "color = alpha < 0.5 ? original0 : original1;" in retained_source
assert "nearest_frame_weight" not in retained_source

# The prepared view is always renderer-linear, but only source HDR is refused.
def diagnostic_refuses_hdr_source(linear_working_view: bool, source_is_hdr: bool) -> bool:
    return source_is_hdr


for label, linear_view, source_hdr, expected in (
    ("SDR source rendered into a linear prepared view", True, False, False),
    ("SDR encoded source-frame route", False, False, False),
    ("PQ source", True, True, True),
    ("HLG source", True, True, True),
    ("Dolby Vision source", True, True, True),
):
    assert diagnostic_refuses_hdr_source(linear_view, source_hdr) is expected, label
motion_capture_patcher_source = MOTION_CAPTURE_PATCHER_PATH.read_text(encoding="utf-8")
assert "m->diagnostic_hdr_rejected = m->diagnostic_source_hdr;" in motion_capture_patcher_source
assert "m->diagnostic_hdr_rejected = m->linear_hdr;" not in motion_capture_patcher_source
assert "pl_color_transfer_is_hdr(source->color.transfer)" in motion_capture_patcher_source
assert "source->repr.sys == PL_COLOR_SYSTEM_DOLBYVISION" in motion_capture_patcher_source
assert "pl_color_space_is_hdr(&source->color)" in motion_capture_patcher_source

def diagnostic_capture_size(width: int, height: int) -> tuple[int, int] | None:
    if width < 8 or height < 8 or width > 4096 or height > 4096 or width * height > 16777216:
        return None
    largest = max(width, height)
    out_width, out_height = width, height
    if largest > 1920:
        out_width = (width * 1920 + largest // 2) // largest
        out_height = (height * 1920 + largest // 2) // largest
    while out_width * out_height > 2073600:
        if out_width * height >= out_height * width:
            out_width -= 1
        else:
            out_height -= 1
    plane_bytes = out_width * out_height * 4
    if out_width < 8 or out_height < 8 or out_width > 1920 or out_height > 1920:
        return None
    if plane_bytes * 7 > 64 * 1024 * 1024:
        return None
    return out_width, out_height


assert diagnostic_capture_size(2275, 1280) == (1920, 1080)
assert diagnostic_capture_size(4097, 1280) is None
assert 1920 * 1080 * 4 * 7 <= 64 * 1024 * 1024
assert "source_position = uv * source_extent - 0.5;" in motion_capture_patcher_source
assert "vec2 scale = source_extent / motion_extent;" in motion_capture_patcher_source
assert r'pl_var_vec2(\"source_size\"), .data = source_size' in motion_capture_patcher_source
assert r'pl_var_vec2(\"source_extent\"), .data = source_size' not in motion_capture_patcher_source
assert "plane_bytes * 7 > 64LL * 1024 * 1024" in motion_capture_patcher_source
assert "capture_width = m->diagnostic_width" in motion_capture_patcher_source

if args.source:
    active_source = args.source.read_text(encoding="utf-8")
    if MOTION_CAPTURE_PATCHER.SOURCE_MARKER in active_source:
        assert active_source.count(PATCHER.MARKER) == 1
        assert PATCHER.LOCAL_SOFTENING_MARKER in active_source
        assert PATCHER.RECONSTRUCTION_MARKER in active_source
        assert PATCHER.WARP_TRAJECTORY_MARKER in active_source
        assert PATCHER.FINE_GRID_MARKER in active_source
        assert PATCHER.SCENE_CUT_MARKER in active_source
        assert "nearest_frame_weight" not in active_source
    else:
        active_result, active_changed = PATCHER.transform_source(active_source)
        assert not active_changed and active_result == active_source, "active pinned shader must contain the verified grid, synthesis, reconstruction, and warp patches exactly once"
    mpv_dir = args.source.parents[3]
    active_header = args.source.with_name("motion.h").read_text(encoding="utf-8")
    active_vo_path = mpv_dir / "video/out/vo_gpu_next.c"
    active_vo = active_vo_path.read_text(encoding="utf-8")
    active_meson = (mpv_dir / "meson.build").read_text(encoding="utf-8")
    if MOTION_CAPTURE_PATCHER.SOURCE_MARKER in active_source:
        diagnostic_result, diagnostic_changed = MOTION_CAPTURE_PATCHER.transform_source(active_source)
        assert not diagnostic_changed and diagnostic_result == active_source
        for channel_branch in ("diagnostic_mode == 3", "diagnostic_mode == 4", "diagnostic_mode == 5", "diagnostic_mode == 6"):
            assert channel_branch in active_source
        assert "float applied_trust = clamp(trust, 0.0, 1.0);" in active_source
        assert "vec4(applied_trust, applied_trust, applied_trust, 1.0)" in active_source
        assert "mix(source_blend, raw_warped, applied_trust)" in active_source
        assert "m->diagnostic_hdr_rejected = m->diagnostic_source_hdr;" in active_source
        assert "m->diagnostic_hdr_rejected = m->linear_hdr;" not in active_source
        assert "source_position = uv * source_extent - 0.5" in active_source
        assert 'pl_var_vec2("source_size"), .data = source_size' in active_source
        assert 'pl_var_vec2("source_extent"), .data = source_size' not in active_source
        assert "sampling_position = diagnostic_mode == 0 ? vec2(pixel) : source_position" in active_source
        assert "original0 = texelFetch(source0, source_pixel, 0)" in active_source
        assert "sample_rgba(source0, sampling_position + f.xy * scale)" in active_source
        assert "sample_rgba(source1, sampling_position + r.xy * scale - local_motion_offset)" in active_source
        assert "vec2 scale = source_extent / motion_extent" in active_source
        assert "plane_bytes * 7 > 64LL * 1024 * 1024" in active_source
        assert "m->diagnostic_requested = !source_hdr;" in active_source
        assert "m->diagnostic_attempted = source_hdr;" in active_source
        assert "m->diagnostic_hdr_rejected = source_hdr;" in active_source
        assert "bool mp_motion_source_is_hdr(const struct pl_frame *source)" in active_source
        assert "pl_color_transfer_is_hdr(source->color.transfer)" in active_source
        assert "source->repr.sys == PL_COLOR_SYSTEM_DOLBYVISION" in active_source
        assert "pl_color_space_is_hdr(&source->color)" in active_source
        assert "if (m->diagnostic_hdr_rejected)" in active_source
        release_start = active_source.index("void mp_motion_release_diagnostic_capture")
        release_end = active_source.index("static void record_synthesis", release_start)
        assert "m->diagnostic_requested = false;" in active_source[release_start:release_end]
        assert "diagnostic_planes[6]" in active_source and "host_readable = true" in active_source
        assert "m->diagnostic_width = capture_width;" in active_source
        assert "m->diagnostic_height = capture_height;" in active_source
        assert ".capture_width = m->diagnostic_width" in active_source
        assert ".capture_height = m->diagnostic_height" in active_source
        header_result, header_changed = MOTION_CAPTURE_PATCHER.transform_header(active_header)
        assert not header_changed and header_result == active_header
        assert "bool hdr_rejected;" in active_header and "bool source_hdr;" in active_header
        assert "int capture_width, capture_height;" in active_header
        assert "mp_motion_source_is_hdr(const struct pl_frame *source);" in active_header
        assert "mp_motion_request_diagnostic_capture(struct mp_motion *motion, bool source_hdr);" in active_header
        vo_result, vo_changed = VO_CAPTURE_PATCHER.transform_vo(active_vo)
        assert not vo_changed and vo_result == active_vo
        assert "START_ONE_SHOT" in active_vo and "fongmi_capture_finish(vo)" in active_vo
        assert "fongmi_diagnostic_actual_hdr_source_unsupported" in active_vo
        assert "direct ? \"prepared_view_shader\" : \"prepared_view_texture\"" in active_vo
        assert "described_source_frames" in active_vo and "raw_source_textures" in active_vo
        assert "mp_motion_source_is_hdr(mix->frames[pair[0]])" in active_vo
        assert "mp_motion_source_is_hdr(mix->frames[pair[1]])" in active_vo
        assert "active_route" in active_vo and "source_is_hdr" in active_vo and "refusal_reason" in active_vo
        assert active_vo.count("fongmi_capture_arm(vo, mix, pair,") == 2
        assert active_vo.count("fongmi_capture_finish(vo)") == 2
        assert "int sample_index = i < 2 ? -1 : 0;" in active_vo
        assert "source_dimensions" in active_vo and "captured_dimensions" in active_vo
        assert "fongmi_capture_download(\n            p, capture.planes[i], capture.capture_width, capture.capture_height)" in active_vo
        assert ".output_width = capture.capture_width" in active_vo
        assert ".output_height = capture.capture_height" in active_vo
        expected_channels = (
            "source0", "source1", "pre_confidence_warp", "fallback_color", "trust_map", "synthesized"
        )
        assert all(f'"{channel}"' in active_vo for channel in expected_channels)
        active_writer = (mpv_dir / "video/out/gpu_next/mpvflow_capture_io.c").read_text(encoding="utf-8")
        active_writer_header = (mpv_dir / "video/out/gpu_next/mpvflow_capture_io.h").read_text(encoding="utf-8")
        assert "is_single_output_channel" in active_writer
        assert "MPVFLOW_CAPTURE_SINGLE_MAX_SOURCE_DIMENSION 4096" in active_writer_header
        assert "MPVFLOW_CAPTURE_SINGLE_MAX_SOURCE_PIXELS 16777216" in active_writer_header
        assert "shared aspect-preserving RGBA8 downscale; source and captured dimensions recorded" in active_writer
        assert all(f'"{channel}"' in active_writer for channel in expected_channels)
        assert ".source_width = capture.width" in active_vo
        assert ".source_height = capture.height" in active_vo
        assert ".pts0 = p->fongmi_capture_pts0" in active_vo
        assert ".pts1 = p->fongmi_capture_pts1" in active_vo
        assert ".output_pts = p->fongmi_capture_output_pts" in active_vo
        meson_result, meson_changed = VO_CAPTURE_PATCHER.transform_meson(active_meson)
        assert not meson_changed and meson_result == active_meson
        assert "video/out/gpu_next/mpvflow_capture_io.c" in active_meson
        print("Debug FongMi runtime contains the complete one-shot six-plane capture mapping")
    else:
        assert "MPVFLOW_FONGMI_VO_SINGLE_OUTPUT_CAPTURE_V2" not in active_vo
        assert "video/out/gpu_next/mpvflow_capture_io.c" not in active_meson
        assert not (mpv_dir / "video/out/gpu_next/mpvflow_capture_io.c").exists()
        print("Non-debug FongMi runtime is free of one-shot capture instrumentation")
print(
    "FongMi artifact policy passed: max taper trust step "
    f"{old_step:.4f} -> {new_step:.4f}; local directional softness is capped at 0.24 "
    "with path coherence limited to uncertain recovery and no per-pixel temporal source switch; "
    "spatial cut detection flags palette-matched scene changes without firing on one-tile motion; "
    f"the 480-cap grid changes {coarse_grid[0]}x{coarse_grid[1]} to {fine_grid[0]}x{fine_grid[1]}, "
    f"halving the bilinear mixed-flow band {coarse_mix_band:.0f}px to {fine_mix_band:.0f}px in the supplied-resolution case"
)
assert "sampling_position = diagnostic_mode == 0 ? vec2(pixel) : source_position;" in motion_capture_patcher_source
assert "original0 = texelFetch(source0, source_pixel, 0);" in motion_capture_patcher_source
assert "sample_rgba(source0, sampling_position + f.xy * scale)" in motion_capture_patcher_source
assert "sample_rgba(source1, sampling_position + r.xy * scale - local_motion_offset)" in motion_capture_patcher_source
