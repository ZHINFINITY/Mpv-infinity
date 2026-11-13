#!/usr/bin/env python3
"""Tests for the app-owned safeguards applied to the pinned FongMi shader."""
from __future__ import annotations

import argparse
import importlib.util
import math
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATCHER_PATH = ROOT / "scripts/patch_fongmi_motion_artifact.py"
PATCHER_SOURCE = PATCHER_PATH.read_text(encoding="utf-8")
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
        continue;
    vec4 hypothesis = texelFetch(translation, ivec2(0), 0);
    if (hypothesis.w > 0.0) {
        vec2 vector = direction != 0 ? -hypothesis.xy : hypothesis.xy;
        vector = clamp(vector, -p, size - 1.0 - p);
        float score = patch_cost(second, p, vector);
        if (score <= cost + 0.000001) {
            selected = vec4(vector, score, hypothesis.w);
            cost = score;
        }
    }"""

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
    float residual = length((source + weight * f.xy - uv) * output_size);
    float reliable = 1.0 - smoothstep(0.001, 0.01, max(f.z, reverse.z));
    reliable *= 1.0 - smoothstep(0.1, 0.5, cycle);
    float trust = quality(f);
    float geometry = 1.0;
    color = vec4(displacement, trust * geometry, reliable * geometry);
);"""

BASE_GRID = """static void fixture_analyze(int level) {
        int spacing = level == LEVELS - 1 ? 4 : 8;
}"""

BASE_REFINEMENT = """static const char refine_body[] = GLSL(
    residual = patch_cost(second, p, vector);
    if (any(notEqual(vector, vec2(0))) || residual != 0.0) {
        if (!tensor_valid)
            tensor = patch_tensor(first, p);
        float xx = tensor.x, xy = tensor.y, yy = tensor.z;
        float trace = xx + yy;
        if (trace > 0.000001) {
            vec2 axis = xx > yy ? vec2(1, 0) : vec2(0, 1);
            float projected = xx > yy ? vector.x + xy / xx * vector.y
                                      : vector.y + xy / yy * vector.x;
            for (int offset = -1; offset <= 1; offset++) {
                vec2 candidate = clamp(axis * (round(projected) + float(offset)),
                                       -p, size - 1.0 - p);
                float delta = abs(dot(candidate, axis) - projected);
            }
        }
    }
    if (all(equal(motion_scale, vec2(1))) && any(notEqual(vector, vec2(0)))) {
        for (int index = 0; index < 2 * (2 * settings.z + 1); index++) {
            int offset = index % (2 * settings.z + 1) - settings.z;
            vec2 axis = index < 2 * settings.z + 1 ? vec2(1, 0) : vec2(0, 1);
            vec2 candidate = clamp(axis * float(offset), -p, size - 1.0 - p);
            float norm = dot(candidate, candidate);
        }
    }
);"""

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
        + BASE_REFINEMENT
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


def motion_grid_spacing(level: int, scale: float, levels: int = 3) -> int:
    if level == 0 and scale < 0.333334:
        return 3
    return 4 if level in (0, levels - 1) else 8


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


def relative_fit(stationary_error: float, warped_error: float) -> float:
    return max(0.0, (stationary_error - warped_error) / stationary_error) if stationary_error > 0.0 else 0.0


def source_visibility_weights(
    blend: float,
    forward_confidence: float,
    reverse_confidence: float,
    forward_certified: float,
    reverse_certified: float,
    color_error: float,
    stationary_error: float,
    paired_errors: tuple[float, float] | None = None,
    boundary_support: float = 1.0,
    spatial_cap: float = 1.0,
) -> tuple[float, float]:
    certified = min(forward_certified, reverse_certified)
    unique = min(forward_confidence, reverse_confidence, certified)
    aperture = certified * (1.0 - smoothstep(0.002, 0.02, color_error))
    reliable = max(unique, aperture)
    conflict = smoothstep(0.03, 0.15, color_error)
    confidence = (
        max(forward_confidence * relative_fit(stationary_error, color_error), reliable),
        max(reverse_confidence * relative_fit(stationary_error, color_error), reliable),
    )
    phase = (1.0 - blend, blend)
    visibility = [phase[i] * confidence[i] * (1.0 - conflict) * taper_weight(color_error) for i in range(2)]
    boundary_scale = min(1.0, max(0.0, boundary_support))
    visibility = [value * boundary_scale for value in visibility]
    if conflict > 0.0 and stationary_error > 0.0 and paired_errors is not None:
        for index, paired_error in enumerate(paired_errors):
            support = 1.0 - smoothstep(0.03, 0.15, paired_error)
            evidence = support * relative_fit(stationary_error, paired_error)
            direction_confidence = forward_confidence if index == 0 else reverse_confidence
            visibility[index] += conflict * phase[index] * direction_confidence * evidence
    scale = min(1.0, max(0.0, spatial_cap))
    visibility = [min(1.0, max(0.0, value * scale)) for value in visibility]
    total = sum(visibility)
    if total > 1.0:
        visibility = [value / total for value in visibility]
    return visibility[0], visibility[1]


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


