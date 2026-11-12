/* SPDX-License-Identifier: LGPL-2.1-or-later */
#undef NDEBUG
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

#include <libplacebo/dispatch.h>
#include <libplacebo/log.h>
#include <libplacebo/shaders/custom.h>
#include <libplacebo/shaders/sampling.h>
#include <libplacebo/vulkan.h>

#include "video/out/gpu_next/motion.h"
#include "gpu_motion_shaders.h"

#define WIDTH 128
#define HEIGHT 96

static int gpu_errors;

static void log_message(void *opaque, enum pl_log_level level, const char *message)
{
    if (level <= PL_LOG_ERR)
        gpu_errors++;
    pl_log_simple(opaque, level, message);
}

static float noise(int x, int y, int channel)
{
    uint32_t value = x * 73856093u ^ y * 19349663u ^ channel * 83492791u;
    value ^= value >> 13;
    value *= 1274126177u;
    return (value & 255) / 255.0f;
}

static void pattern(float *pixels, int shift)
{
    for (int y = 0; y < HEIGHT; y++) {
        for (int x = 0; x < WIDTH; x++) {
            for (int c = 0; c < 3; c++)
                pixels[(y * WIDTH + x) * 4 + c] = noise(x - shift, y, c);
            pixels[(y * WIDTH + x) * 4 + 3] = 1;
        }
    }
}

static void copy_texture(pl_gpu gpu, pl_tex source, pl_tex target)
{
    pl_dispatch dispatch = pl_dispatch_create(gpu->log, gpu);
    pl_shader shader = pl_dispatch_begin(dispatch);
    assert(pl_shader_sample_nearest(shader, pl_sample_src(.tex = source)));
    assert(pl_dispatch_finish(dispatch, pl_dispatch_params(
        .shader = &shader, .target = target,
    )));
    pl_dispatch_destroy(&dispatch);
}

static pl_tex upload_size(pl_gpu gpu, const float *pixels, int width, int height)
{
    pl_fmt format = pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 32, 32,
        PL_FMT_CAP_SAMPLEABLE);
    assert(format);
    pl_tex texture = pl_tex_create(gpu, pl_tex_params(
        .w = width, .h = height, .format = format,
        .sampleable = true, .host_writable = true, .initial_data = pixels,
    ));
    assert(texture);
    pl_tex source = texture;
    format = pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 16, 0,
        PL_FMT_CAP_SAMPLEABLE | PL_FMT_CAP_LINEAR | PL_FMT_CAP_RENDERABLE);
    assert(format);
    texture = pl_tex_create(gpu, pl_tex_params(
        .w = width, .h = height, .format = format,
        .sampleable = true, .renderable = true,
    ));
    assert(texture);
    copy_texture(gpu, source, texture);
    pl_tex_destroy(gpu, &source);
    return texture;
}

static pl_tex upload(pl_gpu gpu, const float *pixels)
{
    return upload_size(gpu, pixels, WIDTH, HEIGHT);
}

static void readback(pl_gpu gpu, pl_tex source, float *pixels)
{
    pl_fmt format = pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 32, 32,
        PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_HOST_READABLE);
    assert(format);
    pl_tex target = pl_tex_create(gpu, pl_tex_params(
        .w = source->params.w, .h = source->params.h, .format = format,
        .renderable = true, .host_readable = true,
    ));
    assert(target);
    copy_texture(gpu, source, target);
    assert(pl_tex_download(gpu, pl_tex_transfer_params(.tex = target, .ptr = pixels)));
    pl_tex_destroy(gpu, &target);
}

static float signal(float x, float y, int channel)
{
    return 0.5f + 0.2f * sinf(x * (0.13f + channel * 0.03f) + y * 0.07f) +
           0.2f * cosf(y * (0.17f + channel * 0.02f) - x * 0.05f);
}

static float affine_signal(float x, float y, int channel, float alpha)
{
    float a = 1 + 0.015f * alpha;
    float b = -0.04f * alpha;
    float c = 0.03f * alpha;
    float d = 1 - 0.01f * alpha;
    float qx = x - 64.0f - alpha * 4;
    float qy = y - 48.0f + alpha * 2;
    float determinant = a * d - b * c;
    return signal((d * qx - b * qy) / determinant + 64,
                  (a * qy - c * qx) / determinant + 48, channel);
}

static void affine_pair(pl_gpu gpu, struct mp_motion *motion,
                        int width, int height, int scale)
{
    float *first = calloc(width * height * 4, sizeof(float));
    float *second = calloc(width * height * 4, sizeof(float));
    float *output = calloc(width * height * 4, sizeof(float));
    assert(first && second && output);
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            for (int c = 0; c < 4; c++) {
                int index = (y * width + x) * 4 + c;
                float sx = (x + 0.5f) / scale - 0.5f;
                float sy = (y + 0.5f) / scale - 0.5f;
                first[index] = c == 3 ? 1 : affine_signal(sx, sy, c, 0);
                second[index] = c == 3 ? 1 : affine_signal(sx, sy, c, 1);
            }
        }
    }
    pl_tex a = upload_size(gpu, first, width, height);
    pl_tex b = upload_size(gpu, second, width, height);
    for (int step = 1; step <= 3; step++) {
        float alpha = step * 0.25f;
        pl_tex result;
        assert(mp_motion_render(motion, a, b, 30, 31, alpha, &result));
        assert(result->params.w == width && result->params.h == height);
        readback(gpu, result, output);
        double motion_error = 0, blend_error = 0;
        int samples = 0;
        int margin = 16 * scale;
        for (int y = margin; y < height - margin; y++) {
            for (int x = margin; x < width - margin; x++) {
                for (int c = 0; c < 3; c++) {
                    int index = (y * width + x) * 4 + c;
                    float sx = (x + 0.5f) / scale - 0.5f;
                    float sy = (y + 0.5f) / scale - 0.5f;
                    float expected = affine_signal(sx, sy, c, alpha);
                    float blend = first[index] * (1 - alpha) + second[index] * alpha;
                    assert(isfinite(output[index]));
                    motion_error += fabs(output[index] - expected);
                    blend_error += fabs(blend - expected);
                    samples++;
                }
            }
        }
        motion_error /= samples;
        blend_error /= samples;
        printf("affine %dx%d alpha=%.2f motion_error=%.6f blend_error=%.6f\n",
               width, height, alpha, motion_error, blend_error);
        fflush(stdout);
        assert(motion_error < 0.02);
        assert(motion_error < blend_error * 0.65);
    }
    mp_motion_reset(motion);
    pl_tex_destroy(gpu, &a);
    pl_tex_destroy(gpu, &b);
    free(first);
    free(second);
    free(output);
}

