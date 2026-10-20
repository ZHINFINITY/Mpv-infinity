/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 *
 * Clean-room block-motion interpolation core for Mpv∞. This is an independent
 * MEMC prototype inspired by publicly documented motion-vector pipelines; it is
 * not SVPFlow source code.
 */
#ifndef MPV_INFINITY_MPVFLOW_CORE_H
#define MPV_INFINITY_MPVFLOW_CORE_H
#include "mpvflow_visibility.h"
#include <stdbool.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct MPVFlowContext MPVFlowContext;
typedef struct MPVFlowPair MPVFlowPair;
enum MPVFlowResult {
    MPVFLOW_ERROR = -1,
    MPVFLOW_OK = 0,
    MPVFLOW_SCENE_CUT = 1,
};
struct MPVFlowStats {
    double mean_block_sad;
    double mean_vector_consistency_error;
    double mean_motion_pixels;
};
/* Return true only when more target frames remain and the 85% safety reserve is spent. */
MPVFLOW_INTERNAL bool mpvflow_should_yield_for_deadline(double processing_ms,
                                       double pair_budget_ms,
                                       bool work_remains);
/* High-rate sources need no motion analysis; preserve their original timestamps for VO pacing. */
MPVFLOW_INTERNAL bool mpvflow_should_bypass_for_source_rate(double source_delta_seconds,
                                          double target_step_seconds);
/* Auto mode adapts its next-pair working cap with pressure/recovery hysteresis. */
MPVFLOW_INTERNAL int mpvflow_update_adaptive_dimension(int current_dimension,
                                      int configured_max_dimension,
                                      double budget_ratio,
                                      unsigned int *recovery_count);
MPVFLOW_INTERNAL MPVFlowContext *mpvflow_create(int block_size, int search_radius);
MPVFLOW_INTERNAL void mpvflow_destroy(MPVFlowContext *context);
MPVFLOW_INTERNAL int mpvflow_analyze_pair(MPVFlowContext *context,
                         const uint8_t *frame0,
                         const uint8_t *frame1,
                         int width,
                         int height,
                         MPVFlowPair **pair,
                         struct MPVFlowStats *stats);
MPVFLOW_INTERNAL void mpvflow_pair_destroy(MPVFlowPair *pair);
/*
 * Synthesize one packed RGB24 frame at 0 < timestep < 1 using cached vectors.
 * Call mpvflow_analyze_pair once for each decoded pair, then reuse that pair for
 * every target timestamp in the source-frame interval.
 */
MPVFLOW_INTERNAL int mpvflow_synthesize_rgb24(MPVFlowContext *context,
                             const MPVFlowPair *pair,
                             const uint8_t *frame0,
                             const uint8_t *frame1,
                             float timestep,
                             uint8_t *output,
                             struct MPVFlowStats *stats);
#ifdef __cplusplus
}
#endif
#endif /* MPV_INFINITY_MPVFLOW_CORE_H */