def inverse_warp_candidates(target: float, weight: float, field):
    source = target
    vector = field(source)
    residual = abs(source + weight * vector - target)
    best = (residual, source, vector)
    for _ in range(4):
        source = target - weight * vector
        vector = field(source)
        residual = abs(source + weight * vector - target)
        if math.isfinite(residual) and residual < best[0]:
            best = (residual, source, vector)
    return best, (residual, source, vector)


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


def select_global_translation(
    local_vector: tuple[float, float],
    global_vector: tuple[float, float],
    reverse_at_global: tuple[float, float],
    global_cost: float,
    local_cost: float,
) -> tuple[float, float]:
    cycle = math.hypot(
        global_vector[0] + reverse_at_global[0],
        global_vector[1] + reverse_at_global[1],
    )
    if global_cost <= local_cost + 0.000001 and cycle < 1.5:
        return global_vector
    return local_vector


def aperture_normal(xx: float, xy: float, yy: float) -> tuple[float, float] | None:
    trace = xx + yy
    discriminant = math.sqrt(max((xx - yy) ** 2 + 4 * xy * xy, 0.0))
    lambda_min = max(0.5 * (trace - discriminant), 0.0)
    lambda_max = max(0.5 * (trace + discriminant), 0.0)
    if trace <= 1e-6 or lambda_max <= 1e-6 or lambda_min >= 0.12 * lambda_max:
        return None
    if abs(xy) > 1e-6:
        nx, ny = lambda_max - yy, xy
    else:
        nx, ny = (1.0, 0.0) if xx >= yy else (0.0, 1.0)
    length = math.hypot(nx, ny)
    return nx / length, ny / length


def aperture_refined_vector(
    vector: tuple[float, float], tensor: tuple[float, float, float]
) -> tuple[float, float]:
    normal = aperture_normal(*tensor)
    if normal is None:
        return vector
    projected = vector[0] * normal[0] + vector[1] * normal[1]
    correction = round(projected) - projected
    return vector[0] + normal[0] * correction, vector[1] + normal[1] * correction


