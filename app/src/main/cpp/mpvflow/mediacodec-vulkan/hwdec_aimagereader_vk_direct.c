/*
 * Android AImageReader / Vulkan interop.
 *
 * MediaCodec surface output is normally backed by an AHardwareBuffer. Import
 * its concrete or external Vulkan format and let libplacebo sample it through
 * the driver's YCbCr conversion sampler in the final render pipeline.
 */

#include "config.h"

#include <errno.h>
#include <inttypes.h>
#include <poll.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

#include <android/data_space.h>
#include <libplacebo/vulkan.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include "common/common.h"
#include "common/msg.h"
#include "video/out/gpu/context.h"
#include "video/out/gpu/hwdec.h"
#include "video/out/placebo/ra_pl.h"
#include "video/out/vulkan/context.h"

#include "hwdec_aimagereader_vk_private.h"

#if PL_API_VER >= 372

#define FRAME_COUNT 3
#define INPUT_CACHE_SIZE 8

struct source_config {
    AHardwareBuffer_Desc desc;
    VkAndroidHardwareBufferFormatPropertiesANDROID format_props;
    AImageCropRect crop;
    int sample_depth;
    VkFilter chroma_filter;
    bool raw_dovi;
};

struct vk_input {
    AHardwareBuffer *buffer;
    AHardwareBuffer_Desc desc;
    VkAndroidHardwareBufferFormatPropertiesANDROID raw_format_props;
    VkAndroidHardwareBufferPropertiesANDROID buffer_props;
    VkImage image;
    VkDeviceMemory memory;
    pl_tex pltex;
    struct ra_tex *ratex;
    int release_fence;
    bool release_pending;
    VkSemaphore acquire_pending;
    uint64_t last_used;
    int users;
    bool removed;
};

struct vk_frame {
    VkSemaphore acquire;
    VkSemaphore release;
    AImage *source_image;
    struct mp_image *source_frame;
    struct mp_image **source_aliases;
    int num_source_aliases;
    struct vk_input *input;
    bool acquire_in_use;
};

struct cached_acquire {
    VkSemaphore semaphore;
    int release_fence;
};

struct aimagereader_vk_direct {
    struct mp_log *log;
    struct ra_hwdec_mapper *mapper;
    struct aimagereader_vk_api api;

    pl_gpu gpu;
    pl_vulkan vk;
    VkDevice device;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAHBProperties;
    PFN_vkImportSemaphoreFdKHR ImportSemaphoreFd;
    PFN_vkGetSemaphoreFdKHR GetSemaphoreFd;
    bool acquire_sync_fd;
    bool release_sync_fd;

    struct source_config source;
    bool source_valid;
    int frame_index;
    struct vk_frame frames[FRAME_COUNT];

    struct vk_input inputs[INPUT_CACHE_SIZE];
    int num_inputs;
    uint64_t input_serial;

    struct cached_acquire acquire_cache[INPUT_CACHE_SIZE];
    int num_acquire_cache;
    uint64_t acquire_sem_created;
    uint64_t acquire_sem_reused;
    uint64_t acquire_sem_cached;
    uint64_t acquire_cache_stalls;
    uint64_t acquire_sem_destroyed;
};

static bool finish_frame(struct aimagereader_vk_direct *p, struct vk_frame *frame);

static bool vk_success(struct aimagereader_vk_direct *p, VkResult result,
                       const char *operation)
{
    if (result == VK_SUCCESS)
        return true;
    mp_err(p->log, "%s failed: %d\n", operation, result);
    return false;
}

static bool has_extension(pl_vulkan vk, const char *name)
{
    for (int n = 0; n < vk->num_extensions; n++) {
        if (strcmp(vk->extensions[n], name) == 0)
            return true;
    }
    return false;
}

static bool has_ycbcr_conversion(pl_vulkan vk)
{
    const VkBaseOutStructure *feature = vk->features->pNext;
    while (feature) {
        if (feature->sType ==
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES) {
            const VkPhysicalDeviceVulkan11Features *vk11 =
                (const void *)feature;
            if (vk11->samplerYcbcrConversion)
                return true;
        }
        if (feature->sType ==
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES) {
            const VkPhysicalDeviceSamplerYcbcrConversionFeatures *ycbcr =
                (const void *)feature;
            if (ycbcr->samplerYcbcrConversion)
                return true;
        }
        feature = feature->pNext;
    }
    return false;
}

static bool has_synchronization2(pl_vulkan vk)
{
    const VkBaseOutStructure *feature = vk->features->pNext;
    while (feature) {
        if (feature->sType ==
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES) {
            const VkPhysicalDeviceVulkan13Features *vk13 =
                (const void *)feature;
            if (vk13->synchronization2)
                return true;
        }
        if (feature->sType ==
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES) {
            const VkPhysicalDeviceSynchronization2Features *sync2 =
                (const void *)feature;
            if (sync2->synchronization2)
                return true;
        }
        feature = feature->pNext;
    }
    return false;
}

static int collect_queue_families(pl_vulkan vk, uint32_t families[3])
{
    const struct pl_vulkan_queue queues[] = {
        vk->queue_graphics,
        vk->queue_compute,
        vk->queue_transfer,
    };
    int count = 0;
    for (int n = 0; n < (int)MP_ARRAY_SIZE(queues); n++) {
        if (!queues[n].count)
            continue;
        bool duplicate = false;
        for (int i = 0; i < count; i++)
            duplicate |= families[i] == queues[n].index;
        if (!duplicate)
            families[count++] = queues[n].index;
    }
    return count;
}

static bool direct_queue_families_supported(pl_vulkan vk)
{
    uint32_t queue_families[3];
    return collect_queue_families(vk, queue_families) <= 1 ||
           has_synchronization2(vk);
}

static PFN_vkGetPhysicalDeviceExternalSemaphoreProperties
get_external_semaphore_properties(pl_vulkan vk)
{
    PFN_vkGetPhysicalDeviceExternalSemaphoreProperties get_properties =
        (PFN_vkGetPhysicalDeviceExternalSemaphoreProperties)
        vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceExternalSemaphoreProperties");
    if (!get_properties) {
        get_properties =
            (PFN_vkGetPhysicalDeviceExternalSemaphoreProperties)
            vk->get_proc_addr(
                vk->instance,
                "vkGetPhysicalDeviceExternalSemaphorePropertiesKHR");
    }
    return get_properties;
}

