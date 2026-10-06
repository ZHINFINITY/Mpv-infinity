/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Clean-room block-motion interpolation core for Mpv∞. This is an independent
 * MEMC prototype inspired by publicly documented motion-vector pipelines; it is
 * not SVPFlow source code.
 */
#ifndef MPV_INFINITY_MPVFLOW_CORE_H
#define MPV_INFINITY_MPVFLOW_CORE_H
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
MPVFlowContext *mpvflow_create(int block_size, int search_radius);
void mpvflow_destroy(MPVFlowContext *context);
int mpvflow_analyze_pair(MPVFlowContext *context,
                         const uint8_t *frame0,
                         const uint8_t *frame1,
                         int width,
                         int height,
                         MPVFlowPair **pair,
                         struct MPVFlowStats *stats);
void mpvflow_pair_destroy(MPVFlowPair *pair);
/*
 * Synthesize one packed RGB24 frame at 0 < timestep < 1 using cached vectors.
 * Call mpvflow_analyze_pair once for each decoded pair, then reuse that pair for
 * every target timestamp in the source-frame interval.
 */
int mpvflow_synthesize_rgb24(MPVFlowContext *context,
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
