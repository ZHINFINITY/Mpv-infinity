/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include <math.h>
#include <stdlib.h>

#include <libplacebo/dispatch.h>
#include <libplacebo/shaders/custom.h>

#include "motion.h"

#define LEVELS 3
#define MATCH_GROUP_SIZE 64
#define MATCH_SHARED_BYTES (49 * 16 + 169 * 4)
#define GLSL(...) #__VA_ARGS__

struct mp_motion {
    pl_gpu gpu;
    pl_fmt format;
    pl_dispatch dispatch;
    pl_renderer renderer;
    pl_tex images[LEVELS][2];
    pl_tex vectors[LEVELS][2];
    pl_tex evidence[2];
    pl_tex reconstructed[2];
    pl_tex warps[2];
    pl_tex scene;
    pl_tex result;
    int max_dimension;
    int source_width, source_height;
    uint64_t ids[2];
    bool pair_valid;
    bool result_valid;
    float last_alpha;
    struct mp_motion_stats stats;
    int timing_stage;
};

static const char sample_header[] = GLSL(
    vec4 sample_rgba(sampler2D image, vec2 p)
    {
        ivec2 size = textureSize(image, 0);
        p = clamp(p, vec2(0), vec2(size - 1));
        ivec2 lo = ivec2(floor(p));
        ivec2 hi = min(lo + ivec2(1), size - 1);
        vec2 fraction = p - vec2(lo);
        vec4 a = texelFetch(image, lo, 0);
        vec4 b = texelFetch(image, ivec2(hi.x, lo.y), 0);
        vec4 c = texelFetch(image, ivec2(lo.x, hi.y), 0);
        vec4 d = texelFetch(image, hi, 0);
        return mix(mix(a, b, fraction.x), mix(c, d, fraction.x), fraction.y);
    }

    vec3 sample_color(sampler2D image, vec2 p)
    {
        return sample_rgba(image, p).rgb;
    }
);

static const char match_header[] = GLSL(
    shared vec3 reference_colors[49];
    shared float candidate_cost[169];
    float difference(sampler2D second, vec2 p, vec2 delta,
                     float limit)
    {
        float sum = 0.0;
        for (int y = -3; y <= 3; y++) {
            for (int x = -3; x <= 3; x++) {
                vec2 q = p + vec2(x, y);
                vec3 a = reference_colors[(y + 3) * 7 + x + 3];
                vec3 b = sample_color(second, q + delta);
                vec3 d = abs(a - b);
                sum += dot(d, vec3(0.25)) +
                       0.25 * abs(dot(a - b, vec3(0.2126, 0.7152, 0.0722)));
                if (sum > limit * 49.0)
                    return sum / 49.0;
            }
        }
        return sum / 49.0;
    }

    float refine(float left, float center, float right)
    {
        float curvature = left + right - 2.0 * center;
        return curvature > 0.000001
             ? clamp(0.5 * (left - right) / curvature, -0.5, 0.5) : 0.0;
    }
);

static const char match_body[] = GLSL(
    vec2 uv = (vec2(gl_WorkGroupID.xy) + 0.5) / vec2(settings.xy);
    ivec2 size = textureSize(first, 0);
    vec2 p = uv * vec2(size) - 0.5;
    int lane = int(gl_LocalInvocationIndex);
    if (lane < 49)
        reference_colors[lane] = sample_color(first,
            p + vec2(lane % 7 - 3, lane / 7 - 3));
    // Every candidate uses the same reference patch.
    barrier();
    int side = settings.z * 2 + 1;
    for (int index = lane; index < side * side; index += 64) {
        ivec2 delta = ivec2(index % side, index / side) - settings.zz;
        vec2 q = p + vec2(delta);
        candidate_cost[index] = any(lessThan(q, vec2(0))) ||
                               any(greaterThan(q, vec2(size - 1)))
                             ? 1e20 : difference(second, p, vec2(delta), 1e20);
    }
    // Retain search order and tie handling when selecting the best two costs.
    barrier();
    if (lane == 0) {
        ivec2 best_vector = ivec2(0);
        float best = candidate_cost[settings.z * side + settings.z];
        float runner_up = 1e20;
        for (int y = -settings.z; y <= settings.z; y++) {
            for (int x = -settings.z; x <= settings.z; x++) {
                ivec2 delta = ivec2(x, y);
                if (x == 0 && y == 0)
                    continue;
                vec2 q = p + vec2(delta);
                if (any(lessThan(q, vec2(0))) ||
                    any(greaterThan(q, vec2(size - 1))))
                    continue;
                float cost = candidate_cost[(y + settings.z) * side + x + settings.z];
                if (cost < best || (cost == best &&
                    abs(delta.x) + abs(delta.y) <
                    abs(best_vector.x) + abs(best_vector.y))) {
                    runner_up = best;
                    best = cost;
                    best_vector = delta;
                } else {
                    runner_up = min(runner_up, cost);
                }
            }
        }
        vec2 vector = vec2(best_vector);
        if (best > 0.000001) {
            vec2 subpixel;
            subpixel.x = refine(
                difference(second, p, vector - vec2(1, 0), 1e20), best,
                difference(second, p, vector + vec2(1, 0), 1e20));
            subpixel.y = refine(
                difference(second, p, vector - vec2(0, 1), 1e20), best,
                difference(second, p, vector + vec2(0, 1), 1e20));
            vec2 candidate = clamp(vector + subpixel, -p, vec2(size - 1) - p);
            float cost = difference(second, p, candidate, 1e20);
            if (cost < best) {
                vector = candidate;
                best = cost;
            }
        }
        float uniqueness = clamp((runner_up - best) / max(runner_up, 0.000001),
                                 0.0, 1.0);
        imageStore(out_image, ivec2(gl_WorkGroupID.xy),
                   vec4(vector * motion_scale, best, uniqueness));
    }
);