original = fixture()
patched, changed = PATCHER.transform_source(original)
assert changed
assert PATCHER.MARKER in patched
assert PATCHER.SOURCE_VISIBILITY_MARKER in patched
assert PATCHER.SPATIAL_VISIBILITY_CAP_MARKER in patched
assert PATCHER.SPATIAL_TRUST_MARKER in patched
assert PATCHER.LOCAL_SOFTENING_MARKER in patched
assert PATCHER.SPATIAL_MEDOID_MARKER in patched
assert PATCHER.GLOBAL_TRANSLATION_CYCLE_MARKER in patched
assert PATCHER.HIGH_RES_FINE_GRID_MARKER in patched
assert PATCHER._has_global_translation_cycle_guard(patched)
assert "translation_position + vector / size, 0.0" in patched
assert "translation_cycle < 1.5 &&" in patched
assert PATCHER.HIGH_RES_FINE_GRID_CONDITION in patched
assert "spacing = 3;" in patched
# A camera-pan vector remains available on coherent background in both directions.
pan = (4.0, -1.0)
assert select_global_translation(pan, pan, (-4.0, 1.0), 0.02, 0.02) == pan
# A moving character's supported reverse flow must preserve its independent motion.
character = (1.0, 1.0)
assert select_global_translation(character, pan, (-1.0, -1.0), 0.02, 0.02) == character
# A pose-changing character with a non-closing reverse path must not inherit the pan.
deforming_character = (0.0, 3.0)
assert select_global_translation(deforming_character, pan, (0.0, -3.0), 0.02, 0.02) == deforming_character
assert PATCHER.RECONSTRUCTION_MARKER in patched
assert PATCHER.WARP_TRAJECTORY_MARKER in patched
assert PATCHER.FINE_GRID_MARKER in patched
assert PATCHER.FINE_GRID_ASSIGNMENT in patched
assert PATCHER.REJECTED_WARP_TRUST_MARKER not in patched
assert PATCHER.SCENE_CUT_MARKER in patched
assert PATCHER.REFINEMENT_APERTURE_NORMAL_MARKER in patched
assert PATCHER.REFINEMENT_APERTURE_TANGENT_MARKER in patched
assert PATCHER._has_refinement_aperture_normal(patched)
assert "lambda_min < 0.12 * lambda_max;" in patched
assert "float projected = dot(vector, aperture_normal);" in patched
assert "vec2 candidate = clamp(vector + aperture_normal *\n" in patched
assert "(round(projected) + float(offset) - projected)," in patched
assert "(float(offset) - dot(vector, aperture_normal))," in patched
assert "for (int offset = -settings.z; offset <= settings.z; offset++) {" in patched
assert "vec2 axis = xx > yy ? vec2(1, 0) : vec2(0, 1);" not in patched
assert "vec2 axis = index < 2 * settings.z + 1" not in patched
assert PATCHER.REJECTED_INSHOT_FALLBACK_MARKER not in patched
assert PATCHER._has_scene_cut_guard(patched)
assert "int tile_index = (y / 4) * 4 + x / 4;" in patched
assert "tile_luma_delta[tile_index] += dot(a - b, vec3(0.2126, 0.7152, 0.0722));" in patched
assert "cut = max(cut, spatial_cut);" in patched
assert PATCHER.WARP_BEST_RESIDUAL_MARKER in patched
assert "vec4 initial_field = textureLod(forward, uv, 0.0);" in patched
assert "vec2 initial_motion = initial_field.xy;" in patched
assert "vec4 best_field = f;" in patched
assert "candidate_residual < best_residual" in patched
assert "source = best_source;" in patched and "f = best_field;" in patched
assert "float residual = best_residual;" in patched
assert PATCHER.WARP_ITERATION_PATTERN.search(patched) is None
assert "vec2 motion_vector = f.xy;" in patched
assert "float trajectory_error = abs(weight) * length(motion_vector - initial_motion);" in patched
assert "reliable *= trajectory_quality;" in patched
assert PATCHER.PRIMARY_TRAJECTORY_TRUST_MARKER in patched
assert "trust = min(trust, trajectory_quality);" in patched
assert "agreeing_support / 3.0" in patched
assert "if (candidate_support <= 0.0)" in patched
assert "if (trust < 0.999 && boundary_activation > 0.0 && local_quality < 0.70)" in patched
assert "float coherent_fallback = (1.0 - clamp(trust, 0.0, 1.0)) * coherent_support *" in patched
assert "trust *= trajectory_quality;" not in patched, "path stability caps trust instead of multiplying it"
assert "vec2 displacement = -weight * f.xy;" in patched
assert "color = vec4(displacement, trust * geometry, reliable * geometry);" in patched
assert "source = uv - weight * f.xy / map_size;" in patched
assert "if (!(evidence > 0.0 &&" in patched
assert "if (!(evidence > best || (evidence == best && distance < nearest)))" not in patched
assert "neighborhood_quality(sampler2D field, vec2 uv, vec2 center)" in patched
assert "spatial_trust_consensus(sampler2D warp0, sampler2D warp1," in patched
assert "1.0 / vec2(textureSize(warp0, 0))" in patched
assert re.search(r"(?m)^[ \t]*if \(total < 0\.25\)\n[ \t]+return -1\.0;", patched)
assert "if (spatial_consensus >= 0.0) {" in patched
assert "float spatial_island = max(0.0, clamp(trust, 0.0, 1.0) - spatial_consensus);" in patched
assert "0.75 * boundary_activation *" in patched
assert "trust = mix(trust, min(trust, spatial_consensus)," in patched
assert "spatial_visibility_scale = clamp(trust_after_spatial / trust_before_spatial, 0.0, 1.0);" in patched
assert "trust = mix(trust, spatial_consensus," not in patched
assert "float luma_error = abs(dot(metric0," in patched
assert "float chroma_error = max(abs((metric0.r - metric0.g)" in patched
assert "smoothstep(0.03, 0.30," in patched
assert "smoothstep(0.03, 0.15, color_error)" in patched
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
assert "color = source_blend * (1.0 - visibility_sum) +" in patched
assert "visibility += conflict_weight * phase * vec2(f.z, r.z) * evidence;" in patched
assert "visibility *= clamp(boundary_trust * region3_local_trust, 0.0, 1.0);" in patched
assert "visibility *= clamp(spatial_visibility_scale, 0.0, 1.0);" in patched
assert "FongMi synthesis prefix\n" in patched and "FongMi synthesis suffix\n" in patched
texture_flow = (3.25, -2.5)
assert aperture_normal(1.0, 0.0, 1.0) is None
assert aperture_refined_vector(texture_flow, (1.0, 0.0, 1.0)) == texture_flow
diagonal_edge_tensor = (1.0, 0.96, 1.0)
diagonal_normal = aperture_normal(*diagonal_edge_tensor)
assert diagonal_normal is not None
assert abs(diagonal_normal[0] - math.sqrt(0.5)) < 1e-6
assert abs(diagonal_normal[1] - math.sqrt(0.5)) < 1e-6
diagonal_edge_vector = (2.8, 3.2)
diagonal_refined = aperture_refined_vector(diagonal_edge_vector, diagonal_edge_tensor)
diagonal_tangent = (-diagonal_normal[1], diagonal_normal[0])
assert abs(sum(a * b for a, b in zip(diagonal_refined, diagonal_tangent)) -
           sum(a * b for a, b in zip(diagonal_edge_vector, diagonal_tangent))) < 1e-6
