/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
#include "mpvflow_core.h"
#include <assert.h>
#include <math.h>
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
static void fill_crossing_scene(uint8_t *frame, int width, int height, int second_frame)
{
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int background = 24 + ((x * 3 + y * 5) % 40);
            size_t i = ((size_t)y * width + x) * 3;
            frame[i] = (uint8_t)background;
            frame[i + 1] = (uint8_t)(background + 12);
            frame[i + 2] = (uint8_t)(background - 12);
            int red_x = second_frame ? 60 : 20;
            int blue_x = second_frame ? 20 : 60;
            if (x >= red_x && x < red_x + 12 && y >= 20 && y < 36) {
                frame[i] = 240;
                frame[i + 1] = 38;
                frame[i + 2] = 24;
            }
            if (x >= blue_x && x < blue_x + 12 && y >= 20 && y < 36) {
                frame[i] = 24;
                frame[i + 1] = 40;
                frame[i + 2] = 240;
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
static void test_revealed_background_does_not_extend_the_translating_object(void)
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
    assert(mpvflow_analyze_pair(context, a, b, width, height, &pair, NULL) == MPVFLOW_OK);
    assert(mpvflow_synthesize_rgb24(context, pair, a, b, 0.5f, out, NULL) == MPVFLOW_OK);
    size_t newly_revealed = ((size_t)27 * width + 20) * 3;
    size_t translated_object = ((size_t)27 * width + 28) * 3;
    assert(out[newly_revealed] < 120);
    assert(out[translated_object] > 180);
    assert(out[translated_object + 1] < 90);
    mpvflow_pair_destroy(pair);
    mpvflow_destroy(context);
    free(a);
    free(b);
    free(out);
}
static void test_one_pixel_contour_stays_separate_from_background(void)
{
    const int width = 96, height = 64;
    size_t bytes = (size_t)width * height * 3;
    uint8_t *a = malloc(bytes), *b = malloc(bytes), *out = malloc(bytes);
    assert(a && b && out);
    fill_texture(a, width, height, 24);
    fill_texture(b, width, height, 32);
    for (int y = 8; y < 56; y++) {
        size_t i = ((size_t)y * width + 12) * 3;
        memset(a + i, 0, 3);
        memset(b + i, 0, 3);
    }
    MPVFlowContext *context = mpvflow_create(8, 8);
    assert(context);
    MPVFlowPair *pair = NULL;
    assert(mpvflow_analyze_pair(context, a, b, width, height, &pair, NULL) == MPVFLOW_OK);
    assert(mpvflow_synthesize_rgb24(context, pair, a, b, 0.5f, out, NULL) == MPVFLOW_OK);
    size_t contour = ((size_t)27 * width + 12) * 3;
    size_t left_neighbor = ((size_t)27 * width + 11) * 3;
    size_t right_neighbor = ((size_t)27 * width + 13) * 3;
    assert(out[contour] < 24);
    assert(out[left_neighbor] > 12);
    assert(out[right_neighbor] > 12);
    mpvflow_pair_destroy(pair);
    mpvflow_destroy(context);
    free(a);
    free(b);
    free(out);
}
static void test_crossing_motion_fixture_retains_both_object_appearances(void)
{
    const int width = 96, height = 64;
    size_t bytes = (size_t)width * height * 3;
    uint8_t *a = malloc(bytes), *b = malloc(bytes), *out = malloc(bytes);
    assert(a && b && out);
    fill_crossing_scene(a, width, height, 0);
    fill_crossing_scene(b, width, height, 1);
    MPVFlowContext *context = mpvflow_create(8, 8);
    assert(context);
    MPVFlowPair *pair = NULL;
    struct MPVFlowStats stats = {0};
    assert(mpvflow_analyze_pair(context, a, b, width, height, &pair, &stats) == MPVFLOW_OK);
    assert(mpvflow_synthesize_rgb24(context, pair, a, b, 0.5f, out, NULL) == MPVFLOW_OK);
    int red_pixels = 0, blue_pixels = 0;
    for (int y = 20; y < 36; y++) {
        for (int x = 15; x < 78; x++) {
            size_t i = ((size_t)y * width + x) * 3;
            red_pixels += out[i] > 150 && out[i + 1] < 100 && out[i + 2] < 100;
            blue_pixels += out[i + 2] > 150 && out[i + 1] < 100 && out[i] < 100;
        }
    }
    assert(stats.mean_motion_pixels > 2.0);
    assert(red_pixels > 50);
    assert(blue_pixels > 50);
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
static void test_deadline_guard_yields_only_when_work_remains(void)
{
    assert(!mpvflow_should_yield_for_deadline(38.90, 41.00, false));
    assert(!mpvflow_should_yield_for_deadline(41.81, 42.00, false));
    assert(!mpvflow_should_yield_for_deadline(35.69, 42.00, true));
    assert(mpvflow_should_yield_for_deadline(35.70, 42.00, true));
    assert(mpvflow_should_yield_for_deadline(42.01, 42.00, true));
    assert(!mpvflow_should_yield_for_deadline(100.0, 0.0, true));
    assert(!mpvflow_should_yield_for_deadline(NAN, 42.0, true));
}
static void test_high_rate_source_bypass_preserves_rate_boundary(void)
{
    assert(mpvflow_should_bypass_for_source_rate(1.0 / 120.0, 1.0 / 60.0));
    assert(mpvflow_should_bypass_for_source_rate(1.0 / 90.0, 1.0 / 60.0));
    assert(!mpvflow_should_bypass_for_source_rate(1.0 / 60.0, 1.0 / 60.0));
    assert(!mpvflow_should_bypass_for_source_rate(1.0 / 24.0, 1.0 / 60.0));
    assert(!mpvflow_should_bypass_for_source_rate(NAN, 1.0 / 60.0));
}
static void test_adaptive_dimension_reduces_under_pressure_and_recovers_slowly(void)
{
    unsigned int recovery_count = 0;
    int dimension = mpvflow_update_adaptive_dimension(480, 480, 0.995,
                                                       &recovery_count);
    assert(dimension == 416);
    assert(recovery_count == 0);
    for (int i = 0; i < 59; i++)
        dimension = mpvflow_update_adaptive_dimension(dimension, 480, 0.50,
                                                       &recovery_count);
    assert(dimension == 416);
    dimension = mpvflow_update_adaptive_dimension(dimension, 480, 0.50,
                                                   &recovery_count);
    assert(dimension == 432);
    assert(recovery_count == 0);
    dimension = mpvflow_update_adaptive_dimension(dimension, 480, 0.70,
                                                   &recovery_count);
    assert(recovery_count == 0);
    assert(dimension == 432);
}
int main(void)
{
    test_translation_is_compensated();
    test_revealed_background_does_not_extend_the_translating_object();
    test_one_pixel_contour_stays_separate_from_background();
    test_crossing_motion_fixture_retains_both_object_appearances();
    test_identical_frames_are_preserved();
    test_parallel_synthesis_is_deterministic();
    test_parallel_analysis_is_deterministic();
    test_scene_cut_avoids_synthetic_blend();
    test_deadline_guard_yields_only_when_work_remains();
    test_high_rate_source_bypass_preserves_rate_boundary();
    test_adaptive_dimension_reduces_under_pressure_and_recovers_slowly();
    puts("mpvflow core tests passed");
    return 0;
}
