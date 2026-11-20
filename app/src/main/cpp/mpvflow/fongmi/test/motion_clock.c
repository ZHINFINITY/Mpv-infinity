/* SPDX-License-Identifier: LGPL-2.1-or-later */
#undef NDEBUG
#include <assert.h>
#include <math.h>
#include <stdio.h>

#include "video/out/gpu_next/motion_clock.h"

static void cadence(double display_fps, double target_fps, double speed)
{
    struct mp_motion_clock clock = {0};
    int changes = 0;
    double previous = -1;
    for (int i = 0; i < (int) display_fps * 10; i++) {
        double pts = 3600 + i * speed / display_fps;
        double sampled = mp_motion_clock_sample(&clock, pts, speed, target_fps);
        assert(sampled <= pts + 1e-8);
        assert(sampled >= previous);
        assert(pts - sampled < speed / target_fps + 1e-7);
        assert(mp_motion_clock_sample(&clock, pts, speed, target_fps) == sampled);
        if (sampled > previous)
            changes++;
        previous = sampled;
    }
    assert(changes == (int) target_fps * 10);
}

int main(void)
{
    cadence(120, 60, 1);
    cadence(120, 120, 1);
    cadence(144, 60, 1);
    cadence(120, 60, 2);
    cadence(120, 60, 0.5);
    struct mp_motion_clock clock = {0};
    assert(mp_motion_clock_sample(&clock, 10, 1, 60) == 10);
    assert(mp_motion_clock_sample(&clock, 5, 1, 60) == 5);
    assert(mp_motion_clock_sample(&clock, 6, 1, 120) == 6);
    assert(mp_motion_clock_sample(&clock, 7, 0, 60) == 7);
    assert(!clock.initialized);
    assert(isnan(mp_motion_clock_sample(&clock, NAN, 1, 60)));
    puts("motion clock: cadence, speed, repeat, seek, target change, invalid input passed");
    return 0;
}