assert abs(diagonal_refined[0] - diagonal_refined[1]) > 1e-3
horizontal_normal = aperture_normal(1.0, 0.0, 0.01)
assert horizontal_normal == (1.0, 0.0)
assert aperture_refined_vector((2.2, -1.0), (1.0, 0.0, 0.01)) == (2.0, -1.0)


def fit_camera_pan_zoom(samples: list[tuple[float, float, float, float, float]]):
    def solve(weights: list[float]):
        total = sum(weights)
        if total <= 0.001:
            return None
        px = sum(w * sample[0] for w, sample in zip(weights, samples))
        py = sum(w * sample[1] for w, sample in zip(weights, samples))
        fx = sum(w * sample[2] for w, sample in zip(weights, samples))
        fy = sum(w * sample[3] for w, sample in zip(weights, samples))
        denominator = sum(
            w * (sample[0] * sample[0] + sample[1] * sample[1])
            for w, sample in zip(weights, samples)
        ) - (px * px + py * py) / total
        if denominator <= 0.001:
            return None
        cross = sum(
            w * (sample[0] * sample[2] + sample[1] * sample[3])
            for w, sample in zip(weights, samples)
        ) - (px * fx + py * fy) / total
        zoom = cross / denominator
        return fx / total - zoom * px / total, fy / total - zoom * py / total, zoom

    base_weights = [sample[4] for sample in samples]
    initial = solve(base_weights)
    assert initial is not None
    robust_weights = []
    for sample, weight in zip(samples, base_weights):
        predicted = (initial[0] + initial[2] * sample[0],
                     initial[1] + initial[2] * sample[1])
        residual = math.hypot(sample[2] - predicted[0], sample[3] - predicted[1])
        robust_weights.append(weight * (1.0 - smoothstep(1.5, 3.5, residual)))
    fitted = solve(robust_weights)
    assert fitted is not None
    confidence = sum(robust_weights) / sum(base_weights)
    return fitted, confidence


camera_samples = []
expected_camera = (2.5, -1.75, 0.035)
for iy, y in enumerate(range(-60, 61, 20)):
    for ix, x in enumerate(range(-80, 81, 20)):
        flow_x = expected_camera[0] + expected_camera[2] * x + 0.03 * math.sin(ix + iy)
        flow_y = expected_camera[1] + expected_camera[2] * y + 0.03 * math.cos(ix - iy)
        if (ix + 2 * iy) % 6 == 0:
            flow_x += 5.5
            flow_y -= 4.0
        camera_samples.append((float(x), float(y), flow_x, flow_y, 1.0))
camera_fit, camera_confidence = fit_camera_pan_zoom(camera_samples)
assert abs(camera_fit[0] - expected_camera[0]) < 0.10
assert abs(camera_fit[1] - expected_camera[1]) < 0.10
assert abs(camera_fit[2] - expected_camera[2]) < 0.001
assert camera_confidence > 0.75, "the robust fit should reject local foreground motion, not the camera model"
assert "camera_cycle < 1.5" in PATCHER_SOURCE
assert "camera_score <= cost + 0.000001" in PATCHER_SOURCE

unstable_field = lambda x: 3.0 * (x - 63.9) + 0.2
best_candidate, final_candidate = inverse_warp_candidates(64.0, 0.5, unstable_field)
assert abs(best_candidate[0] - 0.25) < 1e-9
assert final_candidate[0] > 1.0 and best_candidate[0] < final_candidate[0]
constant_candidate, constant_final = inverse_warp_candidates(64.0, 0.5, lambda _x: 8.0)
assert constant_candidate[0] == 0.0 and constant_final[0] == 0.0
coarse_grid = vector_grid_shape(480, 221, 8)
previous_fine_grid = vector_grid_shape(480, 221, 4)
previous_high_res_grid = vector_grid_shape(480, 221, 2)
fine_grid = vector_grid_shape(480, 221, 3)
coarse_mix_band = bilinear_mixed_flow_band_pixels(480, 1920, 8)
previous_fine_mix_band = bilinear_mixed_flow_band_pixels(480, 1920, 4)
fine_mix_band = bilinear_mixed_flow_band_pixels(480, 1920, 3)
assert coarse_grid == (60, 28)
assert previous_fine_grid == (120, 56)
assert previous_high_res_grid == (240, 111)
assert fine_grid == (160, 74)
assert coarse_mix_band == 32.0 and previous_fine_mix_band == 16.0 and fine_mix_band == 12.0
assert fine_mix_band < previous_fine_mix_band < coarse_mix_band
assert math.isclose(
    1.0 - (fine_grid[0] * fine_grid[1]) / (previous_high_res_grid[0] * previous_high_res_grid[1]),
    5.0 / 9.0,
)
assert motion_grid_spacing(0, 480 / 1920) == 3
assert motion_grid_spacing(0, 480 / 1440) == 3
assert motion_grid_spacing(0, 480 / 1280) == 4
assert motion_grid_spacing(1, 480 / 1920) == 8
assert motion_grid_spacing(2, 480 / 1920) == 4
broken_grid = patched.replace(PATCHER.FINE_GRID_ASSIGNMENT, "int spacing = 8;", 1)
try:
    PATCHER.transform_source(broken_grid)
