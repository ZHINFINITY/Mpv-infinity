/* SPDX-License-Identifier: LGPL-2.1-or-later */
#ifndef MP_GPU_NEXT_MOTION_CLOCK_H
#define MP_GPU_NEXT_MOTION_CLOCK_H

#include <math.h>
#include <stdbool.h>

struct mp_motion_clock {
    bool initialized;
    double input_pts;
    double output_pts;
    double elapsed;
    double tick;
    double fps;
};

// Integrate media time at the current speed, then retain one media timestamp
// per output tick. Repeated display callbacks do not advance the output clock.
static inline double mp_motion_clock_sample(struct mp_motion_clock *clock,
                                            double pts, double speed, double fps)
{
    if (!isfinite(pts) || !isfinite(speed) || !isfinite(fps) ||
        speed <= 0 || fps <= 0) {
        *clock = (struct mp_motion_clock) {0};
        return pts;
    }
    if (!clock->initialized || clock->fps != fps || pts < clock->input_pts) {
        *clock = (struct mp_motion_clock) {
            .initialized = true, .input_pts = pts,
            .output_pts = pts, .fps = fps,
        };
        return pts;
    }
    clock->elapsed += (pts - clock->input_pts) / speed;
    double tick = floor(clock->elapsed * fps + 1e-7);
    if (tick > clock->tick) {
        double remainder = clock->elapsed - tick / fps;
        clock->output_pts = pts - remainder * speed;
        clock->tick = tick;
    }
    clock->input_pts = pts;
    return clock->output_pts;
}

#endif