static const char refine_header[] = GLSL(
    // Keep FP32 ordering scores exact in the supported RGBA16F format.
    vec4 pack_evidence(float evidence)
    {
        uint bits = floatBitsToUint(evidence);
        return vec4(uvec4(bits, bits >> 8, bits >> 16, bits >> 24) & uvec4(255));
    }

    float unpack_evidence(vec4 evidence)
    {
        uvec4 bytes = uvec4(evidence);
        uint bits = bytes.x | (bytes.y << 8) | (bytes.z << 16) |
                    (bytes.w << 24);
        return uintBitsToFloat(bits);
    }

    // The first patch is identical for every parent, LK and axis candidate.
    vec3 reference_colors[16];

    // Central differences stay inside the matching patch extent.
    vec3 patch_tensor(sampler2D first, vec2 p)
    {
        float xx = 0.0, xy = 0.0, yy = 0.0;
        for (int y = -2; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                vec2 q = p + vec2(x, y);
                vec3 dx = sample_color(first, q + vec2(1, 0)) -
                          sample_color(first, q - vec2(1, 0));
                vec3 dy = sample_color(first, q + vec2(0, 1)) -
                          sample_color(first, q - vec2(0, 1));
                xx += dot(dx, dx);
                xy += dot(dx, dy);
                yy += dot(dy, dy);
            }
        }
        return vec3(xx, xy, yy);
    }

    float predictor_strength(sampler2D image, vec2 p)
    {
        vec3 center = sample_color(image, p);
        vec3 contrast = max(abs(center - sample_color(image, p + vec2(6, 0))),
                            abs(center - sample_color(image, p - vec2(6, 0))));
        contrast = max(contrast, abs(center - sample_color(image, p + vec2(0, 6))));
        contrast = max(contrast, abs(center - sample_color(image, p - vec2(0, 6))));
        return max(contrast.r, max(contrast.g, contrast.b));
    }

    vec2 dense_pair_cost(sampler2D first, sampler2D second, vec2 p, vec2 vector)
    {
        vec2 cost = vec2(0);
        for (int y = -3; y <= 3; y++) {
            for (int x = -3; x <= 3; x++) {
                vec2 q = p + vec2(x, y);
                vec3 source = sample_color(first, q);
                vec3 zero_error = source - sample_color(second, q);
                vec3 motion_error = source - sample_color(second, q + vector);
                cost.x += dot(abs(zero_error), vec3(0.25)) +
                    0.25 * abs(dot(zero_error, vec3(0.2126, 0.7152, 0.0722)));
                cost.y += dot(abs(motion_error), vec3(0.25)) +
                    0.25 * abs(dot(motion_error, vec3(0.2126, 0.7152, 0.0722)));
            }
        }
        return cost / 49.0;
    }

    float dense_cost(sampler2D first, sampler2D second, vec2 p, vec2 vector,
                     float limit)
    {
        float cost = 0.0;
        for (int y = -3; y <= 3; y++) {
            for (int x = -3; x <= 3; x++) {
                vec2 q = p + vec2(x, y);
                vec3 error = sample_color(first, q) -
                             sample_color(second, q + vector);
                cost += dot(abs(error), vec3(0.25)) +
                        0.25 * abs(dot(error, vec3(0.2126, 0.7152, 0.0722)));
                if (cost / 49.0 > limit)
                    return cost / 49.0;
            }
        }
        return cost / 49.0;
    }

    float patch_cost(sampler2D second, vec2 p, vec2 vector)
    {
        float cost = 0.0;
        for (int y = -3; y <= 3; y += 2) {
            for (int x = -3; x <= 3; x += 2) {
                vec2 q = p + vec2(x, y);
                vec3 error = reference_colors[((y + 3) / 2) * 4 + (x + 3) / 2] -
                             sample_color(second, q + vector);
                cost += dot(abs(error), vec3(0.25)) +
                        0.25 * abs(dot(error, vec3(0.2126, 0.7152, 0.0722)));
            }
        }
        return cost / 16.0;
    }
);