static void scaled_source_pair(pl_gpu gpu, struct mp_motion *motion)
{
    const int analysis_width = 128, analysis_height = 96, scale = 2;
    const int width = analysis_width * scale, height = analysis_height * scale;
    const int output_width = width * 2, output_height = height * 2;
    float *analysis[2] = {
        calloc(analysis_width * analysis_height * 4, sizeof(float)),
        calloc(analysis_width * analysis_height * 4, sizeof(float)),
    };
    float *source[2] = {
        calloc(width * height * 4, sizeof(float)),
        calloc(width * height * 4, sizeof(float)),
    };
    float *output = calloc(width * height * 4, sizeof(float));
    float *scaled = calloc(output_width * output_height * 4, sizeof(float));
    assert(analysis[0] && analysis[1] && source[0] && source[1] && output && scaled);

    for (int y = 0; y < analysis_height; y++) {
        for (int x = 0; x < analysis_width; x++) {
            for (int c = 0; c < 4; c++) {
                int index = (y * analysis_width + x) * 4 + c;
                analysis[0][index] = c == 3 ? 1 : affine_signal(x, y, c, 0);
                analysis[1][index] = c == 3 ? 1 : affine_signal(x, y, c, 1);
            }
        }
    }
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            float sx = (x + 0.5f) / scale - 0.5f;
            float sy = (y + 0.5f) / scale - 0.5f;
            for (int c = 0; c < 4; c++) {
                int index = (y * width + x) * 4 + c;
                source[0][index] = c == 3 ? 1 : affine_signal(sx, sy, c, 0);
                source[1][index] = c == 3 ? 1 : affine_signal(sx, sy, c, 1);
            }
        }
    }
    pl_tex analysis0 = upload_size(gpu, analysis[0], analysis_width, analysis_height);
    pl_tex analysis1 = upload_size(gpu, analysis[1], analysis_width, analysis_height);
    pl_tex source0 = upload_size(gpu, source[0], width, height);
    pl_tex source1 = upload_size(gpu, source[1], width, height);
    pl_tex result;
    assert(mp_motion_render_sources(motion, analysis0, analysis1, source0, source1,
                                    50, 51, 0.5f, &result));
    assert(result->params.w == width && result->params.h == height);
    struct mp_motion_stats stats = mp_motion_get_stats(motion);
    assert(stats.analysis_width == analysis_width && stats.analysis_height == analysis_height);
    assert(stats.synthesis_width == width && stats.synthesis_height == height);
    readback(gpu, result, output);
    double error = 0;
    int samples = 0;
    for (int y = 32; y < height - 32; y++) {
        for (int x = 32; x < width - 32; x++) {
            float sx = (x + 0.5f) / scale - 0.5f;
            float sy = (y + 0.5f) / scale - 0.5f;
            for (int c = 0; c < 3; c++) {
                int index = (y * width + x) * 4 + c;
                error += fabsf(output[index] - affine_signal(sx, sy, c, 0.5f));
                samples++;
            }
        }
    }
    error /= samples;
    printf("scaled sources %dx%d -> %dx%d error=%.6f\n",
           analysis_width, analysis_height, width, height, error);
    fflush(stdout);
    assert(error < 0.025);

    pl_fmt format = pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 16, 0,
        PL_FMT_CAP_SAMPLEABLE | PL_FMT_CAP_LINEAR | PL_FMT_CAP_RENDERABLE |
        PL_FMT_CAP_STORABLE);
    assert(format);
    pl_tex target = pl_tex_create(gpu, pl_tex_params(
        .w = output_width, .h = output_height, .format = format,
        .sampleable = true, .renderable = true, .storable = true,
    ));
    assert(target);
    assert(mp_motion_render_sources_to(motion, analysis0, analysis1, source0, source1,
                                       50, 51, 0.5f, target));
    assert(mp_motion_get_stats(motion).synthesis_width == output_width);
    assert(mp_motion_get_stats(motion).synthesis_height == output_height);
    readback(gpu, target, scaled);
    error = 0;
    samples = 0;
    for (int y = 64; y < output_height - 64; y++) {
        for (int x = 64; x < output_width - 64; x++) {
            float sx = (x + 0.5f) / (scale * 2) - 0.5f;
            float sy = (y + 0.5f) / (scale * 2) - 0.5f;
            for (int c = 0; c < 3; c++) {
                int index = (y * output_width + x) * 4 + c;
                error += fabsf(scaled[index] - affine_signal(sx, sy, c, 0.5f));
                samples++;
            }
        }
    }
    error /= samples;
    printf("scaled direct output %dx%d error=%.6f\n", output_width, output_height, error);
    fflush(stdout);
    assert(error < 0.025);

    mp_motion_reset(motion);
    pl_tex_destroy(gpu, &target);
    pl_tex_destroy(gpu, &analysis0);
    pl_tex_destroy(gpu, &analysis1);
    pl_tex_destroy(gpu, &source0);
    pl_tex_destroy(gpu, &source1);
    for (int i = 0; i < 2; i++) {
        free(analysis[i]);
        free(source[i]);
    }
    free(output);
    free(scaled);
}

static void translated_pair(pl_gpu gpu, struct mp_motion *motion)
{
    float *first = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    float *second = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    float *output = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    assert(first && second && output);
    pattern(first, 0);
    pattern(second, 20);
    pl_tex a = upload(gpu, first);
    pl_tex b = upload(gpu, second);
    for (int step = 1; step <= 3; step++) {
        float alpha = step * 0.25f;
        pl_tex result;
        assert(mp_motion_render(motion, a, b, 10, 11, alpha, &result));
        readback(gpu, result, output);
        double motion_error = 0;
        double blend_error = 0;
        int samples = 0;
        for (int y = 24; y < HEIGHT - 24; y++) {
            for (int x = 40; x < WIDTH - 40; x++) {
                for (int c = 0; c < 3; c++) {
                    int index = (y * WIDTH + x) * 4 + c;
                    float expected = noise(x - step * 5, y, c);
                    float blend = first[index] * (1 - alpha) + second[index] * alpha;
                    assert(isfinite(output[index]));
                    motion_error += fabs(output[index] - expected);
                    blend_error += fabs(blend - expected);
                    samples++;
                }
            }
        }
        motion_error /= samples;
        blend_error /= samples;
        printf("translation alpha=%.2f motion_error=%.6f blend_error=%.6f\n",
               alpha, motion_error, blend_error);
        fflush(stdout);
        assert(motion_error < 0.04);
        assert(motion_error < blend_error * 0.2);
        struct mp_motion_stats stats = mp_motion_get_stats(motion);
        assert(stats.analysis_width == WIDTH && stats.analysis_height == HEIGHT);
        assert(stats.synthesis_width == WIDTH && stats.synthesis_height == HEIGHT);
        assert(stats.analyzed_pairs == 1);
        assert(stats.generated_frames == (uint64_t) step);
        pl_tex repeated;
        assert(mp_motion_render(motion, a, b, 10, 11, alpha, &repeated));
        assert(repeated == result);
        assert(mp_motion_get_stats(motion).generated_frames == stats.generated_frames);
        assert(mp_motion_get_stats(motion).repeated_frames == (uint64_t) step);
    }
    assert(!mp_motion_render(motion, a, b, 10, 11, NAN, &(pl_tex){0}));
    assert(!mp_motion_render(motion, a, b, 10, 11, 0, &(pl_tex){0}));
    assert(!mp_motion_render(motion, a, b, 10, 11, 1, &(pl_tex){0}));
    mp_motion_reset(motion);
    pl_tex_destroy(gpu, &a);
    pl_tex_destroy(gpu, &b);
    free(first);
    free(second);
    free(output);
}

static void diagonal_pixels(float *pixels, int width, int height, int scale,
                             float shift, bool bands, int variant)
{
    static const float background[6][3] = {
        {1, 0, 0}, {0, 1, 0}, {1, 1, 0},
        {0, 0, 1}, {1, 0, 1}, {0, 1, 1},
    };
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            float origin = variant == 1 ? 96 : variant == 2 ? 180 : 24;
            float slope = variant == 2 || (variant >= 20 && variant < 24)
                        ? -0.25f : 0.25f;
            float center = (origin + shift) * scale + slope * (x + 0.5f);
            float edge = 2 * scale + 0.5f - fabsf(y + 0.5f - center);
            if (variant >= 4) {
                const int periods[] = {32, 40, 48, 64};
                int index = variant < 20 ? (variant - 4) / 4
                          : variant < 24 ? 0 : 2;
                float period = periods[index];
                float phase = remainderf(y + 0.5f - center - period * 0.5f, period);
                edge = 1.5f - fabsf(phase);
            }
            float coverage = fminf(1, fmaxf(0, edge));
            for (int c = 0; c < 3; c++) {
                float base = bands ? background[x * 6 / width][c] : 0;
                pixels[(y * width + x) * 4 + c] =
                    base + coverage * (1 - 2 * base);
                if (variant == 3 || variant >= 24)
                    pixels[(y * width + x) * 4 + c] =
                        0.45f + 0.1f * pixels[(y * width + x) * 4 + c];
            }
            pixels[(y * width + x) * 4 + 3] = 1;
        }
    }
}

static void diagonal_edge(pl_gpu gpu, struct mp_motion *motion,
                           bool bands, int variant)
{
    int width = 1920, height = 1080, scale = 4;
    size_t bytes = width * height * 4 * sizeof(float);
    float *pixels = malloc(bytes);
    float *expected = malloc(bytes);
    float *output = malloc(bytes);
    assert(pixels && expected && output);
    const float shifts[] = {1, -1, 2, -2};
    float displacement = variant >= 4 ? shifts[(variant - 4) % 4]
                       : variant == 1 ? -16 : 16;
    diagonal_pixels(pixels, width, height, scale, 0, bands, variant);
    pl_tex a = upload_size(gpu, pixels, width, height);
    diagonal_pixels(pixels, width, height, scale, displacement, bands, variant);
    pl_tex b = upload_size(gpu, pixels, width, height);
    for (int step = 1; step <= 3; step++) {
        float alpha = step * 0.25f;
        diagonal_pixels(expected, width, height, scale, displacement * alpha,
                        bands, variant);
        pl_tex result;
        assert(mp_motion_render(motion, a, b, 90, 91, alpha, &result));
        readback(gpu, result, output);
        float maximum = 0;
        double error = 0;
        int count = 0;
        for (int y = 16 * scale; y < height - 16 * scale; y++) {
            for (int x = 16 * scale; x < width - 16 * scale; x++) {
                for (int c = 0; c < 3; c++) {
                    int index = (y * width + x) * 4 + c;
                    assert(isfinite(output[index]));
                    float difference = fabsf(output[index] - expected[index]);
                    if (variant == 3 || variant >= 24)
                        difference /= 0.1f;
                    maximum = fmaxf(maximum, difference);
                    error += difference;
                    count++;
                }
            }
        }
        printf("diagonal variant=%d %s alpha=%.2f error=%.6f maximum=%.6f\n",
               variant, bands ? "bands" : "flat", alpha, error / count,
               maximum);
        fflush(stdout);
        assert(error / count < 0.02);
        assert(maximum < 0.1);
    }
    mp_motion_reset(motion);
    pl_tex_destroy(gpu, &a);
    pl_tex_destroy(gpu, &b);
    free(pixels);
    free(expected);
    free(output);
}

