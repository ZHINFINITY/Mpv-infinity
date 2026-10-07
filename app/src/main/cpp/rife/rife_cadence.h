/* SPDX-License-Identifier: AGPL-3.0-or-later */

#ifndef MPV_INFINITY_RIFE_CADENCE_H
#define MPV_INFINITY_RIFE_CADENCE_H

#include <math.h>
#include <stdbool.h>
#include <stdint.h>

#define RIFE_PAIR_BUDGET_FRACTION 0.85

static inline bool rife_cadence_count_outputs(double next_pts, double end_pts,
                                               double frame_step,
                                               double tolerance,
                                               unsigned int max_outputs,
                                               unsigned int *output_count)
{
    if (!output_count || !isfinite(next_pts) || !isfinite(end_pts) ||
        !isfinite(frame_step) || !isfinite(tolerance) || frame_step <= 0.0 ||
        tolerance < 0.0 || max_outputs == 0)
        return false;

    double remaining = end_pts - next_pts;
    if (!isfinite(remaining))
        return false;
    if (remaining <= tolerance) {
        *output_count = 0;
        return true;
    }

    double count = ceil((remaining - tolerance) / frame_step);
    if (!isfinite(count) || count < 1.0 || count > max_outputs)
        return false;
    *output_count = (unsigned int)count;
    return true;
}

// Select at most one presentation sample for each tick on a stream-anchored
// target-FPS grid. Keep `origin_pts` stable across source-frame pairs; the VO
// still computes the RIFE timestep from the actual presentation PTS.
static inline bool rife_cadence_grid_index(double origin_pts,
                                            double presentation_pts,
                                            int target_fps,
                                            bool have_last_index,
                                            int64_t last_index,
                                            int64_t *output_index)
{
    if (!output_index || !isfinite(origin_pts) || !isfinite(presentation_pts) ||
        target_fps <= 0 || presentation_pts < origin_pts)
        return false;

    double ticks = (presentation_pts - origin_pts) * target_fps;
    if (!isfinite(ticks) || ticks < 0.0 || ticks >= (double)INT64_MAX)
        return false;

    double nearest = floor(ticks + 0.5);
    if (nearest < 1.0 || fabs(ticks - nearest) > 0.45)
        return false;

    int64_t index = (int64_t)nearest;
    if (have_last_index && index <= last_index)
        return false;

    *output_index = index;
    return true;
}

static inline bool rife_cadence_source_budget_ns(double source_delta_seconds,
                                                  int64_t *budget_ns)
{
    if (!budget_ns || !isfinite(source_delta_seconds) ||
        source_delta_seconds <= 0.0 ||
        source_delta_seconds > (double)INT64_MAX / 1e9)
        return false;

    double budget = floor(source_delta_seconds * 1e9 * RIFE_PAIR_BUDGET_FRACTION);
    if (!isfinite(budget) || budget < 1.0 || budget > (double)INT64_MAX)
        return false;
    *budget_ns = (int64_t)budget;
    return true;
}

static inline bool rife_cadence_work_exceeds_budget(int64_t completed_ns,
                                                     int64_t additional_ns,
                                                     int64_t budget_ns)
{
    if (completed_ns < 0 || additional_ns < 0 || budget_ns <= 0 ||
        completed_ns > budget_ns)
        return true;
    return additional_ns > budget_ns - completed_ns;
}

// Estimate the remaining gap cost without multiplying potentially large values.
static inline bool rife_cadence_prediction_exceeds_budget(
    int64_t completed_ns, int64_t average_per_output_ns,
    unsigned int outputs_remaining, int64_t budget_ns)
{
    if (completed_ns < 0 || average_per_output_ns < 0 || budget_ns <= 0 ||
        completed_ns > budget_ns)
        return true;
    if (outputs_remaining == 0)
        return false;

    int64_t remaining_budget_ns = budget_ns - completed_ns;
    return average_per_output_ns >
        remaining_budget_ns / (int64_t)outputs_remaining;
}

#endif // MPV_INFINITY_RIFE_CADENCE_H