// Select observed parent vectors before refinement. A flat child patch can
// use a neighbor supported by visible structure, without averaging motions
// across independent surfaces into a predictor belonging to neither.
static const char refine_body[] = GLSL(
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (all(lessThan(pixel, settings.xy))) {
        vec2 uv = (vec2(gl_GlobalInvocationID.xy) + 0.5) / vec2(settings.xy);
        vec2 size = vec2(textureSize(first, 0));
        vec2 p = uv * size - 0.5;
        for (int y = -3; y <= 3; y += 2) {
            for (int x = -3; x <= 3; x += 2)
                reference_colors[((y + 3) / 2) * 4 + (x + 3) / 2] =
                    sample_color(first, p + vec2(x, y));
        }
        vec3 tensor = vec3(0);
        bool tensor_valid = false;
        ivec2 parent_size = textureSize(parent, 0);
        ivec2 nearest = clamp(ivec2(uv * vec2(parent_size)), ivec2(0), parent_size - 1);
        vec4 inherited = texelFetch(parent, nearest, 0);
        vec2 seed = clamp(inherited.xy / motion_scale, -p, size - 1.0 - p);
        float best = patch_cost(second, p, seed);
        float strength = predictor_strength(first,
            (vec2(nearest) + 0.5) / vec2(parent_size) * size - 0.5);
        ivec2 base = ivec2(floor(uv * vec2(parent_size) - 0.5));
        // Include one parent ring to carry observed motion across flat support.
        for (int y = -1; y <= 2; y++) {
            for (int x = -1; x <= 2; x++) {
                ivec2 q = clamp(base + ivec2(x, y), ivec2(0), parent_size - 1);
                vec4 candidate = texelFetch(parent, q, 0);
                vec2 vector = clamp(candidate.xy / motion_scale, -p, size - 1.0 - p);
                if (all(equal(vector, seed)))
                    continue;
                float cost = patch_cost(second, p, vector);
                float support = predictor_strength(first,
                    (vec2(q) + 0.5) / vec2(parent_size) * size - 0.5);
                bool preferred = support > strength;
                // Flat support inherits structure. Observed ties retain the shorter
                // normal displacement instead of jumping to a repeated edge.
                if (cost == best) {
                    if (!tensor_valid) {
                        tensor = patch_tensor(first, p);
                        tensor_valid = true;
                    }
                    if (tensor.x + tensor.z > 0.000001) {
                        vec2 normal = tensor.x > tensor.z
                            ? vec2(1, tensor.y / tensor.x)
                            : vec2(tensor.y / tensor.z, 1);
                        float candidate_motion = abs(dot(normal, vector));
                        float seed_motion = abs(dot(normal, seed));
                        preferred = candidate_motion < seed_motion ||
                            (candidate_motion == seed_motion && support > strength);
                    }
                }
                if (cost < best || (cost == best && preferred)) {
                    best = cost;
                    strength = support;
                    seed = vector;
                    inherited = candidate;
                }
            }
        }
        vec2 vector = seed;
        float residual = 0.0;
        for (int iteration = 0; iteration < 3 && best > 0.000001; iteration++) {
            residual = 0.0;
            float xx = 0.000001;
            float xy = 0.0;
            float yy = 0.000001;
            vec2 rhs = vec2(0.0);
            for (int y = -3; y <= 3; y += 2) {
                for (int x = -3; x <= 3; x += 2) {
                    vec2 q = p + vec2(x, y);
                    vec2 position = q + vector;
                    vec3 error = sample_color(second, position) -
                                 reference_colors[((y + 3) / 2) * 4 + (x + 3) / 2];
                    residual += dot(abs(error), vec3(1.0 / 3.0));
                    vec3 dx = 0.5 * (sample_color(second, position + vec2(1, 0)) -
                                      sample_color(second, position - vec2(1, 0)));
                    vec3 dy = 0.5 * (sample_color(second, position + vec2(0, 1)) -
                                      sample_color(second, position - vec2(0, 1)));
                    xx += dot(dx, dx);
                    xy += dot(dx, dy);
                    yy += dot(dy, dy);
                    rhs += vec2(dot(dx, error), dot(dy, error));
                }
            }
            if (residual < 0.000001)
                break;
            float determinant = xx * yy - xy * xy;
            if (determinant <= 0.000000000001)
                break;
            vec2 step = vec2(yy * rhs.x - xy * rhs.y,
                              xx * rhs.y - xy * rhs.x) / determinant;
            vec2 next = clamp(vector - step, seed - vec2(settings.z),
                               seed + vec2(settings.z));
            next = clamp(next, -p, size - 1.0 - p);
            if (any(isnan(next)) || any(isinf(next)))
                break;
            float change = length(next - vector);
            vector = next;
            if (change < 0.001)
                break;
        }
        residual = patch_cost(second, p, vector);
        // Exact zero motion is already the unique closest zero-cost candidate.
        if (any(notEqual(vector, vec2(0))) || residual != 0.0) {
            // Choose an equivalent lattice representative before interpolation mixes
            // tangentially ambiguous vectors. Sampled edges need not produce an
            // exactly rank-one structure tensor.
            if (!tensor_valid)
                tensor = patch_tensor(first, p);
            float xx = tensor.x, xy = tensor.y, yy = tensor.z;
            float trace = xx + yy;
            if (trace > 0.000001) {
                vec2 axis = xx > yy ? vec2(1, 0) : vec2(0, 1);
                float projected = xx > yy ? vector.x + xy / xx * vector.y
                                          : vector.y + xy / yy * vector.x;
                // Bound acceptance by the original minimum, then rank candidates.
                float limit = residual + 0.000001;
                float lowest = 1e20;
                float distance = 1e20;
                float magnitude = 1e20;
                vec2 selected = vector;
                bool unique = false;
                for (int offset = -1; offset <= 1; offset++) {
                    vec2 candidate = clamp(axis * (round(projected) + float(offset)),
                                           -p, size - 1.0 - p);
                    float cost = patch_cost(second, p, candidate);
                    float delta = abs(dot(candidate, axis) - projected);
                    float norm = dot(candidate, candidate);
                    if (cost > limit)
                        continue;
                    if (cost < lowest || (cost == lowest &&
                        (delta < distance ||
                         (delta == distance && norm < magnitude)))) {
                        lowest = cost;
                        distance = delta;
                        magnitude = norm;
                        selected = candidate;
                        unique = true;
                    } else if (cost == lowest && delta == distance &&
                               norm == magnitude &&
                               any(notEqual(candidate, selected))) {
                        unique = false;
                    }
                }
                if (unique) {
                    vector = selected;
                    residual = lowest;
                }
            }
        }
        // Dense support can observe motion missed by central-difference tensors.
        // Resolve equivalent axis vectors without increasing the original cost.
        if (all(equal(motion_scale, vec2(1))) && any(notEqual(vector, vec2(0)))) {
            vec2 cost = dense_pair_cost(first, second, p, vector);
            if (cost.x > cost.y) {
                float limit = cost.y + 0.000001;
                vec2 original = vector;
                float magnitude = dot(vector, vector);
                float distance = 0.0;
                for (int index = 0; index < 2 * (2 * settings.z + 1); index++) {
                    int offset = index % (2 * settings.z + 1) - settings.z;
                    vec2 axis = index < 2 * settings.z + 1 ? vec2(1, 0) : vec2(0, 1);
                    vec2 candidate = clamp(axis * float(offset), -p, size - 1.0 - p);
                    float norm = dot(candidate, candidate);
                    float delta = dot(candidate - original, candidate - original);
                    if ((norm < magnitude || (norm == magnitude && delta < distance)) &&
                        dense_cost(first, second, p, candidate, limit) <= limit) {
                        vector = candidate;
                        magnitude = norm;
                        distance = delta;
                    }
                }
                residual = patch_cost(second, p, vector);
            }
        }
        color = vec4(vector * motion_scale, residual, inherited.w);
    }
);