static void static_and_cut(pl_gpu gpu, struct mp_motion *motion)
{
    float *pixels = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    float *output = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    assert(pixels && output);
    pattern(pixels, 0);
    pl_tex source = upload(gpu, pixels);
    pl_tex result;
    assert(mp_motion_render(motion, source, source, 20, 20, 0.5f, &result));
    readback(gpu, result, output);
    for (int i = 0; i < WIDTH * HEIGHT * 4; i++)
        assert(fabs(output[i] - pixels[i]) < 0.001);
    pl_tex_destroy(gpu, &source);
    for (int i = 0; i < WIDTH * HEIGHT * 4; i++)
        pixels[i] = i % 4 == 3 ? 1 : 0;
    pl_tex black = upload(gpu, pixels);
    for (int i = 0; i < WIDTH * HEIGHT * 4; i++)
        pixels[i] = 1;
    pl_tex white = upload(gpu, pixels);
    for (int step = 1; step <= 3; step++) {
        float alpha = step * 0.25f;
        assert(mp_motion_render(motion, black, white, 21, 22, alpha, &result));
        readback(gpu, result, output);
        float expected = alpha < 0.5f ? 0 : 1;
        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            for (int c = 0; c < 3; c++)
                assert(fabs(output[i * 4 + c] - expected) < 0.001);
        }
    }
    pl_tex_destroy(gpu, &black);
    pl_tex_destroy(gpu, &white);
    free(pixels);
    free(output);
    puts("static, scene cut, invalid alpha, and reset: passed");
}

static void straight_edge(pl_gpu gpu, struct mp_motion *motion,
                          int scale, bool horizontal, int thickness)
{
    int width = WIDTH * scale;
    int height = HEIGHT * scale;
    int origin = horizontal ? 24 : 40;
    int tangent = horizontal ? width : height;
    float *pixels[2] = {calloc(width * height * 4, sizeof(float)),
                        calloc(width * height * 4, sizeof(float))};
    float *output = calloc(width * height * 4, sizeof(float));
    assert(pixels[0] && pixels[1] && output);
    for (int frame = 0; frame < 2; frame++) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int axis = horizontal ? y : x;
                bool bright = axis >= (origin + frame * 16) * scale &&
                              axis < (origin + frame * 16 + thickness) * scale;
                for (int c = 0; c < 4; c++)
                    pixels[frame][(y * width + x) * 4 + c] = c == 3 || bright;
            }
        }
    }
    pl_tex a = upload_size(gpu, pixels[0], width, height);
    pl_tex b = upload_size(gpu, pixels[1], width, height);
    for (int step = 1; step <= 3; step++) {
        float alpha = step * 0.25f;
        pl_tex result;
        assert(mp_motion_render(motion, a, b, 60, 61, alpha, &result));
        assert(result->params.w == width && result->params.h == height);
        readback(gpu, result, output);
        double error = 0;
        float maximum_error = 0;
        int worst_x = 0, worst_y = 0;
        int samples = 0;
        for (int v = 16 * scale; v < tangent - 16 * scale; v++) {
            for (int u = (origin - 8) * scale; u < (origin + 40) * scale; u++) {
                int x = horizontal ? v : u;
                int y = horizontal ? u : v;
                float expected = u >= (origin + 4 * step) * scale &&
                                 u < (origin + 4 * step + thickness) * scale;
                float actual = output[(y * width + x) * 4];
                assert(isfinite(actual));
                float difference = fabs(actual - expected);
                if (difference > maximum_error) {
                    maximum_error = difference;
                    worst_x = x;
                    worst_y = y;
                }
                error += difference;
                samples++;
            }
        }
        printf("straight %s scale=%d width=%d alpha=%.2f error=%.6f maximum=%.6f at=%d,%d\n",
               horizontal ? "horizontal" : "vertical", scale, thickness, alpha,
               error / samples, maximum_error, worst_x, worst_y);
        fflush(stdout);
        assert(error / samples < 0.02);
        assert(maximum_error < 0.1);
    }
    mp_motion_reset(motion);
    pl_tex_destroy(gpu, &a);
    pl_tex_destroy(gpu, &b);
    free(pixels[0]);
    free(pixels[1]);
    free(output);
}

static struct pl_shader_desc policy_sampled(const char *name, pl_tex texture)
{
    return (struct pl_shader_desc) {
        .desc = {.name = name, .type = PL_DESC_SAMPLED_TEX},
        .binding = {.object = texture, .sample_mode = PL_TEX_SAMPLE_LINEAR,
                    .address_mode = PL_TEX_ADDRESS_CLAMP},
    };
}

static pl_tex policy_target(pl_gpu gpu)
{
    pl_fmt format = pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 16, 0,
        PL_FMT_CAP_SAMPLEABLE | PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_STORABLE);
    assert(format);
    pl_tex target = pl_tex_create(gpu, pl_tex_params(
        .w = WIDTH, .h = HEIGHT, .format = format,
        .sampleable = true, .renderable = true, .storable = true,
    ));
    assert(target);
    return target;
}

static float bilinear(const float *pixels, int width, int height,
                      float x, float y, int channel)
{
    x = fminf(fmaxf(x, 0), width - 1);
    y = fminf(fmaxf(y, 0), height - 1);
    int x0 = floorf(x), y0 = floorf(y);
    int x1 = x0 + 1 < width ? x0 + 1 : x0;
    int y1 = y0 + 1 < height ? y0 + 1 : y0;
    float fx = x - x0, fy = y - y0;
    float a = pixels[(y0 * width + x0) * 4 + channel];
    float b = pixels[(y0 * width + x1) * 4 + channel];
    float c = pixels[(y1 * width + x0) * 4 + channel];
    float d = pixels[(y1 * width + x1) * 4 + channel];
    return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
}

static void oblique_texture_translation(pl_gpu gpu, struct mp_motion *motion)
{
    const int shift_x = 8, shift_y = -6;
    float *first = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    float *second = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    float *output = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    assert(first && second && output);
    for (int y = 0; y < HEIGHT; y++) {
        for (int x = 0; x < WIDTH; x++) {
            int index = (y * WIDTH + x) * 4;
            for (int c = 0; c < 4; c++) {
                first[index + c] = c == 3 ? 1 : noise(x, y, c);
                second[index + c] = c == 3 ? 1 :
                    noise(x - shift_x, y - shift_y, c);
            }
        }
    }
    pl_tex a = upload(gpu, first);
    pl_tex b = upload(gpu, second);
    for (int step = 1; step <= 3; step++) {
        float alpha = step * 0.25f;
        pl_tex result;
        assert(mp_motion_render(motion, a, b, 12, 13, alpha, &result));
        readback(gpu, result, output);
        double motion_error = 0, blend_error = 0;
        int samples = 0;
        for (int y = 24; y < HEIGHT - 24; y++) {
            for (int x = 24; x < WIDTH - 24; x++) {
                int index = (y * WIDTH + x) * 4;
                for (int c = 0; c < 3; c++) {
                    float expected = bilinear(first, WIDTH, HEIGHT,
                        x - alpha * shift_x, y - alpha * shift_y, c);
                    float blend = first[index + c] * (1 - alpha) +
                                  second[index + c] * alpha;
                    assert(isfinite(output[index + c]));
                    motion_error += fabs(output[index + c] - expected);
                    blend_error += fabs(blend - expected);
                    samples++;
                }
            }
        }
        motion_error /= samples;
        blend_error /= samples;
        printf("oblique texture alpha=%.2f motion_error=%.6f blend_error=%.6f\n",
               alpha, motion_error, blend_error);
        fflush(stdout);
        assert(motion_error < 0.08);
        assert(motion_error < blend_error * 0.6);
    }
    mp_motion_reset(motion);
    pl_tex_destroy(gpu, &a);
    pl_tex_destroy(gpu, &b);
    free(first);
    free(second);
    free(output);
}

static float matching_cost(const float *first, const float *second,
                           float x, float y, float dx, float dy)
{
    float cost = 0;
    for (int oy = -3; oy <= 3; oy++) {
        for (int ox = -3; ox <= 3; ox++) {
            float error[3];
            for (int c = 0; c < 3; c++) {
                error[c] = bilinear(first, 32, 24, x + ox, y + oy, c) -
                    bilinear(second, 32, 24, x + ox + dx, y + oy + dy, c);
                cost += 0.25f * fabsf(error[c]);
            }
            cost += 0.25f * fabsf(error[0] * 0.2126f + error[1] * 0.7152f +
                                  error[2] * 0.0722f);
        }
    }
    return cost / 49;
}