except ValueError:
    pass
else:
    raise AssertionError("fine-grid marker must reject a mismatched assignment")
again, changed_again = PATCHER.transform_source(patched)
assert not changed_again and again == patched

# Upgrade the prior V1 grid patch without touching its unchanged coarse levels.
legacy_two_pixel_grid = patched.replace("spacing = 3;", "spacing = 2;", 1)
upgraded_two_pixel_grid, upgraded_two_pixel_grid_changed = PATCHER.transform_source(legacy_two_pixel_grid)
assert upgraded_two_pixel_grid_changed and PATCHER._has_fine_motion_grid(upgraded_two_pixel_grid)
assert "spacing = 3;" in upgraded_two_pixel_grid and "spacing = 2;" not in upgraded_two_pixel_grid
upgraded_two_pixel_grid_again, upgraded_two_pixel_grid_changed_again = PATCHER.transform_source(upgraded_two_pixel_grid)
assert not upgraded_two_pixel_grid_changed_again and upgraded_two_pixel_grid_again == upgraded_two_pixel_grid

old_high_res_grid = patched.replace(
    PATCHER._indent_block(PATCHER.HIGH_RES_FINE_GRID_BLOCK, "        ") + "\n",
    "",
    1,
)
assert PATCHER.HIGH_RES_FINE_GRID_MARKER not in old_high_res_grid
upgraded_grid, upgraded_grid_changed = PATCHER.transform_source(old_high_res_grid)
assert upgraded_grid_changed and PATCHER._has_fine_motion_grid(upgraded_grid)
upgraded_grid_again, upgraded_grid_changed_again = PATCHER.transform_source(upgraded_grid)
assert not upgraded_grid_changed_again and upgraded_grid_again == upgraded_grid

# Cached trees carrying the previous normal-only candidates upgrade in place.
legacy_aperture = re.sub(
    rf"(?m)^[ \t]*// {re.escape(PATCHER.REFINEMENT_APERTURE_TANGENT_MARKER)}:.*\n",
    "",
    patched,
    count=1,
)
legacy_aperture, refinement_replacements = re.subn(
    r"(?m)^(?P<indent>[ \t]*)vec2 candidate = clamp\(vector \+ aperture_normal \*\n"
    r"(?P=indent)(?P<spaces>[ \t]+)\(round\(projected\) \+ float\(offset\) - projected\),\n"
    r"(?P=indent)(?P=spaces)-p, size - 1\.0 - p\);$",
    lambda match: (
        match.group("indent")
        + "vec2 candidate = clamp(aperture_normal * (round(projected) + float(offset)),\n"
        + match.group("indent") + match.group("spaces") + "-p, size - 1.0 - p);"
    ),
    legacy_aperture,
    count=1,
)
assert refinement_replacements == 1
legacy_aperture, dense_replacements = re.subn(
    r"(?m)^(?P<indent>[ \t]*)vec2 candidate = clamp\(vector \+ aperture_normal \*\n"
    r"(?P=indent)(?P<spaces>[ \t]+)\(float\(offset\) - dot\(vector, aperture_normal\)\),\n"
    r"(?P=indent)(?P=spaces)-p, size - 1\.0 - p\);$",
    lambda match: (
        match.group("indent")
        + "vec2 candidate = clamp(aperture_normal * float(offset), -p, size - 1.0 - p);"
    ),
    legacy_aperture,
    count=1,
)
assert dense_replacements == 1
assert PATCHER._has_refinement_aperture_normal(legacy_aperture, require_tangent=False)
upgraded_aperture, aperture_upgrade_changed = PATCHER.transform_source(legacy_aperture)
assert aperture_upgrade_changed and PATCHER._has_refinement_aperture_normal(upgraded_aperture)