static VkExternalSemaphoreFeatureFlags get_sync_fd_features(pl_vulkan vk)
{
    PFN_vkGetPhysicalDeviceExternalSemaphoreProperties get_properties =
        get_external_semaphore_properties(vk);
    if (!get_properties)
        return 0;

    VkPhysicalDeviceExternalSemaphoreInfo info = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO,
        .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
    };
    VkExternalSemaphoreProperties properties = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES,
    };
    get_properties(vk->phys_device, &info, &properties);
    return properties.externalSemaphoreFeatures;
}

bool aimagereader_vk_direct_available(struct ra_ctx *ra_ctx, struct mp_log *log)
{
    struct mpvk_ctx *ctx = ra_vk_ctx_get(ra_ctx);
    pl_gpu gpu = ra_pl_get(ra_ctx->ra);
    pl_vulkan vk = gpu ? pl_vulkan_get(gpu) : NULL;
    if (!ctx || !vk || !vk->get_proc_addr || !vk->features ||
        !vk->queue_graphics.count || !has_ycbcr_conversion(vk))
        return false;

    if (!direct_queue_families_supported(vk)) {
        mp_verbose(log, "Direct Vulkan AImageReader sampling requires "
                        "synchronization2 with multiple queue families\n");
        return false;
    }

    if (!has_extension(
            vk,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) ||
        !has_extension(vk, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
        mp_verbose(log, "Vulkan device lacks Android hardware-buffer interop\n");
        return false;
    }

    PFN_vkGetDeviceProcAddr get_device_proc =
        (PFN_vkGetDeviceProcAddr)vk->get_proc_addr(vk->instance,
                                                   "vkGetDeviceProcAddr");
    return get_device_proc &&
           get_device_proc(vk->device,
                           "vkGetAndroidHardwareBufferPropertiesANDROID");
}

static VkSemaphore create_binary_semaphore(struct aimagereader_vk_direct *p,
                                           bool export_sync_fd)
{
    VkExportSemaphoreCreateInfo export_info = {
        .sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
    };
    VkSemaphoreCreateInfo semaphore_info = {
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
        .pNext = export_sync_fd ? &export_info : NULL,
    };
    VkSemaphore semaphore = VK_NULL_HANDLE;
    if (vkCreateSemaphore(p->device, &semaphore_info, NULL, &semaphore) !=
        VK_SUCCESS)
        return VK_NULL_HANDLE;
    return semaphore;
}

static void destroy_acquire_semaphore(struct aimagereader_vk_direct *p,
                                      VkSemaphore *semaphore)
{
    if (!*semaphore)
        return;
    vkDestroySemaphore(p->device, *semaphore, NULL);
    *semaphore = VK_NULL_HANDLE;
    p->acquire_sem_destroyed++;
}

static bool release_fence_signaled(int fence_fd)
{
    struct pollfd fence = {
        .fd = fence_fd,
        .events = POLLIN,
    };
    int result;
    do {
        result = poll(&fence, 1, 0);
    } while (result < 0 && (errno == EINTR || errno == EAGAIN));
    return result > 0 && (fence.revents & POLLIN) &&
           !(fence.revents & (POLLERR | POLLNVAL));
}

static VkSemaphore remove_ready_acquire(struct aimagereader_vk_direct *p)
{
    for (int n = 0; n < p->num_acquire_cache; n++) {
        struct cached_acquire *cached = &p->acquire_cache[n];
        if (cached->release_fence >= 0 &&
            !release_fence_signaled(cached->release_fence))
            continue;

        if (cached->release_fence >= 0)
            close(cached->release_fence);
        VkSemaphore semaphore = cached->semaphore;
        *cached = p->acquire_cache[--p->num_acquire_cache];
        p->acquire_cache[p->num_acquire_cache] =
            (struct cached_acquire){0};
        return semaphore;
    }
    return VK_NULL_HANDLE;
}

static VkSemaphore take_acquire_semaphore(struct aimagereader_vk_direct *p)
{
    if (!p->acquire_sync_fd)
        return VK_NULL_HANDLE;

    VkSemaphore semaphore = remove_ready_acquire(p);
    if (semaphore) {
        p->acquire_sem_reused++;
        return semaphore;
    }

    semaphore = create_binary_semaphore(p, false);
    if (semaphore)
        p->acquire_sem_created++;
    return semaphore;
}

static bool ensure_frame_sync(struct aimagereader_vk_direct *p)
{
    for (int n = 0; n < FRAME_COUNT; n++) {
        struct vk_frame *frame = &p->frames[n];
        if (frame->release)
            continue;

        if (p->acquire_sync_fd && !frame->acquire) {
            frame->acquire = take_acquire_semaphore(p);
            if (!frame->acquire)
                p->acquire_sync_fd = false;
        }
        frame->release =
            create_binary_semaphore(p, p->release_sync_fd);
        if (!frame->release && p->release_sync_fd) {
            p->release_sync_fd = false;
            frame->release = create_binary_semaphore(p, false);
        }
        if (!frame->release) {
            mp_err(p->log, "Failed creating AHardwareBuffer frame sync\n");
            return false;
        }
    }
    return true;
}

static void mark_acquire_cache_ready(struct aimagereader_vk_direct *p)
{
    for (int n = 0; n < p->num_acquire_cache; n++) {
        struct cached_acquire *cached = &p->acquire_cache[n];
        if (cached->release_fence >= 0)
            close(cached->release_fence);
        cached->release_fence = -1;
    }
}

static void cache_acquire_semaphore(struct aimagereader_vk_direct *p,
                                    VkSemaphore *semaphore,
                                    int *release_fence)
{
    mp_assert(*release_fence >= 0);
    if (!*semaphore) {
        close(*release_fence);
        *release_fence = -1;
        return;
    }

    if (p->num_acquire_cache ==
        (int)MP_ARRAY_SIZE(p->acquire_cache)) {
        VkSemaphore ready = remove_ready_acquire(p);
        destroy_acquire_semaphore(p, &ready);
    }
    if (p->num_acquire_cache ==
        (int)MP_ARRAY_SIZE(p->acquire_cache)) {
        // Keep the cache bounded if the GPU has more outstanding acquire
        // waits than the AHardwareBuffer import cache can represent.
        pl_gpu_finish(p->gpu);
        mark_acquire_cache_ready(p);
        p->acquire_cache_stalls++;
        VkSemaphore ready = remove_ready_acquire(p);
        destroy_acquire_semaphore(p, &ready);
    }

    struct cached_acquire *cached =
        &p->acquire_cache[p->num_acquire_cache++];
    cached->semaphore = *semaphore;
    cached->release_fence = *release_fence;
    *semaphore = VK_NULL_HANDLE;
    *release_fence = -1;
    p->acquire_sem_cached++;
}

static uint32_t find_memory_type(struct aimagereader_vk_direct *p,
                                 uint32_t type_bits)
{
    VkPhysicalDeviceMemoryProperties props;
    vkGetPhysicalDeviceMemoryProperties(p->vk->phys_device, &props);
    for (uint32_t n = 0; n < props.memoryTypeCount; n++) {
        if (type_bits & (1u << n))
            return n;
    }
    return UINT32_MAX;
}

static int ycbcr_format_depth(VkFormat format)
{
    switch (format) {
    case VK_FORMAT_G8B8G8R8_422_UNORM:
    case VK_FORMAT_B8G8R8G8_422_UNORM:
    case VK_FORMAT_G8_B8_R8_3PLANE_420_UNORM:
    case VK_FORMAT_G8_B8R8_2PLANE_420_UNORM:
    case VK_FORMAT_G8_B8_R8_3PLANE_422_UNORM:
    case VK_FORMAT_G8_B8R8_2PLANE_422_UNORM:
    case VK_FORMAT_G8_B8_R8_3PLANE_444_UNORM:
    case VK_FORMAT_G8_B8R8_2PLANE_444_UNORM:
        return 8;
    case VK_FORMAT_G10X6B10X6G10X6R10X6_422_UNORM_4PACK16:
    case VK_FORMAT_B10X6G10X6R10X6G10X6_422_UNORM_4PACK16:
    case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_420_UNORM_3PACK16:
    case VK_FORMAT_G10X6_B10X6R10X6_2PLANE_420_UNORM_3PACK16:
    case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_422_UNORM_3PACK16:
    case VK_FORMAT_G10X6_B10X6R10X6_2PLANE_422_UNORM_3PACK16:
    case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_444_UNORM_3PACK16:
    case VK_FORMAT_G10X6_B10X6R10X6_2PLANE_444_UNORM_3PACK16:
        return 10;
    case VK_FORMAT_G12X4B12X4G12X4R12X4_422_UNORM_4PACK16:
    case VK_FORMAT_B12X4G12X4R12X4G12X4_422_UNORM_4PACK16:
    case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_420_UNORM_3PACK16:
    case VK_FORMAT_G12X4_B12X4R12X4_2PLANE_420_UNORM_3PACK16:
    case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_422_UNORM_3PACK16:
    case VK_FORMAT_G12X4_B12X4R12X4_2PLANE_422_UNORM_3PACK16:
    case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_444_UNORM_3PACK16:
    case VK_FORMAT_G12X4_B12X4R12X4_2PLANE_444_UNORM_3PACK16:
        return 12;
    case VK_FORMAT_G16B16G16R16_422_UNORM:
    case VK_FORMAT_B16G16R16G16_422_UNORM:
    case VK_FORMAT_G16_B16_R16_3PLANE_420_UNORM:
    case VK_FORMAT_G16_B16R16_2PLANE_420_UNORM:
    case VK_FORMAT_G16_B16_R16_3PLANE_422_UNORM:
    case VK_FORMAT_G16_B16R16_2PLANE_422_UNORM:
    case VK_FORMAT_G16_B16_R16_3PLANE_444_UNORM:
    case VK_FORMAT_G16_B16R16_2PLANE_444_UNORM:
        return 16;
    default:
        return 0;
    }
}

static bool supports_chroma_location(VkFormatFeatureFlags features,
                                     VkChromaLocation location)
{
    switch (location) {
    case VK_CHROMA_LOCATION_MIDPOINT:
        return features &
            VK_FORMAT_FEATURE_MIDPOINT_CHROMA_SAMPLES_BIT;
    case VK_CHROMA_LOCATION_COSITED_EVEN:
        return features &
            VK_FORMAT_FEATURE_COSITED_CHROMA_SAMPLES_BIT;
    default:
        return false;
    }
}

static bool query_concrete_format(
    struct aimagereader_vk_direct *p,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props,
    VkFormatFeatureFlags *features)
{
    if (!ycbcr_format_depth(props->format))
        return false;

    VkPhysicalDeviceExternalImageFormatInfo external_info = {
        .sType =
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO,
        .handleType =
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
    };
    VkPhysicalDeviceImageFormatInfo2 format_info = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2,
        .pNext = &external_info,
        .format = props->format,
        .type = VK_IMAGE_TYPE_2D,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
    };
    VkSamplerYcbcrConversionImageFormatProperties ycbcr_props = {
        .sType =
            VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_IMAGE_FORMAT_PROPERTIES,
    };
    VkExternalImageFormatProperties external_props = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES,
        .pNext = &ycbcr_props,
    };
    VkImageFormatProperties2 image_props = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2,
        .pNext = &external_props,
    };
    VkResult result = vkGetPhysicalDeviceImageFormatProperties2(
        p->vk->phys_device, &format_info, &image_props);
    VkExternalMemoryProperties *memory =
        &external_props.externalMemoryProperties;
    if (result != VK_SUCCESS ||
        !(memory->externalMemoryFeatures &
          VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) ||
        !(memory->compatibleHandleTypes &
          VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID) ||
        !ycbcr_props.combinedImageSamplerDescriptorCount) {
        return false;
    }

    VkFormatProperties2 format_props = {
        .sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2,
    };
    vkGetPhysicalDeviceFormatProperties2(p->vk->phys_device, props->format,
                                         &format_props);
    *features = format_props.formatProperties.optimalTilingFeatures;
    const VkFormatFeatureFlags required =
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT |
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_SEPARATE_RECONSTRUCTION_FILTER_BIT;
    return (*features & required) == required &&
           supports_chroma_location(*features,
                                    props->suggestedXChromaOffset) &&
           supports_chroma_location(*features,
                                    props->suggestedYChromaOffset);
}

