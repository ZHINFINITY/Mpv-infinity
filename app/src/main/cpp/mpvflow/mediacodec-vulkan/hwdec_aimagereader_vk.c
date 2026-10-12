/*
 * Android AImageReader / Vulkan backend using GPU-side YCbCr conversion.
 *
 * The pinned upstream libplacebo version cannot wrap external-format YCbCr
 * images directly, so convert MediaCodec AHardwareBuffers to a normal RGB
 * Vulkan texture before MPVFlow consumes them. No video pixels are read back
 * to the CPU.
 */

#include "config.h"

#include <string.h>

#include "common/common.h"
#include "common/msg.h"
#include "osdep/threads.h"
#include "video/out/gpu/hwdec.h"

#include "hwdec_aimagereader_vk_private.h"

struct aimagereader_vk {
    struct mp_log *log;
    struct ra_hwdec_mapper *mapper;
    struct aimagereader_vk_api api;
    mp_mutex lock;
    struct aimagereader_vk_convert *convert;
    enum android_vulkan_aimagereader_backend backend;
};

static const char *backend_name(enum android_vulkan_aimagereader_backend backend)
{
    switch (backend) {
    case ANDROID_VULKAN_AIMAGEREADER_BACKEND_COMPUTE:
        return "compute";
    case ANDROID_VULKAN_AIMAGEREADER_BACKEND_FRAGMENT:
        return "fragment";
    default:
        return "auto";
    }
}

static void reset_mapper_params(struct aimagereader_vk *p)
{
    p->mapper->dst_params = p->mapper->src_params;
    p->mapper->dst_params.imgfmt = IMGFMT_RGB0;
    p->mapper->dst_params.hw_subfmt = 0;
    p->mapper->dst_params_ready = false;
    p->mapper->dst_params_preserve_repr = false;
    p->mapper->dst_params_map_coordinates = false;
    p->mapper->dst_num_components = 0;
    memset(p->mapper->dst_component_mapping, 0,
           sizeof(p->mapper->dst_component_mapping));
}

bool aimagereader_vk_available(struct ra_ctx *ra_ctx, struct mp_log *log,
                              enum android_vulkan_aimagereader_backend backend)
{
    (void)backend;
    return aimagereader_vk_convert_available(ra_ctx, log);
}

struct aimagereader_vk *aimagereader_vk_create(
    struct ra_hwdec_mapper *mapper, const struct aimagereader_vk_api *api,
    enum android_vulkan_aimagereader_backend backend)
{
    struct aimagereader_vk *p = talloc_zero(NULL, struct aimagereader_vk);
    p->log = mapper->log;
    p->mapper = mapper;
    p->api = *api;
    p->backend = backend;
    mp_mutex_init(&p->lock);
    mp_info(p->log, "Vulkan AImageReader backend: %s\n",
            backend_name(backend));

    reset_mapper_params(p);
    p->convert = aimagereader_vk_convert_create(mapper, api, backend);
    if (p->convert)
        return p;

    mp_mutex_destroy(&p->lock);
    talloc_free(p);
    return NULL;
}

void aimagereader_vk_destroy(struct aimagereader_vk **state)
{
    struct aimagereader_vk *p = *state;
    if (!p)
        return;

    mp_mutex_lock(&p->lock);
    aimagereader_vk_convert_destroy(&p->convert);
    mp_mutex_unlock(&p->lock);
    mp_mutex_destroy(&p->lock);
    talloc_free(p);
    *state = NULL;
}

void aimagereader_vk_buffer_removed(struct aimagereader_vk *p,
                                    AHardwareBuffer *buffer)
{
    mp_mutex_lock(&p->lock);
    aimagereader_vk_convert_buffer_removed(p->convert, buffer);
    mp_mutex_unlock(&p->lock);
}

bool aimagereader_vk_reuse(struct aimagereader_vk *p,
                           struct mp_image *frame)
{
    mp_mutex_lock(&p->lock);
    bool reused = aimagereader_vk_convert_reuse(p->convert, frame);
    mp_mutex_unlock(&p->lock);
    return reused;
}

bool aimagereader_vk_retain_last(struct aimagereader_vk *p,
                                struct mp_image *frame)
{
    mp_mutex_lock(&p->lock);
    bool retained = aimagereader_vk_convert_retain_last(p->convert, frame);
    mp_mutex_unlock(&p->lock);
    return retained;
}

int aimagereader_vk_map(struct aimagereader_vk *p, AImage *image,
                        AHardwareBuffer *buffer,
                        const AImageCropRect *crop, int32_t data_space,
                        struct mp_image *frame, int *acquire_fence)
{
    mp_mutex_lock(&p->lock);
    int result = aimagereader_vk_convert_map(
        p->convert, image, buffer, crop, data_space, frame, acquire_fence);
    mp_mutex_unlock(&p->lock);
    return result;
}
