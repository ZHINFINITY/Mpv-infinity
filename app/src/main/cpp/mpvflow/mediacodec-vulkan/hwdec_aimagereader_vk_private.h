#pragma once

#include "hwdec_aimagereader_vk.h"

struct aimagereader_vk_convert;

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
bool aimagereader_vk_convert_retain_last(
    struct aimagereader_vk_convert *state, struct mp_image *frame);
int aimagereader_vk_convert_map(
    struct aimagereader_vk_convert *state, AImage *image,
    AHardwareBuffer *buffer, const AImageCropRect *crop, int32_t data_space,
    struct mp_image *frame, int *acquire_fence);