static int source_sample_depth(
    struct aimagereader_vk_direct *p, const AHardwareBuffer_Desc *desc,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props,
    int32_t data_space)
{
    const struct pl_bit_encoding *bits = &p->mapper->src_params.repr.bits;
    int depth = bits->color_depth ? bits->color_depth : bits->sample_depth;
    depth = MPMAX(depth, ycbcr_format_depth(props->format));

    if (desc->format == AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT ||
        props->format == VK_FORMAT_R16G16B16A16_SFLOAT || depth > 10)
        return 16;

    int32_t transfer = data_space & ADATASPACE_TRANSFER_MASK;
    if (depth > 8 ||
        desc->format == AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM ||
        desc->format == AHARDWAREBUFFER_FORMAT_YCbCr_P010 ||
        desc->format == AHARDWAREBUFFER_FORMAT_YCbCr_P210 ||
        pl_color_transfer_is_hdr(p->mapper->src_params.color.transfer) ||
        transfer == ADATASPACE_TRANSFER_ST2084 ||
        transfer == ADATASPACE_TRANSFER_HLG)
        return 10;

    return 8;
}

static VkSamplerYcbcrModelConversion data_space_ycbcr_model(
    int32_t data_space)
{
    switch (data_space & ADATASPACE_STANDARD_MASK) {
    case ADATASPACE_STANDARD_BT709:
        return VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709;
    case ADATASPACE_STANDARD_BT601_625:
    case ADATASPACE_STANDARD_BT601_525:
        return VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601;
    case ADATASPACE_STANDARD_BT2020:
        return VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_2020;
    default:
        return VK_SAMPLER_YCBCR_MODEL_CONVERSION_RGB_IDENTITY;
    }
}