pinned_patched, pinned_changed = PATCHER.transform_source(
    fixture(output=PINNED_OUTPUT, output_indent="            ")
)
assert pinned_changed and PATCHER._is_patched(pinned_patched)
assert PATCHER.FINE_GRID_MARKER in pinned_patched
assert "sample_rgba(source0, vec2(pixel) + f.xy * scale + local_motion_offset)" in pinned_patched
assert "sample_rgba(source1, vec2(pixel) + r.xy * scale - local_motion_offset)" in pinned_patched
assert "return source_blend * (1.0 - visibility_sum) +" in pinned_patched
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
    PATCHER._indent_block(PATCHER.SOURCE_VISIBILITY_OUTPUT_BLOCK_LEGACY, "    "),
    PATCHER._indent_block(
        "color = mix(source_blend, mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));",
        "    ",
    ),
    1,
)
legacy_v3 = legacy_v3.replace(
    PATCHER._indent_block(PATCHER.LEGACY_SOFTENED_OUTPUT_BLOCK, "    "),
    PATCHER._indent_block(BASE_OUTPUT, "    "),
    1,
)
legacy_v3 = legacy_v3.replace(
    PATCHER._indent_block(PATCHER.SOURCE_VISIBILITY_HELPER, "    ") + "\n\n",
    "",
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
    PATCHER._indent_block(PATCHER.SOURCE_VISIBILITY_OUTPUT_BLOCK_PINNED, "            "),
    PATCHER._indent_block(
        "return mix(source_blend, mix(warped0, warped1, blend), clamp(trust, 0.0, 1.0));",
        "            ",
    ),
    1,
)
pinned_v3 = pinned_v3.replace(
    PATCHER._indent_block(PATCHER.PINNED_SOFTENED_OUTPUT_BLOCK, "            "),
    PATCHER._indent_block(PINNED_OUTPUT, "            "),
    1,
)
pinned_v3 = pinned_v3.replace(
    PATCHER._indent_block(PATCHER.SOURCE_VISIBILITY_HELPER, "    ") + "\n\n",
    "",
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
assert "return source_blend * (1.0 - visibility_sum) +" in pinned_upgraded

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
    PATCHER.SOURCE_VISIBILITY_MARKER,
    PATCHER.SOURCE_VISIBILITY_MARKER + "\nfloat nearest_frame_weight = 1.0;",
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

# Per-source visibility retains coherent motion and lets only the supported
# direction contribute across a conflicting/occluded edge.
def witness_projection_ratios(alpha: float) -> tuple[float, float]:
    assert 0.0 < alpha < 1.0
    remaining = 1.0 - alpha
    return remaining / alpha, alpha / remaining


ratio0, ratio1 = witness_projection_ratios(0.25)
assert math.isclose(ratio0, 3.0) and math.isclose(ratio1, 1.0 / 3.0)
output_x, displacement0, displacement1 = 10.0, -2.0, 6.0
assert math.isclose(output_x - displacement0 * ratio0, output_x + displacement1)
assert math.isclose(output_x - displacement1 * ratio1, output_x + displacement0)
assert witness_projection_ratios(0.5) == (1.0, 1.0)

coherent_pan = source_visibility_weights(
    0.5, 0.82, 0.79, 0.92, 0.90, 0.10, 0.26, (0.01, 0.01), spatial_cap=0.9
)
unsupported_crossing = source_visibility_weights(
    0.5, 0.82, 0.79, 0.92, 0.90, 0.10, 0.26, (0.22, 0.22), spatial_cap=0.9
)
assert coherent_pan[0] > 0.0 and coherent_pan[1] > 0.0
assert sum(coherent_pan) > 0.4, "consistent pan evidence must not become a hard hold"
assert sum(coherent_pan) > sum(unsupported_crossing), "paired color evidence must recover coherent moving content"
one_sided_occlusion = source_visibility_weights(
    0.5, 0.88, 0.12, 0.70, 0.70, 0.10, 0.26, (0.01, 0.22), boundary_support=0.9
)
assert one_sided_occlusion[0] > one_sided_occlusion[1]
assert one_sided_occlusion[1] > 0.0, "the weaker direction should retain soft ghosting, not be hard-zeroed"
assert 0.0 <= sum(one_sided_occlusion) <= 1.0
high_contrast_motion = source_visibility_weights(
    0.5, 0.9, 0.1, 0.7, 0.7, 0.22, 0.45, (0.01, 0.22), boundary_support=0.0
)
assert high_contrast_motion[0] > 0.0, "a paired witness may recover a supported path at a strong edge"
assert high_contrast_motion[1] == 0.0, "an unsupported opposing path must not be restored"
for blend in (0.1, 0.25, 0.5, 0.75, 0.9):
    for forward in (0.0, 0.25, 0.5, 0.9, 1.0):
        for reverse in (0.0, 0.25, 0.5, 0.9, 1.0):
            weights = source_visibility_weights(
                blend, forward, reverse, 0.8, 0.7, 0.1, 0.25, (0.02, 0.10)
            )
            assert all(math.isfinite(value) and 0.0 <= value <= 1.0 for value in weights)
            assert sum(weights) <= 1.0 + 1e-9

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
assert "color = source_blend * (1.0 - visibility_sum) +" in patched

# Regression: neighboring support may cap an isolated high-confidence warp,
# but it must never undo this pixel's photometric disagreement rejection.
def spatial_trust_downward_cap(center: float, consensus: float, boundary_activation: float) -> float:
    if consensus < 0.0:
        return center
    island = max(0.0, min(1.0, center) - consensus)
    weight = 0.75 * min(1.0, max(0.0, boundary_activation)) * smoothstep(0.08, 0.35, island)
    return center + (min(center, consensus) - center) * min(0.75, max(0.0, weight))


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
    [spatial_trust_downward_cap(value, cardinal_support_mean(island_trust, x, y), 1.0)
     for x, value in enumerate(row)]
    for y, row in enumerate(island_trust)
]
interior_original = [row[2:14] for row in island_trust[2:14]]
interior_guarded = [row[2:14] for row in guarded_trust[2:14]]
assert mean_neighbor_variation(interior_guarded) < 0.30 * mean_neighbor_variation(interior_original)
assert all(after <= before for old_row, new_row in zip(island_trust, guarded_trust)
           for before, after in zip(old_row, new_row)), "neighbor support must not promote warp confidence"