static void subpixel_matching(pl_gpu gpu)
{
    const struct {
        int radius;
        float dx, dy;
        bool flat;
    } cases[] = {
        {2, 1.00f, -0.5f, false},
        {2, 1.25f, -0.5f, false},
        {2, 1.50f, -0.5f, false},
        {6, 5.25f, 5.5f, false},
        {6, -5.25f, 5.5f, false},
        {6, 5.25f, -5.5f, false},
        {6, -5.25f, -5.5f, false},
        {6, 0, 0, true},
    };
    float first[32 * 24 * 4], second[32 * 24 * 4], output[4 * 3 * 4];
    pl_fmt format = pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 16, 0,
        PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_STORABLE | PL_FMT_CAP_SAMPLEABLE);
    assert(format);
    pl_tex target = pl_tex_create(gpu, pl_tex_params(
        .w = 4, .h = 3, .format = format,
        .renderable = true, .storable = true, .sampleable = true,
    ));
    assert(target);
    pl_dispatch dispatch = pl_dispatch_create(gpu->log, gpu);
    assert(dispatch);
    for (int test = 0; test < (int) (sizeof(cases) / sizeof(cases[0])); test++) {
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 32; x++) {
                for (int c = 0; c < 3; c++)
                    first[(y * 32 + x) * 4 + c] = cases[test].flat
                                               ? 0.5f : noise(x, y, c) > 0.5f;
                first[(y * 32 + x) * 4 + 3] = 1;
            }
        }
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 32; x++) {
                for (int c = 0; c < 4; c++)
                    second[(y * 32 + x) * 4 + c] = bilinear(
                        first, 32, 24, x - cases[test].dx, y - cases[test].dy, c);
            }
        }
        pl_tex a = upload_size(gpu, first, 32, 24);
        pl_tex b = upload_size(gpu, second, 32, 24);
        struct pl_shader_desc descriptors[] = {
            policy_sampled("first", a), policy_sampled("second", b),
            {
                .desc = {.name = "out_image", .type = PL_DESC_STORAGE_IMG,
                         .access = PL_DESC_ACCESS_WRITEONLY},
                .binding.object = target,
            },
        };
        int settings[] = {4, 3, cases[test].radius};
        float scale[] = {1, 1};
        struct pl_shader_var variables[] = {
            {.var = pl_var_ivec3("settings"), .data = settings},
            {.var = pl_var_vec2("motion_scale"), .data = scale},
        };
        pl_shader shader = pl_dispatch_begin(dispatch);
        assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
            .header = test_sample_header,
        }));
        assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
            .header = test_match_header, .body = test_match_body,
            .compute = true, .compute_group_size = {64, 1},
            .compute_shmem = 49 * 16 + 169 * 4,
            .output = PL_SHADER_SIG_NONE,
            .descriptors = descriptors, .num_descriptors = 3,
            .variables = variables, .num_variables = 2,
        }));
        assert(pl_dispatch_compute(dispatch, pl_dispatch_compute_params(
            .shader = &shader, .dispatch_size = {4, 3, 1},
        )));
        readback(gpu, target, output);
        float maximum_excess = 0, maximum_residual_error = 0;
        bool matched_last_batch = false;
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 4; x++) {
                float px = (x + 0.5f) * 8 - 0.5f;
                float py = (y + 0.5f) * 8 - 0.5f;
                float best = INFINITY;
                for (int dy = -settings[2]; dy <= settings[2]; dy++) {
                    for (int dx = -settings[2]; dx <= settings[2]; dx++) {
                        if (px + dx < 0 || px + dx > 31 ||
                            py + dy < 0 || py + dy > 23)
                            continue;
                        float cost = matching_cost(first, second, px, py, dx, dy);
                        best = fminf(best, cost);
                    }
                }
                const float *vector = &output[(y * 4 + x) * 4];
                for (int c = 0; c < 4; c++)
                    assert(isfinite(vector[c]));
                float cost = matching_cost(first, second, px, py,
                                           vector[0], vector[1]);
                maximum_excess = fmaxf(maximum_excess, cost - best);
                maximum_residual_error = fmaxf(maximum_residual_error,
                                               fabsf(cost - vector[2]));
                matched_last_batch |= vector[1] > 4;
                if (cases[test].flat) {
                    assert(vector[0] == 0 && vector[1] == 0);
                    assert(vector[3] == 0);
                }
            }
        }
        printf("subpixel case=%d radius=%d excess=%.6f residual_error=%.6f\n",
               test, settings[2], maximum_excess, maximum_residual_error);
        fflush(stdout);
        assert(maximum_excess < 0.001);
        assert(maximum_residual_error < 0.001);
        if (cases[test].dy > 4)
            assert(matched_last_batch);
        pl_tex_destroy(gpu, &a);
        pl_tex_destroy(gpu, &b);
    }
    pl_dispatch_destroy(&dispatch);
    pl_tex_destroy(gpu, &target);
}

static void warp_policy(pl_gpu gpu)
{
    float *pixels[2] = {calloc(WIDTH * HEIGHT * 4, sizeof(float)),
                        calloc(WIDTH * HEIGHT * 4, sizeof(float))};
    float *output = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    assert(pixels[0] && pixels[1] && output);
    pl_tex target = policy_target(gpu);
    pl_dispatch dispatch = pl_dispatch_create(gpu->log, gpu);
    assert(dispatch);
    for (int test = 0; test < 7; test++) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int index = (y * WIDTH + x) * 4;
                pixels[0][index] = test == 6
                    ? 3.0f * (x - 63.9f) + 0.2f
                    : (test == 3 ? WIDTH * 2 : 8);
                pixels[1][index] = test == 6 ? -0.5f : (test == 1 ? 8 : -8);
                if (test == 4)
                    pixels[0][index] = NAN;
                if (test == 5) {
                    pixels[0][index] = -0.5f * (x + 0.5f - WIDTH * 0.5f);
                    pixels[1][index] = x + 0.5f - WIDTH * 0.5f;
                }
                pixels[0][index + 2] = test == 2 ? 0.1f : 0;
                pixels[0][index + 3] = test == 6 ? 1 : 0;
                pixels[1][index + 3] = test == 6 ? 1 : 0;
            }
        }
        pl_tex a = upload(gpu, pixels[0]);
        pl_tex b = upload(gpu, pixels[1]);
        struct pl_shader_desc descriptors[] = {
            policy_sampled("forward", a), policy_sampled("backward", b),
        };
        float map_size[] = {WIDTH, HEIGHT};
        float size[] = {WIDTH * (test == 5 ? 128 : 1), HEIGHT};
        float weight = 0.5f;
        struct pl_shader_var variables[] = {
            {.var = pl_var_vec2("map_size"), .data = map_size},
            {.var = pl_var_vec2("output_size"), .data = size},
            {.var = pl_var_float("weight"), .data = &weight},
        };
        pl_shader shader = pl_dispatch_begin(dispatch);
        assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
            .header = test_synthesis_header, .body = test_warp_body,
            .compute = true, .compute_group_size = {8, 8},
            .output = PL_SHADER_SIG_COLOR,
            .descriptors = descriptors, .num_descriptors = 2,
            .variables = variables, .num_variables = 3,
        }));
        assert(pl_dispatch_finish(dispatch, pl_dispatch_params(
            .shader = &shader, .target = target,
        )));
        readback(gpu, target, output);
        int sample_x = test == 6 ? WIDTH / 2 : WIDTH / 4;
        int index = ((HEIGHT / 2) * WIDTH + sample_x) * 4;
        assert(isfinite(output[index + 2]) && isfinite(output[index + 3]));
        if (test == 0) {
            assert(output[index + 2] == 0);
            assert(fabs(output[index] + 4) < 0.001);
            assert(output[index + 3] > 0.99);
        } else if (test == 6) {
            assert(fabs(output[index] + 0.25f) < 0.01f);
            assert(output[index + 2] > 0.99);
            assert(output[index + 3] > 0.99);
        } else {
            assert(output[index + 2] == 0);
            assert(output[index + 3] == 0);
        }
        printf("motion policy case=%d unique=%.3f recovered=%.3f\n",
               test, output[index + 2], output[index + 3]);
        pl_tex_destroy(gpu, &a);
        pl_tex_destroy(gpu, &b);
    }
    pl_dispatch_destroy(&dispatch);
    pl_tex_destroy(gpu, &target);
    free(pixels[0]);
    free(pixels[1]);
    free(output);
}