static VkSamplerYcbcrRange data_space_ycbcr_range(int32_t data_space)
{
    switch (data_space & ADATASPACE_RANGE_MASK) {
    case ADATASPACE_RANGE_FULL:
        return VK_SAMPLER_YCBCR_RANGE_ITU_FULL;
    case ADATASPACE_RANGE_LIMITED:
        return VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
    default:
        return VK_SAMPLER_YCBCR_RANGE_MAX_ENUM;
    }
}

static void apply_data_space(
    VkAndroidHardwareBufferFormatPropertiesANDROID *props,
    int32_t data_space)
{
    bool is_ycbcr =
        ycbcr_format_depth(props->format) ||
        props->suggestedYcbcrModel !=
            VK_SAMPLER_YCBCR_MODEL_CONVERSION_RGB_IDENTITY;
    if (data_space == ADATASPACE_UNKNOWN || !is_ycbcr)
        return;

    VkSamplerYcbcrModelConversion model =
        data_space_ycbcr_model(data_space);
    if (model != VK_SAMPLER_YCBCR_MODEL_CONVERSION_RGB_IDENTITY)
        props->suggestedYcbcrModel = model;

    VkSamplerYcbcrRange range = data_space_ycbcr_range(data_space);
    if (range != VK_SAMPLER_YCBCR_RANGE_MAX_ENUM)
        props->suggestedYcbcrRange = range;
}

static bool query_buffer_properties(
    struct aimagereader_vk_direct *p, AHardwareBuffer *buffer,
    AHardwareBuffer_Desc *desc,
    VkAndroidHardwareBufferFormatPropertiesANDROID *format_props,
    VkAndroidHardwareBufferPropertiesANDROID *buffer_props)
{
    *format_props = (VkAndroidHardwareBufferFormatPropertiesANDROID) {
        .sType =
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID,
    };
    *buffer_props = (VkAndroidHardwareBufferPropertiesANDROID) {
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID,
        .pNext = format_props,
    };
    p->api.AHardwareBuffer_describe(buffer, desc);
    if (!vk_success(p, p->GetAHBProperties(p->device, buffer, buffer_props),
                    "querying Android hardware-buffer properties"))
        return false;

    buffer_props->pNext = NULL;
    format_props->pNext = NULL;
    if (format_props->format == VK_FORMAT_UNDEFINED &&
        !format_props->externalFormat) {
        mp_err(p->log, "Android hardware buffer has no usable Vulkan YCbCr "
                       "format (format %d, external 0x%" PRIx64 ")\n",
               format_props->format, format_props->externalFormat);
        return false;
    }
    return true;
}

static int build_source_config(
    struct aimagereader_vk_direct *p, const AHardwareBuffer_Desc *desc,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *format_props,
    const AImageCropRect *crop, int32_t data_space,
    struct source_config *source)
{
    *source = (struct source_config) {
        .desc = *desc,
        .format_props = *format_props,
        .crop = *crop,
    };
    source->format_props.pNext = NULL;

    if (!desc->width || !desc->height ||
        desc->width > INT32_MAX || desc->height > INT32_MAX ||
        desc->layers != 1 ||
        !(desc->usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE) ||
        crop->left < 0 || crop->top < 0 ||
        crop->right <= crop->left || crop->bottom <= crop->top ||
        (uint32_t)crop->right > desc->width ||
        (uint32_t)crop->bottom > desc->height) {
        mp_err(p->log, "Unsupported Android hardware-buffer geometry or "
                       "sampling usage (buffer %ux%u, crop %d,%d-%d,%d, "
                       "layers %u, usage 0x%" PRIx64 ")\n",
               desc->width, desc->height, crop->left, crop->top,
               crop->right, crop->bottom, desc->layers, desc->usage);
        return -1;
    }

    VkFormatFeatureFlags concrete_features = 0;
    if (query_concrete_format(p, format_props, &concrete_features)) {
        source->format_props.externalFormat = 0;
        source->format_props.formatFeatures = concrete_features;
    } else {
        source->format_props.format = VK_FORMAT_UNDEFINED;
    }
    VkFormatFeatureFlags selected_features =
        source->format_props.formatFeatures;
    const VkFormatFeatureFlags required_features =
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    if ((selected_features & required_features) != required_features) {
        mp_verbose(p->log, "Android hardware buffer cannot be sampled "
                           "directly with linear filtering\n");
        return AIMAGEREADER_VK_MAP_UNSUPPORTED;
    }

    apply_data_space(&source->format_props, data_space);
    source->raw_dovi =
        p->mapper->src_params.repr.sys == PL_COLOR_SYSTEM_DOLBYVISION;
    if (source->raw_dovi) {
        // Dolby Vision reshaping needs the original normalized Y/Cb/Cr code
        // values. RGB identity still lets the sampler reconstruct chroma, but
        // skips range expansion and the YCbCr-to-RGB matrix.
        source->format_props.suggestedYcbcrModel =
            VK_SAMPLER_YCBCR_MODEL_CONVERSION_RGB_IDENTITY;
        source->format_props.suggestedYcbcrRange =
            VK_SAMPLER_YCBCR_RANGE_ITU_FULL;
    }
    source->sample_depth =
        source_sample_depth(p, desc, &source->format_props, data_space);
    source->chroma_filter =
        selected_features &
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT
            ? VK_FILTER_LINEAR
            : VK_FILTER_NEAREST;
    return 0;
}

