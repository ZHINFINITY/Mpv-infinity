#pragma once

#include <stdbool.h>
#include <stdint.h>
#include <android/hardware_buffer.h>
#include <media/NdkImage.h>

enum android_vulkan_aimagereader_backend {
    ANDROID_VULKAN_AIMAGEREADER_BACKEND_AUTO = 0,
    ANDROID_VULKAN_AIMAGEREADER_BACKEND_COMPUTE,
    ANDROID_VULKAN_AIMAGEREADER_BACKEND_FRAGMENT,
};

struct mp_log;
struct mp_image;
struct ra_ctx;
struct ra_hwdec_mapper;

struct aimagereader_vk_api {
    void (*AImage_delete)(AImage *image);
    void (*AImage_deleteAsync)(AImage *image, int release_fence);
    void (*AHardwareBuffer_describe)(const AHardwareBuffer *buffer,
                                     AHardwareBuffer_Desc *desc);
};

struct aimagereader_vk;

bool aimagereader_vk_available(struct ra_ctx *ra_ctx, struct mp_log *log,
                              enum android_vulkan_aimagereader_backend backend);
struct aimagereader_vk *aimagereader_vk_create(
    struct ra_hwdec_mapper *mapper, const struct aimagereader_vk_api *api,
    enum android_vulkan_aimagereader_backend backend);
void aimagereader_vk_destroy(struct aimagereader_vk **state);
void aimagereader_vk_buffer_removed(struct aimagereader_vk *state,
                                    AHardwareBuffer *buffer);

// Reuse a mapped texture when libplacebo acquires the same source frame again,
// for example while redrawing OSD on top of a queued video frame.
bool aimagereader_vk_reuse(struct aimagereader_vk *state,
                           struct mp_image *frame);
bool aimagereader_vk_retain_last(struct aimagereader_vk *state,
                                struct mp_image *frame);

// Takes ownership of image on success. Sets acquire_fence to -1 when consumed.
int aimagereader_vk_map(struct aimagereader_vk *state, AImage *image,
                        AHardwareBuffer *buffer,
                        const AImageCropRect *crop, int32_t data_space,
                        struct mp_image *frame, int *acquire_fence);