static void synthesis_policy(pl_gpu gpu)
{
    float *pixels = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    float *output = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    assert(pixels && output);
    pl_tex target = policy_target(gpu);
    pl_dispatch dispatch = pl_dispatch_create(gpu->log, gpu);
    assert(dispatch);
    for (int test = 0; test < 4; test++) {
        pl_tex inputs[5];
        for (int input = 0; input < 5; input++) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int index = (y * WIDTH + x) * 4;
                    for (int c = 0; c < 4; c++)
                        pixels[index + c] = 0;
                    if (input < 2) {
                        float value = test == 1 && input == 1 ? 0.95f : 1;
                        bool bright = x >= 40 + 16 * input && x < 48 + 16 * input;
                        for (int c = 0; c < 3; c++)
                            pixels[index + c] = bright ? value : 0;
                        pixels[index + 3] = 1;
                    } else if (input < 4) {
                        pixels[index] = input == 2 ? -8 : 8;
                        if (test == 2 && input == 2)
                            pixels[index] = NAN;
                        pixels[index + 3] = test == 3 && input == 3 ? 0 : 1;
                    }
                }
            }
            inputs[input] = upload(gpu, pixels);
        }
        struct pl_shader_desc descriptors[] = {
            policy_sampled("first", inputs[0]), policy_sampled("second", inputs[1]),
            policy_sampled("forward", inputs[2]), policy_sampled("backward", inputs[3]),
            policy_sampled("scene", inputs[4]),
        };
        float size[] = {WIDTH, HEIGHT};
        float source_size[] = {WIDTH, HEIGHT};
        float alpha = 0.5f;
        struct pl_shader_var variables[] = {
            {.var = pl_var_vec2("motion_size"), .data = size},
            {.var = pl_var_vec2("output_size"), .data = size},
            {.var = pl_var_vec2("source_size"), .data = source_size},
            {.var = pl_var_float("alpha"), .data = &alpha},
        };
        pl_shader shader = pl_dispatch_begin(dispatch);
        assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
            .header = test_sample_header,
            .body = test_synthesis_body,
            .compute = true, .compute_group_size = {8, 8},
            .output = PL_SHADER_SIG_COLOR,
            .descriptors = descriptors, .num_descriptors = 5,
            .variables = variables, .num_variables = 4,
        }));
        assert(pl_dispatch_finish(dispatch, pl_dispatch_params(
            .shader = &shader, .target = target,
        )));
        readback(gpu, target, output);
        float value = output[((HEIGHT / 2) * WIDTH + 50) * 4];
        assert(isfinite(value));
        assert(fabs(value - (test == 0 ? 1 : 0)) < 0.001);
        printf("synthesis policy case=%d midpoint=%.3f\n", test, value);
        for (int input = 0; input < 5; input++)
            pl_tex_destroy(gpu, &inputs[input]);
    }
    pl_dispatch_destroy(&dispatch);
    pl_tex_destroy(gpu, &target);
    free(pixels);
    free(output);
}

static void scene_transitions(pl_gpu gpu, struct mp_motion *motion, bool fade)
{
    float *first = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    float *second = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    float *output = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    assert(first && second && output);
    for (int y = 0; y < HEIGHT; y++) {
        for (int x = 0; x < WIDTH; x++) {
            for (int c = 0; c < 4; c++) {
                int index = (y * WIDTH + x) * 4 + c;
                first[index] = c == 3 ? 1 : fade
                             ? 0.05f + 0.8f * noise(x, y, 0) : noise(x, y, c);
                second[index] = c == 3 ? 1 : fade ? first[index] + 0.12f
                              : noise((37 * x + 53) % WIDTH,
                                      (17 * y + 31) % HEIGHT, c);
            }
        }
    }
    pl_tex a = upload(gpu, first);
    pl_tex b = upload(gpu, second);
    for (int step = 1; step <= 3; step++) {
        float alpha = step * 0.25f;
        pl_tex result;
        assert(mp_motion_render(motion, a, b, 50, 51, alpha, &result));
        readback(gpu, result, output);
        double error = 0;
        for (int i = 0; i < WIDTH * HEIGHT * 4; i++) {
            float expected = fade ? first[i] * (1 - alpha) + second[i] * alpha
                           : alpha < 0.5f ? first[i] : second[i];
            assert(isfinite(output[i]));
            error += fabs(output[i] - expected);
        }
        error /= WIDTH * HEIGHT * 4;
        printf("%s alpha=%.2f error=%.6f\n",
               fade ? "brightness fade" : "equal-histogram scene cut", alpha, error);
        fflush(stdout);
        assert(error < 0.003);
    }
    mp_motion_reset(motion);
    pl_tex_destroy(gpu, &a);
    pl_tex_destroy(gpu, &b);
    free(first);
    free(second);
    free(output);
}

static void foreground_pair(pl_gpu gpu, struct mp_motion *motion,
                            int width, int height, int scale)
{
    float *pixels[2] = {calloc(width * height * 4, sizeof(float)),
                        calloc(width * height * 4, sizeof(float))};
    float *output = calloc(width * height * 4, sizeof(float));
    assert(pixels[0] && pixels[1] && output);
    for (int frame = 0; frame < 2; frame++) {
        int shift = frame * 16;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                bool foreground = x >= (24 + shift) * scale &&
                                  x < (88 + shift) * scale &&
                                  y >= 16 * scale && y < 80 * scale;
                for (int c = 0; c < 4; c++) {
                    float sx = (x + 0.5f) / scale - 0.5f;
                    float sy = (y + 0.5f) / scale - 0.5f;
                    pixels[frame][(y * width + x) * 4 + c] = c == 3 ? 1 :
                        foreground ? noise(x / scale - shift, y / scale, c)
                                   : signal(sx, sy, c);
                }
            }
        }
    }
    pl_tex a = upload_size(gpu, pixels[0], width, height);
    pl_tex b = upload_size(gpu, pixels[1], width, height);
    pl_tex result;
    assert(mp_motion_render(motion, a, b, 40, 41, 0.5f, &result));
    assert(result->params.w == width && result->params.h == height);
    readback(gpu, result, output);
    double foreground_error = 0, background_error = 0;
    for (int y = 36 * scale; y < 60 * scale; y++) {
        for (int x = 56 * scale; x < 72 * scale; x++) {
            for (int c = 0; c < 3; c++)
                foreground_error += fabs(output[(y * width + x) * 4 + c] -
                                          noise(x / scale - 8, y / scale, c));
        }
    }
    for (int y = 2 * scale; y < 10 * scale; y++) {
        for (int x = 2 * scale; x < 10 * scale; x++) {
            float sx = (x + 0.5f) / scale - 0.5f;
            float sy = (y + 0.5f) / scale - 0.5f;
            for (int c = 0; c < 3; c++)
                background_error += fabs(output[(y * width + x) * 4 + c] -
                                          signal(sx, sy, c));
        }
    }
    foreground_error /= 24 * 16 * 3 * scale * scale;
    background_error /= 8 * 8 * 3 * scale * scale;
    printf("independent foreground %dx%d: foreground_error=%.6f "
           "background_error=%.6f\n",
           width, height, foreground_error, background_error);
    fflush(stdout);
    assert(foreground_error < 0.04);
    assert(background_error < 0.003);
    mp_motion_reset(motion);
    pl_tex_destroy(gpu, &a);
    pl_tex_destroy(gpu, &b);
    free(pixels[0]);
    free(pixels[1]);
    free(output);
}

struct captured_source {
    pl_tex texture;
    int acquired;
    int released;
};

static float ramp(float x, float y, int channel)
{
    if (channel == 0)
        return 0.1f + 0.6f * x / WIDTH;
    if (channel == 1)
        return 0.1f + 0.6f * y / HEIGHT;
    return 0.1f + 0.2f * x / WIDTH + 0.2f * y / HEIGHT;
}

static bool acquire(pl_gpu gpu, struct pl_frame *frame)
{
    (void)gpu;
    struct captured_source *source = frame->user_data;
    source->acquired++;
    frame->planes[0].texture = source->texture;
    return true;
}

static void release(pl_gpu gpu, struct pl_frame *frame)
{
    (void)gpu;
    struct captured_source *source = frame->user_data;
    source->released++;
}