static bool same_source_config(const struct source_config *a,
                               const struct source_config *b)
{
    const VkAndroidHardwareBufferFormatPropertiesANDROID *x =
        &a->format_props;
    const VkAndroidHardwareBufferFormatPropertiesANDROID *y =
        &b->format_props;
    return a->desc.width == b->desc.width &&
           a->desc.height == b->desc.height &&
           a->desc.format == b->desc.format &&
           a->crop.left == b->crop.left &&
           a->crop.top == b->crop.top &&
           a->crop.right == b->crop.right &&
           a->crop.bottom == b->crop.bottom &&
           a->sample_depth == b->sample_depth &&
           a->chroma_filter == b->chroma_filter &&
           a->raw_dovi == b->raw_dovi &&
           x->format == y->format &&
           x->externalFormat == y->externalFormat &&
           x->formatFeatures == y->formatFeatures &&
           x->suggestedYcbcrModel == y->suggestedYcbcrModel &&
           x->suggestedYcbcrRange == y->suggestedYcbcrRange &&
           x->suggestedXChromaOffset == y->suggestedXChromaOffset &&
           x->suggestedYChromaOffset == y->suggestedYChromaOffset &&
           !memcmp(&x->samplerYcbcrConversionComponents,
                   &y->samplerYcbcrConversionComponents,
                   sizeof(x->samplerYcbcrConversionComponents));
}

static bool same_output_config(const struct source_config *a,
                               const struct source_config *b)
{
    return a->desc.width == b->desc.width &&
           a->desc.height == b->desc.height &&
           a->crop.left == b->crop.left &&
           a->crop.top == b->crop.top &&
           a->crop.right == b->crop.right &&
           a->crop.bottom == b->crop.bottom &&
           a->sample_depth == b->sample_depth &&
           a->raw_dovi == b->raw_dovi;
}

static void configure_dst_params(struct aimagereader_vk_direct *p,
                                 const struct source_config *source)
{
    struct mp_image_params params = p->mapper->src_params;
    params.imgfmt = IMGFMT_RGB0;
    params.hw_subfmt = 0;
    params.w = source->desc.width;
    params.h = source->desc.height;
    params.crop = (struct mp_rect) {
        source->crop.left,
        source->crop.top,
        source->crop.right,
        source->crop.bottom,
    };
    params.repr.bits = (struct pl_bit_encoding) {
        // VkSamplerYcbcrConversion returns normalized samples. Do not carry
        // P010's 16-bit storage padding into the representation or libplacebo
        // would scale the values a second time.
        .sample_depth = source->sample_depth,
        .color_depth = source->sample_depth,
    };
    p->mapper->dst_params = params;
    p->mapper->dst_params_preserve_repr = source->raw_dovi;
    p->mapper->dst_params_map_coordinates = true;
    p->mapper->dst_num_components = source->raw_dovi ? 3 : 0;
    if (source->raw_dovi) {
        // YCbCr conversion exposes raw components as Cr/Y/Cb in R/G/B with
        // RGB_IDENTITY. Describe those sampled channels as Y/Cb/Cr.
        const int mapping[4] = {
            PL_CHANNEL_CR,
            PL_CHANNEL_Y,
            PL_CHANNEL_CB,
            PL_CHANNEL_NONE,
        };
        memcpy(p->mapper->dst_component_mapping, mapping, sizeof(mapping));
    }
}

static void clear_frame_refs(struct vk_frame *frame)
{
    mp_image_unrefp(&frame->source_frame);
    for (int n = 0; n < frame->num_source_aliases; n++)
        mp_image_unrefp(&frame->source_aliases[n]);
    frame->num_source_aliases = 0;
}

static void destroy_input(struct aimagereader_vk_direct *p, struct vk_input *input)
{
    mp_assert(!input->users);
    if (input->release_pending) {
        if (!release_fence_signaled(input->release_fence))
            pl_gpu_finish(p->gpu);
        close(input->release_fence);
    }
    destroy_acquire_semaphore(p, &input->acquire_pending);
    talloc_free(input->ratex);
    if (input->pltex)
        pl_tex_destroy(p->gpu, &input->pltex);
    if (input->image)
        vkDestroyImage(p->device, input->image, NULL);
    if (input->memory)
        vkFreeMemory(p->device, input->memory, NULL);
    *input = (struct vk_input){0};
}

static struct vk_input *find_input(struct aimagereader_vk_direct *p,
                                   AHardwareBuffer *buffer)
{
    for (int n = 0; n < p->num_inputs; n++) {
        if (p->inputs[n].buffer == buffer)
            return &p->inputs[n];
    }
    return NULL;
}

static void purge_removed_inputs(struct aimagereader_vk_direct *p)
{
    for (int n = 0; n < p->num_inputs; n++) {
        struct vk_input *input = &p->inputs[n];
        if (input->removed && !input->users)
            destroy_input(p, input);
    }
}

static struct vk_input *select_input_slot(struct aimagereader_vk_direct *p)
{
    for (int n = 0; n < p->num_inputs; n++) {
        if (!p->inputs[n].buffer)
            return &p->inputs[n];
    }
    if (p->num_inputs < INPUT_CACHE_SIZE)
        return &p->inputs[p->num_inputs];

    struct vk_input *oldest = NULL;
    for (int n = 0; n < p->num_inputs; n++) {
        struct vk_input *input = &p->inputs[n];
        if (!input->users &&
            (!oldest || input->last_used < oldest->last_used))
            oldest = input;
    }
    if (!oldest) {
        mp_err(p->log, "All AHardwareBuffer import slots are in use\n");
        return NULL;
    }
    destroy_input(p, oldest);
    return oldest;
}

