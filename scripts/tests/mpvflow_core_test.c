/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
#include "mpvflow_core.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static void fill_texture(uint8_t *frame, int width, int height, int rect_x)
{
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            size_t i = ((size_t)y * width + x) * 3;
            uint8_t background = (uint8_t)(24 + ((x * 3 + y * 5) % 40));
            frame[i] = background;
            frame[i + 1] = (uint8_t)(background + 12);
            frame[i + 2] = (uint8_t)(background > 12 ? background - 12 : 0);
            if (x >= rect_x && x < rect_x + 8 && y >= 20 && y < 36) {
                frame[i] = 240;
                frame[i + 1] = 38;
                frame[i + 2] = 24;
            }
        }
    }
}
static void test_translation_is_compensated(void)
{
    const int width = 96, height = 64;
    size_t bytes = (size_t)width * height * 3;
    uint8_t *a = malloc(bytes), *b = malloc(bytes), *out = malloc(bytes);
    assert(a && b && out);
    fill_texture(a, width, height, 24);
    fill_texture(b, width, height, 32);
    MPVFlowContext *context = mpvflow_create(8, 8);
    assert(context);
    MPVFlowPair *pair = NULL;
    struct MPVFlowStats stats = {0};
    assert(mpvflow_analyze_pair(context, a, b, width, height, &pair, &stats) == MPVFLOW_OK);
    assert(pair);
    assert(mpvflow_synthesize_rgb24(context, pair, a, b, 0.25f, out, NULL) == MPVFLOW_OK);
    assert(mpvflow_synthesize_rgb24(context, pair, a, b, 0.5f, out, &stats) == MPVFLOW_OK);
    /* The moving red object should occupy the half-way position, not ghost at
     * either source position. */
    size_t center = ((size_t)27 * width + 31) * 3;
    assert(out[center] > 180);
    assert(out[center + 1] < 90);
    assert(stats.mean_motion_pixels > 0.1);
    assert(mpvflow_synthesize_rgb24(context, pair, a, b, 0.75f, out, NULL) == MPVFLOW_OK);
    mpvflow_pair_destroy(pair);
    mpvflow_destroy(context);
    free(a);
    free(b);
    free(out);
}
static void test_identical_frames_are_preserved(void)
{
    const int width = 64, height = 48;
    size_t bytes = (size_t)width * height * 3;
    uint8_t *a = malloc(bytes), *out = malloc(bytes);
    assert(a && out);
    fill_texture(a, width, height, 20);
    MPVFlowContext *context = mpvflow_create(8, 6);
    assert(context);
    MPVFlowPair *pair = NULL;
    assert(mpvflow_analyze_pair(context, a, a, width, height, &pair, NULL) == MPVFLOW_OK);
    assert(mpvflow_synthesize_rgb24(context, pair, a, a, 0.37f, out, NULL) == MPVFLOW_OK);
    assert(memcmp(a, out, bytes) == 0);
    mpvflow_pair_destroy(pair);
    mpvflow_destroy(context);
    free(a);
    free(out);
}
static void test_parallel_synthesis_is_deterministic(void)
{
    const int width = 160, height = 128;
    size_t bytes = (size_t)width * height * 3;
    uint8_t *a = malloc(bytes), *b = malloc(bytes);
    uint8_t *out0 = malloc(bytes), *out1 = malloc(bytes);
    assert(a && b && out0 && out1);
    fill_texture(a, width, height, 40);
    fill_texture(b, width, height, 48);
    MPVFlowContext *context = mpvflow_create(8, 8);
    assert(context);
    MPVFlowPair *pair = NULL;
    assert(mpvflow_analyze_pair(context, a, b, width, height, &pair, NULL) == MPVFLOW_OK);
    assert(mpvflow_synthesize_rgb24(context, pair, a, b, 0.5f, out0, NULL) == MPVFLOW_OK);
    assert(mpvflow_synthesize_rgb24(context, pair, a, b, 0.5f, out1, NULL) == MPVFLOW_OK);
    assert(memcmp(out0, out1, bytes) == 0);
    mpvflow_pair_destroy(pair);
    mpvflow_destroy(context);
    free(a);
    free(b);
    free(out0);
    free(out1);
}
static void test_parallel_analysis_is_deterministic(void)
{
    const int width = 320, height = 192;
    size_t bytes = (size_t)width * height * 3;
    uint8_t *a = malloc(bytes), *b = malloc(bytes);
    uint8_t *out0 = malloc(bytes), *out1 = malloc(bytes);
    assert(a && b && out0 && out1);
    fill_texture(a, width, height, 96);
    fill_texture(b, width, height, 104);
    MPVFlowContext *context0 = mpvflow_create(8, 8);
    MPVFlowContext *context1 = mpvflow_create(8, 8);
    assert(context0 && context1);
    MPVFlowPair *pair0 = NULL, *pair1 = NULL;
    struct MPVFlowStats stats0 = {0}, stats1 = {0};
    assert(mpvflow_analyze_pair(context0, a, b, width, height,
                                &pair0, &stats0) == MPVFLOW_OK);
    assert(mpvflow_analyze_pair(context1, a, b, width, height,
                                &pair1, &stats1) == MPVFLOW_OK);
    assert(pair0 && pair1);
    assert(stats0.mean_block_sad == stats1.mean_block_sad);
    assert(stats0.mean_vector_consistency_error ==
           stats1.mean_vector_consistency_error);
    assert(stats0.mean_motion_pixels == stats1.mean_motion_pixels);
    assert(mpvflow_synthesize_rgb24(context0, pair0, a, b, 0.5f,
                                    out0, NULL) == MPVFLOW_OK);
    assert(mpvflow_synthesize_rgb24(context1, pair1, a, b, 0.5f,
                                    out1, NULL) == MPVFLOW_OK);
    assert(memcmp(out0, out1, bytes) == 0);
    mpvflow_pair_destroy(pair0);
    mpvflow_pair_destroy(pair1);
    mpvflow_destroy(context0);
    mpvflow_destroy(context1);
    free(a);
    free(b);
    free(out0);
    free(out1);
}
static void test_scene_cut_avoids_synthetic_blend(void)
{
    const int width = 64, height = 48;
    size_t bytes = (size_t)width * height * 3;
    uint8_t *a = calloc(bytes, 1), *b = malloc(bytes), *out = malloc(bytes);
    assert(a && b && out);
    memset(b, 255, bytes);
    MPVFlowContext *context = mpvflow_create(8, 6);
    assert(context);
    MPVFlowPair *pair = NULL;
    struct MPVFlowStats stats = {0};
    assert(mpvflow_analyze_pair(context, a, b, width, height, &pair, &stats) == MPVFLOW_SCENE_CUT);
    assert(mpvflow_synthesize_rgb24(context, pair, a, b, 0.4f, out, NULL) == MPVFLOW_SCENE_CUT);
    assert(memcmp(a, out, bytes) == 0);
    assert(stats.mean_block_sad >= 48.0);
    mpvflow_pair_destroy(pair);
    mpvflow_destroy(context);
    free(a);
    free(b);
    free(out);
}
int main(void)
{
    test_translation_is_compensated();
    test_identical_frames_are_preserved();
    test_parallel_synthesis_is_deterministic();
    test_parallel_analysis_is_deterministic();
    test_scene_cut_avoids_synthetic_blend();
    puts("mpvflow core tests passed");
    return 0;
}