static void capture_ownership(pl_gpu gpu, struct mp_motion *motion)
{
    float *pixels = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    float *output = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    assert(pixels && output);
    pattern(pixels, 0);
    struct captured_source source = {.texture = upload(gpu, pixels)};
    struct pl_frame frame = {
        .num_planes = 1,
        .planes[0] = {.components = 4, .component_mapping = {0, 1, 2, 3}},
        .repr = pl_color_repr_rgb, .color = pl_color_space_bt709,
        .rotation = PL_ROTATION_90,
        .acquire = acquire, .release = release, .user_data = &source,
    };
    pl_tex captured = NULL;
    assert(mp_motion_capture(motion, &frame, WIDTH, HEIGHT, &captured));
    assert(source.acquired > 0 && source.acquired == source.released);
    assert(frame.rotation == PL_ROTATION_90);
    pl_tex_destroy(gpu, &source.texture);
    readback(gpu, captured, output);
    for (int i = 0; i < WIDTH * HEIGHT * 4; i++)
        assert(fabs(output[i] - pixels[i]) < 0.002);
    for (int y = 0; y < HEIGHT; y++) {
        for (int x = 0; x < WIDTH; x++) {
            for (int c = 0; c < 3; c++)
                pixels[(y * WIDTH + x) * 4 + c] = ramp(x, y, c);
        }
    }
    source.texture = upload(gpu, pixels);
    frame.rotation = PL_ROTATION_0;
    frame.color.transfer = PL_COLOR_TRC_LINEAR;
    frame.crop = (struct pl_rect2df) {8, 4, 120, 92};
    assert(mp_motion_capture(motion, &frame, WIDTH, HEIGHT, &captured));
    assert(source.acquired == source.released);
    readback(gpu, captured, output);
    float maximum_error = 0;
    for (int y = 4; y < HEIGHT - 4; y++) {
        for (int x = 4; x < WIDTH - 4; x++) {
            for (int c = 0; c < 3; c++) {
                float sx = 8 + (x + 0.5f) * 112 / WIDTH - 0.5f;
                float sy = 4 + (y + 0.5f) * 88 / HEIGHT - 0.5f;
                float error = fabs(output[(y * WIDTH + x) * 4 + c] -
                                   ramp(sx, sy, c));
                if (error > maximum_error)
                    maximum_error = error;
            }
        }
    }
    printf("capture source crop: maximum error %.6f\n", maximum_error);
    fflush(stdout);
    assert(maximum_error < 0.003);
    pl_tex_destroy(gpu, &source.texture);
    pl_tex_destroy(gpu, &captured);
    free(pixels);
    free(output);
    puts("capture: balanced acquire/release, independent RGB storage, unrotated pixels passed");
}

static void color_roundtrip(pl_gpu gpu, struct mp_motion *motion)
{
    float *pixels = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    float *expected = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    float *output = calloc(WIDTH * HEIGHT * 4, sizeof(float));
    assert(pixels && expected && output);
    pattern(pixels, 0);
    pl_tex source = upload(gpu, pixels);
    struct pl_frame frame = {
        .num_planes = 1,
        .planes[0] = {
            .texture = source, .components = 4,
            .component_mapping = {0, 1, 2, 3},
        },
        .repr = pl_color_repr_rgb,
        .color = pl_color_space_bt709,
    };
    frame.color.primaries = PL_COLOR_PRIM_BT_601_525;
    frame.color.hdr.max_luma = 160;
    pl_tex captured = NULL;
    assert(mp_motion_capture(motion, &frame, WIDTH, HEIGHT, &captured));
    pl_fmt format = pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 32, 32,
        PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_HOST_READABLE);
    assert(format);
    pl_tex target = pl_tex_create(gpu, pl_tex_params(
        .w = WIDTH, .h = HEIGHT, .format = format,
        .renderable = true, .host_readable = true,
    ));
    assert(target);
    struct pl_frame destination = frame;
    destination.planes[0].texture = target;
    destination.color = pl_color_space_bt709;
    pl_renderer renderer = pl_renderer_create(gpu->log, gpu);
    struct pl_render_params params = pl_render_default_params;
    params.dither_params = NULL;
    params.peak_detect_params = NULL;
    assert(pl_render_image(renderer, &frame, &destination, &params));
    assert(pl_tex_download(gpu, pl_tex_transfer_params(.tex = target, .ptr = expected)));
    frame.planes[0].texture = captured;
    assert(frame.color.hdr.max_luma == 160);
    assert(pl_render_image(renderer, &frame, &destination, &params));
    assert(pl_tex_download(gpu, pl_tex_transfer_params(.tex = target, .ptr = output)));
    float maximum_error = 0;
    for (int i = 0; i < WIDTH * HEIGHT * 4; i++) {
        float error = fabs(output[i] - expected[i]);
        if (error > maximum_error)
            maximum_error = error;
    }
    printf("BT.601 RGB capture and SDR reference white: maximum error %.6f\n", maximum_error);
    fflush(stdout);
    assert(maximum_error < 0.003);
    pl_renderer_destroy(&renderer);
    pl_tex_destroy(gpu, &target);
    pl_tex_destroy(gpu, &source);
    pl_tex_destroy(gpu, &captured);
    free(pixels);
    free(expected);
    free(output);
}

static pl_tex cache_input(pl_gpu gpu, int width, int height, int offset)
{
    float *pixels = calloc(width * height * 4, sizeof(float));
    assert(pixels);
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            for (int c = 0; c < 4; c++)
                pixels[(y * width + x) * 4 + c] = c == 3
                    ? 1 : noise(x + offset, y - offset, c);
        }
    }
    pl_tex result = upload_size(gpu, pixels, width, height);
    free(pixels);
    return result;
}

static void cache_compare(pl_gpu gpu, struct mp_motion *motion,
                          struct mp_motion *fresh, pl_tex first, pl_tex second,
                          uint64_t first_id, uint64_t second_id, float alpha,
                          bool expected_analysis)
{
    struct mp_motion_stats stats = mp_motion_get_stats(motion);
    pl_tex result = NULL;
    assert(mp_motion_render(motion, first, second, first_id, second_id,
                            alpha, &result));
    assert(mp_motion_get_stats(motion).analyzed_pairs - stats.analyzed_pairs ==
           expected_analysis);
    assert(result->params.w == first->params.w);
    assert(result->params.h == first->params.h);
    size_t count = (size_t)first->params.w * first->params.h * 4;
    float *actual = calloc(count, sizeof(float));
    float *expected = calloc(count, sizeof(float));
    assert(actual && expected);
    readback(gpu, result, actual);
    mp_motion_reset(fresh);
    assert(mp_motion_render(fresh, first, second, first_id, second_id,
                            alpha, &result));
    readback(gpu, result, expected);
    for (size_t i = 0; i < count; i++) {
        assert(isfinite(actual[i]) && isfinite(expected[i]));
        assert(actual[i] == expected[i]);
    }
    free(actual);
    free(expected);
}

static void pyramid_reuse(pl_gpu gpu)
{
    pl_tex inputs[6];
    for (int i = 0; i < 6; i++)
        inputs[i] = cache_input(gpu, i < 4 ? 128 : 256,
                               i < 4 ? 96 : 192, i * 3);
    const struct {
        const char *name;
        int first, second;
        uint64_t first_id, second_id;
        bool reset, analyze;
    } cases[] = {
        {"forward-adjacent", 1, 2, 11, 12, false, true},
        {"backward-adjacent", 2, 0, 12, 10, false, true},
        {"shared-first", 0, 2, 10, 12, false, true},
        {"shared-second", 2, 1, 12, 11, false, true},
        {"reversed", 1, 0, 11, 10, false, true},
        {"disjoint", 2, 3, 12, 13, false, true},
        {"same-pair-new-alpha", 0, 1, 10, 11, false, false},
        {"reset-same-pair", 0, 1, 10, 11, true, true},
        {"same-ids-new-source-size", 4, 5, 10, 11, false, true},
    };
    for (int i = 0; i < (int)(sizeof(cases) / sizeof(cases[0])); i++) {
        struct mp_motion *motion = mp_motion_create(gpu, 128);
        struct mp_motion *fresh = mp_motion_create(gpu, 128);
        assert(motion && fresh);
        pl_tex result;
        assert(mp_motion_render(motion, inputs[0], inputs[1], 10, 11, 0.2f,
                                &result));
        if (cases[i].reset)
            mp_motion_reset(motion);
        cache_compare(gpu, motion, fresh, inputs[cases[i].first],
                      inputs[cases[i].second], cases[i].first_id,
                      cases[i].second_id, 0.5f, cases[i].analyze);
        printf("pyramid reuse %s: exact output\n", cases[i].name);
        mp_motion_destroy(&motion);
        mp_motion_destroy(&fresh);
    }
    for (int i = 0; i < 6; i++)
        pl_tex_destroy(gpu, &inputs[i]);
    puts("Pyramid reuse regression: passed (9 cases, exact half-output equality)");
}

static void padding_sizes(pl_gpu gpu)
{
    static const int sizes[][2] = {
        {8, 8}, {8, 17}, {17, 8}, {9, 9}, {65, 73}, {129, 97},
    };
    for (int i = 0; i < 6; i++) {
        int width = sizes[i][0], height = sizes[i][1];
        pl_tex first = cache_input(gpu, width, height, 0);
        pl_tex second = cache_input(gpu, width, height, 1);
        struct mp_motion *motion = mp_motion_create(gpu, 480);
        assert(motion);
        for (int step = 1; step <= 3; step++) {
            pl_tex result;
            assert(mp_motion_render(motion, first, second, 1, 2,
                                    step * 0.25f, &result));
            size_t count = (size_t) width * height * 4;
            float *pixels = malloc(count * sizeof(float));
            assert(pixels);
            readback(gpu, result, pixels);
            for (size_t j = 0; j < count; j++)
                assert(isfinite(pixels[j]));
            free(pixels);
            printf("padding size=%dx%d step=%d finite output\n", width, height, step);
        }
        assert(mp_motion_get_stats(motion).analyzed_pairs == 1);
        mp_motion_destroy(&motion);
        pl_tex_destroy(gpu, &first);
        pl_tex_destroy(gpu, &second);
    }
}