static struct vk_input *create_input(
    struct aimagereader_vk_direct *p, AHardwareBuffer *buffer,
    const struct source_config *source,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *raw_format_props,
    const VkAndroidHardwareBufferPropertiesANDROID *buffer_props)
{
    struct vk_input *input = select_input_slot(p);
    if (!input)
        return NULL;

    const AHardwareBuffer_Desc *desc = &source->desc;
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props =
        &source->format_props;
    VkExternalFormatANDROID external_format = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
        .externalFormat = props->externalFormat,
    };
    VkExternalMemoryImageCreateInfo external_memory = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .pNext = props->externalFormat ? &external_format : NULL,
        .handleTypes =
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
    };
    uint32_t queue_families[3];
    int num_queue_families =
        collect_queue_families(p->vk, queue_families);
    VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &external_memory,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = props->format,
        .extent = {desc->width, desc->height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
        .sharingMode = num_queue_families > 1
            ? VK_SHARING_MODE_CONCURRENT
            : VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount =
            num_queue_families > 1 ? num_queue_families : 0,
        .pQueueFamilyIndices =
            num_queue_families > 1 ? queue_families : NULL,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (!vk_success(p, vkCreateImage(p->device, &image_info, NULL,
                                     &input->image),
                    "creating Vulkan YCbCr AHardwareBuffer image"))
        goto error;

    uint32_t memory_type =
        find_memory_type(p, buffer_props->memoryTypeBits);
    if (memory_type == UINT32_MAX) {
        mp_err(p->log, "No memory type for Android hardware buffer\n");
        goto error;
    }
    VkImportAndroidHardwareBufferInfoANDROID import_info = {
        .sType =
            VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
        .buffer = buffer,
    };
    VkMemoryDedicatedAllocateInfo dedicated_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .pNext = &import_info,
        .image = input->image,
    };
    VkMemoryAllocateInfo alloc_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = &dedicated_info,
        .allocationSize = buffer_props->allocationSize,
        .memoryTypeIndex = memory_type,
    };
    if (!vk_success(p, vkAllocateMemory(p->device, &alloc_info, NULL,
                                        &input->memory),
                    "importing Android hardware-buffer memory") ||
        !vk_success(p, vkBindImageMemory(p->device, input->image,
                                         input->memory, 0),
                    "binding Android hardware-buffer memory"))
        goto error;

    struct pl_vulkan_ycbcr_params ycbcr = {
        .external_format = props->externalFormat,
        .components = props->samplerYcbcrConversionComponents,
        .model = props->suggestedYcbcrModel,
        .range = props->suggestedYcbcrRange,
        .x_chroma_offset = props->suggestedXChromaOffset,
        .y_chroma_offset = props->suggestedYChromaOffset,
        .chroma_filter = source->chroma_filter,
        .separate_reconstruction_filter =
            props->formatFeatures &
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_SEPARATE_RECONSTRUCTION_FILTER_BIT,
        .sample_depth = source->sample_depth,
    };
    input->pltex = pl_vulkan_wrap(
        p->gpu, pl_vulkan_wrap_params(
            .image = input->image,
            .width = desc->width,
            .height = desc->height,
            .format = props->format,
            .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
            .ycbcr = &ycbcr,
        ));
    input->ratex = talloc_ptrtype(NULL, input->ratex);
    if (!input->pltex || !input->ratex ||
        !mppl_wrap_tex(p->mapper->ra, input->pltex, input->ratex) ||
        !input->ratex->params.render_src) {
        mp_err(p->log, "libplacebo rejected Vulkan YCbCr AHardwareBuffer\n");
        goto error;
    }

    input->buffer = buffer;
    input->desc = source->desc;
    input->raw_format_props = *raw_format_props;
    input->raw_format_props.pNext = NULL;
    input->buffer_props = *buffer_props;
    input->buffer_props.pNext = NULL;
    if (input == &p->inputs[p->num_inputs])
        p->num_inputs++;
    return input;

error:
    destroy_input(p, input);
    return NULL;
}

static bool wait_acquire_fence(struct aimagereader_vk_direct *p, int fence_fd)
{
    struct pollfd fence = {
        .fd = fence_fd,
        .events = POLLIN,
    };
    int result;
    do {
        result = poll(&fence, 1, 100);
    } while (result < 0 && (errno == EINTR || errno == EAGAIN));
    close(fence_fd);
    if (result > 0 && !(fence.revents & (POLLERR | POLLNVAL)))
        return true;
    mp_err(p->log, "Waiting for AImage acquire fence failed: "
                   "%d (revents 0x%x)\n",
           result, (unsigned)fence.revents);
    return false;
}

static int import_acquire_fence(struct aimagereader_vk_direct *p,
                                VkSemaphore semaphore, int fence_fd)
{
    if (fence_fd < 0)
        return 0;
    if (!p->acquire_sync_fd || !semaphore)
        return wait_acquire_fence(p, fence_fd) ? 0 : -1;

    VkImportSemaphoreFdInfoKHR import_info = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR,
        .semaphore = semaphore,
        .flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT,
        .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
        .fd = fence_fd,
    };
    VkResult result = p->ImportSemaphoreFd(p->device, &import_info);
    if (result == VK_SUCCESS)
        return 1;

    mp_verbose(p->log, "Importing AImage acquire fence failed: %d; "
                       "using a CPU wait\n", result);
    p->acquire_sync_fd = false;
    return wait_acquire_fence(p, fence_fd) ? 0 : -1;
}

static int export_release_fence(struct aimagereader_vk_direct *p,
                                VkSemaphore semaphore)
{
    if (!p->release_sync_fd || !semaphore)
        return -1;

    int release_fence = -1;
    VkSemaphoreGetFdInfoKHR fd_info = {
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,
        .semaphore = semaphore,
        .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
    };
    VkResult result =
        p->GetSemaphoreFd(p->device, &fd_info, &release_fence);
    if (result == VK_SUCCESS)
        return release_fence;

    mp_verbose(p->log, "Exporting AImage release fence failed: %d; "
                       "using a GPU wait\n", result);
    p->release_sync_fd = false;
    return -1;
}

static bool reset_release_semaphore(struct aimagereader_vk_direct *p,
                                    struct vk_frame *frame)
{
    if (frame->release)
        vkDestroySemaphore(p->device, frame->release, NULL);
    frame->release =
        create_binary_semaphore(p, p->release_sync_fd);
    if (frame->release)
        return true;
    mp_err(p->log, "Failed resetting AHardwareBuffer release semaphore\n");
    return false;
}

static bool finish_frame(struct aimagereader_vk_direct *p, struct vk_frame *frame)
{
    if (!frame->source_image)
        return true;

    mp_assert(frame->input);
    mp_assert(frame->release);
    bool held = pl_vulkan_hold_ex(
        p->gpu, pl_vulkan_hold_params(
            .tex = frame->input->pltex,
            .layout = VK_IMAGE_LAYOUT_GENERAL,
            .qf = VK_QUEUE_FAMILY_FOREIGN_EXT,
            .semaphore = (pl_vulkan_sem){.sem = frame->release},
        ));
    int release_fence = held
        ? export_release_fence(p, frame->release)
        : -1;
    int retained_release_fence = release_fence >= 0
        ? dup(release_fence)
        : -1;
    bool semaphore_ready = true;
    if (!held || release_fence < 0 || retained_release_fence < 0) {
        pl_gpu_finish(p->gpu);
        if (held)
            semaphore_ready = reset_release_semaphore(p, frame);
    }

    if (release_fence >= 0)
        p->api.AImage_deleteAsync(frame->source_image, release_fence);
    else
        p->api.AImage_delete(frame->source_image);
    frame->source_image = NULL;
    clear_frame_refs(frame);

    struct vk_input *input = frame->input;
    frame->input = NULL;
    if (retained_release_fence >= 0) {
        mp_assert(!input->release_pending);
        input->release_fence = retained_release_fence;
        input->release_pending = true;
        if (frame->acquire_in_use) {
            mp_assert(!input->acquire_pending);
            input->acquire_pending = frame->acquire;
            frame->acquire = VK_NULL_HANDLE;
        }
    }
    frame->acquire_in_use = false;
    if (input->users > 0)
        input->users--;
    if (!input->users && input->removed)
        destroy_input(p, input);

    if (!held)
        mp_err(p->log, "Failed returning AHardwareBuffer ownership\n");
    return held && semaphore_ready;
}

