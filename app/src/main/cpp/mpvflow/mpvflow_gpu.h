/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 *
 * GPU-resident block-motion interpolation API for Mpv∞. The caller owns the
 * pl_gpu and input textures; this module owns pair scratch textures and one
 * reusable synthesized output texture per analyzed pair.
 */
#ifndef MPV_INFINITY_MPVFLOW_GPU_H
#define MPV_INFINITY_MPVFLOW_GPU_H
#include "mpvflow_visibility.h"

#include <libplacebo/gpu.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>


#ifdef __cplusplus
extern "C" {
#endif

typedef struct MPVFlowGPUContext MPVFlowGPUContext;
typedef struct MPVFlowGPUPair MPVFlowGPUPair;

#define MPVFLOW_GPU_MOTION_FINE_BLOCK_SIZE 4
#define MPVFLOW_GPU_MOTION_BLOCK_SIZE 8
#define MPVFLOW_GPU_MOTION_GRID_STEP 4
/* Experimental whole-frame ambiguous-pair crossfade; set to 0 to disable. */
#ifndef MPVFLOW_PAIR_GHOST_EXPERIMENT
#define MPVFLOW_PAIR_GHOST_EXPERIMENT 1
#endif

enum MPVFlowGPUResult {
    MPVFLOW_GPU_ERROR = -1,
    MPVFLOW_GPU_UNSUPPORTED = -2,
    MPVFLOW_GPU_INVALID = -3,
    MPVFLOW_GPU_UNSUPPORTED_API = -4,
    MPVFLOW_GPU_UNSUPPORTED_DIMENSION = -5,
    MPVFLOW_GPU_UNSUPPORTED_FORMAT = -6,
    MPVFLOW_GPU_UNSUPPORTED_SHADER = -7,
    MPVFLOW_GPU_OK = 0,
};

enum MPVFlowGPUComputeAPI {
    MPVFLOW_GPU_COMPUTE_AUTO = 0,
    MPVFLOW_GPU_COMPUTE_OPENCL = 1,
    MPVFLOW_GPU_COMPUTE_VULKAN = 2,
};

enum MPVFlowGPUPassMask {
    MPVFLOW_GPU_PASS_PREPROCESS = 1u << 0,
    MPVFLOW_GPU_PASS_LUMA = 1u << 1,
    MPVFLOW_GPU_PASS_DOWNSAMPLE = 1u << 2,
    MPVFLOW_GPU_PASS_REDUCE_FIRST = 1u << 3,
    MPVFLOW_GPU_PASS_REDUCE = 1u << 4,
    MPVFLOW_GPU_PASS_MOTION = 1u << 5,
    MPVFLOW_GPU_PASS_SYNTHESIZE = 1u << 6,
    MPVFLOW_GPU_PASS_PAIR_GATE_FIRST = 1u << 7,
    MPVFLOW_GPU_PASS_PAIR_GATE_REDUCE = 1u << 8,
    MPVFLOW_GPU_PASS_PAIR_GATE_MERGE = 1u << 9,
};

struct MPVFlowGPUCreateDiagnostics {
    /* Static stage name; valid for the lifetime of the process. */
    const char *stage;
    size_t max_pushc_size;
    size_t required_pushc_size;
    uint32_t failed_pass_mask;
    uint32_t rgba16f_caps;
    uint32_t rgba8_caps;
    bool rgba16f_found;
    bool rgba8_found;
    bool optical_flow_attempted;
    bool optical_flow_ready;
    char optical_flow_reason[160];
    bool qcom_seed_adapter_ready;
    bool qcom_seed_luma_pass_ready;
    char qcom_seed_reason[160];
};

enum MPVFlowGPUStage {
    MPVFLOW_GPU_STAGE_PREPROCESS,
    MPVFLOW_GPU_STAGE_ANALYSIS,
    MPVFLOW_GPU_STAGE_MOTION_FINE,
    MPVFLOW_GPU_STAGE_MOTION_MIDDLE,
    MPVFLOW_GPU_STAGE_MOTION_COARSE,
    MPVFLOW_GPU_STAGE_SYNTHESIS,
    MPVFLOW_GPU_STAGE_COUNT,
};

struct MPVFlowGPUStageTiming {
    bool timer_created;
    uint64_t dispatches;
    uint64_t completed_samples;
    uint64_t total_ns;
    uint64_t maximum_ns;
};

struct MPVFlowGPUTimingStats {
    uint32_t stage_count;
    uint32_t compute_queue_family;
    uint32_t compute_timestamp_valid_bits;
    bool compute_timestamps_supported;
    bool timestamp_compute_and_graphics;
    double timestamp_period_ns;
    struct MPVFlowGPUStageTiming stages[MPVFLOW_GPU_STAGE_COUNT];
};

struct MPVFlowGPUQcomDiagnostics {
    bool adapter_ready;
    bool luma_pass_ready;
    uint64_t pair_attempts;
    uint64_t dispatches;
    uint64_t fallbacks;
    char state[24];
    char reason[160];
};

struct MPVFlowGPUConfig {
    /* Auto uses Vulkan; OpenCL is selected only by an explicit request. */
    enum MPVFlowGPUComputeAPI compute_api;
    /* Identifies the caller in QCOM path diagnostics; copied at creation. */
    const char *diagnostic_engine;
    /* Largest motion-analysis dimension; defaults to 640 when <= 0 and is
     * hard-capped at 640. Synthesized color output remains source-resolution. */
    int max_dimension;
    /* Search radius in coarsest-pyramid pixels; clamped to [1, 8], default 8. */
    int search_radius;
};

/*
 * Create Vulkan/SPIR-V passes and scratch-format capability checks for an
 * existing libplacebo GPU. This function never creates/destroys the GPU.
 * On failure, status distinguishes unsupported compute/formats from errors.
 */
MPVFLOW_INTERNAL MPVFlowGPUContext *mpvflow_gpu_create(pl_gpu gpu,
                                      const struct MPVFlowGPUConfig *config,
                                      enum MPVFlowGPUResult *status,
                                      struct MPVFlowGPUCreateDiagnostics *diagnostics);
MPVFLOW_INTERNAL void mpvflow_gpu_destroy(MPVFlowGPUContext *context);
/* Returns the resolved runtime backend (OpenCL or Vulkan), never the Auto request. */
MPVFLOW_INTERNAL enum MPVFlowGPUComputeAPI mpvflow_gpu_get_compute_api(MPVFlowGPUContext *context);
MPVFLOW_INTERNAL const char *mpvflow_gpu_get_compute_api_name(MPVFlowGPUContext *context);
/* True only when a Vulkan RGBA16F output pass and sampling/storage caps are ready. */
MPVFLOW_INTERNAL bool mpvflow_gpu_rgba16f_synthesis_supported(MPVFlowGPUContext *context);

/* Poll completed libplacebo GPU timestamps without waiting for the device. */
MPVFLOW_INTERNAL void mpvflow_gpu_get_timing_stats(MPVFlowGPUContext *context,
                                  struct MPVFlowGPUTimingStats *stats);
MPVFLOW_INTERNAL void mpvflow_gpu_get_qcom_diagnostics(MPVFlowGPUContext *context,
                                      struct MPVFlowGPUQcomDiagnostics *stats);

/*
 * Downscale a sampleable RGB texture to the context's bounded working size.
 * The returned RGBA8 texture is owned by the caller and must be destroyed with
 * pl_tex_destroy after all pairs using it have been released. Pixels stay on
 * the Vulkan device; no CPU readback or upload occurs.
 */
MPVFLOW_INTERNAL enum MPVFlowGPUResult mpvflow_gpu_prepare_input(MPVFlowGPUContext *context,
                                                pl_tex input,
                                                pl_tex *prepared_out);

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
MPVFLOW_INTERNAL enum MPVFlowGPUResult mpvflow_gpu_analyze_pair(MPVFlowGPUContext *context,
                                               pl_tex frame0,
                                               pl_tex frame1,
                                               MPVFlowGPUPair **pair_out);
MPVFLOW_INTERNAL void mpvflow_gpu_pair_release(MPVFlowGPUContext *context,
                             MPVFlowGPUPair *pair);

/*
 * Synthesize at the supplied full-resolution source dimensions using the
 * pair's bounded motion fields. Source textures must be sampleable RGB, have
 * equal 2D dimensions, and remain alive/unchanged until commands are submitted.
 * The pair lazily reuses a full-resolution RGBA8 output texture. No CPU pixel
 * readback, upload, or synthesis occurs; GPU commands are queued asynchronously.
 */
MPVFLOW_INTERNAL enum MPVFlowGPUResult mpvflow_gpu_synthesize(MPVFlowGPUContext *context,
                                             MPVFlowGPUPair *pair,
                                             pl_tex source0,
                                             pl_tex source1,
                                             float t,
                                             pl_tex *output);
/* Opt-in synthesis policies. The legacy entry point preserves default behavior.
 * The policy variant caps warped contribution on uncertain-motion and conflict
 * pixels, trading mild ghosting for fewer hard motion-block artifacts. */
MPVFLOW_INTERNAL enum MPVFlowGPUResult mpvflow_gpu_synthesize_with_visibility(
    MPVFlowGPUContext *context, MPVFlowGPUPair *pair,
    pl_tex source0, pl_tex source1, float t,
    bool enable_visibility_resolver, bool enable_soft_blend,
    pl_tex *output);
MPVFLOW_INTERNAL enum MPVFlowGPUResult mpvflow_gpu_synthesize_with_policy(
    MPVFlowGPUContext *context, MPVFlowGPUPair *pair,
    pl_tex source0, pl_tex source1, float t,
    bool enable_visibility_resolver, bool enable_soft_blend,
    bool preserve_motion_blend_on_conflict, pl_tex *output);

/* Explicit, bounded diagnostic capture. Capture is inactive until start; the
 * selected Vulkan path performs synchronous capture-only readbacks into
 * capture-owned host-readable staging textures. Caller textures stay borrowed. */
MPVFLOW_INTERNAL enum MPVFlowGPUResult mpvflow_gpu_capture_start(
    MPVFlowGPUContext *context, const char *base_dir, const char *session_name,
    const char *engine, const char *effective_settings_json, bool paired_output);
MPVFLOW_INTERNAL enum MPVFlowGPUResult mpvflow_gpu_capture_sample(
    MPVFlowGPUContext *context, MPVFlowGPUPair *pair,
    pl_tex source0, pl_tex source1, pl_tex synthesized_output, float timestep,
    double source_pts0, double source_pts1, double output_pts,
    bool visibility_resolver, bool soft_blend,
    bool preserve_motion_blend_on_conflict);
MPVFLOW_INTERNAL void mpvflow_gpu_capture_stop(MPVFlowGPUContext *context);
MPVFLOW_INTERNAL void mpvflow_gpu_capture_fail(MPVFlowGPUContext *context,
                                                const char *reason);
MPVFLOW_INTERNAL bool mpvflow_gpu_capture_active(MPVFlowGPUContext *context);
MPVFLOW_INTERNAL bool mpvflow_gpu_capture_is_paired(MPVFlowGPUContext *context);
MPVFLOW_INTERNAL enum MPVFlowGPUResult mpvflow_gpu_capture_paired_sample(
    MPVFlowGPUContext *context, MPVFlowGPUPair *pair,
    pl_tex source0, pl_tex source1, float timestep,
    double source_pts0, double source_pts1, double output_pts,
    bool preserve_motion_blend_on_conflict,
    bool production_visibility_resolver, bool production_soft_blend,
    bool production_preserve_motion_blend_on_conflict, pl_tex *production_output);


#ifdef __cplusplus
}
#endif
#endif /* MPV_INFINITY_MPVFLOW_GPU_H */