assert min(value for row in guarded_trust for value in row) >= 0.05
assert max(value for row in guarded_trust for value in row) < 0.95
assert math.isclose(spatial_trust_downward_cap(0.7, 0.7, 1.0), 0.7)
assert math.isclose(spatial_trust_downward_cap(0.7, -1.0, 1.0), 0.7), "no coherent neighbor support must skip the cap"
assert math.isclose(spatial_trust_downward_cap(0.7, 0.95, 0.0), 0.7), "the cap is disabled outside boundary-active pixels"
island_phase = [[alpha + 0.4 * trust for trust in row] for row in island_trust]
guarded_phase = [[alpha + 0.4 * trust for trust in row] for row in guarded_trust]
assert mean_neighbor_variation(guarded_phase[2:14]) < 0.30 * mean_neighbor_variation(island_phase[2:14])
photometric_rejection = 0.05
assert math.isclose(spatial_trust_downward_cap(photometric_rejection, 0.95, 1.0), photometric_rejection), (
    "coherent neighbors must not restore warp confidence rejected by the pixel's own color check"
)
assert math.isclose(spatial_trust_downward_cap(0.95, 0.05, 1.0), 0.275, abs_tol=1e-12), (
    "an isolated high-confidence warp may still be capped by coherent neighbors"
)
assert "float spatial_gap = abs(clamp(trust, 0.0, 1.0) - spatial_consensus);" not in patched

# Spatial medoid fallback is unchanged on a coherent camera-pan field, can reject
# one unsupported local path in favor of a supported majority, and is inactive
# when no supported path cluster exists.
def motion_pair_distance(a, b):
    return 0.5 * (math.dist(a[0], b[0]) + math.dist(a[1], b[1]))


def motion_medoid(samples):
    best = None
    best_cost = float("inf")
    for candidate in samples:
        total = sum(sample[2] for sample in samples)
        cost = (sum(sample[2] * motion_pair_distance(candidate, sample) for sample in samples) / total
                if total > 0.0 else float("inf"))
        if best is None or cost < best_cost or (cost == best_cost and candidate[2] > best[2]):
            best, best_cost = candidate, cost
    agreement = sum(
        sample[2] * (1.0 - smoothstep(0.65, 2.8, motion_pair_distance(best, sample)))
        for sample in samples
    )
    selected_support = best[2] * min(1.0, max(0.0, agreement / 3.0))
    return best, selected_support


pan_forward = (-4.0, 0.5)
pan_backward = (4.0, -0.5)
stable_pan = [(pan_forward, pan_backward, 0.9)] * 5
pan_medoid, pan_support = motion_medoid(stable_pan)
assert pan_medoid == stable_pan[0]
assert pan_support == 0.9
assert motion_pair_distance(pan_medoid, stable_pan[0]) == 0.0

def medoid_fallback_activation(trust, boundary_activation, local_quality):
    return trust < 0.999 and boundary_activation > 0.0 and local_quality < 0.70


assert not medoid_fallback_activation(0.9, 0.0, 0.0)
assert not medoid_fallback_activation(1.0, 1.0, 0.0), "stable trusted pan does not run the fallback"
assert not medoid_fallback_activation(0.2, 1.0, 0.9), "coherent local fields remain untouched"
assert medoid_fallback_activation(0.05, 1.0, 0.2), "only a weak, inconsistent boundary enters consensus"

stable_background_pair = ((-3.0, 0.0), (3.0, 0.0), 0.9)
unstable_local_pair = ((8.0, -7.0), (-2.0, 5.0), 0.02)
local_outlier = [unstable_local_pair] + [stable_background_pair] * 4
local_medoid, local_support = motion_medoid(local_outlier)
assert local_medoid == stable_background_pair
assert local_support > 0.89
fallback_weight = (1.0 - 0.05) * local_support * smoothstep(
    0.5, 1.5, motion_pair_distance(unstable_local_pair, local_medoid)
) * (1.0 - smoothstep(0.03, 0.30, 0.0))
assert fallback_weight > 0.85
no_supported_paths = [(pan_forward, pan_backward, 0.02)] * 5
_, no_support = motion_medoid(no_supported_paths)
assert no_support < 0.04
assert no_support * 0.95 < 0.04, "unstable local paths cannot create a strong fallback"