static void expose_input(struct aimagereader_vk_direct *p, struct vk_input *input)
{
    p->mapper->tex[0] = input->ratex;
    for (int n = 1; n < (int)MP_ARRAY_SIZE(p->mapper->tex); n++)
        p->mapper->tex[n] = NULL;
}

static int map_direct_image(struct aimagereader_vk_direct *p,
                            struct vk_frame *slot,
                            struct vk_input *input, AImage *image,
                            struct mp_image *frame,
                            int *acquire_fence)
{
    struct mp_image *frame_ref = mp_image_new_ref(frame);
    if (!frame_ref)
        return -1;

    if (input->release_pending) {
        // The release fence proves that libplacebo's prior acquire wait has
        // completed. Cache unsignaled pairs with their fence instead of
        // reimporting a semaphore which may still be in use.
        cache_acquire_semaphore(
            p, &input->acquire_pending, &input->release_fence);
        input->release_pending = false;
    }
    if (*acquire_fence >= 0 && p->acquire_sync_fd && !slot->acquire) {
        slot->acquire = take_acquire_semaphore(p);
        if (!slot->acquire) {
            p->acquire_sync_fd = false;
            mp_verbose(p->log, "Vulkan sync-fd import disabled after "
                               "acquiring a semaphore failed\n");
        }
    }

    int acquire_imported =
        import_acquire_fence(p, slot->acquire, *acquire_fence);
    *acquire_fence = -1;
    if (acquire_imported < 0) {
        mp_image_unrefp(&frame_ref);
        return -1;
    }

    pl_vulkan_release_ex(
        p->gpu, pl_vulkan_release_params(
            .tex = input->pltex,
            .layout = VK_IMAGE_LAYOUT_GENERAL,
            .qf = VK_QUEUE_FAMILY_FOREIGN_EXT,
            .semaphore = acquire_imported
                ? (pl_vulkan_sem){.sem = slot->acquire}
                : (pl_vulkan_sem){0},
        ));

    slot->source_image = image;
    slot->source_frame = frame_ref;
    slot->input = input;
    slot->acquire_in_use = acquire_imported > 0;
    input->users++;
    input->last_used = ++p->input_serial;
    expose_input(p, input);
    return 0;
}

static void destroy_imports(struct aimagereader_vk_direct *p)
{
    for (int n = 0; n < FRAME_COUNT; n++)
        finish_frame(p, &p->frames[n]);

    bool has_imports = false;
    for (int n = 0; n < p->num_inputs; n++)
        has_imports |= !!p->inputs[n].buffer;
    if (has_imports || p->num_acquire_cache) {
        pl_gpu_finish(p->gpu);
        mark_acquire_cache_ready(p);
    }
    for (int n = 0; n < p->num_inputs; n++)
        destroy_input(p, &p->inputs[n]);
    p->num_inputs = 0;
    for (int n = 0; n < (int)MP_ARRAY_SIZE(p->mapper->tex); n++)
        p->mapper->tex[n] = NULL;
}

static bool ensure_source_config(struct aimagereader_vk_direct *p,
                                 const struct source_config *source)
{
    if (p->source_valid && same_source_config(&p->source, source))
        return true;

    if (p->source_valid && !same_output_config(&p->source, source)) {
        mp_err(p->log, "Android hardware-buffer output geometry or precision "
                       "changed without hardware-decoder reconfiguration\n");
        return false;
    }

    if (p->source_valid) {
        mp_verbose(p->log, "Reconfiguring Vulkan YCbCr sampler\n");
        destroy_imports(p);
    }
    p->source = *source;
    p->source.format_props.pNext = NULL;
    bool first_config = !p->source_valid;
    p->source_valid = true;
    if (first_config)
        configure_dst_params(p, source);
    mp_info(p->log, "Using Vulkan YCbCr AHardwareBuffer sampling "
                    "(%ux%u, crop %d,%d-%d,%d, format %d, external "
                    "0x%" PRIx64 ", %d-bit%s)\n",
            source->desc.width, source->desc.height,
            source->crop.left, source->crop.top,
            source->crop.right, source->crop.bottom,
            source->format_props.format,
            source->format_props.externalFormat, source->sample_depth,
            source->raw_dovi ? ", raw Dolby Vision" : "");
    return true;
}

struct aimagereader_vk_direct *aimagereader_vk_direct_create(
    struct ra_hwdec_mapper *mapper, const struct aimagereader_vk_api *api)
{
    struct aimagereader_vk_direct *p =
        talloc_zero(NULL, struct aimagereader_vk_direct);
    p->log = mapper->log;
    p->mapper = mapper;
    p->api = *api;
    p->frame_index = -1;

    struct mpvk_ctx *ctx = ra_vk_ctx_get(mapper->owner->ra_ctx);
    p->gpu = ra_pl_get(mapper->ra);
    p->vk = p->gpu ? pl_vulkan_get(p->gpu) : NULL;
    if (!ctx || !p->vk || !p->vk->get_proc_addr || !p->vk->features ||
        !p->vk->queue_graphics.count ||
        !has_ycbcr_conversion(p->vk) ||
        !direct_queue_families_supported(p->vk) ||
        !has_extension(
            p->vk,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) ||
        !has_extension(p->vk, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME))
        goto error;

    p->device = p->vk->device;
    PFN_vkGetDeviceProcAddr get_device_proc =
        (PFN_vkGetDeviceProcAddr)p->vk->get_proc_addr(
            p->vk->instance, "vkGetDeviceProcAddr");
    if (get_device_proc) {
        p->GetAHBProperties =
            (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)
            get_device_proc(
                p->device,
                "vkGetAndroidHardwareBufferPropertiesANDROID");
        if (has_extension(
                p->vk, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)) {
            p->ImportSemaphoreFd =
                (PFN_vkImportSemaphoreFdKHR)get_device_proc(
                    p->device, "vkImportSemaphoreFdKHR");
            p->GetSemaphoreFd =
                (PFN_vkGetSemaphoreFdKHR)get_device_proc(
                    p->device, "vkGetSemaphoreFdKHR");
        }
    }
    if (!p->GetAHBProperties)
        goto error;

    VkExternalSemaphoreFeatureFlags sync_fd_features =
        get_sync_fd_features(p->vk);
    p->acquire_sync_fd =
        p->ImportSemaphoreFd &&
        (sync_fd_features & VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT);
    p->release_sync_fd =
        p->GetSemaphoreFd &&
        (sync_fd_features & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT);
    mp_verbose(p->log, "Vulkan AImageReader sync-fd: acquire %s, release %s\n",
               p->acquire_sync_fd ? "enabled" : "disabled",
               p->release_sync_fd ? "enabled" : "disabled");