static void capabilities(pl_gpu gpu)
{
    assert(!mp_motion_supported(NULL));
    assert(mp_motion_supported(gpu));
    struct pl_gpu_t limited = *gpu;
    limited.glsl.compute = false;
    assert(!mp_motion_supported(&limited));
    limited = *gpu;
    limited.glsl.max_group_threads = 63;
    assert(!mp_motion_supported(&limited));
    limited = *gpu;
    limited.glsl.max_group_size[0] = 63;
    assert(!mp_motion_supported(&limited));
    limited = *gpu;
    limited.glsl.max_group_size[1] = 7;
    assert(!mp_motion_supported(&limited));
    limited = *gpu;
    limited.glsl.max_shmem_size = 49 * 16 + 169 * 4 - 1;
    assert(!mp_motion_supported(&limited));
    limited.glsl.max_shmem_size++;
    limited.glsl.max_group_threads = 64;
    limited.glsl.max_group_size[0] = 64;
    limited.glsl.max_group_size[1] = 8;
    assert(mp_motion_supported(&limited));
}

static float reference_sample(const float *pixels, int width, int height,
                              float x, float y, int channel)
{
    x = fminf(width - 1, fmaxf(0, x));
    y = fminf(height - 1, fmaxf(0, y));
    int x0 = (int)floorf(x), y0 = (int)floorf(y);
    int x1 = x0 + 1 < width ? x0 + 1 : x0;
    int y1 = y0 + 1 < height ? y0 + 1 : y0;
    float fx = x - x0, fy = y - y0;
    float a = pixels[(y0 * width + x0) * 4 + channel];
    float b = pixels[(y0 * width + x1) * 4 + channel];
    float c = pixels[(y1 * width + x0) * 4 + channel];
    float d = pixels[(y1 * width + x1) * 4 + channel];
    return (a + fx * (b - a)) * (1 - fy) + (c + fx * (d - c)) * fy;
}

static float reference_smooth(float lo, float hi, float value)
{
    float t = fminf(1, fmaxf(0, (value - lo) / (hi - lo)));
    return t * t * (3 - 2 * t);
}

static void synthesis_sampling(pl_gpu gpu)
{
    int width = 1920, height = 1080;
    size_t bytes = (size_t)width * height * 4 * sizeof(float);
    float *pixels = malloc(bytes), *first = malloc(bytes);
    float *second = malloc(bytes), *output = malloc(bytes);
    assert(pixels && first && second && output);
    pl_dispatch dispatch = pl_dispatch_create(gpu->log, gpu);
    bool failed = false;
    for (int test = 0; test < 3; test++) {
        width = test == 2 ? 127 : 1920;
        height = test == 2 ? 95 : 1080;
        float shift = test ? .30029296875f : 6;
        diagonal_pixels(pixels, width, height, 4, 0, true, 0);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            for (int c = 0; c < 3; c++) {
                int index = (y * width + x) * 4 + c;
                pixels[index] = fminf(1, fmaxf(0, pixels[index] +
                    (noise(x, y, c) - .5f) / 255));
            }
        if (test == 2) for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            pixels[(y * width + x) * 4 + 3] = (float)x / (width - 1);
        pl_tex a = upload_size(gpu, pixels, width, height);
        diagonal_pixels(pixels, width, height, 4, shift, true, 0);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            for (int c = 0; c < 3; c++) {
                int index = (y * width + x) * 4 + c;
                pixels[index] = fminf(1, fmaxf(0, pixels[index] +
                    (noise(x, y, c + (int)(shift * 4)) - .5f) / 255));
            }
        if (test == 2) for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            pixels[(y * width + x) * 4 + 3] = (float)y / (height - 1);
        pl_tex b = upload_size(gpu, pixels, width, height);
        readback(gpu, a, first);
        readback(gpu, b, second);
        pl_fmt format = pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 16, 0,
            PL_FMT_CAP_SAMPLEABLE | PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_STORABLE);
        assert(format);
        pl_tex target = pl_tex_create(gpu, pl_tex_params(
            .w = width, .h = height, .format = format,
            .sampleable = true, .renderable = true, .storable = true));
        assert(target);
        for (int step = 1; step <= 3; step++) {
            float alpha = step * .25f;
            pl_tex maps[3];
            float displacement[2];
            for (int direction = 0; direction < 3; direction++) {
                for (int i = 0; i < 480 * 270; i++) {
                    pixels[i * 4] = 0;
                    pixels[i * 4 + 1] = direction == 0 ? -alpha * shift :
                        direction == 1 ? (1 - alpha) * shift : 0;
                    pixels[i * 4 + 2] = direction < 2 ? .0625f : 0;
                    pixels[i * 4 + 3] = 1;
                }
                maps[direction] = upload_size(gpu, pixels, 480, 270);
                if (direction < 2) {
                    readback(gpu, maps[direction], pixels);
                    displacement[direction] = pixels[1] * ((float)height / 270);
                }
            }
            struct pl_shader_desc descriptors[] = {
                policy_sampled("first", a), policy_sampled("second", b),
                policy_sampled("forward", maps[0]), policy_sampled("backward", maps[1]),
                policy_sampled("scene", maps[2]),
            };
            float size[] = {width, height}, motion_size[] = {480, 270};
            float source_size[] = {width, height};
            struct pl_shader_var variables[] = {
                {.var = pl_var_vec2("motion_size"), .data = motion_size},
                {.var = pl_var_vec2("output_size"), .data = size},
                {.var = pl_var_vec2("source_size"), .data = source_size},
                {.var = pl_var_float("alpha"), .data = &alpha},
            };
            pl_shader shader = pl_dispatch_begin(dispatch);
            assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
                .header = test_sample_header, .body = test_synthesis_body,
                .compute = true, .compute_group_size = {8, 8},
                .output = PL_SHADER_SIG_COLOR,
                .descriptors = descriptors, .num_descriptors = 5,
                .variables = variables, .num_variables = 4,
            }));
            assert(pl_dispatch_finish(dispatch, pl_dispatch_params(
                .shader = &shader, .target = target,
            )));
            readback(gpu, target, output);
            float maximum = 0;
            int worst_x = 0, worst_y = 0;
            int border = test == 2 ? 0 : 64;
            for (int y = border; y < height - border; y++) {
                for (int x = border; x < width - border; x++) {
                    float wa[4], wb[4], conflict = 0;
                    for (int c = 0; c < 4; c++) {
                        wa[c] = reference_sample(first, width, height, x, y + displacement[0], c);
                        wb[c] = reference_sample(second, width, height, x, y + displacement[1], c);
                        if (c < 3) conflict = fmaxf(conflict, fabsf(wa[c] - wb[c]));
                    }
                    // Known geometry is reliable; coarse-match quality is lower.
                    float trust = fmaxf(.0625f, 1 - reference_smooth(.002f, .02f, conflict));
                    trust *= 1 - reference_smooth(.03f, .15f, conflict);
                    for (int c = 0; c < 4; c++) {
                        int index = (y * width + x) * 4 + c;
                        float original = first[index] * (1 - alpha) + second[index] * alpha;
                        float warped = wa[c] * (1 - alpha) + wb[c] * alpha;
                        float expected = original * (1 - trust) + warped * trust;
                        assert(isfinite(output[index]) && isfinite(expected));
                        float difference = fabsf(output[index] - expected);
                        if (difference > maximum) {
                            maximum = difference; worst_x = x; worst_y = y;
                        }
                    }
                }
            }
            printf("synthesis sampling case=%d alpha=%.2f max=%.9g at=%d,%d\n",
                   test, alpha, maximum, worst_x, worst_y);
            fflush(stdout);
            failed |= maximum >= .002f;
            for (int i = 0; i < 3; i++) pl_tex_destroy(gpu, &maps[i]);
        }
        pl_tex_destroy(gpu, &a); pl_tex_destroy(gpu, &b);
        pl_tex_destroy(gpu, &target);
    }
    free(pixels); free(first); free(second); free(output);
    pl_dispatch_destroy(&dispatch);
    assert(!failed);
}