# Path stability caps primary trust only when the inverse path diverges; a stable
# high-motion trajectory keeps the same warp contribution.
def cap_primary_trust(primary: float, path_quality: float) -> float:
    return min(primary, path_quality)


def warp_support(primary: float, path_quality: float, geometry: float = 1.0) -> float:
    return cap_primary_trust(primary, path_quality) * geometry


def reliable_support(reliable: float, path_quality: float, geometry: float = 1.0) -> float:
    return reliable * path_quality * geometry


def combined_synthesis_trust(primary0: float, primary1: float, reliable0: float, reliable1: float) -> float:
    return max(min(primary0, primary1), min(reliable0, reliable1))


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
assert math.isclose(cap_primary_trust(0.92, stable_path), 0.92, abs_tol=1e-12)
assert cap_primary_trust(0.92, boundary_path) == 0.0
partial_path = trajectory_weight((0.0, 0.0), (0.3125, 0.0), 1.0)
assert math.isclose(cap_primary_trust(0.92, partial_path), 0.5, abs_tol=1e-12)
assert math.isclose(base_trusted_support, 0.72, abs_tol=1e-12)
assert math.isclose(stable_path, 1.0, abs_tol=1e-12)
assert boundary_path == 0.0
assert gated_uncertain_support == 0.0
unstable_forward = trajectory_weight((12.0, 0.0), (-4.0, 0.0), 0.5)
stable_backward = trajectory_weight((8.0, 0.0), (8.1, 0.0), 1.0)
primary_forward = warp_support(0.99, unstable_forward)
primary_backward = warp_support(0.99, stable_backward)
reliable_forward = reliable_support(0.99, unstable_forward)
reliable_backward = reliable_support(0.99, stable_backward)
assert combined_synthesis_trust(primary_forward, primary_backward, reliable_forward, reliable_backward) <= 0.001, (
    "high local match confidence must not bypass an unstable inverse trajectory"
)
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
assert not retained_changed and retained_result == retained_source, "retained snapshot should mirror active refinement, grid, synthesis, reconstruction, warp, and scene-cut safeguards"
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
    assert PATCHER.CAMERA_SIMILARITY_MARKER in active_source
    assert PATCHER._has_camera_similarity_model(active_source)
    if MOTION_CAPTURE_PATCHER.SOURCE_MARKER in active_source:
        assert active_source.count(PATCHER.MARKER) == 1
        assert active_source.count(PATCHER.SOURCE_VISIBILITY_MARKER) == 1
        assert active_source.count(PATCHER.SPATIAL_VISIBILITY_CAP_MARKER) == 1
        assert PATCHER.LOCAL_SOFTENING_MARKER in active_source
        assert PATCHER.RECONSTRUCTION_MARKER in active_source
        assert PATCHER.WARP_TRAJECTORY_MARKER in active_source
        assert PATCHER.FINE_GRID_MARKER in active_source
        assert PATCHER.REFINEMENT_APERTURE_NORMAL_MARKER in active_source
        assert PATCHER.SCENE_CUT_MARKER in active_source
        assert "nearest_frame_weight" not in active_source
    else:
        active_result, active_changed = PATCHER.transform_source(active_source)
        assert not active_changed and active_result == active_source, "active pinned shader must contain the verified refinement, grid, synthesis, reconstruction, and warp patches exactly once"
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
        assert "float applied_trust = clamp(visibility_sum, 0.0, 1.0);" in active_source
        assert "vec4(applied_trust, applied_trust, applied_trust, 1.0)" in active_source
        assert "source_blend * (1.0 - applied_trust) +" in active_source
        assert "warped0 * visibility.x + warped1 * visibility.y" in active_source
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
    "with trajectory stability capping primary warp confidence and no per-pixel temporal source switch; "
    "spatial cut detection flags palette-matched scene changes without firing on one-tile motion; "
    f"the high-resolution 480-cap finest grid uses {fine_grid[0]}x{fine_grid[1]} cells instead of "
    f"{previous_high_res_grid[0]}x{previous_high_res_grid[1]} "
    f"({1.0 - (fine_grid[0] * fine_grid[1]) / (previous_high_res_grid[0] * previous_high_res_grid[1]):.1%} fewer matches), "
    f"retaining a {fine_mix_band:.0f}px mixed-flow band versus {previous_fine_mix_band:.0f}px at the prior 4px grid; "
    "mid/coarse levels remain unchanged"
)
assert "sampling_position = diagnostic_mode == 0 ? vec2(pixel) : source_position;" in motion_capture_patcher_source
assert "original0 = texelFetch(source0, source_pixel, 0);" in motion_capture_patcher_source
assert "sample_rgba(source0, sampling_position + f.xy * scale)" in motion_capture_patcher_source
assert "sample_rgba(source1, sampling_position + r.xy * scale - local_motion_offset)" in motion_capture_patcher_source
