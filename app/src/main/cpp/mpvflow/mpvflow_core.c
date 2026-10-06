/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Clean-room block-motion interpolation core for Mpv∞. This is a compact
 * reference implementation, not a production-optimized or exact SVPFlow clone.
 */
#include "mpvflow_core.h"
#include <limits.h>
#include <math.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif
#define MPVFLOW_PYRAMID_LEVELS 3
#define MPVFLOW_SCENE_CUT_MEAN_DELTA 48.0
#define MPVFLOW_CONSISTENCY_LIMIT 4.0
#define MPVFLOW_PHOTOMETRIC_LIMIT 96.0
struct flow_level {
    int width;
    int height;
    int grid_width;
    int grid_height;
    uint8_t *luma0;
    uint8_t *luma1;
    int16_t *forward;
    int16_t *backward;
};
struct MPVFlowContext {
    int block_size;
    int search_radius;
};
struct MPVFlowPair {
    int width;
    int height;
    int step;
    int level_count;
    bool scene_cut;
    bool identical_frames;
    struct flow_level levels[MPVFLOW_PYRAMID_LEVELS];
    struct MPVFlowStats stats;
};
static int clamp_int(int value, int low, int high)
{
    return value < low ? low : value > high ? high : value;
}
static float clamp_float(float value, float low, float high)
{
    return value < low ? low : value > high ? high : value;
}
static void free_levels(struct flow_level *levels, int count)
{
    for (int i = 0; i < count; i++) {
        free(levels[i].luma0);
        free(levels[i].luma1);
        free(levels[i].forward);
        free(levels[i].backward);
    }
}
static bool allocate_level(struct flow_level *level, int width, int height,
                           int step)
{
    if (width <= 0 || height <= 0 || step <= 0 ||
        (size_t)width > SIZE_MAX / (size_t)height)
        return false;
    level->width = width;
    level->height = height;
    level->grid_width = (width + step - 1) / step;
    level->grid_height = (height + step - 1) / step;
    size_t pixels = (size_t)width * (size_t)height;
    size_t vectors = (size_t)level->grid_width * (size_t)level->grid_height;
    if (vectors > SIZE_MAX / (2 * sizeof(int16_t)))
        return false;
    level->luma0 = malloc(pixels);
    level->luma1 = malloc(pixels);
    level->forward = calloc(vectors * 2, sizeof(int16_t));
    level->backward = calloc(vectors * 2, sizeof(int16_t));
    return level->luma0 && level->luma1 && level->forward && level->backward;
}
static void rgb_to_luma(const uint8_t *rgb, uint8_t *luma, int width, int height)
{
    size_t pixels = (size_t)width * (size_t)height;
    for (size_t i = 0; i < pixels; i++) {
        unsigned int r = rgb[i * 3 + 0];
        unsigned int g = rgb[i * 3 + 1];
        unsigned int b = rgb[i * 3 + 2];
        luma[i] = (uint8_t)((77 * r + 150 * g + 29 * b + 128) >> 8);
    }
}
static void downsample_luma(const uint8_t *source, int source_width,
                            int source_height, uint8_t *dest,
                            int dest_width, int dest_height)
{
    for (int y = 0; y < dest_height; y++) {
        for (int x = 0; x < dest_width; x++) {
            int sum = 0;
            int count = 0;
            for (int oy = 0; oy < 2; oy++) {
                int sy = y * 2 + oy;
                if (sy >= source_height)
                    continue;
                for (int ox = 0; ox < 2; ox++) {
                    int sx = x * 2 + ox;
                    if (sx >= source_width)
                        continue;
                    sum += source[(size_t)sy * source_width + sx];
                    count++;
                }
            }
            dest[(size_t)y * dest_width + x] =
                (uint8_t)(count ? (sum + count / 2) / count : 0);
        }
    }
}
static const int16_t *vector_at(const struct flow_level *level,
                                const int16_t *field, int x, int y, int step)
{
    int gx = clamp_int(x / step, 0, level->grid_width - 1);
    int gy = clamp_int(y / step, 0, level->grid_height - 1);
    return field + ((size_t)gy * level->grid_width + gx) * 2;
}
static int block_sad(const uint8_t *source, const uint8_t *target,
                     int width, int x, int y, int dx, int dy, int block,
                     int cutoff)
{
    int sum = 0;
#if defined(__aarch64__)
    if (block == 16) {
        for (int by = 0; by < block; by++) {
            const uint8_t *a = source + (size_t)(y + by) * width + x;
            const uint8_t *b = target + (size_t)(y + by + dy) * width + x + dx;
            sum += vaddlvq_u8(vabdq_u8(vld1q_u8(a), vld1q_u8(b)));
            if (sum > cutoff)
                return sum;
        }
        return sum;
    }
    if (block == 8) {
        for (int by = 0; by < block; by++) {
            const uint8_t *a = source + (size_t)(y + by) * width + x;
            const uint8_t *b = target + (size_t)(y + by + dy) * width + x + dx;
            sum += vaddlv_u8(vabd_u8(vld1_u8(a), vld1_u8(b)));
            if (sum > cutoff)
                return sum;
        }
        return sum;
    }
#endif
    for (int by = 0; by < block; by++) {
        const uint8_t *a = source + (size_t)(y + by) * width + x;
        const uint8_t *b = target + (size_t)(y + by + dy) * width + x + dx;
        for (int bx = 0; bx < block; bx++)
            sum += abs((int)a[bx] - (int)b[bx]);
        if (sum > cutoff)
            return sum;
    }
    return sum;
}
static void estimate_one_direction(struct flow_level *levels, int level_count,
                                   int block, int step, int search_radius,
                                   bool forward)
{
    for (int li = level_count - 1; li >= 0; li--) {
        struct flow_level *level = &levels[li];
        int16_t *field = forward ? level->forward : level->backward;
        const uint8_t *source = forward ? level->luma0 : level->luma1;
        const uint8_t *target = forward ? level->luma1 : level->luma0;
        bool coarse = li == level_count - 1;
        struct flow_level *parent = coarse ? NULL : &levels[li + 1];
        int radius = coarse ? search_radius : 2;
        for (int gy = 0; gy < level->grid_height; gy++) {
            int y = clamp_int(gy * step, 0, level->height - block);
            for (int gx = 0; gx < level->grid_width; gx++) {
                int x = clamp_int(gx * step, 0, level->width - block);
                int center_dx = 0;
                int center_dy = 0;
                if (parent) {
                    const int16_t *prediction = vector_at(
                        parent, forward ? parent->forward : parent->backward,
                        x / 2, y / 2, step);
                    center_dx = prediction[0] * 2;
                    center_dy = prediction[1] * 2;
                }
                int min_dx = clamp_int(center_dx - radius, -x,
                                       level->width - block - x);
                int max_dx = clamp_int(center_dx + radius, -x,
                                       level->width - block - x);
                int min_dy = clamp_int(center_dy - radius, -y,
                                       level->height - block - y);
                int max_dy = clamp_int(center_dy + radius, -y,
                                       level->height - block - y);
                int best_dx = clamp_int(center_dx, min_dx, max_dx);
                int best_dy = clamp_int(center_dy, min_dy, max_dy);
                int best_cost = block_sad(source, target, level->width, x, y,
                                          best_dx, best_dy, block, INT32_MAX);
                for (int dy = min_dy; dy <= max_dy; dy++) {
                    for (int dx = min_dx; dx <= max_dx; dx++) {
                        if (dx == best_dx && dy == best_dy)
                            continue;
                        int cost = block_sad(source, target, level->width,
                                             x, y, dx, dy, block, best_cost);
                        if (cost < best_cost ||
                            (cost == best_cost &&
                             abs(dx) + abs(dy) < abs(best_dx) + abs(best_dy))) {
                            best_cost = cost;
                            best_dx = dx;
                            best_dy = dy;
                        }
                    }
                }
                size_t index = ((size_t)gy * level->grid_width + gx) * 2;
                field[index] = (int16_t)best_dx;
                field[index + 1] = (int16_t)best_dy;
            }
        }
    }
}
static void sample_vector(const struct flow_level *level, const int16_t *field,
                          int step, float x, float y, float *dx, float *dy)
{
    float gx = clamp_float(x / step, 0.0f, (float)(level->grid_width - 1));
    float gy = clamp_float(y / step, 0.0f, (float)(level->grid_height - 1));
    int x0 = (int)floorf(gx);
    int y0 = (int)floorf(gy);
    int x1 = x0 + 1 < level->grid_width ? x0 + 1 : x0;
    int y1 = y0 + 1 < level->grid_height ? y0 + 1 : y0;
    float tx = gx - x0;
    float ty = gy - y0;
    const int16_t *v00 = field + ((size_t)y0 * level->grid_width + x0) * 2;
    const int16_t *v10 = field + ((size_t)y0 * level->grid_width + x1) * 2;
    const int16_t *v01 = field + ((size_t)y1 * level->grid_width + x0) * 2;
    const int16_t *v11 = field + ((size_t)y1 * level->grid_width + x1) * 2;
    float top_x = v00[0] + (v10[0] - v00[0]) * tx;
    float top_y = v00[1] + (v10[1] - v00[1]) * tx;
    float bot_x = v01[0] + (v11[0] - v01[0]) * tx;
    float bot_y = v01[1] + (v11[1] - v01[1]) * tx;
    *dx = top_x + (bot_x - top_x) * ty;
    *dy = top_y + (bot_y - top_y) * ty;
}
static void sample_rgb24(const uint8_t *rgb, int width, int height,
                         float x, float y, float color[3])
{
    x = clamp_float(x, 0.0f, (float)(width - 1));
    y = clamp_float(y, 0.0f, (float)(height - 1));
    int x0 = (int)floorf(x);
    int y0 = (int)floorf(y);
    int x1 = x0 + 1 < width ? x0 + 1 : x0;
    int y1 = y0 + 1 < height ? y0 + 1 : y0;
    float tx = x - x0;
    float ty = y - y0;
    for (int channel = 0; channel < 3; channel++) {
        float c00 = rgb[((size_t)y0 * width + x0) * 3 + channel];
        float c10 = rgb[((size_t)y0 * width + x1) * 3 + channel];
        float c01 = rgb[((size_t)y1 * width + x0) * 3 + channel];
        float c11 = rgb[((size_t)y1 * width + x1) * 3 + channel];
        float top = c00 + (c10 - c00) * tx;
        float bottom = c01 + (c11 - c01) * tx;
        color[channel] = top + (bottom - top) * ty;
    }
}
static double scene_delta(const uint8_t *a, const uint8_t *b, size_t pixels)
{
    uint64_t sum = 0;
    for (size_t i = 0; i < pixels; i++)
        sum += (uint64_t)abs((int)a[i] - (int)b[i]);
    return pixels ? (double)sum / pixels : 255.0;
}
static bool collect_stats(const struct flow_level *level, int step,
                          struct MPVFlowStats *stats)
{
    if (!stats)
        return true;
    double sad_sum = 0.0;
    double motion_sum = 0.0;
    size_t blocks = 0;
    for (int gy = 0; gy < level->grid_height; gy++) {
        for (int gx = 0; gx < level->grid_width; gx++) {
            size_t index = ((size_t)gy * level->grid_width + gx) * 2;
            float fx = level->forward[index];
            float fy = level->forward[index + 1];
            float bx = level->backward[index];
            float by = level->backward[index + 1];
            motion_sum += (hypot(fx, fy) + hypot(bx, by)) * 0.5;
            float error = hypot(fx + bx, fy + by);
            stats->mean_vector_consistency_error += error;
            blocks++;
        }
    }
    /* SAD is recomputed on the final grid for bounded, stable diagnostics. */
    int block = step * 2;
    for (int gy = 0; gy < level->grid_height; gy++) {
        int y = clamp_int(gy * step, 0, level->height - block);
        for (int gx = 0; gx < level->grid_width; gx++) {
            int x = clamp_int(gx * step, 0, level->width - block);
            size_t index = ((size_t)gy * level->grid_width + gx) * 2;
            sad_sum += block_sad(level->luma0, level->luma1, level->width,
                                 x, y, level->forward[index],
                                 level->forward[index + 1], block, INT32_MAX);
        }
    }
    if (!blocks)
        return false;
    stats->mean_block_sad = sad_sum / blocks / (block * block);
    stats->mean_vector_consistency_error /= blocks;
    stats->mean_motion_pixels = motion_sum / blocks;
    return true;
}
MPVFlowContext *mpvflow_create(int block_size, int search_radius)
{
    if (block_size != 8 && block_size != 16)
        block_size = 8;
    if (search_radius < 1 || search_radius > 32)
        search_radius = 8;
    MPVFlowContext *context = calloc(1, sizeof(*context));
    if (!context)
        return NULL;
    context->block_size = block_size;
    context->search_radius = search_radius;
    return context;
}
void mpvflow_destroy(MPVFlowContext *context)
{
    free(context);
}
int mpvflow_analyze_pair(MPVFlowContext *context,
                         const uint8_t *frame0,
                         const uint8_t *frame1,
                         int width,
                         int height,
                         MPVFlowPair **pair_out,
                         struct MPVFlowStats *stats)
{
    if (pair_out)
        *pair_out = NULL;
    if (!context || !frame0 || !frame1 || !pair_out ||
        width < context->block_size || height < context->block_size ||
        width > 16384 || height > 16384 ||
        (size_t)width > SIZE_MAX / (size_t)height / 3)
        return MPVFLOW_ERROR;
    if (stats)
        memset(stats, 0, sizeof(*stats));
    MPVFlowPair *pair = calloc(1, sizeof(*pair));
    if (!pair)
        return MPVFLOW_ERROR;
    pair->width = width;
    pair->height = height;
    if (memcmp(frame0, frame1, (size_t)width * height * 3) == 0) {
        pair->identical_frames = true;
        *pair_out = pair;
        if (stats)
            *stats = pair->stats;
        return MPVFLOW_OK;
    }
    pair->step = context->block_size / 2;
    int w = width;
    int h = height;
    for (int i = 0; i < MPVFLOW_PYRAMID_LEVELS; i++) {
        if (w < context->block_size || h < context->block_size)
            break;
        if (!allocate_level(&pair->levels[i], w, h, pair->step)) {
            pair->level_count = i + 1;
            mpvflow_pair_destroy(pair);
            return MPVFLOW_ERROR;
        }
        pair->level_count++;
        w = (w + 1) / 2;
        h = (h + 1) / 2;
    }
    if (pair->level_count == 0) {
        mpvflow_pair_destroy(pair);
        return MPVFLOW_ERROR;
    }
    rgb_to_luma(frame0, pair->levels[0].luma0, width, height);
    rgb_to_luma(frame1, pair->levels[0].luma1, width, height);
    for (int i = 1; i < pair->level_count; i++) {
        downsample_luma(pair->levels[i - 1].luma0, pair->levels[i - 1].width,
                        pair->levels[i - 1].height, pair->levels[i].luma0,
                        pair->levels[i].width, pair->levels[i].height);
        downsample_luma(pair->levels[i - 1].luma1, pair->levels[i - 1].width,
                        pair->levels[i - 1].height, pair->levels[i].luma1,
                        pair->levels[i].width, pair->levels[i].height);
    }
    double frame_delta = scene_delta(pair->levels[0].luma0,
                                     pair->levels[0].luma1,
                                     (size_t)width * height);
    if (frame_delta >= MPVFLOW_SCENE_CUT_MEAN_DELTA) {
        pair->scene_cut = true;
        pair->stats.mean_block_sad = frame_delta;
        *pair_out = pair;
        if (stats)
            *stats = pair->stats;
        return MPVFLOW_SCENE_CUT;
    }
    estimate_one_direction(pair->levels, pair->level_count,
                           context->block_size, pair->step,
                           context->search_radius, true);
    estimate_one_direction(pair->levels, pair->level_count,
                           context->block_size, pair->step,
                           context->search_radius, false);
    if (!collect_stats(&pair->levels[0], pair->step, &pair->stats)) {
        mpvflow_pair_destroy(pair);
        return MPVFLOW_ERROR;
    }
    *pair_out = pair;
    if (stats)
        *stats = pair->stats;
    return MPVFLOW_OK;
}
void mpvflow_pair_destroy(MPVFlowPair *pair)
{
    if (!pair)
        return;
    free_levels(pair->levels, pair->level_count);
    free(pair);
}
int mpvflow_synthesize_rgb24(MPVFlowContext *context,
                             const MPVFlowPair *pair,
                             const uint8_t *frame0,
                             const uint8_t *frame1,
                             float timestep,
                             uint8_t *output,
                             struct MPVFlowStats *stats)
{
    if (!context || !pair || !frame0 || !frame1 || !output ||
        pair->width < context->block_size || pair->height < context->block_size ||
        !isfinite(timestep) || timestep <= 0.0f || timestep >= 1.0f ||
        (size_t)pair->width > SIZE_MAX / (size_t)pair->height / 3)
        return MPVFLOW_ERROR;
    if (stats)
        *stats = pair->stats;
    if (pair->scene_cut) {
        size_t bytes = (size_t)pair->width * pair->height * 3;
        memcpy(output, timestep <= 0.5f ? frame0 : frame1, bytes);
        return MPVFLOW_SCENE_CUT;
    }
    if (pair->identical_frames) {
        size_t bytes = (size_t)pair->width * pair->height * 3;
        memcpy(output, frame0, bytes);
        return MPVFLOW_OK;
    }
    const struct flow_level *full = &pair->levels[0];
    double consistency_sum = 0.0;
    size_t pixel_count = (size_t)pair->width * pair->height;
    for (int y = 0; y < pair->height; y++) {
        for (int x = 0; x < pair->width; x++) {
            float fdx, fdy, bdx, bdy;
            sample_vector(full, full->forward, pair->step, (float)x, (float)y,
                          &fdx, &fdy);
            sample_vector(full, full->backward, pair->step, (float)x, (float)y,
                          &bdx, &bdy);
            float ax = (float)x - timestep * fdx;
            float ay = (float)y - timestep * fdy;
            float bx = (float)x - (1.0f - timestep) * bdx;
            float by = (float)y - (1.0f - timestep) * bdy;
            for (int iteration = 0; iteration < 2; iteration++) {
                sample_vector(full, full->forward, pair->step, ax, ay, &fdx, &fdy);
                ax = (float)x - timestep * fdx;
                ay = (float)y - timestep * fdy;
                sample_vector(full, full->backward, pair->step, bx, by, &bdx, &bdy);
                bx = (float)x - (1.0f - timestep) * bdx;
                by = (float)y - (1.0f - timestep) * bdy;
            }
            float consistency = hypotf(fdx + bdx, fdy + bdy);
            consistency_sum += consistency;
            float color_a[3], color_b[3];
            sample_rgb24(frame0, pair->width, pair->height, ax, ay, color_a);
            sample_rgb24(frame1, pair->width, pair->height, bx, by, color_b);
            float photo_delta = (fabsf(color_a[0] - color_b[0]) +
                                 fabsf(color_a[1] - color_b[1]) +
                                 fabsf(color_a[2] - color_b[2])) / 3.0f;
            float flow_confidence =
                clamp_float(1.0f - consistency / MPVFLOW_CONSISTENCY_LIMIT,
                            0.0f, 1.0f);
            float photo_confidence =
                clamp_float(1.0f - photo_delta / MPVFLOW_PHOTOMETRIC_LIMIT,
                            0.0f, 1.0f);
            float confidence = flow_confidence * photo_confidence;
            float weight_a = (1.0f - timestep) * confidence +
                             (timestep <= 0.5f ? 1.0f - confidence : 0.0f);
            float weight_b = timestep * confidence +
                             (timestep > 0.5f ? 1.0f - confidence : 0.0f);
            float weight_sum = weight_a + weight_b;
            if (weight_sum <= 0.0f) {
                weight_a = 1.0f - timestep;
                weight_b = timestep;
                weight_sum = 1.0f;
            }
            size_t output_index = ((size_t)y * pair->width + x) * 3;
            for (int channel = 0; channel < 3; channel++) {
                float value = (color_a[channel] * weight_a +
                               color_b[channel] * weight_b) / weight_sum;
                output[output_index + channel] =
                    (uint8_t)clamp_int((int)lrintf(value), 0, 255);
            }
        }
    }
    if (stats)
        stats->mean_vector_consistency_error = consistency_sum / pixel_count;
    return MPVFLOW_OK;
}