static const char evidence_body[] = GLSL(
    ivec2 grid = textureSize(forward, 0);
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (all(lessThan(pixel, grid))) {
        vec2 position = (vec2(pixel) + 0.5) / vec2(grid);
        vec2 size = vec2(textureSize(first, 0));
        vec2 center = position * size - 0.5;
        vec4 value = texelFetch(forward, pixel, 0);
        float evidence = 0.0;
        if (any(notEqual(value.xy, vec2(0)))) {
            vec2 cost = dense_pair_cost(first, second, center, value.xy);
            evidence = max(0.0, cost.x - cost.y);
        }
        color = pack_evidence(evidence);
    }
);

static const char reconstruction_body[] = GLSL(
    ivec2 grid = textureSize(forward, 0);
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (all(lessThan(pixel, grid))) {
        vec2 uv = (vec2(pixel) + 0.5) / vec2(grid);
        vec2 size = vec2(textureSize(first, 0));
        vec2 p = uv * size - 0.5;
        for (int y = -3; y <= 3; y += 2) {
            for (int x = -3; x <= 3; x += 2)
                reference_colors[((y + 3) / 2) * 4 + (x + 3) / 2] =
                    sample_color(first, p + vec2(x, y));
        }
        // Select an observed vector; averaging aliases can invent invalid motion.
        vec4 selected = texelFetch(forward, pixel, 0);
        float limit = patch_cost(second, p, selected.xy) + 0.000001;
        float best = -1.0;
        float nearest = 1e20;
        for (int y = -2; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                ivec2 q = clamp(pixel + ivec2(x, y), ivec2(0), grid - 1);
                float evidence = unpack_evidence(texelFetch(evidence_map, q, 0));
                float distance = float(x * x + y * y);
                // MPVFLOW_RECONSTRUCTION_POSITIVE_EVIDENCE_V1: only propagate measured motion.
                if (!(evidence > 0.0 &&
                      (evidence > best || (evidence == best && distance < nearest))))
                    continue;
                vec2 position = (vec2(q) + 0.5) / vec2(grid);
                vec4 value = texelFetch(forward, q, 0);
                vec4 reverse = textureLod(backward,
                                          position + value.xy / size, 0.0);
                if (any(isnan(value)) || any(isinf(value)) ||
                    any(isnan(reverse)) || any(isinf(reverse)))
                    continue;
                float cycle = length(value.xy + reverse.xy);
                if (cycle >= 1.5 || patch_cost(second, p, value.xy) > limit)
                    continue;
                selected = value;
                best = evidence;
                nearest = distance;
            }
        }
        color = vec4(selected.xy, patch_cost(second, p, selected.xy), selected.w);
    }
);

static const char scene_body[] = GLSL(
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
    // A global brightness change contributes matching error without a cut.
    float brightness_error = dot(mean_distance, vec3(0.25)) +
        0.25 * abs(dot((mean0 - mean1) / 256.0, vec3(0.2126, 0.7152, 0.0722)));
    color = vec4(cut, max(0.0, residual / 256.0 - brightness_error), 0.0, 1.0);
);

static const char synthesis_header[] = GLSL(
    float support(vec2 p, vec2 size)
    {
        vec2 distance = min(p, 1.0 - p) * size;
        return clamp(min(distance.x, distance.y), 0.0, 1.0);
    }

    float quality(vec4 vector)
    {
        return (1.0 - smoothstep(0.01, 0.08, vector.z)) *
               smoothstep(0.01, 0.15, vector.w);
    }

    float neighborhood_quality(sampler2D field, vec2 uv, vec2 center)
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
    }
);

static const char warp_body[] = GLSL(
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
    float trust = quality(f);
    float reliable = 1.0 - smoothstep(0.001, 0.01, max(f.z, reverse.z));
    reliable *= 1.0 - smoothstep(0.1, 0.5, cycle);
    float geometry = (1.0 - smoothstep(0.5, 1.5, cycle)) *
                     (1.0 - smoothstep(0.25, 1.0, residual));
    geometry *= min(support(source, map_size), support(source + f.xy, map_size));
    if (any(isnan(f)) || any(isinf(f)) ||
        any(isnan(reverse)) || any(isinf(reverse)) ||
        isnan(geometry) || isinf(geometry))
        geometry = 0.0;
    // Use the solved vector directly. Subtracting normalized coordinates
    // loses precision before half-float storage, even for integer translations.
    color = vec4(displacement,
                 trust * geometry, reliable * geometry);
);

