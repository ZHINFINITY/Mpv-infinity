#pragma once

#include "hwdec_aimagereader_vk.h"

// The direct backend rejected the image before consuming its acquire fence or
// taking ownership of the AImage, so the dispatcher may try conversion.
#define AIMAGEREADER_VK_MAP_UNSUPPORTED (-2)

struct aimagereader_vk_direct;
struct aimagereader_vk_convert;

bool aimagereader_vk_direct_available(struct ra_ctx *ra_ctx,
                                      struct mp_log *log);
struct aimagereader_vk_direct *aimagereader_vk_direct_create(
    struct ra_hwdec_mapper *mapper, const struct aimagereader_vk_api *api);
void aimagereader_vk_direct_destroy(struct aimagereader_vk_direct **state);
void aimagereader_vk_direct_buffer_removed(
    struct aimagereader_vk_direct *state, AHardwareBuffer *buffer);
bool aimagereader_vk_direct_reuse(struct aimagereader_vk_direct *state,
                                 struct mp_image *frame);
bool aimagereader_vk_direct_retain_last(struct aimagereader_vk_direct *state,
                                       struct mp_image *frame);
int aimagereader_vk_direct_map(
    struct aimagereader_vk_direct *state, AImage *image,
    AHardwareBuffer *buffer, const AImageCropRect *crop, int32_t data_space,
    struct mp_image *frame, int *acquire_fence);

bool aimagereader_vk_convert_available(struct ra_ctx *ra_ctx,
                                       struct mp_log *log);
struct aimagereader_vk_convert *aimagereader_vk_convert_create(
    struct ra_hwdec_mapper *mapper, const struct aimagereader_vk_api *api,
    enum android_vulkan_aimagereader_backend backend);
void aimagereader_vk_convert_destroy(struct aimagereader_vk_convert **state);
void aimagereader_vk_convert_buffer_removed(
    struct aimagereader_vk_convert *state, AHardwareBuffer *buffer);
bool aimagereader_vk_convert_reuse(struct aimagereader_vk_convert *state,
                                  struct mp_image *frame);
bool aimagereader_vk_convert_retain_last(struct aimagereader_vk_convert *state,
                                        struct mp_image *frame);
int aimagereader_vk_convert_map(
    struct aimagereader_vk_convert *state, AImage *image,
    AHardwareBuffer *buffer, const AImageCropRect *crop, int32_t data_space,
    struct mp_image *frame, int *acquire_fence);
