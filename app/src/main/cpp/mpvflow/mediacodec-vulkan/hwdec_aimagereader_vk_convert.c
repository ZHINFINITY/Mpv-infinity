/*
 * Android AImageReader / Vulkan interop.
 *
 * Import a MediaCodec AHardwareBuffer that cannot use the direct path and
 * convert it with the driver's YCbCr sampler into a regular image that
 * libplacebo can consume. Compute is preferred, with a fragment pipeline used
 * when compute resources cannot be created.
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

#include "hwdec_aimagereader_comp.h"
#include "hwdec_aimagereader_frag.h"
#include "hwdec_aimagereader_vert.h"
#include "hwdec_aimagereader_vk_private.h"

#define OUTPUT_COUNT 3
#define INPUT_CACHE_SIZE 8
#define FALLBACK_EXTERNAL_DESCRIPTOR_COUNT 4

struct conversion_push_constants {
    int32_t crop_offset[2];
    int32_t crop_size[2];
    int32_t output_size[2];
    int32_t source_size[2];
};

enum output_precision {
    OUTPUT_PRECISION_8_BIT,
    OUTPUT_PRECISION_10_BIT,
    OUTPUT_PRECISION_FLOAT,
};

enum conversion_backend {
    CONVERSION_BACKEND_NONE,
    CONVERSION_BACKEND_COMPUTE,
    CONVERSION_BACKEND_FRAGMENT,
};

struct source_config {
    AHardwareBuffer_Desc desc;
    VkAndroidHardwareBufferFormatPropertiesANDROID format_props;
    int output_width;
    int output_height;
    enum output_precision output_precision;
    int sample_depth;
    bool raw_dovi;
};

struct vk_input {
    AHardwareBuffer *buffer;
    AHardwareBuffer_Desc desc;
    VkAndroidHardwareBufferFormatPropertiesANDROID raw_format_props;
    VkAndroidHardwareBufferPropertiesANDROID buffer_props;
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    uint64_t last_used;
    int users;
    bool removed;
};

struct vk_output {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    pl_tex pltex;
    struct ra_tex *ratex;
    VkFramebuffer framebuffer;
    VkDescriptorSet descriptor;
    VkCommandBuffer command;
    VkSemaphore available;
    VkSemaphore ready;
    VkSemaphore acquire;
    VkSemaphore release;
    VkFence fence;
    AImage *source_image;
    struct mp_image *source_frame;
    struct mp_image **source_aliases;
    int num_source_aliases;
    struct vk_input *input;
    bool pending;
    bool written;
    bool has_been_released;
};

struct aimagereader_vk_convert {
    struct mp_log *log;
    struct ra_hwdec_mapper *mapper;
    struct aimagereader_vk_api api;

    pl_gpu gpu;
    pl_vulkan vk;
    VkDevice device;
    VkQueue queue;
    uint32_t queue_family;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAHBProperties;
    PFN_vkGetPhysicalDeviceImageFormatProperties2 GetImageFormatProperties2;
    PFN_vkImportSemaphoreFdKHR ImportSemaphoreFd;
    PFN_vkGetSemaphoreFdKHR GetSemaphoreFd;
    bool acquire_sync_fd;
    bool release_sync_fd;

    VkCommandPool command_pool;
    VkFormat output_format;
    enum conversion_backend backend;
    enum conversion_backend required_backend;
    struct source_config source;
    int output_index;

    VkSamplerYcbcrConversion conversion;
    VkSampler sampler;
    VkDescriptorSetLayout descriptor_layout;
    VkDescriptorPool descriptor_pool;
    VkPipelineLayout pipeline_layout;
    VkRenderPass render_pass;
    VkPipeline pipeline;

    struct vk_input inputs[INPUT_CACHE_SIZE];
    int num_inputs;
    uint64_t input_serial;
    struct vk_output outputs[OUTPUT_COUNT];
    bool conversion_geometry_valid;
    struct conversion_push_constants conversion_geometry;
};

static bool vk_success(struct aimagereader_vk_convert *p, VkResult result,
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

static VkSemaphore create_binary_semaphore(struct aimagereader_vk_convert *p,
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

static PFN_vkGetPhysicalDeviceImageFormatProperties2
get_image_format_properties2(pl_vulkan vk)
{
    PFN_vkGetPhysicalDeviceImageFormatProperties2 get_properties =
        (PFN_vkGetPhysicalDeviceImageFormatProperties2)
        vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceImageFormatProperties2");
    if (!get_properties) {
        get_properties =
            (PFN_vkGetPhysicalDeviceImageFormatProperties2)
            vk->get_proc_addr(
                vk->instance,
                "vkGetPhysicalDeviceImageFormatProperties2KHR");
    }
    return get_properties;
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

static VkExternalSemaphoreFeatureFlags
get_sync_fd_features(pl_vulkan vk)
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

bool aimagereader_vk_convert_available(struct ra_ctx *ra_ctx, struct mp_log *log)
{
    struct mpvk_ctx *ctx = ra_vk_ctx_get(ra_ctx);
    pl_gpu gpu = ra_pl_get(ra_ctx->ra);
    pl_vulkan vk = gpu ? pl_vulkan_get(gpu) : NULL;
    if (!ctx || !vk || !vk->get_proc_addr || !vk->features ||
        !vk->queue_graphics.count ||
        !has_ycbcr_conversion(vk))
        return false;

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
                           "vkGetAndroidHardwareBufferPropertiesANDROID") &&
           get_image_format_properties2(vk);
}

static uint32_t find_memory_type(struct aimagereader_vk_convert *p,
                                 uint32_t type_bits,
                                 VkMemoryPropertyFlags preferred)
{
    VkPhysicalDeviceMemoryProperties props;
    vkGetPhysicalDeviceMemoryProperties(p->vk->phys_device, &props);

    for (uint32_t n = 0; n < props.memoryTypeCount; n++) {
        if ((type_bits & (1u << n)) &&
            (props.memoryTypes[n].propertyFlags & preferred) == preferred)
            return n;
    }
    for (uint32_t n = 0; n < props.memoryTypeCount; n++) {
        if (type_bits & (1u << n))
            return n;
    }
    return UINT32_MAX;
}

static void destroy_input(struct aimagereader_vk_convert *p,
                          struct vk_input *input)
{
    if (input->view)
        vkDestroyImageView(p->device, input->view, NULL);
    if (input->image)
        vkDestroyImage(p->device, input->image, NULL);
    if (input->memory)
        vkFreeMemory(p->device, input->memory, NULL);
    *input = (struct vk_input){0};
}

static void clear_output_frames(struct vk_output *output)
{
    mp_image_unrefp(&output->source_frame);
    for (int n = 0; n < output->num_source_aliases; n++)
        mp_image_unrefp(&output->source_aliases[n]);
    output->num_source_aliases = 0;
}

static bool finish_output(struct aimagereader_vk_convert *p,
                          struct vk_output *output, bool wait)
{
    if (!output->pending)
        return true;

    VkResult result = wait
        ? vkWaitForFences(p->device, 1, &output->fence, VK_TRUE, UINT64_MAX)
        : vkGetFenceStatus(p->device, output->fence);
    if (result == VK_NOT_READY)
        return false;
    bool device_lost = result == VK_ERROR_DEVICE_LOST;
    if (result != VK_SUCCESS && !device_lost) {
        vk_success(p, result, "waiting for AHardwareBuffer conversion");
        return false;
    }
    if (device_lost)
        mp_err(p->log, "Vulkan device lost during AHardwareBuffer conversion\n");

    if (output->source_image) {
        p->api.AImage_delete(output->source_image);
        output->source_image = NULL;
    }
    if (output->input) {
        if (output->input->users > 0)
            output->input->users--;
        if (!output->input->users && output->input->removed)
            destroy_input(p, output->input);
        output->input = NULL;
    }
    if (device_lost) {
        output->pending = false;
        clear_output_frames(output);
        return false;
    }
    result = vkResetFences(p->device, 1, &output->fence);
    if (result == VK_ERROR_DEVICE_LOST) {
        mp_err(p->log, "Vulkan device lost while resetting conversion fence\n");
        output->pending = false;
        clear_output_frames(output);
        return false;
    }
    if (!vk_success(p, result, "resetting conversion fence"))
        return false;

    output->pending = false;
    return true;
}

static void destroy_output(struct aimagereader_vk_convert *p,
                           struct vk_output *output)
{
    finish_output(p, output, true);
    clear_output_frames(output);
    talloc_free(output->source_aliases);

    if (output->ratex)
        ra_tex_free(p->mapper->ra, &output->ratex);
    if (output->framebuffer)
        vkDestroyFramebuffer(p->device, output->framebuffer, NULL);
    if (output->view)
        vkDestroyImageView(p->device, output->view, NULL);
    if (output->image)
        vkDestroyImage(p->device, output->image, NULL);
    if (output->memory)
        vkFreeMemory(p->device, output->memory, NULL);
    if (output->available)
        vkDestroySemaphore(p->device, output->available, NULL);
    if (output->ready)
        vkDestroySemaphore(p->device, output->ready, NULL);
    if (output->acquire)
        vkDestroySemaphore(p->device, output->acquire, NULL);
    if (output->release)
        vkDestroySemaphore(p->device, output->release, NULL);
    if (output->fence)
        vkDestroyFence(p->device, output->fence, NULL);
    *output = (struct vk_output){0};
}

static void destroy_conversion_resources(struct aimagereader_vk_convert *p)
{
    for (int n = 0; n < OUTPUT_COUNT; n++)
        finish_output(p, &p->outputs[n], true);

    VkCommandBuffer commands[OUTPUT_COUNT];
    uint32_t num_commands = 0;
    for (int n = 0; n < OUTPUT_COUNT; n++) {
        struct vk_output *output = &p->outputs[n];
        output->descriptor = VK_NULL_HANDLE;
        if (output->command)
            commands[num_commands++] = output->command;
        output->command = VK_NULL_HANDLE;
    }
    if (num_commands) {
        vkFreeCommandBuffers(p->device, p->command_pool, num_commands,
                             commands);
    }

    if (p->descriptor_pool)
        vkDestroyDescriptorPool(p->device, p->descriptor_pool, NULL);
    p->descriptor_pool = VK_NULL_HANDLE;

    p->mapper->tex[0] = NULL;
    for (int n = 0; n < OUTPUT_COUNT; n++)
        destroy_output(p, &p->outputs[n]);

    if (p->command_pool)
        vkDestroyCommandPool(p->device, p->command_pool, NULL);
    p->command_pool = VK_NULL_HANDLE;

    for (int n = 0; n < p->num_inputs; n++)
        destroy_input(p, &p->inputs[n]);
    p->num_inputs = 0;

    if (p->pipeline)
        vkDestroyPipeline(p->device, p->pipeline, NULL);
    if (p->render_pass)
        vkDestroyRenderPass(p->device, p->render_pass, NULL);
    if (p->pipeline_layout)
        vkDestroyPipelineLayout(p->device, p->pipeline_layout, NULL);
    if (p->descriptor_layout)
        vkDestroyDescriptorSetLayout(p->device, p->descriptor_layout, NULL);
    if (p->sampler)
        vkDestroySampler(p->device, p->sampler, NULL);
    if (p->conversion)
        vkDestroySamplerYcbcrConversion(p->device, p->conversion, NULL);

    p->pipeline = VK_NULL_HANDLE;
    p->render_pass = VK_NULL_HANDLE;
    p->pipeline_layout = VK_NULL_HANDLE;
    p->descriptor_layout = VK_NULL_HANDLE;
    p->sampler = VK_NULL_HANDLE;
    p->conversion = VK_NULL_HANDLE;
    p->output_format = VK_FORMAT_UNDEFINED;
    p->backend = CONVERSION_BACKEND_NONE;
    p->queue = VK_NULL_HANDLE;
    p->queue_family = 0;
    p->source = (struct source_config){0};
    p->output_index = -1;
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

static enum output_precision source_output_precision(
    struct aimagereader_vk_convert *p, const AHardwareBuffer_Desc *desc,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props,
    int32_t data_space)
{
    const struct pl_bit_encoding *bits =
        &p->mapper->src_params.repr.bits;
    // sample_depth includes storage padding (for example, P010 uses 16 bits).
    int depth = bits->color_depth;
    if (!depth)
        depth = bits->sample_depth;
    depth = MPMAX(depth, ycbcr_format_depth(props->format));

    // The mapper exposes opaque RGB0, so 10-bit alpha storage does not require
    // a 16-bit float conversion output.
    if (desc->format == AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT ||
        props->format == VK_FORMAT_R16G16B16A16_SFLOAT ||
        depth > 10)
        return OUTPUT_PRECISION_FLOAT;

    int32_t transfer = data_space & ADATASPACE_TRANSFER_MASK;
    if (depth > 8 ||
        desc->format == AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM ||
        desc->format == AHARDWAREBUFFER_FORMAT_YCbCr_P010 ||
        desc->format == AHARDWAREBUFFER_FORMAT_YCbCr_P210 ||
        pl_color_transfer_is_hdr(p->mapper->src_params.color.transfer) ||
        transfer == ADATASPACE_TRANSFER_ST2084 ||
        transfer == ADATASPACE_TRANSFER_HLG)
        return OUTPUT_PRECISION_10_BIT;

    return OUTPUT_PRECISION_8_BIT;
}

static bool compute_backend_available(struct aimagereader_vk_convert *p)
{
    return p->vk->queue_compute.count &&
           p->vk->features->features.shaderStorageImageWriteWithoutFormat;
}

static const char *conversion_backend_name(enum conversion_backend backend)
{
    switch (backend) {
    case CONVERSION_BACKEND_COMPUTE:
        return "compute";
    case CONVERSION_BACKEND_FRAGMENT:
        return "fragment";
    default:
        return "none";
    }
}

static bool try_output_backend(struct aimagereader_vk_convert *p,
                               VkFormat candidate,
                               enum conversion_backend backend)
{
    VkFormatFeatureFlags backend_feature;
    if (backend == CONVERSION_BACKEND_COMPUTE) {
        if (!compute_backend_available(p))
            return false;
        backend_feature = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
    } else {
        if (!p->vk->queue_graphics.count)
            return false;
        backend_feature = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
    }

    VkFormatProperties props;
    vkGetPhysicalDeviceFormatProperties(p->vk->phys_device, candidate, &props);
    VkFormatFeatureFlags features = props.optimalTilingFeatures;
    VkFormatFeatureFlags required =
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | backend_feature;
    if ((features & required) != required)
        return false;

    p->output_format = candidate;
    p->backend = backend;
    return true;
}

static bool choose_output_format(struct aimagereader_vk_convert *p,
                                 enum output_precision precision,
                                 enum conversion_backend required_backend)
{
    const enum conversion_backend backends[] = {
        CONVERSION_BACKEND_COMPUTE,
        CONVERSION_BACKEND_FRAGMENT,
    };
    const VkFormat eight_bit_candidates[] = {
        VK_FORMAT_R8G8B8A8_UNORM,
        VK_FORMAT_A2B10G10R10_UNORM_PACK32,
        VK_FORMAT_R16G16B16A16_SFLOAT,
    };
    const VkFormat ten_bit_candidates[] = {
        VK_FORMAT_A2B10G10R10_UNORM_PACK32,
        VK_FORMAT_R16G16B16A16_SFLOAT,
    };
    const VkFormat float_candidates[] = {
        VK_FORMAT_R16G16B16A16_SFLOAT,
    };
    const VkFormat *candidates;
    int num_candidates;
    switch (precision) {
    case OUTPUT_PRECISION_8_BIT:
        candidates = eight_bit_candidates;
        num_candidates = MP_ARRAY_SIZE(eight_bit_candidates);
        break;
    case OUTPUT_PRECISION_10_BIT:
        candidates = ten_bit_candidates;
        num_candidates = MP_ARRAY_SIZE(ten_bit_candidates);
        break;
    case OUTPUT_PRECISION_FLOAT:
        candidates = float_candidates;
        num_candidates = MP_ARRAY_SIZE(float_candidates);
        break;
    default:
        abort();
    }

    // Prefer the narrowest suitable format before choosing a backend. This
    // avoids widening 10-bit output to RGBA16F merely to retain compute.
    for (int n = 0; n < num_candidates; n++) {
        for (int i = 0; i < (int)MP_ARRAY_SIZE(backends); i++) {
            if ((required_backend == CONVERSION_BACKEND_NONE ||
                 required_backend == backends[i]) &&
                try_output_backend(p, candidates[n], backends[i]))
                return true;
        }
    }
    return false;
}

static int collect_queue_families(struct aimagereader_vk_convert *p,
                                  uint32_t families[3])
{
    const struct pl_vulkan_queue queues[] = {
        p->vk->queue_graphics,
        p->vk->queue_compute,
        p->vk->queue_transfer,
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

static bool create_output_image(struct aimagereader_vk_convert *p,
                                struct vk_output *output,
                                const uint32_t *queue_families,
                                int num_queue_families)
{
    const VkImageUsageFlags usage =
        VK_IMAGE_USAGE_SAMPLED_BIT |
        (p->backend == CONVERSION_BACKEND_COMPUTE
            ? VK_IMAGE_USAGE_STORAGE_BIT
            : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
    VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = p->output_format,
        .extent = {p->source.output_width, p->source.output_height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = usage,
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
                                     &output->image),
                    "creating conversion output image"))
        return false;

    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(p->device, output->image, &requirements);
    uint32_t memory_type = find_memory_type(
        p, requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memory_type == UINT32_MAX) {
        mp_err(p->log, "No memory type for conversion output image\n");
        return false;
    }

    VkMemoryAllocateInfo alloc_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = memory_type,
    };
    if (!vk_success(p, vkAllocateMemory(p->device, &alloc_info, NULL,
                                        &output->memory),
                    "allocating conversion output memory") ||
        !vk_success(p, vkBindImageMemory(p->device, output->image,
                                         output->memory, 0),
                    "binding conversion output memory"))
        return false;

    VkImageViewCreateInfo view_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = output->image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = p->output_format,
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .levelCount = 1,
            .layerCount = 1,
        },
    };
    if (!vk_success(p, vkCreateImageView(p->device, &view_info, NULL,
                                         &output->view),
                    "creating conversion output view"))
        return false;

    output->pltex = pl_vulkan_wrap(p->gpu, pl_vulkan_wrap_params(
        .image = output->image,
        .width = p->source.output_width,
        .height = p->source.output_height,
        .format = p->output_format,
        .usage = usage,
    ));
    if (!output->pltex) {
        mp_err(p->log, "libplacebo cannot wrap conversion output format %d\n",
               p->output_format);
        return false;
    }

    output->ratex = talloc_ptrtype(NULL, output->ratex);
    if (!mppl_wrap_tex(p->mapper->ra, output->pltex, output->ratex)) {
        pl_tex_destroy(p->gpu, &output->pltex);
        TA_FREEP(&output->ratex);
        return false;
    }

    VkSemaphoreCreateInfo semaphore_info = {
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
    };
    VkFenceCreateInfo fence_info = {
        .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
    };
    if (!vk_success(p, vkCreateSemaphore(p->device, &semaphore_info, NULL,
                                         &output->available),
                    "creating conversion availability semaphore") ||
        !vk_success(p, vkCreateSemaphore(p->device, &semaphore_info, NULL,
                                         &output->ready),
                    "creating conversion completion semaphore") ||
        !vk_success(p, vkCreateFence(p->device, &fence_info, NULL,
                                     &output->fence),
                    "creating conversion fence"))
        return false;

    if (p->acquire_sync_fd) {
        output->acquire = create_binary_semaphore(p, false);
        if (!output->acquire) {
            mp_verbose(p->log, "Vulkan sync-fd import unavailable; "
                               "using CPU acquire-fence waits\n");
            p->acquire_sync_fd = false;
        }
    }
    if (p->release_sync_fd) {
        output->release = create_binary_semaphore(p, true);
        if (!output->release) {
            mp_verbose(p->log, "Vulkan sync-fd export unavailable; "
                               "releasing AImages after conversion fences\n");
            p->release_sync_fd = false;
        }
    }

    return true;
}

static bool select_conversion_backend(struct aimagereader_vk_convert *p,
                                      const struct source_config *source,
                                      enum conversion_backend required_backend)
{
    p->source = *source;
    p->source.format_props.pNext = NULL;
    if (!choose_output_format(p, source->output_precision,
                              required_backend)) {
        mp_err(p->log, "No sampleable storage or color-attachment format for "
                       "AHardwareBuffer conversion\n");
        return false;
    }

    const struct pl_vulkan_queue queue =
        p->backend == CONVERSION_BACKEND_COMPUTE
            ? p->vk->queue_compute
            : p->vk->queue_graphics;
    p->queue_family = queue.index;
    vkGetDeviceQueue(p->device, p->queue_family, 0, &p->queue);
    if (!p->queue) {
        mp_err(p->log, "No Vulkan queue for AHardwareBuffer %s conversion\n",
               conversion_backend_name(p->backend));
        return false;
    }

    VkCommandPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = p->queue_family,
    };
    return vk_success(p, vkCreateCommandPool(p->device, &pool_info, NULL,
                                             &p->command_pool),
                      "creating AHardwareBuffer conversion command pool");
}

static bool create_outputs(struct aimagereader_vk_convert *p)
{
    uint32_t queue_families[3];
    int num_queue_families = collect_queue_families(p, queue_families);
    for (int n = 0; n < OUTPUT_COUNT; n++) {
        if (!create_output_image(p, &p->outputs[n], queue_families,
                                 num_queue_families))
            return false;
    }

    VkCommandBufferAllocateInfo alloc_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = p->command_pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = OUTPUT_COUNT,
    };
    VkCommandBuffer commands[OUTPUT_COUNT];
    if (!vk_success(p, vkAllocateCommandBuffers(p->device, &alloc_info,
                                                commands),
                    "allocating conversion command buffers"))
        return false;
    for (int n = 0; n < OUTPUT_COUNT; n++)
        p->outputs[n].command = commands[n];

    return true;
}

static bool same_source_config(struct aimagereader_vk_convert *p,
                               const struct source_config *source)
{
    const struct source_config *current = &p->source;
    const VkAndroidHardwareBufferFormatPropertiesANDROID *a =
        &current->format_props;
    const VkAndroidHardwareBufferFormatPropertiesANDROID *b =
        &source->format_props;

    // Transfer characteristics stay in mp_image color metadata. The Vulkan
    // conversion only needs rebuilding when its effective sampler fields or
    // output precision change.
    return current->desc.width == source->desc.width &&
           current->desc.height == source->desc.height &&
           current->desc.format == source->desc.format &&
           current->output_width == source->output_width &&
           current->output_height == source->output_height &&
           current->output_precision == source->output_precision &&
           current->sample_depth == source->sample_depth &&
           current->raw_dovi == source->raw_dovi &&
           a->format == b->format &&
           a->externalFormat == b->externalFormat &&
           a->formatFeatures == b->formatFeatures &&
           a->suggestedYcbcrModel == b->suggestedYcbcrModel &&
           a->suggestedYcbcrRange == b->suggestedYcbcrRange &&
           a->suggestedXChromaOffset == b->suggestedXChromaOffset &&
           a->suggestedYChromaOffset == b->suggestedYChromaOffset &&
           memcmp(&a->samplerYcbcrConversionComponents,
                  &b->samplerYcbcrConversionComponents,
                  sizeof(a->samplerYcbcrConversionComponents)) == 0;
}

static uint32_t external_sampler_descriptor_count(
    struct aimagereader_vk_convert *p)
{
#ifdef VK_KHR_MAINTENANCE_6_EXTENSION_NAME
    bool maintenance6 = has_extension(
        p->vk, VK_KHR_MAINTENANCE_6_EXTENSION_NAME);
    maintenance6 |= p->vk->api_version >= VK_MAKE_API_VERSION(0, 1, 4, 0);
    if (maintenance6) {
        VkPhysicalDeviceMaintenance6PropertiesKHR maintenance6_props = {
            .sType =
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_6_PROPERTIES_KHR,
        };
        VkPhysicalDeviceProperties2 properties = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
            .pNext = &maintenance6_props,
        };
        vkGetPhysicalDeviceProperties2(p->vk->phys_device, &properties);
        if (maintenance6_props.maxCombinedImageSamplerDescriptorCount) {
            return maintenance6_props
                .maxCombinedImageSamplerDescriptorCount;
        }
    }
#endif

    // External formats did not expose an exact descriptor count before
    // maintenance6. Four covers the implementation-defined YCbCr planes plus
    // the optional alpha component.
    return FALLBACK_EXTERNAL_DESCRIPTOR_COUNT;
}

static uint32_t sampler_descriptor_count(
    struct aimagereader_vk_convert *p,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props,
    bool needs_conversion)
{
    if (!needs_conversion)
        return 1;

    if (props->externalFormat)
        return external_sampler_descriptor_count(p);

    VkPhysicalDeviceImageFormatInfo2 format_info = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2,
        .format = props->format,
        .type = VK_IMAGE_TYPE_2D,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
    };
    VkSamplerYcbcrConversionImageFormatProperties ycbcr_props = {
        .sType =
            VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_IMAGE_FORMAT_PROPERTIES,
    };
    VkImageFormatProperties2 image_props = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2,
        .pNext = &ycbcr_props,
    };
    if (!vk_success(p, p->GetImageFormatProperties2(
                           p->vk->phys_device, &format_info, &image_props),
                    "querying YCbCr sampler descriptor count")) {
        return 0;
    }

    return ycbcr_props.combinedImageSamplerDescriptorCount;
}

static bool create_pipeline_descriptors(
    struct aimagereader_vk_convert *p, uint32_t sampler_descriptors,
    VkFilter sample_filter, VkFilter chroma_filter,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props)
{
    VkDescriptorPoolSize pool_sizes[] = {
        {
            .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = OUTPUT_COUNT * sampler_descriptors,
        }, {
            .type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .descriptorCount = OUTPUT_COUNT,
        },
    };
    VkDescriptorPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = OUTPUT_COUNT,
        .poolSizeCount =
            p->backend == CONVERSION_BACKEND_COMPUTE ? 2 : 1,
        .pPoolSizes = pool_sizes,
    };
    if (!vk_success(p, vkCreateDescriptorPool(p->device, &pool_info, NULL,
                                               &p->descriptor_pool),
                    "creating conversion descriptor pool"))
        return false;

    VkDescriptorSetLayout layouts[OUTPUT_COUNT];
    for (int n = 0; n < OUTPUT_COUNT; n++)
        layouts[n] = p->descriptor_layout;
    VkDescriptorSet descriptors[OUTPUT_COUNT];
    VkDescriptorSetAllocateInfo descriptor_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = p->descriptor_pool,
        .descriptorSetCount = OUTPUT_COUNT,
        .pSetLayouts = layouts,
    };
    if (!vk_success(p, vkAllocateDescriptorSets(
                        p->device, &descriptor_info, descriptors),
                    "allocating conversion descriptors"))
        return false;

    for (int n = 0; n < OUTPUT_COUNT; n++) {
        struct vk_output *output = &p->outputs[n];
        output->descriptor = descriptors[n];
        if (p->backend == CONVERSION_BACKEND_COMPUTE) {
            VkDescriptorImageInfo target = {
                .imageView = output->view,
                .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
            };
            VkWriteDescriptorSet write = {
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .dstSet = output->descriptor,
                .dstBinding = 1,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                .pImageInfo = &target,
            };
            vkUpdateDescriptorSets(p->device, 1, &write, 0, NULL);
        }
    }

    mp_info(p->log, "Using Vulkan AHardwareBuffer %s conversion "
                    "(source format %d, external format 0x%" PRIx64
                    ", YCbCr model/range %d/%d, output format %d, "
                    "sampler descriptors %u, "
                    "sample/chroma filter %s/%s)\n",
            conversion_backend_name(p->backend), props->format,
            props->externalFormat, props->suggestedYcbcrModel,
            props->suggestedYcbcrRange, p->output_format,
            sampler_descriptors,
            sample_filter == VK_FILTER_LINEAR ? "linear" : "nearest",
            chroma_filter == VK_FILTER_LINEAR ? "linear" : "nearest");
    return true;
}

static bool create_pipeline(
    struct aimagereader_vk_convert *p,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *props)
{
    bool needs_conversion = props->externalFormat ||
                            ycbcr_format_depth(props->format);
    uint32_t sampler_descriptors =
        sampler_descriptor_count(p, props, needs_conversion);
    if (!sampler_descriptors)
        return false;

    VkFilter chroma_filter = VK_FILTER_NEAREST;
    VkFilter sample_filter = VK_FILTER_NEAREST;
    VkFormatFeatureFlags format_features = 0;
    // Android CTS exercises opaque external formats with nearest sampling.
    // The conversion output has the same visible dimensions, so filtering the
    // external image here is unnecessary and exposes vendor-specific paths.
    if (!props->externalFormat) {
        VkFormatProperties format_props;
        vkGetPhysicalDeviceFormatProperties(
            p->vk->phys_device, props->format, &format_props);
        format_features = format_props.optimalTilingFeatures;
        bool linear_chroma =
            format_features &
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT;
        bool linear_sample =
            format_features &
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
        bool separate_filter =
            format_features &
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_SEPARATE_RECONSTRUCTION_FILTER_BIT;
        if (!needs_conversion) {
            if (linear_sample)
                sample_filter = VK_FILTER_LINEAR;
        } else if (separate_filter) {
            if (linear_chroma)
                chroma_filter = VK_FILTER_LINEAR;
            if (linear_sample)
                sample_filter = VK_FILTER_LINEAR;
        } else if (linear_chroma && linear_sample) {
            chroma_filter = VK_FILTER_LINEAR;
            sample_filter = VK_FILTER_LINEAR;
        }
    }
    VkExternalFormatANDROID external_format = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
        .externalFormat = props->externalFormat,
    };
    if (needs_conversion) {
        VkSamplerYcbcrConversionCreateInfo conversion_info = {
            .sType =
                VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO,
            .pNext = props->externalFormat ? &external_format : NULL,
            .format = props->externalFormat
                ? VK_FORMAT_UNDEFINED
                : props->format,
            .ycbcrModel = props->suggestedYcbcrModel,
            .ycbcrRange = props->suggestedYcbcrRange,
            .components = props->samplerYcbcrConversionComponents,
            .xChromaOffset = props->suggestedXChromaOffset,
            .yChromaOffset = props->suggestedYChromaOffset,
            .chromaFilter = chroma_filter,
        };
        if (!vk_success(p, vkCreateSamplerYcbcrConversion(
                            p->device, &conversion_info, NULL, &p->conversion),
                        "creating Android YCbCr conversion"))
            return false;
    }

    VkSamplerYcbcrConversionInfo conversion_info = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
        .conversion = p->conversion,
    };
    VkSamplerCreateInfo sampler_info = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .pNext = p->conversion ? &conversion_info : NULL,
        .magFilter = sample_filter,
        .minFilter = sample_filter,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .maxLod = 0.0f,
    };
    if (!vk_success(p, vkCreateSampler(p->device, &sampler_info, NULL,
                                       &p->sampler),
                    "creating Android hardware-buffer sampler"))
        return false;

    VkShaderStageFlags shader_stage =
        p->backend == CONVERSION_BACKEND_COMPUTE
            ? VK_SHADER_STAGE_COMPUTE_BIT
            : VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutBinding bindings[] = {
        {
            .binding = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .stageFlags = shader_stage,
            .pImmutableSamplers = &p->sampler,
        }, {
            .binding = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        },
    };
    VkDescriptorSetLayoutCreateInfo descriptor_layout_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = p->backend == CONVERSION_BACKEND_COMPUTE ? 2 : 1,
        .pBindings = bindings,
    };
    if (!vk_success(p, vkCreateDescriptorSetLayout(
                        p->device, &descriptor_layout_info, NULL,
                        &p->descriptor_layout),
                    "creating conversion descriptor layout"))
        return false;

    VkPushConstantRange push_range = {
        .stageFlags = shader_stage,
        .size = sizeof(struct conversion_push_constants),
    };
    VkPipelineLayoutCreateInfo pipeline_layout_info = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &p->descriptor_layout,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &push_range,
    };
    if (!vk_success(p, vkCreatePipelineLayout(
                        p->device, &pipeline_layout_info, NULL,
                        &p->pipeline_layout),
                    "creating conversion pipeline layout"))
        return false;

    if (p->backend == CONVERSION_BACKEND_COMPUTE) {
        VkShaderModuleCreateInfo shader_info = {
            .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
            .codeSize = sizeof(aimagereader_comp_spv),
            .pCode = aimagereader_comp_spv,
        };
        VkShaderModule shader = VK_NULL_HANDLE;
        if (!vk_success(p, vkCreateShaderModule(p->device, &shader_info, NULL,
                                                &shader),
                        "creating conversion compute shader"))
            return false;

        VkComputePipelineCreateInfo pipeline_info = {
            .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
            .stage = {
                .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                .stage = VK_SHADER_STAGE_COMPUTE_BIT,
                .module = shader,
                .pName = "main",
            },
            .layout = p->pipeline_layout,
        };
        VkResult result = vkCreateComputePipelines(
            p->device, VK_NULL_HANDLE, 1, &pipeline_info, NULL, &p->pipeline);
        vkDestroyShaderModule(p->device, shader, NULL);
        if (!vk_success(p, result,
                        "creating AHardwareBuffer compute pipeline"))
            return false;

        return create_pipeline_descriptors(
            p, sampler_descriptors, sample_filter, chroma_filter, props);
    }

    VkAttachmentDescription attachment = {
        .format = p->output_format,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
        .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
        .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
        .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
        .initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        .finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
    };
    VkAttachmentReference color_attachment = {
        .attachment = 0,
        .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
    };
    VkSubpassDescription subpass = {
        .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
        .colorAttachmentCount = 1,
        .pColorAttachments = &color_attachment,
    };
    VkRenderPassCreateInfo render_pass_info = {
        .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
        .attachmentCount = 1,
        .pAttachments = &attachment,
        .subpassCount = 1,
        .pSubpasses = &subpass,
    };
    if (!vk_success(p, vkCreateRenderPass(p->device, &render_pass_info, NULL,
                                          &p->render_pass),
                    "creating conversion render pass"))
        return false;

    VkShaderModuleCreateInfo vertex_info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = sizeof(aimagereader_vert_spv),
        .pCode = aimagereader_vert_spv,
    };
    VkShaderModuleCreateInfo fragment_info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = sizeof(aimagereader_frag_spv),
        .pCode = aimagereader_frag_spv,
    };
    VkShaderModule vertex = VK_NULL_HANDLE;
    VkShaderModule fragment = VK_NULL_HANDLE;
    if (!vk_success(p, vkCreateShaderModule(p->device, &vertex_info, NULL,
                                            &vertex),
                    "creating conversion vertex shader") ||
        !vk_success(p, vkCreateShaderModule(p->device, &fragment_info, NULL,
                                            &fragment),
                    "creating conversion fragment shader")) {
        if (vertex)
            vkDestroyShaderModule(p->device, vertex, NULL);
        return false;
    }

    VkPipelineShaderStageCreateInfo shader_stages[] = {
        {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_VERTEX_BIT,
            .module = vertex,
            .pName = "main",
        }, {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
            .module = fragment,
            .pName = "main",
        },
    };
    VkPipelineVertexInputStateCreateInfo vertex_input = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
    };
    VkPipelineInputAssemblyStateCreateInfo input_assembly = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
    };
    VkViewport viewport = {
        .width = p->source.output_width,
        .height = p->source.output_height,
        .minDepth = 0.0f,
        .maxDepth = 1.0f,
    };
    VkRect2D scissor = {
        .extent = {
            p->source.output_width,
            p->source.output_height,
        },
    };
    VkPipelineViewportStateCreateInfo viewport_state = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
        .viewportCount = 1,
        .pViewports = &viewport,
        .scissorCount = 1,
        .pScissors = &scissor,
    };
    VkPipelineRasterizationStateCreateInfo rasterization = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .polygonMode = VK_POLYGON_MODE_FILL,
        .cullMode = VK_CULL_MODE_NONE,
        .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE,
        .lineWidth = 1.0f,
    };
    VkPipelineMultisampleStateCreateInfo multisample = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT,
    };
    VkPipelineColorBlendAttachmentState blend_attachment = {
        .colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT,
    };
    VkPipelineColorBlendStateCreateInfo color_blend = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
        .attachmentCount = 1,
        .pAttachments = &blend_attachment,
    };
    VkGraphicsPipelineCreateInfo pipeline_info = {
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .stageCount = MP_ARRAY_SIZE(shader_stages),
        .pStages = shader_stages,
        .pVertexInputState = &vertex_input,
        .pInputAssemblyState = &input_assembly,
        .pViewportState = &viewport_state,
        .pRasterizationState = &rasterization,
        .pMultisampleState = &multisample,
        .pColorBlendState = &color_blend,
        .layout = p->pipeline_layout,
        .renderPass = p->render_pass,
    };
    VkResult result = vkCreateGraphicsPipelines(
        p->device, VK_NULL_HANDLE, 1, &pipeline_info, NULL, &p->pipeline);
    vkDestroyShaderModule(p->device, fragment, NULL);
    vkDestroyShaderModule(p->device, vertex, NULL);
    if (!vk_success(p, result, "creating AHardwareBuffer conversion pipeline"))
        return false;

    for (int n = 0; n < OUTPUT_COUNT; n++) {
        struct vk_output *output = &p->outputs[n];
        VkFramebufferCreateInfo framebuffer_info = {
            .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
            .renderPass = p->render_pass,
            .attachmentCount = 1,
            .pAttachments = &output->view,
            .width = p->source.output_width,
            .height = p->source.output_height,
            .layers = 1,
        };
        if (!vk_success(p, vkCreateFramebuffer(
                            p->device, &framebuffer_info, NULL,
                            &output->framebuffer),
                        "creating conversion framebuffer"))
            return false;
    }

    return create_pipeline_descriptors(
        p, sampler_descriptors, sample_filter, chroma_filter, props);
}

static bool query_buffer_properties(
    struct aimagereader_vk_convert *p, AHardwareBuffer *buffer,
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
        mp_err(p->log, "Android hardware buffer has no Vulkan format\n");
        return false;
    }
    return true;
}

static bool build_source_config(
    struct aimagereader_vk_convert *p, const AHardwareBuffer_Desc *desc,
    const VkAndroidHardwareBufferFormatPropertiesANDROID *format_props,
    const AImageCropRect *crop, int32_t data_space,
    struct source_config *source)
{
    *source = (struct source_config) {
        .desc = *desc,
        .format_props = *format_props,
        .output_width = p->mapper->src_params.w,
        .output_height = p->mapper->src_params.h,
    };
    source->format_props.pNext = NULL;

    if (!desc->width || !desc->height ||
        desc->width > INT32_MAX || desc->height > INT32_MAX ||
        desc->layers != 1 ||
        !(desc->usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE) ||
        crop->left < 0 || crop->top < 0 ||
        crop->right <= crop->left || crop->bottom <= crop->top ||
        (uint32_t)crop->right > desc->width ||
        (uint32_t)crop->bottom > desc->height ||
        source->output_width <= 0 || source->output_height <= 0) {
        mp_err(p->log, "Unsupported Android hardware-buffer geometry "
                       "(buffer %ux%u, crop %d,%d-%d,%d, output %dx%d, "
                       "layers %u, usage 0x%" PRIx64 ")\n",
               desc->width, desc->height, crop->left, crop->top,
               crop->right, crop->bottom, source->output_width,
               source->output_height, desc->layers, desc->usage);
        return false;
    }

    source->raw_dovi =
        p->mapper->src_params.repr.sys == PL_COLOR_SYSTEM_DOLBYVISION;
    if (source->raw_dovi) {
        source->format_props.suggestedYcbcrModel =
            VK_SAMPLER_YCBCR_MODEL_CONVERSION_RGB_IDENTITY;
        source->format_props.suggestedYcbcrRange =
            VK_SAMPLER_YCBCR_RANGE_ITU_FULL;
    }
    source->output_precision =
        source_output_precision(p, desc, &source->format_props, data_space);
    source->sample_depth = source->output_precision == OUTPUT_PRECISION_8_BIT
        ? 8 : source->output_precision == OUTPUT_PRECISION_10_BIT ? 10 : 16;
    return true;
}

static void configure_dst_params(struct aimagereader_vk_convert *p,
                                 const struct source_config *source)
{
    struct mp_image_params params = p->mapper->src_params;
    params.imgfmt = IMGFMT_RGB0;
    params.hw_subfmt = 0;
    params.w = source->output_width;
    params.h = source->output_height;
    params.crop = (struct mp_rect) {0, 0, params.w, params.h};
    params.repr.bits = (struct pl_bit_encoding) {
        .sample_depth = source->sample_depth,
        .color_depth = source->sample_depth,
    };
    p->mapper->dst_params = params;
    p->mapper->dst_params_preserve_repr = source->raw_dovi;
    p->mapper->dst_params_map_coordinates = false;
    p->mapper->dst_num_components = source->raw_dovi ? 3 : 0;
    if (source->raw_dovi) {
        const int mapping[4] = {
            PL_CHANNEL_CR,
            PL_CHANNEL_Y,
            PL_CHANNEL_CB,
            PL_CHANNEL_NONE,
        };
        memcpy(p->mapper->dst_component_mapping, mapping, sizeof(mapping));
    }
}

static bool ensure_conversion_resources(struct aimagereader_vk_convert *p,
                                         const struct source_config *source)
{
    if (p->pipeline && same_source_config(p, source))
        return true;

    if (p->pipeline) {
        mp_verbose(p->log,
                   "Reconfiguring Android hardware-buffer conversion\n");
        pl_gpu_finish(p->gpu);
        destroy_conversion_resources(p);
    }

    p->conversion_geometry_valid = false;
    if (select_conversion_backend(p, source, p->required_backend) &&
        create_outputs(p) &&
        create_pipeline(p, &source->format_props)) {
        configure_dst_params(p, source);
        return true;
    }

    enum conversion_backend failed_backend = p->backend;
    if (p->required_backend != CONVERSION_BACKEND_NONE) {
        mp_err(p->log, "Forced Vulkan AHardwareBuffer %s conversion is "
                       "unavailable\n",
               conversion_backend_name(p->required_backend));
        destroy_conversion_resources(p);
        return false;
    }
    enum conversion_backend fallback_backend = CONVERSION_BACKEND_NONE;
    if (failed_backend == CONVERSION_BACKEND_FRAGMENT)
        fallback_backend = CONVERSION_BACKEND_COMPUTE;
    else if (failed_backend == CONVERSION_BACKEND_COMPUTE)
        fallback_backend = CONVERSION_BACKEND_FRAGMENT;
    destroy_conversion_resources(p);
    if (fallback_backend != CONVERSION_BACKEND_NONE) {
        mp_verbose(p->log, "%s conversion unavailable; retrying the %s "
                           "backend\n",
                   conversion_backend_name(failed_backend),
                   conversion_backend_name(fallback_backend));
        if (select_conversion_backend(p, source, fallback_backend) &&
            create_outputs(p) &&
            create_pipeline(p, &source->format_props)) {
            configure_dst_params(p, source);
            return true;
        }
        destroy_conversion_resources(p);
    }

    return false;
}

static struct vk_input *find_input(struct aimagereader_vk_convert *p,
                                   AHardwareBuffer *buffer)
{
    for (int n = 0; n < p->num_inputs; n++) {
        if (p->inputs[n].buffer == buffer)
            return &p->inputs[n];
    }
    return NULL;
}

static void purge_removed_inputs(struct aimagereader_vk_convert *p)
{
    for (int n = 0; n < p->num_inputs; n++) {
        struct vk_input *input = &p->inputs[n];
        if (input->removed && !input->users)
            destroy_input(p, input);
    }
}

static struct vk_input *select_input_slot(struct aimagereader_vk_convert *p)
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

static struct vk_input *create_input(struct aimagereader_vk_convert *p,
                                     AHardwareBuffer *buffer,
                                     const struct source_config *source,
                                     const VkAndroidHardwareBufferFormatPropertiesANDROID
                                         *raw_format_props,
                                     const VkAndroidHardwareBufferPropertiesANDROID
                                         *buffer_props)
{
    const AHardwareBuffer_Desc *desc = &source->desc;
    const VkAndroidHardwareBufferFormatPropertiesANDROID *format_props =
        &source->format_props;
    struct vk_input *input = select_input_slot(p);
    if (!input)
        return NULL;

    VkExternalFormatANDROID external_format = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
        .externalFormat = format_props->externalFormat,
    };
    VkExternalMemoryImageCreateInfo external_memory = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .pNext = format_props->externalFormat ? &external_format : NULL,
        .handleTypes =
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
    };
    VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &external_memory,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format_props->externalFormat
            ? VK_FORMAT_UNDEFINED
            : format_props->format,
        .extent = {desc->width, desc->height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (!vk_success(p, vkCreateImage(p->device, &image_info, NULL,
                                     &input->image),
                    "creating imported Android hardware-buffer image"))
        goto error;

    uint32_t memory_type =
        find_memory_type(p, buffer_props->memoryTypeBits, 0);
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

    VkSamplerYcbcrConversionInfo conversion_info = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
        .conversion = p->conversion,
    };
    VkImageViewCreateInfo view_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .pNext = p->conversion ? &conversion_info : NULL,
        .image = input->image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format_props->externalFormat
            ? VK_FORMAT_UNDEFINED
            : format_props->format,
        .components = {
            VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,
        },
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .levelCount = 1,
            .layerCount = 1,
        },
    };
    if (!vk_success(p, vkCreateImageView(p->device, &view_info, NULL,
                                         &input->view),
                    "creating Android hardware-buffer image view"))
        goto error;

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

static void release_reclaimed_output(struct aimagereader_vk_convert *p,
                                      struct vk_output *output,
                                      bool reclaimed)
{
    if (!reclaimed)
        return;

    pl_vulkan_release_ex(p->gpu, pl_vulkan_release_params(
        .tex = output->pltex,
        .layout = VK_IMAGE_LAYOUT_GENERAL,
        .qf = VK_QUEUE_FAMILY_IGNORED,
        .semaphore = (pl_vulkan_sem){.sem = output->available},
    ));
}

static void log_conversion_geometry(
    struct aimagereader_vk_convert *p,
    const struct conversion_push_constants *push)
{
    if (p->conversion_geometry_valid &&
        p->conversion_geometry.crop_offset[0] == push->crop_offset[0] &&
        p->conversion_geometry.crop_offset[1] == push->crop_offset[1] &&
        p->conversion_geometry.crop_size[0] == push->crop_size[0] &&
        p->conversion_geometry.crop_size[1] == push->crop_size[1] &&
        p->conversion_geometry.output_size[0] == push->output_size[0] &&
        p->conversion_geometry.output_size[1] == push->output_size[1] &&
        p->conversion_geometry.source_size[0] == push->source_size[0] &&
        p->conversion_geometry.source_size[1] == push->source_size[1])
        return;

    mp_info(p->log, "Vulkan conversion geometry: imported image %ux%u "
                    "(buffer stride %u, format %u), crop %d,%d %dx%d, "
                    "%s output %dx%d, source format %d, "
                    "external format 0x%" PRIx64 ", output format %d\n",
            p->source.desc.width, p->source.desc.height,
            p->source.desc.stride, p->source.desc.format,
            push->crop_offset[0], push->crop_offset[1],
            push->crop_size[0], push->crop_size[1],
            conversion_backend_name(p->backend),
            push->output_size[0], push->output_size[1],
            p->source.format_props.format,
            p->source.format_props.externalFormat, p->output_format);
    p->conversion_geometry_valid = true;
    p->conversion_geometry = *push;
}

static bool record_conversion(struct aimagereader_vk_convert *p,
                               struct vk_output *output,
                               struct vk_input *input,
                               const struct conversion_push_constants *push)
{
    VkCommandBufferBeginInfo begin_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    if (!vk_success(p, vkResetCommandBuffer(output->command, 0),
                    "resetting conversion command buffer") ||
        !vk_success(p, vkBeginCommandBuffer(output->command, &begin_info),
                    "beginning conversion command buffer"))
        return false;

    bool use_compute = p->backend == CONVERSION_BACKEND_COMPUTE;
    VkPipelineStageFlags conversion_stage = use_compute
        ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
        : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT |
          VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkAccessFlags output_access = use_compute
        ? VK_ACCESS_SHADER_WRITE_BIT
        : VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkImageLayout output_layout = use_compute
        ? VK_IMAGE_LAYOUT_GENERAL
        : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkImageMemoryBarrier acquire[] = {
        {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
            // Images returning from Android foreign access are in GENERAL.
            .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
            .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
            .dstQueueFamilyIndex = p->queue_family,
            .image = input->image,
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .levelCount = 1,
                .layerCount = 1,
            },
        }, {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = 0,
            .dstAccessMask = output_access,
            .oldLayout = output->written
                ? VK_IMAGE_LAYOUT_GENERAL
                : VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = output_layout,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = output->image,
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .levelCount = 1,
                .layerCount = 1,
            },
        },
    };
    vkCmdPipelineBarrier(output->command,
                         VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         conversion_stage, 0,
                         0, NULL, 0, NULL, MP_ARRAY_SIZE(acquire), acquire);

    log_conversion_geometry(p, push);
    if (use_compute) {
        vkCmdBindPipeline(output->command, VK_PIPELINE_BIND_POINT_COMPUTE,
                          p->pipeline);
        vkCmdBindDescriptorSets(output->command,
                                VK_PIPELINE_BIND_POINT_COMPUTE,
                                p->pipeline_layout, 0, 1,
                                &output->descriptor, 0, NULL);
        vkCmdPushConstants(output->command, p->pipeline_layout,
                           VK_SHADER_STAGE_COMPUTE_BIT, 0,
                           sizeof(*push), push);
        vkCmdDispatch(output->command,
                      MP_ALIGN_UP(push->output_size[0], 16) / 16,
                      MP_ALIGN_UP(push->output_size[1], 8) / 8, 1);
    } else {
        VkRenderPassBeginInfo render_pass_info = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .renderPass = p->render_pass,
            .framebuffer = output->framebuffer,
            .renderArea = {
                .extent = {
                    p->source.output_width,
                    p->source.output_height,
                },
            },
        };
        vkCmdBeginRenderPass(output->command, &render_pass_info,
                             VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(output->command, VK_PIPELINE_BIND_POINT_GRAPHICS,
                          p->pipeline);
        vkCmdBindDescriptorSets(output->command,
                                VK_PIPELINE_BIND_POINT_GRAPHICS,
                                p->pipeline_layout, 0, 1,
                                &output->descriptor, 0, NULL);
        vkCmdPushConstants(output->command, p->pipeline_layout,
                           VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                           sizeof(*push), push);
        vkCmdDraw(output->command, 3, 1, 0, 0);
        vkCmdEndRenderPass(output->command);
    }

    VkImageMemoryBarrier release[] = {
        {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_SHADER_READ_BIT,
            .dstAccessMask = 0,
            .oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = p->queue_family,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
            .image = input->image,
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .levelCount = 1,
                .layerCount = 1,
            },
        }, {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = output_access,
            .dstAccessMask = 0,
            .oldLayout = output_layout,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = output->image,
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .levelCount = 1,
                .layerCount = 1,
            },
        },
    };
    vkCmdPipelineBarrier(output->command,
                         conversion_stage,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
                         0, NULL, 0, NULL, MP_ARRAY_SIZE(release), release);

    return vk_success(p, vkEndCommandBuffer(output->command),
                      "ending conversion command buffer");
}

static bool wait_acquire_fence(struct aimagereader_vk_convert *p, int fence_fd)
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

static int import_acquire_fence(struct aimagereader_vk_convert *p,
                                struct vk_output *output, int fence_fd)
{
    if (fence_fd < 0)
        return 0;
    if (!p->acquire_sync_fd || !output->acquire)
        return wait_acquire_fence(p, fence_fd) ? 0 : -1;

    VkImportSemaphoreFdInfoKHR import_info = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR,
        .semaphore = output->acquire,
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

static void reset_acquire_semaphore(struct aimagereader_vk_convert *p,
                                    struct vk_output *output)
{
    if (!output->acquire)
        return;
    vkDestroySemaphore(p->device, output->acquire, NULL);
    output->acquire = create_binary_semaphore(p, false);
    if (output->acquire)
        return;

    p->acquire_sync_fd = false;
    mp_verbose(p->log, "Vulkan sync-fd import disabled after a failed "
                       "queue submission\n");
}

static bool submit_conversion(struct aimagereader_vk_convert *p,
                              struct vk_output *output,
                              bool wait_for_available, int *acquire_fence,
                              int *release_fence, bool *release_exported)
{
    *release_fence = -1;
    *release_exported = false;
    int acquire_imported = 0;
    if (*acquire_fence >= 0) {
        acquire_imported =
            import_acquire_fence(p, output, *acquire_fence);
        *acquire_fence = -1;
        if (acquire_imported < 0)
            return false;
    }

    VkSemaphore wait_semaphores[2];
    VkPipelineStageFlags wait_stages[2];
    uint32_t num_waits = 0;
    if (acquire_imported) {
        wait_semaphores[num_waits] = output->acquire;
        wait_stages[num_waits++] =
            p->backend == CONVERSION_BACKEND_COMPUTE
                ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    }
    if (wait_for_available) {
        wait_semaphores[num_waits] = output->available;
        wait_stages[num_waits++] =
            p->backend == CONVERSION_BACKEND_COMPUTE
                ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                : VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    }

    VkSemaphore signal_semaphores[2] = {output->ready};
    uint32_t num_signals = 1;
    if (p->release_sync_fd && output->release)
        signal_semaphores[num_signals++] = output->release;
    VkSubmitInfo submit_info = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = num_waits,
        .pWaitSemaphores = num_waits ? wait_semaphores : NULL,
        .pWaitDstStageMask = num_waits ? wait_stages : NULL,
        .commandBufferCount = 1,
        .pCommandBuffers = &output->command,
        .signalSemaphoreCount = num_signals,
        .pSignalSemaphores = signal_semaphores,
    };

    p->vk->lock_queue(p->vk, p->queue_family, 0);
    VkResult result = vkQueueSubmit(p->queue, 1, &submit_info, output->fence);
    p->vk->unlock_queue(p->vk, p->queue_family, 0);
    if (!vk_success(p, result, "submitting AHardwareBuffer conversion")) {
        if (acquire_imported)
            reset_acquire_semaphore(p, output);
        return false;
    }

    if (num_signals == 2) {
        VkSemaphoreGetFdInfoKHR fd_info = {
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,
            .semaphore = output->release,
            .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
        };
        result = p->GetSemaphoreFd(p->device, &fd_info, release_fence);
        if (result == VK_SUCCESS) {
            *release_exported = true;
        } else {
            mp_verbose(p->log, "Exporting AImage release fence failed: %d; "
                               "releasing after the conversion fence\n",
                       result);
            p->release_sync_fd = false;
        }
    }
    return true;
}

struct aimagereader_vk_convert *aimagereader_vk_convert_create(
    struct ra_hwdec_mapper *mapper, const struct aimagereader_vk_api *api,
    enum android_vulkan_aimagereader_backend backend)
{
    struct aimagereader_vk_convert *p =
        talloc_zero(NULL, struct aimagereader_vk_convert);
    p->log = mapper->log;
    p->mapper = mapper;
    p->api = *api;

    struct mpvk_ctx *ctx = ra_vk_ctx_get(mapper->owner->ra_ctx);
    p->gpu = ra_pl_get(mapper->ra);
    p->vk = p->gpu ? pl_vulkan_get(p->gpu) : NULL;
    if (!ctx || !p->vk || !p->vk->get_proc_addr || !p->vk->features ||
        !p->vk->queue_graphics.count ||
        !has_ycbcr_conversion(p->vk) ||
        !has_extension(
            p->vk,
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) ||
        !has_extension(p->vk, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME))
        goto error;

    p->device = p->vk->device;
    p->output_index = -1;
    mapper->dst_params_ready = false;
    switch (backend) {
    case ANDROID_VULKAN_AIMAGEREADER_BACKEND_COMPUTE:
        p->required_backend = CONVERSION_BACKEND_COMPUTE;
        break;
    case ANDROID_VULKAN_AIMAGEREADER_BACKEND_FRAGMENT:
        p->required_backend = CONVERSION_BACKEND_FRAGMENT;
        break;
    default:
        p->required_backend = CONVERSION_BACKEND_NONE;
        break;
    }
    mp_info(p->log, "Vulkan AHardwareBuffer conversion backend: %s\n",
            p->required_backend == CONVERSION_BACKEND_NONE
                ? "auto (narrowest format, compute tie-break)"
                : conversion_backend_name(p->required_backend));

    p->GetImageFormatProperties2 = get_image_format_properties2(p->vk);

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
    if (!p->GetAHBProperties || !p->GetImageFormatProperties2)
        goto error;

    return p;

error:
    aimagereader_vk_convert_destroy(&p);
    return NULL;
}

void aimagereader_vk_convert_destroy(struct aimagereader_vk_convert **state)
{
    struct aimagereader_vk_convert *p = *state;
    if (!p)
        return;

    if (p->gpu)
        pl_gpu_finish(p->gpu);
    destroy_conversion_resources(p);

    talloc_free(p);
    *state = NULL;
}

void aimagereader_vk_convert_buffer_removed(struct aimagereader_vk_convert *p,
                                            AHardwareBuffer *buffer)
{
    struct vk_input *input = find_input(p, buffer);
    if (input)
        input->removed = true;
}

bool aimagereader_vk_convert_reuse(struct aimagereader_vk_convert *p,
                                   struct mp_image *frame)
{
    for (int n = 0; n < OUTPUT_COUNT; n++) {
        struct vk_output *output = &p->outputs[n];
        if (output->source_frame &&
            output->source_frame->planes[3] == frame->planes[3]) {
            p->mapper->tex[0] = output->ratex;
            return true;
        }
        for (int i = 0; i < output->num_source_aliases; i++) {
            if (output->source_aliases[i]->planes[3] == frame->planes[3]) {
                p->mapper->tex[0] = output->ratex;
                return true;
            }
        }
    }
    return false;
}

bool aimagereader_vk_convert_retain_last(struct aimagereader_vk_convert *p,
                                         struct mp_image *frame)
{
    if (p->output_index < 0)
        return false;

    struct vk_output *output = &p->outputs[p->output_index];
    if (!output->ratex)
        return false;

    struct mp_image *frame_ref = mp_image_new_ref(frame);
    if (!frame_ref)
        return false;
    MP_TARRAY_APPEND(p, output->source_aliases,
                     output->num_source_aliases, frame_ref);
    p->mapper->tex[0] = output->ratex;
    return true;
}

static int map_image(struct aimagereader_vk_convert *p, AImage *image,
                     AHardwareBuffer *buffer, const AImageCropRect *crop,
                     int32_t data_space, struct mp_image *frame,
                     int *acquire_fence)
{
    purge_removed_inputs(p);

    if (!p->release_sync_fd) {
        for (int n = 0; n < OUTPUT_COUNT; n++)
            finish_output(p, &p->outputs[n], false);
    }

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
    if (!build_source_config(p, &desc, &format_props, crop, data_space,
                             &source) ||
        !ensure_conversion_resources(p, &source))
        return -1;

    input = find_input(p, buffer);
    if (!input)
        input = create_input(p, buffer, &source, &format_props, &buffer_props);
    if (!input)
        return -1;

    p->output_index = (p->output_index + 1) % OUTPUT_COUNT;
    struct vk_output *output = &p->outputs[p->output_index];
    if (!finish_output(p, output, true))
        return -1;

    bool needs_reclaim = output->has_been_released;
    if (needs_reclaim) {
        if (!pl_vulkan_hold_ex(p->gpu, pl_vulkan_hold_params(
                .tex = output->pltex,
                .layout = VK_IMAGE_LAYOUT_GENERAL,
                .qf = VK_QUEUE_FAMILY_IGNORED,
                .semaphore = (pl_vulkan_sem){.sem = output->available},
            ))) {
            mp_err(p->log, "Failed reclaiming conversion output texture\n");
            return -1;
        }
    }
    clear_output_frames(output);

    VkDescriptorImageInfo source_image = {
        .imageView = input->view,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    };
    VkWriteDescriptorSet write = {
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .dstSet = output->descriptor,
        .dstBinding = 0,
        .descriptorCount = 1,
        .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
        .pImageInfo = &source_image,
    };
    vkUpdateDescriptorSets(p->device, 1, &write, 0, NULL);

    struct mp_image *frame_ref = mp_image_new_ref(frame);
    if (!frame_ref) {
        release_reclaimed_output(p, output, needs_reclaim);
        return -1;
    }
    const struct conversion_push_constants push = {
        .crop_offset = {crop->left, crop->top},
        .crop_size = {
            crop->right - crop->left,
            crop->bottom - crop->top,
        },
        .output_size = {source.output_width, source.output_height},
        .source_size = {
            source.desc.width,
            source.desc.height,
        },
    };
    int release_fence = -1;
    bool release_exported = false;
    if (!record_conversion(p, output, input, &push)) {
        mp_image_unrefp(&frame_ref);
        release_reclaimed_output(p, output, needs_reclaim);
        return -1;
    }
    bool submitted = submit_conversion(p, output, needs_reclaim,
                                       acquire_fence, &release_fence,
                                       &release_exported);
    if (!submitted) {
        mp_image_unrefp(&frame_ref);
        release_reclaimed_output(p, output, needs_reclaim);
        return -1;
    }

    if (release_exported) {
        if (release_fence >= 0)
            p->api.AImage_deleteAsync(image, release_fence);
        else
            p->api.AImage_delete(image);
    } else {
        output->source_image = image;
    }
    output->source_frame = frame_ref;
    output->input = input;
    output->pending = true;
    output->written = true;
    output->has_been_released = true;
    input->users++;
    input->last_used = ++p->input_serial;
    pl_vulkan_release_ex(p->gpu, pl_vulkan_release_params(
        .tex = output->pltex,
        .layout = VK_IMAGE_LAYOUT_GENERAL,
        .qf = VK_QUEUE_FAMILY_IGNORED,
        .semaphore = (pl_vulkan_sem){.sem = output->ready},
    ));
    p->mapper->tex[0] = output->ratex;
    return 0;
}

int aimagereader_vk_convert_map(struct aimagereader_vk_convert *p, AImage *image,
                                AHardwareBuffer *buffer,
                                const AImageCropRect *crop, int32_t data_space,
                                struct mp_image *frame, int *acquire_fence)
{
    return map_image(p, image, buffer, crop, data_space, frame, acquire_fence);
}