static const char synthesis_body[] = GLSL(
    vec2 uv = (vec2(gl_GlobalInvocationID.xy) + 0.5) / output_size;
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    vec2 source_position = uv * source_size - 0.5;
    vec4 original0 = sample_rgba(first, source_position);
    vec4 original1 = sample_rgba(second, source_position);
    vec2 scene_change = texelFetch(scene, ivec2(0), 0).rg;
    if (scene_change.r > 0.45 || scene_change.g > 0.08) {
        color = alpha < 0.5 ? original0 : original1;
    } else {
        vec4 f = textureLod(forward, uv, 0.0);
        vec4 r = textureLod(backward, uv, 0.0);
        if (any(isnan(f)) || any(isinf(f)) ||
            any(isnan(r)) || any(isinf(r))) {
            f = vec4(0.0);
            r = vec4(0.0);
        }
        // Match the photometric data term in source pixel coordinates.
        vec2 scale = source_size / motion_size;
        vec4 warped0 = sample_rgba(first, source_position + f.xy * scale);
        vec4 warped1 = sample_rgba(second, source_position + r.xy * scale);
        vec3 difference = warped0.rgb - warped1.rgb;
        vec3 conflict = abs(difference);
        float color_error = max(conflict.r, max(conflict.g, conflict.b));
        float luma_error = abs(dot(warped0.rgb, vec3(0.299, 0.587, 0.114)) -
                               dot(warped1.rgb, vec3(0.299, 0.587, 0.114)));
        float chroma_error = max(abs((warped0.r - warped0.g) - (warped1.r - warped1.g)),
                                 abs((warped0.b - warped0.g) - (warped1.b - warped1.g)));
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
        // Sample local vectors only where photometric disagreement makes a seam likely.
        if (boundary_activation > 0.0) {
            float local_quality = min(neighborhood_quality(forward, uv, f.xy),
                                      neighborhood_quality(backward, uv, r.xy));
            boundary_trust *= mix(1.0, smoothstep(0.45, 0.70, local_quality),
                                  boundary_activation);
            region3_local_trust = mix(1.0, smoothstep(0.30, 0.75, local_quality),
                                      boundary_activation);
        }
        // Fade toward source-frame crossfade on conflict instead of forming block seams.
        // MPVFLOW_ARTIFACT_GUARDS_V3 keeps the color taper continuous through 0.30.
        trust *= 1.0 - smoothstep(0.03, 0.30,
                                   color_error);
        trust *= boundary_trust * region3_local_trust;
        if (isnan(trust) || isinf(trust))
            trust = 0.0;
        color = mix(mix(original0, original1, alpha),
                     mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));
    }
);

static bool recreate(struct mp_motion *m, pl_tex *texture, int w, int h)
{
    return pl_tex_recreate(m->gpu, texture, pl_tex_params(
        .w = w, .h = h,
        .format = m->format,
        .sampleable = true,
        .renderable = true,
        .storable = true,
    ));
}

static struct pl_shader_desc sampled(const char *name, pl_tex texture)
{
    return (struct pl_shader_desc) {
        .desc = { .name = name, .type = PL_DESC_SAMPLED_TEX },
        .binding = {
            .object = texture,
            .sample_mode = PL_TEX_SAMPLE_LINEAR,
            .address_mode = PL_TEX_ADDRESS_CLAMP,
        },
    };
}

static pl_shader begin_dispatch(struct mp_motion *m,
                                struct pl_custom_shader *custom)
{
    pl_shader shader = pl_dispatch_begin(m->dispatch);
    if (!pl_shader_custom(shader, &(struct pl_custom_shader) {
            .header = sample_header,
        }) || !pl_shader_custom(shader, custom)) {
        pl_dispatch_abort(m->dispatch, &shader);
        return NULL;
    }
    return shader;
}

static bool dispatch(struct mp_motion *m, pl_tex target,
                     struct pl_custom_shader *custom)
{
    custom->output = PL_SHADER_SIG_COLOR;
    custom->compute = true;
    custom->compute_group_size[0] = target->params.w == 1 ? 1 : 8;
    custom->compute_group_size[1] = target->params.h == 1 ? 1 : 8;
    pl_shader shader = begin_dispatch(m, custom);
    return shader && pl_dispatch_finish(m->dispatch, pl_dispatch_params(
        .shader = &shader, .target = target,
    ));
}

static bool dispatch_matching(struct mp_motion *m, pl_tex target,
                              struct pl_custom_shader *custom)
{
    custom->output = PL_SHADER_SIG_NONE;
    custom->compute = true;
    custom->compute_group_size[0] = MATCH_GROUP_SIZE;
    custom->compute_group_size[1] = 1;
    custom->compute_shmem = MATCH_SHARED_BYTES;
    pl_shader shader = begin_dispatch(m, custom);
    return shader && pl_dispatch_compute(m->dispatch, pl_dispatch_compute_params(
        .shader = &shader,
        .dispatch_size = {target->params.w, target->params.h, 1},
    ));
}

static bool downsample(struct mp_motion *m, pl_tex source, pl_tex target)
{
    m->timing_stage = -1;
    struct pl_shader_desc desc = sampled("source", source);
    float size[] = {target->params.w, target->params.h};
    struct pl_shader_var var = {.var = pl_var_vec2("output_size"), .data = size};
    return dispatch(m, target, &(struct pl_custom_shader) {
        .description = "motion pyramid sampling",
        .body = GLSL(
            vec2 p = (vec2(gl_GlobalInvocationID.xy) + 0.5) *
                     vec2(textureSize(source, 0)) / output_size - 0.5;
            color = vec4(sample_color(source, p), 1.0);
        ),
        .descriptors = &desc, .num_descriptors = 1,
        .variables = &var, .num_variables = 1,
    });
}