    mapper->dst_params_ready = false;
    return p;

error:
    aimagereader_vk_direct_destroy(&p);
    return NULL;
}

void aimagereader_vk_direct_destroy(struct aimagereader_vk_direct **state)
{
    struct aimagereader_vk_direct *p = *state;
    if (!p)
        return;

    if (p->gpu)
        destroy_imports(p);
    for (int n = 0; n < FRAME_COUNT; n++) {
        struct vk_frame *frame = &p->frames[n];
        destroy_acquire_semaphore(p, &frame->acquire);
        if (frame->release)
            vkDestroySemaphore(p->device, frame->release, NULL);
        talloc_free(frame->source_aliases);
    }
    while (p->num_acquire_cache) {
        struct cached_acquire *cached =
            &p->acquire_cache[--p->num_acquire_cache];
        if (cached->release_fence >= 0)
            close(cached->release_fence);
        destroy_acquire_semaphore(p, &cached->semaphore);
    }
    mp_verbose(p->log, "Vulkan AImageReader acquire semaphores: "
                       "created %" PRIu64 ", reused %" PRIu64
                       ", cached %" PRIu64 ", cache stalls %" PRIu64
                       ", destroyed %" PRIu64 "\n",
               p->acquire_sem_created, p->acquire_sem_reused,
               p->acquire_sem_cached, p->acquire_cache_stalls,
               p->acquire_sem_destroyed);
    talloc_free(p);
    *state = NULL;
}

void aimagereader_vk_direct_buffer_removed(struct aimagereader_vk_direct *p,
                                           AHardwareBuffer *buffer)
{
    struct vk_input *input = find_input(p, buffer);
    if (input)
        input->removed = true;
}

bool aimagereader_vk_direct_reuse(struct aimagereader_vk_direct *p,
                                  struct mp_image *frame)
{
    for (int n = 0; n < FRAME_COUNT; n++) {
        struct vk_frame *slot = &p->frames[n];
        if (slot->source_frame &&
            slot->source_frame->planes[3] == frame->planes[3]) {
            expose_input(p, slot->input);
            return true;
        }
        for (int i = 0; i < slot->num_source_aliases; i++) {
            if (slot->source_aliases[i]->planes[3] == frame->planes[3]) {
                expose_input(p, slot->input);
                return true;
            }
        }
    }
    return false;
}

bool aimagereader_vk_direct_retain_last(struct aimagereader_vk_direct *p,
                                        struct mp_image *frame)
{
    if (p->frame_index < 0)
        return false;

    struct vk_frame *slot = &p->frames[p->frame_index];
    if (!slot->source_image)
        return false;
    struct mp_image *frame_ref = mp_image_new_ref(frame);
    if (!frame_ref)
        return false;
    MP_TARRAY_APPEND(p, slot->source_aliases,
                     slot->num_source_aliases, frame_ref);
    expose_input(p, slot->input);
    return true;
}

static int map_image(struct aimagereader_vk_direct *p, AImage *image,
                     AHardwareBuffer *buffer, const AImageCropRect *crop,
                     int32_t data_space, struct mp_image *frame,
                     int *acquire_fence)
{
    purge_removed_inputs(p);

    struct vk_input *input = find_input(p, buffer);
    AHardwareBuffer_Desc desc;
    VkAndroidHardwareBufferFormatPropertiesANDROID format_props;
    VkAndroidHardwareBufferPropertiesANDROID buffer_props;
    if (input) {
        desc = input->desc;
        format_props = input->raw_format_props;
        buffer_props = input->buffer_props;
    } else if (!query_buffer_properties(p, buffer, &desc, &format_props,
                                        &buffer_props)) {
        return -1;
    }

    struct source_config source;
    int config_result = build_source_config(
        p, &desc, &format_props, crop, data_space, &source);
    if (config_result < 0)
        return config_result;
    if (!ensure_frame_sync(p))
        return AIMAGEREADER_VK_MAP_UNSUPPORTED;
    if (!ensure_source_config(p, &source))
        return -1;

    input = find_input(p, buffer);
    if (!input)
        input = create_input(p, buffer, &source, &format_props, &buffer_props);
    if (!input)
        return AIMAGEREADER_VK_MAP_UNSUPPORTED;

    p->frame_index = (p->frame_index + 1) % FRAME_COUNT;
    struct vk_frame *slot = &p->frames[p->frame_index];
    if (!finish_frame(p, slot) || !slot->release)
        return -1;
    return map_direct_image(p, slot, input, image, frame, acquire_fence);
}

int aimagereader_vk_direct_map(struct aimagereader_vk_direct *p, AImage *image,
                               AHardwareBuffer *buffer,
                               const AImageCropRect *crop, int32_t data_space,
                               struct mp_image *frame, int *acquire_fence)
{
    return map_image(p, image, buffer, crop, data_space, frame, acquire_fence);
}

#else

bool aimagereader_vk_direct_available(struct ra_ctx *ra_ctx, struct mp_log *log)
{
    (void)ra_ctx;
    mp_verbose(log, "Direct Vulkan AImageReader sampling requires "
                    "libplacebo API 372\n");
    return false;
}

struct aimagereader_vk_direct *aimagereader_vk_direct_create(
    struct ra_hwdec_mapper *mapper, const struct aimagereader_vk_api *api)
{
    (void)mapper;
    (void)api;
    return NULL;
}

void aimagereader_vk_direct_destroy(struct aimagereader_vk_direct **state)
{
    if (state)
        *state = NULL;
}

void aimagereader_vk_direct_buffer_removed(struct aimagereader_vk_direct *state,
                                            AHardwareBuffer *buffer)
{
    (void)state;
    (void)buffer;
}

bool aimagereader_vk_direct_reuse(struct aimagereader_vk_direct *state,
                                  struct mp_image *frame)
{
    (void)state;
    (void)frame;
    return false;
}

bool aimagereader_vk_direct_retain_last(struct aimagereader_vk_direct *state,
                                        struct mp_image *frame)
{
    (void)state;
    (void)frame;
    return false;
}

int aimagereader_vk_direct_map(struct aimagereader_vk_direct *state, AImage *image,
                               AHardwareBuffer *buffer,
                               const AImageCropRect *crop, int32_t data_space,
                               struct mp_image *frame, int *acquire_fence)
{
    (void)state;
    (void)image;
    (void)buffer;
    (void)crop;
    (void)data_space;
    (void)frame;
    (void)acquire_fence;
    return -1;
}

#endif
