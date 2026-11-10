/* SPDX-License-Identifier: LGPL-2.1-or-later */
#ifndef MP_GPU_NEXT_MOTION_H
#define MP_GPU_NEXT_MOTION_H

#include <stdbool.h>
#include <stdint.h>
#include <libplacebo/renderer.h>

struct mp_motion;

struct mp_motion_stats {
    uint64_t analyzed_pairs;
    uint64_t generated_frames;
    uint64_t repeated_frames;
    // Rolling GPU means; zero means no completed timer sample is available.
    uint64_t capture_ns;
    uint64_t coarse_ns;
    uint64_t refinement_ns;
    uint64_t evidence_ns;
    uint64_t reconstruction_ns;
    uint64_t warp_ns;
    uint64_t synthesis_ns;
    int analysis_width, analysis_height;
    int synthesis_width, synthesis_height;
};

#ifdef __cplusplus
extern "C" {
#endif

bool mp_motion_supported(pl_gpu gpu);
struct mp_motion *mp_motion_create(pl_gpu gpu, int max_dimension);
void mp_motion_destroy(struct mp_motion **motion);
void mp_motion_reset(struct mp_motion *motion);
struct mp_motion_stats mp_motion_get_stats(const struct mp_motion *motion);

// Capture into a caller-owned RGB texture. Source acquire/release callbacks
// remain responsible for decoder ownership; no source pixels are read back.
bool mp_motion_capture(struct mp_motion *motion, const struct pl_frame *source,
                       int width, int height, pl_tex *texture);

// Dispatch and cache motion analysis before the presentation callback. The
// source textures must remain alive until all queued GPU commands complete.
bool mp_motion_analyze_pair(struct mp_motion *motion, pl_tex first, pl_tex second,
                            uint64_t first_id, uint64_t second_id);

// Cache analysis by source identity. The returned texture is borrowed until
// the next render/reset/destroy; both sources must remain alive during render.
bool mp_motion_render(struct mp_motion *motion, pl_tex first, pl_tex second,
                      uint64_t first_id, uint64_t second_id, float alpha,
                      pl_tex *output);

// Render to a caller-owned storage texture such as an imported AHardwareBuffer.
// Unlike mp_motion_render, this does not allocate/cache the final output image.
bool mp_motion_render_to(struct mp_motion *motion, pl_tex first, pl_tex second,
                         uint64_t first_id, uint64_t second_id, float alpha,
                         pl_tex output);

// Reuse analysis from normalized/downscaled frames while sampling the original
// full-resolution sources for synthesis. Output may use a different size.
bool mp_motion_render_sources(struct mp_motion *motion,
                              pl_tex analysis_first, pl_tex analysis_second,
                              pl_tex first, pl_tex second,
                              uint64_t first_id, uint64_t second_id, float alpha,
                              pl_tex *output);
bool mp_motion_render_sources_to(struct mp_motion *motion,
                                 pl_tex analysis_first, pl_tex analysis_second,
                                 pl_tex first, pl_tex second,
                                 uint64_t first_id, uint64_t second_id, float alpha,
                                 pl_tex output);

#ifdef __cplusplus
}
#endif

#endif