static bool analyze(struct mp_motion *m, pl_tex first, pl_tex second,
                    uint64_t first_id, uint64_t second_id)
{
    bool valid = m->pair_valid && m->source_width == first->params.w &&
                 m->source_height == first->params.h;
    if (valid && m->ids[0] == first_id && m->ids[1] == second_id)
        return true;
    bool reuse[2] = {
        valid && m->ids[0] == first_id,
        valid && m->ids[1] == second_id,
    };
    if (valid && (m->ids[1] == first_id || m->ids[0] == second_id)) {
        for (int level = 0; level < LEVELS; level++) {
            pl_tex image = m->images[level][0];
            m->images[level][0] = m->images[level][1];
            m->images[level][1] = image;
        }
        reuse[0] = m->ids[1] == first_id;
        reuse[1] = m->ids[0] == second_id;
    }
    m->pair_valid = false;
    m->result_valid = false;
    int width = first->params.w;
    int height = first->params.h;
    int largest = width > height ? width : height;
    double scale = largest > m->max_dimension
                 ? (double) m->max_dimension / largest : 1.0;
    width = fmax(8, lround(width * scale));
    height = fmax(8, lround(height * scale));
    pl_tex inputs[2] = {first, second};
    for (int level = 0; level < LEVELS; level++) {
        int spacing = level == LEVELS - 1 ? 4 : 8;
        for (int direction = 0; direction < 2; direction++) {
            if (!recreate(m, &m->images[level][direction], width, height) ||
                !recreate(m, &m->vectors[level][direction],
                          (width + spacing - 1) / spacing,
                          (height + spacing - 1) / spacing) ||
                (!reuse[direction] &&
                 !downsample(m, inputs[direction], m->images[level][direction])))
                return false;
            inputs[direction] = m->images[level][direction];
        }
        width = (width + 1) / 2;
        height = (height + 1) / 2;
    }

    for (int level = LEVELS - 1; level >= 0; level--) {
        for (int direction = 0; direction < 2; direction++) {
            pl_tex target = m->vectors[level][direction];
            bool parent = level + 1 < LEVELS;
            struct pl_shader_desc desc[3] = {
                sampled("first", m->images[level][direction]),
                sampled("second", m->images[level][!direction]),
            };
            if (parent) {
                desc[2] = sampled("parent", m->vectors[level + 1][direction]);
            } else {
                desc[2] = (struct pl_shader_desc) {
                    .desc = {
                        .name = "out_image", .type = PL_DESC_STORAGE_IMG,
                        .access = PL_DESC_ACCESS_WRITEONLY,
                    },
                    .binding.object = target,
                };
            }
            int settings[] = {target->params.w, target->params.h,
                              parent ? 2 : 6};
            // Finest-analysis pixel units retain exact integer motion in
            // RGBA16F, including aspect ratios with nonbinary reciprocals.
            float motion_scale[] = {
                (float) m->images[0][direction]->params.w /
                    m->images[level][direction]->params.w,
                (float) m->images[0][direction]->params.h /
                    m->images[level][direction]->params.h,
            };
            struct pl_shader_var variables[] = {
                {.var = pl_var_ivec3("settings"), .data = settings},
                {.var = pl_var_vec2("motion_scale"), .data = motion_scale},
            };
            m->timing_stage = level;
            struct pl_custom_shader custom = {
                .description = parent ? "motion optical refinement"
                                      : "motion block matching",
                .header = parent ? refine_header : match_header,
                .body = parent ? refine_body : match_body,
                .descriptors = desc, .num_descriptors = 3,
                .variables = variables, .num_variables = 2,
            };
            if (!(parent ? dispatch(m, target, &custom)
                         : dispatch_matching(m, target, &custom)))
                return false;
        }
    }
    for (int direction = 0; direction < 2; direction++) {
        pl_tex vectors = m->vectors[0][direction];
        if (!recreate(m, &m->evidence[direction],
                      vectors->params.w, vectors->params.h))
            return false;
        struct pl_shader_desc evidence_desc[] = {
            sampled("first", m->images[0][direction]),
            sampled("second", m->images[0][!direction]),
            sampled("forward", vectors),
        };
        m->timing_stage = LEVELS + 2;
        if (!dispatch(m, m->evidence[direction], &(struct pl_custom_shader) {
            .description = "motion observed evidence",
            .header = refine_header, .body = evidence_body,
            .descriptors = evidence_desc, .num_descriptors = 3,
        }))
            return false;
        if (!recreate(m, &m->reconstructed[direction],
                      vectors->params.w, vectors->params.h))
            return false;
        struct pl_shader_desc desc[] = {
            sampled("first", m->images[0][direction]),
            sampled("second", m->images[0][!direction]),
            sampled("forward", m->vectors[0][direction]),
            sampled("backward", m->vectors[0][!direction]),
            sampled("evidence_map", m->evidence[direction]),
        };
        m->timing_stage = LEVELS + 3;
        if (!dispatch(m, m->reconstructed[direction], &(struct pl_custom_shader) {
            .description = "motion field reconstruction",
            .header = refine_header, .body = reconstruction_body,
            .descriptors = desc, .num_descriptors = 5,
        }))
            return false;
    }
    struct pl_shader_desc scene_desc[] = {
        sampled("first", m->images[0][0]), sampled("second", m->images[0][1]),
        sampled("forward", m->vectors[0][0]), sampled("backward", m->vectors[0][1]),
    };
    m->timing_stage = -1;
    if (!recreate(m, &m->scene, 1, 1) ||
        !dispatch(m, m->scene, &(struct pl_custom_shader) {
            .description = "motion scene detection",
            .body = scene_body,
            .descriptors = scene_desc, .num_descriptors = 4,
        }))
        return false;
    m->source_width = first->params.w;
    m->source_height = first->params.h;
    m->ids[0] = first_id;
    m->ids[1] = second_id;
    m->pair_valid = true;
    m->stats.analyzed_pairs++;
    m->stats.analysis_width = m->images[0][0]->params.w;
    m->stats.analysis_height = m->images[0][0]->params.h;
    return true;
}

