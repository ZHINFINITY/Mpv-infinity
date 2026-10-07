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

typedef struct RifeVfiGpuCapabilities {
    int vulkan_device_ready;
    int android_ahb_import_extension;
    int foreign_queue_family_extension;
    int ahb_input_probe_available;
    // Remains false until mpv's PTS queue and renderer output handoff are wired
    // and device-accepted. A successful input probe is not playback readiness.
    int gpu_resident_playback_ready;
} RifeVfiGpuCapabilities;

RifeVfiEngine *rife_vfi_create(const char *model_dir, char *error,
                               size_t error_size);
void rife_vfi_destroy(RifeVfiEngine *engine);
int rife_vfi_uses_fp16_arithmetic(const RifeVfiEngine *engine);
int rife_vfi_get_gpu_capabilities(const RifeVfiEngine *engine,
                                  RifeVfiGpuCapabilities *capabilities);
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
 * model's fp16/fp32 element size. The producer must finish and synchronize
 * writes before calling; the input tensors are borrowed for the call. The
 * synthesized result remains a Vulkan VkMat in an owned handle. This API does
 * not import Android decoder buffers or share output with mpv's VO.
 */
int rife_vfi_interpolate_vulkan(RifeVfiEngine *engine,
                                const void *ncnn_vkmat0,
                                const void *ncnn_vkmat1,
                                float timestep,
                                RifeVfiGpuFrame **output);
const void *rife_vfi_gpu_frame_get_ncnn_vkmat(const RifeVfiGpuFrame *frame);
void rife_vfi_gpu_frame_release(RifeVfiGpuFrame *frame);

/*
 * Android API 26+ input-boundary prototype for on-device diagnostics only.
 * Imports an AHardwareBuffer into NCNN's Vulkan device and returns an RGB
 * float32 VkMat. The caller must keep the buffer alive for the duration of the
 * call. This does not perform RIFE inference, convert to the active int8 RIFE
 * tensor, retain a decoder-frame lease, or hand output to mpv's renderer.
 * It is intentionally not used by vf_rife or enabled in playback.
 */
int rife_vfi_import_ahb_rgb32f_for_probe(RifeVfiEngine *engine,
                                         void *android_hardware_buffer,
                                         int output_width, int output_height,
                                         RifeVfiGpuFrame **output);
int rife_vfi_gpu_frame_get_dimensions(const RifeVfiGpuFrame *frame,
                                      int *width, int *height,
                                      int *channels);
// Diagnostic-only synchronized GPU-to-CPU readback, for color/range tests.
int rife_vfi_gpu_frame_copy_rgb32f_for_probe(const RifeVfiGpuFrame *frame,
                                             float *output,
                                             size_t output_float_capacity);

#ifdef __cplusplus
}
#endif

#endif // MPV_INFINITY_RIFE_VFI_H