static void dense_pair_equivalence(pl_gpu gpu)
{
    static const float vectors[][2] = {
        {0, 0}, {3, 0}, {1.25f, -0.5f}, {-3.75f, 2.125f},
    };
    float *pixels = malloc(WIDTH * HEIGHT * 4 * sizeof(float));
    assert(pixels);
    pattern(pixels, 0);
    pl_tex first = upload(gpu, pixels);
    pattern(pixels, 3);
    pl_tex second = upload(gpu, pixels);
    free(pixels);
    pl_fmt format = pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 32, 32,
        PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_STORABLE | PL_FMT_CAP_HOST_READABLE);
    assert(format);
    pl_tex target = pl_tex_create(gpu, pl_tex_params(
        .w = 32, .h = 24, .format = format,
        .renderable = true, .storable = true, .host_readable = true,
    ));
    assert(target);
    pl_dispatch dispatch = pl_dispatch_create(gpu->log, gpu);
    assert(dispatch);
    struct pl_shader_desc descriptors[] = {
        policy_sampled("first", first), policy_sampled("second", second),
    };
    for (size_t i = 0; i < sizeof(vectors) / sizeof(vectors[0]); i++) {
        struct pl_shader_var variable = {
            .var = pl_var_vec2("vector"), .data = vectors[i], .dynamic = true,
        };
        pl_shader shader = pl_dispatch_begin(dispatch);
        assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
            .header = test_sample_header,
        }));
        assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
            .header = test_refine_header,
            .body = "vec2 p = vec2(gl_GlobalInvocationID.xy) *"
                    " vec2(2.75, 2.5) + 0.375;"
                    "vec2 cost = dense_pair_cost(first, second, p, vector);"
                    "color = vec4(cost, dense_cost(first, second, p, vec2(0), 1e20),"
                    "dense_cost(first, second, p, vector, 1e20));",
            .compute = true, .compute_group_size = {8, 8},
            .output = PL_SHADER_SIG_COLOR,
            .descriptors = descriptors, .num_descriptors = 2,
            .variables = &variable, .num_variables = 1,
        }));
        assert(pl_dispatch_finish(dispatch, pl_dispatch_params(
            .shader = &shader, .target = target,
        )));
        uint32_t actual[32 * 24 * 4];
        assert(pl_tex_download(gpu, pl_tex_transfer_params(
            .tex = target, .ptr = actual,
        )));
        for (int pixel = 0; pixel < 32 * 24; pixel++) {
            assert(actual[pixel * 4] == actual[pixel * 4 + 2]);
            assert(actual[pixel * 4 + 1] == actual[pixel * 4 + 3]);
        }
        printf("dense pair vector=%.3f,%.3f exact_costs=1536\n",
               vectors[i][0], vectors[i][1]);
    }
    pl_dispatch_destroy(&dispatch);
    pl_tex_destroy(gpu, &first);
    pl_tex_destroy(gpu, &second);
    pl_tex_destroy(gpu, &target);
}

static void evidence_bits(pl_gpu gpu)
{
    static const uint32_t values[] = {
        0x00000000, 0x80000000, 0x00800000, 0x80800000,
        0x358637bc, 0x358637bd, 0x358637be,
        0x3dcccccc, 0x3dcccccd, 0x3dccccce,
        0x3f7fffff, 0x3f800000, 0x3f800001,
        0x7f7fffff, 0xff7fffff,
    };
    pl_fmt format = pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 16, 0,
        PL_FMT_CAP_SAMPLEABLE | PL_FMT_CAP_LINEAR |
        PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_STORABLE);
    assert(format);
    pl_tex encoded = pl_tex_create(gpu, pl_tex_params(
        .w = 1, .h = 1, .format = format,
        .sampleable = true, .renderable = true, .storable = true,
    ));
    format = pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 32, 32,
        PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_STORABLE | PL_FMT_CAP_HOST_READABLE);
    assert(format);
    pl_tex decoded = pl_tex_create(gpu, pl_tex_params(
        .w = 1, .h = 1, .format = format,
        .renderable = true, .storable = true, .host_readable = true,
    ));
    assert(encoded && decoded);
    pl_dispatch dispatch = pl_dispatch_create(gpu->log, gpu);
    assert(dispatch);
    for (size_t i = 0; i < sizeof(values) / sizeof(values[0]); i++) {
        union { uint32_t bits; float value; } input = {.bits = values[i]};
        struct pl_shader_var variable = {
            .var = pl_var_float("value"), .data = &input.value, .dynamic = true,
        };
        pl_shader shader = pl_dispatch_begin(dispatch);
        assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
            .header = test_sample_header,
        }));
        assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
            .header = test_refine_header,
            .body = "color = pack_evidence(value);",
            .compute = true, .compute_group_size = {1, 1},
            .output = PL_SHADER_SIG_COLOR,
            .variables = &variable, .num_variables = 1,
        }));
        assert(pl_dispatch_finish(dispatch, pl_dispatch_params(
            .shader = &shader, .target = encoded,
        )));
        struct pl_shader_desc descriptor = policy_sampled("encoded", encoded);
        shader = pl_dispatch_begin(dispatch);
        assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
            .header = test_sample_header,
        }));
        assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
            .header = test_refine_header,
            .body = "color = vec4(unpack_evidence("
                    "texelFetch(encoded, ivec2(0), 0)));",
            .compute = true, .compute_group_size = {1, 1},
            .output = PL_SHADER_SIG_COLOR,
            .descriptors = &descriptor, .num_descriptors = 1,
        }));
        assert(pl_dispatch_finish(dispatch, pl_dispatch_params(
            .shader = &shader, .target = decoded,
        )));
        uint32_t actual[4];
        assert(pl_tex_download(gpu, pl_tex_transfer_params(
            .tex = decoded, .ptr = actual,
        )));
        for (int c = 0; c < 4; c++)
            assert(actual[c] == values[i]);
        printf("evidence bits input=%08x output=%08x\n", values[i], actual[0]);
    }
    pl_dispatch_destroy(&dispatch);
    pl_tex_destroy(gpu, &encoded);
    pl_tex_destroy(gpu, &decoded);
}

int main(void)
{
    pl_log log = pl_log_create(PL_API_VER, pl_log_params(
        .log_cb = log_message, .log_level = PL_LOG_WARN,
    ));
    struct pl_vulkan_params vulkan_params = pl_vulkan_default_params;
    vulkan_params.allow_software = true;
    pl_vulkan vulkan = pl_vulkan_create(log, &vulkan_params);
    if (!vulkan) {
        fputs("Vulkan device unavailable\n", stderr);
        return 77;
    }
    VkPhysicalDeviceProperties device;
    PFN_vkGetPhysicalDeviceProperties get_properties =
        (PFN_vkGetPhysicalDeviceProperties) vulkan->get_proc_addr(
            vulkan->instance, "vkGetPhysicalDeviceProperties");
    assert(get_properties);
    get_properties(vulkan->phys_device, &device);
    printf("Vulkan device: %s (API %u.%u.%u)\n", device.deviceName,
           VK_VERSION_MAJOR(device.apiVersion), VK_VERSION_MINOR(device.apiVersion),
           VK_VERSION_PATCH(device.apiVersion));
    fflush(stdout);
    struct mp_motion *motion = mp_motion_create(vulkan->gpu, 128);
    capabilities(vulkan->gpu);
    evidence_bits(vulkan->gpu);
    dense_pair_equivalence(vulkan->gpu);
    assert(motion);
    subpixel_matching(vulkan->gpu);
    pyramid_reuse(vulkan->gpu);
    padding_sizes(vulkan->gpu);
    translated_pair(vulkan->gpu, motion);
    oblique_texture_translation(vulkan->gpu, motion);
    affine_pair(vulkan->gpu, motion, 129, 97, 1);
    scaled_source_pair(vulkan->gpu, motion);
    foreground_pair(vulkan->gpu, motion, WIDTH, HEIGHT, 1);
    static_and_cut(vulkan->gpu, motion);
    straight_edge(vulkan->gpu, motion, 1, false, 8);
    straight_edge(vulkan->gpu, motion, 1, true, 8);
    straight_edge(vulkan->gpu, motion, 4, false, 8);
    straight_edge(vulkan->gpu, motion, 4, true, 8);
    straight_edge(vulkan->gpu, motion, 1, false, 2);
    straight_edge(vulkan->gpu, motion, 1, true, 2);
    straight_edge(vulkan->gpu, motion, 4, false, 2);
    straight_edge(vulkan->gpu, motion, 4, true, 2);
    warp_policy(vulkan->gpu);
    synthesis_policy(vulkan->gpu);
    scene_transitions(vulkan->gpu, motion, true);
    scene_transitions(vulkan->gpu, motion, false);
    capture_ownership(vulkan->gpu, motion);
    color_roundtrip(vulkan->gpu, motion);
    mp_motion_destroy(&motion);
    assert(!motion);
    motion = mp_motion_create(vulkan->gpu, 480);
    assert(motion);
    affine_pair(vulkan->gpu, motion, 1920, 1080, 8);
    foreground_pair(vulkan->gpu, motion, 1920, 1080, 4);
    for (int variant = 0; variant < 28; variant++) {
        diagonal_edge(vulkan->gpu, motion, false, variant);
        diagonal_edge(vulkan->gpu, motion, true, variant);
    }
    synthesis_sampling(vulkan->gpu);
    mp_motion_destroy(&motion);
    pl_vulkan_destroy(&vulkan);
    assert(gpu_errors == 0);
    pl_log_destroy(&log);
    puts("GPU motion interpolation: passed");
    return 0;
}
