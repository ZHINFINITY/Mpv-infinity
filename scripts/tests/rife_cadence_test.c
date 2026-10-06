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

int main(void)
{
    test_output_counts();
    test_source_pair_budget();
    test_prediction_budget();
    puts("RIFE cadence tests passed");
    return 0;
}
