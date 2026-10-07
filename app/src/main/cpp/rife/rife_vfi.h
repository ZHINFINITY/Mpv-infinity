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
typedef struct RifeVfiOutput RifeVfiOutput;

typedef struct RifeVfiGpuCapabilities {
    int vulkan_device_ready;
    int android_ahb_import_extension;
    int foreign_queue_family_extension;
    int ahb_input_available;
    int ahb_rgba_output_slot_created;
    // Remains false until mpv's PTS queue, renderer handoff, synchronization,
    // and target-device playback acceptance are all verified.
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

/* Engine-validated opaque GPU frames. Frames must be produced by this engine's
 * import/processing APIs; callers cannot pass arbitrary VkMat pointers. */
int rife_vfi_interpolate_gpu_frames(RifeVfiEngine *engine,
                                    const RifeVfiGpuFrame *frame0,
                                    const RifeVfiGpuFrame *frame1,
                                    float timestep,
                                    RifeVfiGpuFrame **output);
void rife_vfi_gpu_frame_release(RifeVfiGpuFrame *frame);

/*
 * Android API 26+ full GPU input/output boundary. Import an acquired decoder
 * AHardwareBuffer through NCNN Vulkan, pack its RGB values into the active
 * RIFE int8-storage layout on-GPU, and retain that tensor in an owned frame.
 * The caller must keep the source AHardwareBuffer/AImage lease alive until this
 * synchronous import returns. No source or result pixels are read back to CPU.
 */
int rife_vfi_import_ahb_rgb8(RifeVfiEngine *engine,
                             void *android_hardware_buffer,
                             int output_width, int output_height,
                             RifeVfiGpuFrame **output);

/*
 * Allocate an RGBA8 AHardwareBuffer and a matching writable Vulkan storage
 * image. The returned slot owns the buffer until destroyed. Import/query and
 * storage-image support are checked at runtime; unsupported devices fail
 * closed. A successful write is completed synchronously before GLES access.
 */
RifeVfiOutput *rife_vfi_output_create(RifeVfiEngine *engine,
                                     int width, int height,
                                     char *error, size_t error_size);
void *rife_vfi_output_get_ahb(const RifeVfiOutput *output);
int rife_vfi_write_output_rgba(RifeVfiEngine *engine,
                               const RifeVfiGpuFrame *rgb_frame,
                               RifeVfiOutput *output,
                               char *error, size_t error_size);
void rife_vfi_output_destroy(RifeVfiOutput *output);

#ifdef __cplusplus
}
#endif

#endif // MPV_INFINITY_RIFE_VFI_H