static bool valid_motion_inputs(struct mp_motion *m, pl_tex first, pl_tex second)
{
    return m && first && second &&
           first->params.w == second->params.w &&
           first->params.h == second->params.h &&
           first->params.w >= 8 && first->params.h >= 8 &&
           first->sampler_type == PL_SAMPLER_NORMAL &&
           second->sampler_type == PL_SAMPLER_NORMAL;
}

static bool matching_aspect(pl_tex analysis, pl_tex source)
{
    double a = (double) analysis->params.w / analysis->params.h;
    double b = (double) source->params.w / source->params.h;
    return fabs(a - b) <= fmax(a, b) * 0.005;
}

bool mp_motion_analyze_pair(struct mp_motion *m, pl_tex first, pl_tex second,
                            uint64_t first_id, uint64_t second_id)
{
    if (!valid_motion_inputs(m, first, second))
        return false;
    if (first == second)
        return true;
    pl_dispatch_reset_frame(m->dispatch);
    return analyze(m, first, second, first_id, second_id);
}

static void gpu_timing(void *priv, const struct pl_dispatch_info *info)
{
    struct mp_motion *m = priv;
    if (!info->num_samples)
        return;
    if (m->timing_stage == LEVELS - 1)
        m->stats.coarse_ns = info->average;
    else if (m->timing_stage >= 0 && m->timing_stage < LEVELS - 1)
        m->stats.refinement_ns = info->average;
    else if (m->timing_stage == LEVELS)
        m->stats.warp_ns = info->average;
    else if (m->timing_stage == LEVELS + 1)
        m->stats.synthesis_ns = info->average;
    else if (m->timing_stage == LEVELS + 2)
        m->stats.evidence_ns = info->average;
    else if (m->timing_stage == LEVELS + 3)
        m->stats.reconstruction_ns = info->average;
}

static void capture_timing(void *priv, const struct pl_render_info *info)
{
    struct mp_motion *m = priv;
    m->stats.capture_ns += info->pass->average;
}

static pl_fmt motion_format(pl_gpu gpu)
{
    if (!gpu || !gpu->glsl.compute ||
        gpu->glsl.max_group_threads < MATCH_GROUP_SIZE ||
        gpu->glsl.max_group_size[0] < MATCH_GROUP_SIZE ||
        gpu->glsl.max_group_size[1] < 8 ||
        gpu->glsl.max_shmem_size < MATCH_SHARED_BYTES)
        return NULL;
    return pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 16, 0,
        PL_FMT_CAP_SAMPLEABLE | PL_FMT_CAP_LINEAR |
        PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_STORABLE);
}

bool mp_motion_supported(pl_gpu gpu)
{
    return motion_format(gpu) != NULL;
}

struct mp_motion *mp_motion_create(pl_gpu gpu, int max_dimension)
{
    if (max_dimension < 16 || max_dimension > 1024)
        return NULL;
    pl_fmt format = motion_format(gpu);
    if (!format)
        return NULL;
    struct mp_motion *m = calloc(1, sizeof(*m));
    if (!m)
        return NULL;
    m->gpu = gpu;
    m->format = format;
    m->max_dimension = max_dimension;
    m->dispatch = pl_dispatch_create(gpu->log, gpu);
    m->timing_stage = -1;
    m->renderer = pl_renderer_create(gpu->log, gpu);
    if (!m->dispatch || !m->renderer) {
        mp_motion_destroy(&m);
        return NULL;
    }
    pl_dispatch_callback(m->dispatch, m, gpu_timing);
    return m;
}

void mp_motion_reset(struct mp_motion *m)
{
    if (m) {
        m->pair_valid = false;
        m->result_valid = false;
    }
}

struct mp_motion_stats mp_motion_get_stats(const struct mp_motion *m)
{
    return m ? m->stats : (struct mp_motion_stats) {0};
}

void mp_motion_destroy(struct mp_motion **motion)
{
    struct mp_motion *m = *motion;
    if (!m)
        return;
    for (int level = 0; level < LEVELS; level++) {
        for (int direction = 0; direction < 2; direction++) {
            pl_tex_destroy(m->gpu, &m->images[level][direction]);
            pl_tex_destroy(m->gpu, &m->vectors[level][direction]);
        }
    }
    for (int i = 0; i < 2; i++) {
        pl_tex_destroy(m->gpu, &m->evidence[i]);
        pl_tex_destroy(m->gpu, &m->reconstructed[i]);
        pl_tex_destroy(m->gpu, &m->warps[i]);
    }
    pl_tex_destroy(m->gpu, &m->scene);
    pl_tex_destroy(m->gpu, &m->result);
    pl_dispatch_destroy(&m->dispatch);
    pl_renderer_destroy(&m->renderer);
    free(m);
    *motion = NULL;
}

bool mp_motion_capture(struct mp_motion *m, const struct pl_frame *source,
                       int width, int height, pl_tex *texture)
{
    if (!m || !source || width < 8 || height < 8 ||
        !recreate(m, texture, width, height))
        return false;
    struct pl_frame input = *source;
    input.rotation = PL_ROTATION_0;
    input.overlays = NULL;
    input.num_overlays = 0;
    struct pl_frame target = {
        .num_planes = 1,
        .planes[0] = {
            .texture = *texture, .components = 4,
            .component_mapping = {0, 1, 2, 3},
        },
        .repr = pl_color_repr_rgb,
        .color = source->color,
    };
    struct pl_render_params params = pl_render_default_params;
    params.skip_caching_single_frame = true;
    params.dither_params = NULL;
    params.peak_detect_params = NULL;
    params.info_callback = capture_timing;
    params.info_priv = m;
    m->stats.capture_ns = 0;
    return pl_render_image(m->renderer, &input, &target, &params);
}

