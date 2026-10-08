/* SPDX-License-Identifier: AGPL-3.0-or-later */

#include <assert.h>
#include <limits.h>
#include <stdio.h>

#include "rife_cadence.h"

static void test_output_counts(void)
{
    unsigned int count = 99;
    const double tolerance = 1e-7;

    assert(rife_cadence_count_outputs(1.0 / 60.0, 1.0 / 30.0,
                                      1.0 / 60.0, tolerance, 12, &count));
    assert(count == 1); // one synthesized frame for 30 -> 60 fps

    assert(rife_cadence_count_outputs(1.0 / 60.0, 1.0 / 24.0,
                                      1.0 / 60.0, tolerance, 12, &count));
    assert(count == 2); // two synthesized frames inside one 24 fps interval

    assert(rife_cadence_count_outputs(1.0 / 30.0, 1.0 / 30.0,
                                      1.0 / 60.0, tolerance, 12, &count));
    assert(count == 0);

    assert(!rife_cadence_count_outputs(0.0, 1.0, 0.0,
                                       tolerance, 12, &count));
    assert(!rife_cadence_count_outputs(0.0, 1.0, 1.0 / 60.0,
                                       tolerance, 12, &count));
}

static void test_source_pair_budget(void)
{
    int64_t budget = 0;
    assert(rife_cadence_source_budget_ns(1.0 / 30.0, &budget));
    assert(budget == 28333333);
    assert(rife_cadence_work_exceeds_budget(budget - 10, 11, budget));
    assert(!rife_cadence_work_exceeds_budget(10, budget - 10, budget));
    assert(!rife_cadence_work_exceeds_budget(10, budget - 11, budget));
    assert(rife_cadence_work_exceeds_budget(INT64_MAX, 1, budget));
    assert(!rife_cadence_source_budget_ns(0.0, &budget));
    assert(!rife_cadence_source_budget_ns(-1.0, &budget));
    assert(!rife_cadence_source_budget_ns(INFINITY, &budget));
}

static void test_stable_stream_cadence_grid(void)
{
    int64_t index = -1;
    const double stream_origin = 10.0;

    assert(rife_cadence_grid_index(stream_origin, 10.0 + 1.0 / 30.0,
                                   30, false, 0, &index));
    assert(index == 1);

    // A variable-duration source pair must not create a new target-FPS phase.
    // This timestamp is near the same stream tick, so it is suppressed as a
    // duplicate rather than emitting a second RIFE frame for cadence tick 1.
    assert(!rife_cadence_grid_index(stream_origin, 10.041, 30,
                                    true, index, &index));

    assert(rife_cadence_grid_index(stream_origin, 10.0 + 2.0 / 30.0,
                                   30, true, index, &index));
    assert(index == 2);
    assert(!rife_cadence_grid_index(stream_origin, 10.0 + 2.0 / 30.0,
                                    30, true, index, &index));

    assert(!rife_cadence_grid_index(10.0, 9.99, 30, false, 0, &index));
    assert(!rife_cadence_grid_index(10.0, NAN, 30, false, 0, &index));
    assert(!rife_cadence_grid_index(10.0, 10.1, 0, false, 0, &index));
}

static void test_presentation_aligned_origin_avoids_source_phase_miss(void)
{
    int64_t index = -1;
    double presentation_origin = 0.0;
    const double source_origin = 18.484;
    const double first_presentation_pts = 18.492;

    // A 0.48-tick source/display phase offset falls outside the 0.45-tick
    // tolerance on every presentation sample in a 60-Hz grid.
    assert(!rife_cadence_grid_index(source_origin, first_presentation_pts,
                                    60, false, 0, &index));
    assert(!rife_cadence_grid_index(source_origin,
                                    first_presentation_pts + 1.0 / 60.0,
                                    60, false, 0, &index));

    assert(rife_cadence_origin_from_presentation(first_presentation_pts, 60,
                                                  &presentation_origin));
    assert(rife_cadence_grid_index(presentation_origin, first_presentation_pts,
                                   60, false, 0, &index));
    assert(index == 1);
    assert(!rife_cadence_origin_from_presentation(NAN, 60,
                                                   &presentation_origin));
}

static void test_prediction_budget(void)
{
    int64_t budget = 0;
    assert(rife_cadence_source_budget_ns(1.0 / 24.0, &budget));
    assert(budget == 35416666);
    assert(!rife_cadence_prediction_exceeds_budget(0, 17000000, 2, budget));
    assert(rife_cadence_prediction_exceeds_budget(0, 18000000, 2, budget));
    assert(!rife_cadence_prediction_exceeds_budget(17000000, 18000000, 1, budget));
    assert(rife_cadence_prediction_exceeds_budget(18000000, 18000000, 1, budget));
    assert(!rife_cadence_prediction_exceeds_budget(0, 0, 0, budget));
}

static void test_target_frame_deadline_policy(void)
{
    int64_t budget = 0;
    assert(rife_cadence_target_frame_budget_ns(60, &budget));
    assert(budget == 14166666);
    assert(rife_cadence_target_frame_budget_ns(30, &budget));
    assert(budget == 28333333);
    assert(!rife_cadence_target_frame_budget_ns(0, &budget));

    // The attached phone's ~10 ms input readiness, ~39 ms inference, and
    // ~3 ms output handoff cannot meet either target synchronously.
    assert(rife_cadence_work_exceeds_target_frame_budget(
        10000, 1600, 39000, 3000, 60));
    assert(rife_cadence_work_exceeds_target_frame_budget(
        10000, 1600, 39000, 3000, 30));
    assert(!rife_cadence_work_exceeds_target_frame_budget(
        7000, 1000, 4000, 1000, 60));
    assert(!rife_cadence_work_exceeds_target_frame_budget(
        10000, 0, 4000, 0, 60));
    assert(rife_cadence_work_exceeds_target_frame_budget(
        1000, 0, 0, 0, 60));
    assert(rife_cadence_work_exceeds_target_frame_budget(
        10000, 0, 5000, 0, 60));
    assert(rife_cadence_work_exceeds_target_frame_budget(
        UINT64_MAX, 1, 1, 1, 60));
}

int main(void)
{
    test_output_counts();
    test_stable_stream_cadence_grid();
    test_presentation_aligned_origin_avoids_source_phase_miss();
    test_source_pair_budget();
    test_prediction_budget();
    test_target_frame_deadline_policy();
    puts("RIFE cadence tests passed");
    return 0;
}
