/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * GPU-resident block-motion interpolation API for Mpv∞. The caller owns the
 * pl_gpu and input textures; this module owns pair scratch textures and one
 * reusable synthesized output texture per analyzed pair.
 */
#ifndef MPV_INFINITY_MPVFLOW_GPU_H
#define MPV_INFINITY_MPVFLOW_GPU_H

#include <libplacebo/gpu.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MPVFlowGPUContext MPVFlowGPUContext;
typedef struct MPVFlowGPUPair MPVFlowGPUPair;

enum MPVFlowGPUResult {
    MPVFLOW_GPU_ERROR = -1,
    MPVFLOW_GPU_UNSUPPORTED = -2,
    MPVFLOW_GPU_INVALID = -3,
    MPVFLOW_GPU_OK = 0,
};

struct MPVFlowGPUConfig {
    /* Largest accepted input dimension; defaults to 480 when <= 0 and is
     * conservatively hard-capped at 480 in this initial implementation. */
    int max_dimension;
    /* Search radius in coarsest-pyramid pixels; clamped to [1, 8], default 8. */
    int search_radius;
};

/*
 * Create passes and scratch-format capability checks for an already-current
 * libplacebo GPU. This function never creates/destroys the GPU or GL context.
 * On failure, status distinguishes unsupported compute/formats from errors.
 */
MPVFlowGPUContext *mpvflow_gpu_create(pl_gpu gpu,
                                      const struct MPVFlowGPUConfig *config,
                                      enum MPVFlowGPUResult *status);
void mpvflow_gpu_destroy(MPVFlowGPUContext *context);

/*
 * Analyze a pair of ordinary, sampleable, 2D RGB pl_tex textures containing
 * normalized SDR color values. They must have equal dimensions and remain
 * alive and unchanged until the pair is released and all syntheses using it
 * have been submitted. No texture pixels
 * are read back to the CPU. Motion, luma pyramids and scene-cut reduction are
 * cached in pair-owned GPU textures. A scene cut is handled in the synthesis
 * shader by selecting the nearest endpoint; analyze itself cannot report a
 * CPU-readable scene-cut diagnosis without violating the no-readback contract.
 */
enum MPVFlowGPUResult mpvflow_gpu_analyze_pair(MPVFlowGPUContext *context,
                                               pl_tex frame0,
                                               pl_tex frame1,
                                               MPVFlowGPUPair **pair_out);
void mpvflow_gpu_pair_release(MPVFlowGPUContext *context,
                             MPVFlowGPUPair *pair);

/*
 * Synthesize into the pair's reusable RGBA8 2D GPU texture for 0<t<1. The
 * returned texture is borrowed and remains valid until the pair is released.
 * No per-vsync texture allocation, CPU upload/download, or CPU pixel synthesis
 * is performed. GPU commands are queued on the supplied pl_gpu.
 */
enum MPVFlowGPUResult mpvflow_gpu_synthesize(MPVFlowGPUContext *context,
                                             const MPVFlowGPUPair *pair,
                                             float t,
                                             pl_tex *output);

#ifdef __cplusplus
}
#endif
#endif /* MPV_INFINITY_MPVFLOW_GPU_H */
