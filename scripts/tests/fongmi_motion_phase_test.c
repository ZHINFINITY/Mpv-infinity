/* SPDX-License-Identifier: LGPL-2.1-or-later */
#undef NDEBUG
#include <assert.h>
#include <math.h>
#include <stdbool.h>
#include <stdio.h>

#include "video/out/gpu_next/motion_clock.h"

#define MAX_OUTPUTS 512
#define EPSILON 1e-7

static const double TARGET_FPS_OPTIONS[] = {48.0, 60.0, 72.0, 90.0, 96.0, 120.0, 144.0};
static const double DISPLAY_RATES[] = {60.0, 90.0, 120.0, 144.0};
static const double SOURCE_RATES[] = {24.0, 30.0};

static double phase(double pts, double origin, double source_fps)
{
    double value = fmod((pts - origin) * source_fps, 1.0);
    if (value < 0.0)
        value += 1.0;
    if (value >= 1.0 - EPSILON)
        value = 0.0;
    return value;
}

static int trace_output(double source_fps, double target_fps, double display_fps,
                        double outputs[MAX_OUTPUTS])
{
    struct mp_motion_clock clock = {0};
    const double origin = 3600.0;
    const double vsync_interval = 1.0 / display_fps;
    const int input_ticks = (int)lrint((4.0 / source_fps) * display_fps);
    const bool quantizes = target_fps * vsync_interval < 1.0 - 1e-6;
    const double effective_fps = fmin(target_fps, display_fps);
    int output_count = 0;
    double previous = -1.0;

    for (int i = 0; i <= input_ticks; i++) {
        double pts = origin + i * vsync_interval;
        // This mirrors the exact motion_quantizes_output branch verified in
        // vo_gpu_next.c by the Python active-source contract test.
        double sampled = quantizes
            ? mp_motion_clock_sample(&clock, pts, 1.0, target_fps)
            : pts;
        assert(sampled <= pts + EPSILON);
        assert(sampled + EPSILON >= previous);

        if (output_count == 0 || sampled > previous + EPSILON) {
            assert(output_count < MAX_OUTPUTS);
            double cadence = quantizes ? target_fps : display_fps;
            double expected_pts = origin + output_count / cadence;
            assert(fabs(sampled - expected_pts) < 2e-6);
            outputs[output_count++] = sampled;
        } else {
            assert(fabs(sampled - previous) < EPSILON);
        }
        previous = sampled;
    }

    double duration = input_ticks * vsync_interval;
    int expected_outputs = (int)floor(duration * effective_fps + 1e-6) + 1;
    assert(output_count == expected_outputs);

    for (int i = 0; i < output_count; i++) {
        double expected_pts = origin + i / effective_fps;
        assert(fabs(outputs[i] - expected_pts) < 2e-6);
        double phase_value = phase(outputs[i], origin, source_fps);
        double expected_phase = fmod(i * source_fps / effective_fps, 1.0);
        assert(phase_value >= 0.0 && phase_value < 1.0);
        assert(fabs(phase_value - expected_phase) < 2e-6);
        if (i > 0)
            assert(fabs((outputs[i] - outputs[i - 1]) - 1.0 / effective_fps) < 2e-6);
    }
    return output_count;
}

int main(void)
{
    const int target_count = sizeof(TARGET_FPS_OPTIONS) / sizeof(TARGET_FPS_OPTIONS[0]);
    const int display_count = sizeof(DISPLAY_RATES) / sizeof(DISPLAY_RATES[0]);
    const int source_count = sizeof(SOURCE_RATES) / sizeof(SOURCE_RATES[0]);
    double outputs[MAX_OUTPUTS] = {0};

    for (int s = 0; s < source_count; s++) {
        for (int d = 0; d < display_count; d++) {
            for (int t = 0; t < target_count; t++) {
                int count = trace_output(SOURCE_RATES[s], TARGET_FPS_OPTIONS[t],
                                         DISPLAY_RATES[d], outputs);
                assert(count > 1);
            }
        }
    }

    puts("FongMi clock phase/cadence matrix passed for every app target (48, 60, 72, 90, 96, 120, 144 fps)");
    puts("Covered 24/30-fps sources and 60/90/120/144-Hz displays, including 120-fps direct and quantized paths");
    return 0;
}
