/* SPDX-License-Identifier: AGPL-3.0-or-later */

#ifndef MPV_INFINITY_RIFE_VFI_H
#define MPV_INFINITY_RIFE_VFI_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct RifeVfiEngine RifeVfiEngine;
typedef struct RifeVfiGpuFrame RifeVfiGpuFrame;

RifeVfiEngine *rife_vfi_create(const char *model_dir, char *error,
                               size_t error_size);
void rife_vfi_destroy(RifeVfiEngine *engine);
int rife_vfi_uses_fp16_arithmetic(const RifeVfiEngine *engine);
int rife_vfi_interpolate_rgb24(RifeVfiEngine *engine,
                               const uint8_t *frame0,
                               const uint8_t *frame1,
                               int width, int height, float timestep,
                               uint8_t *output);

/*
 * Experimental Vulkan-native seam. Input pointers must point to ncnn::VkMat
 * RGB data allocated on this engine's ncnn Vulkan device. With the pinned
 * NCNN int8-storage preprocessor, this is a 2-D interleaved RGB8 tensor
 * (dims=2, c=1, elempack=1, elemsize=3); otherwise it is planar RGB with the
 * model's fp16/fp32 element size. The producer must finish
 * and synchronize writes before calling; the input tensors are borrowed for
 * the call. The synthesized result remains a Vulkan VkMat in an owned handle;
 * release that handle before expecting its GPU storage to be reclaimed. This
 * API does not import Android decoder buffers or share output with mpv's VO.
 * The current vf_rife RGB24 filter does not call these functions.
 */
int rife_vfi_interpolate_vulkan(RifeVfiEngine *engine,
                                const void *ncnn_vkmat0,
                                const void *ncnn_vkmat1,
                                float timestep,
                                RifeVfiGpuFrame **output);
const void *rife_vfi_gpu_frame_get_ncnn_vkmat(const RifeVfiGpuFrame *frame);
void rife_vfi_gpu_frame_release(RifeVfiGpuFrame *frame);

#ifdef __cplusplus
}
#endif

#endif // MPV_INFINITY_RIFE_VFI_H