static bool render_motion(struct mp_motion *m,
                          pl_tex analysis_first, pl_tex analysis_second,
                          pl_tex first, pl_tex second,
                          uint64_t first_id, uint64_t second_id, float alpha,
                          pl_tex target, pl_tex *output)
{
    if (output)
        *output = NULL;
    if (!valid_motion_inputs(m, analysis_first, analysis_second) ||
        !valid_motion_inputs(m, first, second) ||
        !matching_aspect(analysis_first, first) ||
        !matching_aspect(analysis_second, second) || !isfinite(alpha) ||
        alpha <= 0 || alpha >= 1 || (!target && !output))
        return false;
    if (target && (target == first || target == second ||
        target->params.w < 8 || target->params.h < 8 ||
        !target->params.storable || !target->params.format ||
        !(target->params.format->caps & PL_FMT_CAP_STORABLE)))
        return false;
    if (first == second) {
        if (!target) {
            *output = first;
            return true;
        }
        return false;
    }

    pl_dispatch_reset_frame(m->dispatch);
    if (!analyze(m, analysis_first, analysis_second, first_id, second_id))
        return false;

    pl_tex destination = target;
    if (!destination) {
        bool same_size = m->result &&
                         m->result->params.w == first->params.w &&
                         m->result->params.h == first->params.h;
        if (!same_size && !recreate(m, &m->result, first->params.w, first->params.h))
            return false;
        if (!same_size)
            m->result_valid = false;
        destination = m->result;
        if (m->result_valid && m->last_alpha == alpha) {
            m->stats.repeated_frames++;
            *output = destination;
            return true;
        }
        m->result_valid = false;
    }

    float size[] = {first->params.w, first->params.h};
    float target_size[] = {destination->params.w, destination->params.h};
    float source_size[] = {first->params.w, first->params.h};
    float map_size[] = {m->images[0][0]->params.w, m->images[0][0]->params.h};
    for (int direction = 0; direction < 2; direction++) {
        if (!recreate(m, &m->warps[direction], map_size[0], map_size[1]))
            return false;
        struct pl_shader_desc warp_desc[] = {
            sampled("forward", m->reconstructed[direction]),
            sampled("backward", m->reconstructed[!direction]),
        };
        float weight = direction ? 1.0f - alpha : alpha;
        struct pl_shader_var warp_vars[] = {
            {.var = pl_var_vec2("output_size"), .data = size},
            {.var = pl_var_vec2("map_size"), .data = map_size},
            {.var = pl_var_float("weight"), .data = &weight, .dynamic = true},
        };
        m->timing_stage = LEVELS;
        if (!dispatch(m, m->warps[direction], &(struct pl_custom_shader) {
            .description = "motion inverse warp",
            .header = synthesis_header, .body = warp_body,
            .descriptors = warp_desc, .num_descriptors = 2,
            .variables = warp_vars, .num_variables = 3,
        }))
            return false;
    }
    struct pl_shader_desc desc[] = {
        sampled("first", first), sampled("second", second),
        sampled("forward", m->warps[0]), sampled("backward", m->warps[1]),
        sampled("scene", m->scene),
    };
    struct pl_shader_var vars[] = {
        {.var = pl_var_vec2("output_size"), .data = target_size},
        {.var = pl_var_vec2("source_size"), .data = source_size},
        {.var = pl_var_vec2("motion_size"), .data = map_size},
        {.var = pl_var_float("alpha"), .data = &alpha, .dynamic = true},
    };
    m->timing_stage = LEVELS + 1;
    if (!dispatch(m, destination, &(struct pl_custom_shader) {
        .description = "motion compensated interpolation",
        .body = synthesis_body,
        .descriptors = desc, .num_descriptors = 5,
        .variables = vars, .num_variables = 4,
    }))
        return false;

    if (!target) {
        m->last_alpha = alpha;
        m->result_valid = true;
    }
    m->stats.generated_frames++;
    m->stats.synthesis_width = destination->params.w;
    m->stats.synthesis_height = destination->params.h;
    if (output)
        *output = destination;
    return true;
}

bool mp_motion_render(struct mp_motion *m, pl_tex first, pl_tex second,
                      uint64_t first_id, uint64_t second_id, float alpha,
                      pl_tex *output)
{
    return render_motion(m, first, second, first, second, first_id, second_id,
                         alpha, NULL, output);
}

bool mp_motion_render_to(struct mp_motion *m, pl_tex first, pl_tex second,
                         uint64_t first_id, uint64_t second_id, float alpha,
                         pl_tex output)
{
    return render_motion(m, first, second, first, second, first_id, second_id,
                         alpha, output, NULL);
}

bool mp_motion_render_sources(struct mp_motion *m,
                              pl_tex analysis_first, pl_tex analysis_second,
                              pl_tex first, pl_tex second,
                              uint64_t first_id, uint64_t second_id, float alpha,
                              pl_tex *output)
{
    return render_motion(m, analysis_first, analysis_second, first, second,
                         first_id, second_id, alpha, NULL, output);
}

bool mp_motion_render_sources_to(struct mp_motion *m,
                                 pl_tex analysis_first, pl_tex analysis_second,
                                 pl_tex first, pl_tex second,
                                 uint64_t first_id, uint64_t second_id, float alpha,
                                 pl_tex target)
{
    return render_motion(m, analysis_first, analysis_second, first, second,
                         first_id, second_id, alpha, target, NULL);
}
