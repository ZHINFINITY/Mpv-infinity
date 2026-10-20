/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 *
 * Optional VK_NV_optical_flow adapter. The caller supplies luma through the
 * wrapped R8 storage images and consumes signed fixed-point vectors through
 * wrapped sampleable images. Unsupported devices retain the ordinary Vulkan
 * block-matching path.
 */
#include "mpvflow_vulkan_optical.h"

#include <libplacebo/gpu.h>

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef VK_NV_optical_flow
#define FLOW_OPTICAL_COMMAND_SLOTS 3
#define FLOW_OPTICAL_GRID VK_OPTICAL_FLOW_GRID_SIZE_8X8_BIT_NV
#define FLOW_OPTICAL_VECTOR_FORMAT_SFIXED5 VK_FORMAT_R16G16_S10_5_NV
#define FLOW_OPTICAL_INPUT_FORMAT VK_FORMAT_R8_UNORM

typedef struct FlowOpticalImage {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    pl_tex texture;
    VkFormat format;
    VkImageUsageFlags usage;
    uint32_t width;
    uint32_t height;
} FlowOpticalImage;

typedef struct FlowOpticalCommand {
    VkCommandBuffer command;
    VkFence fence;
} FlowOpticalCommand;

struct MPVFlowVulkanOptical {
    pl_gpu gpu;
    pl_vulkan vk;
    PFN_vkGetPhysicalDeviceOpticalFlowImageFormatsNV get_image_formats;
    PFN_vkCreateOpticalFlowSessionNV create_session;
    PFN_vkDestroyOpticalFlowSessionNV destroy_session;
    PFN_vkBindOpticalFlowSessionImageNV bind_session_image;
    PFN_vkCmdOpticalFlowExecuteNV cmd_execute;
    VkFormat vector_format;
    const char *vector_format_name;
    VkQueue queue;
    uint32_t queue_family;
    bool queue_is_managed;
    bool disabled;
    char disabled_reason[160];
    uint32_t rejected_width;
    uint32_t rejected_height;
    uint64_t fallback_count;
    VkCommandPool command_pool;
    FlowOpticalCommand commands[FLOW_OPTICAL_COMMAND_SLOTS];
    uint32_t next_command;
    VkSemaphore ready_semaphore;
    VkSemaphore done_semaphore;
    uint64_t ready_value;
    uint64_t done_value;
    uint64_t dispatch_count;
    uint32_t width;
    uint32_t height;
    FlowOpticalImage input[2];
    FlowOpticalImage vectors[2];
    VkOpticalFlowSessionNV sessions[2];
};

static void optical_log(pl_gpu gpu, enum pl_log_level level,
                        const char *format, ...)
{
    if (!gpu || !gpu->log || !gpu->log->params.log_cb)
        return;
    char message[768];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    gpu->log->params.log_cb(gpu->log->params.log_priv, level, message);
}

static bool optical_extension_enabled(pl_vulkan vk)
{
    for (int i = 0; vk && i < vk->num_extensions; i++) {
        if (vk->extensions[i] &&
            strcmp(vk->extensions[i], VK_NV_OPTICAL_FLOW_EXTENSION_NAME) == 0)
            return true;
    }
    return false;
}

static bool optical_feature_enabled(pl_vulkan vk)
{
    if (!vk || !vk->features)
        return false;
    const VkBaseInStructure *feature =
        (const VkBaseInStructure *) vk->features->pNext;
    for (; feature; feature = (const VkBaseInStructure *) feature->pNext) {
        if (feature->sType ==
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV) {
            const VkPhysicalDeviceOpticalFlowFeaturesNV *optical =
                (const VkPhysicalDeviceOpticalFlowFeaturesNV *) feature;
            return optical->opticalFlow == VK_TRUE;
        }
    }
    return false;
}

static bool optical_timeline_enabled(pl_vulkan vk)
{
    if (!vk || !vk->features)
        return false;
    const VkBaseInStructure *feature =
        (const VkBaseInStructure *) vk->features->pNext;
    for (; feature; feature = (const VkBaseInStructure *) feature->pNext) {
        if (feature->sType ==
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES) {
            const VkPhysicalDeviceVulkan12Features *v12 =
                (const VkPhysicalDeviceVulkan12Features *) feature;
            if (v12->timelineSemaphore)
                return true;
        }
        if (feature->sType ==
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES) {
            const VkPhysicalDeviceTimelineSemaphoreFeatures *timeline =
                (const VkPhysicalDeviceTimelineSemaphoreFeatures *) feature;
            if (timeline->timelineSemaphore)
                return true;
        }
    }
    return false;
}

static bool format_present(PFN_vkGetPhysicalDeviceOpticalFlowImageFormatsNV get_formats,
                           VkPhysicalDevice physical_device,
                           VkOpticalFlowUsageFlagsNV usage, VkFormat format)
{
    VkOpticalFlowImageFormatInfoNV query = {
        .sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_INFO_NV,
        .usage = usage,
    };
    uint32_t count = 0;
    if (get_formats(physical_device, &query, &count, NULL) != VK_SUCCESS || !count)
        return false;
    VkOpticalFlowImageFormatPropertiesNV *formats =
        calloc(count, sizeof(*formats));
    if (!formats)
        return false;
    for (uint32_t i = 0; i < count; i++)
        formats[i].sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_PROPERTIES_NV;
    bool found = false;
    if (get_formats(physical_device, &query, &count, formats) == VK_SUCCESS) {
        for (uint32_t i = 0; i < count; i++)
            found |= formats[i].format == format;
    }
    free(formats);
    return found;
}

static PFN_vkGetPhysicalDeviceProperties2 get_physical_device_properties2(pl_vulkan vk)
{
    if (!vk || !vk->get_proc_addr)
        return NULL;
    PFN_vkGetPhysicalDeviceProperties2 get_properties =
        (PFN_vkGetPhysicalDeviceProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceProperties2");
    if (!get_properties)
        get_properties = (PFN_vkGetPhysicalDeviceProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceProperties2KHR");
    return get_properties;
}

static PFN_vkGetPhysicalDeviceImageFormatProperties2
get_physical_device_image_format_properties2(pl_vulkan vk)
{
    if (!vk || !vk->get_proc_addr)
        return NULL;
    PFN_vkGetPhysicalDeviceImageFormatProperties2 get_properties =
        (PFN_vkGetPhysicalDeviceImageFormatProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceImageFormatProperties2");
    if (!get_properties)
        get_properties = (PFN_vkGetPhysicalDeviceImageFormatProperties2)
            vk->get_proc_addr(vk->instance,
                              "vkGetPhysicalDeviceImageFormatProperties2KHR");
    return get_properties;
}

static VkResult format_usage_result(pl_vulkan vk, VkFormat format,
                                    VkImageUsageFlags usage,
                                    VkOpticalFlowUsageFlagsNV optical_usage)
{
    PFN_vkGetPhysicalDeviceImageFormatProperties2 get_properties =
        get_physical_device_image_format_properties2(vk);
    if (!get_properties)
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    VkOpticalFlowImageFormatInfoNV flow_info = {
        .sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_INFO_NV,
        .usage = optical_usage,
    };
    VkPhysicalDeviceImageFormatInfo2 image_info = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2,
        .pNext = &flow_info,
        .format = format,
        .type = VK_IMAGE_TYPE_2D,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = usage,
        .flags = 0,
    };
    VkImageFormatProperties2 image_properties = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2,
    };
    return get_properties(vk->phys_device, &image_info, &image_properties);
}

static bool format_usage_supported(pl_vulkan vk, VkFormat format,
                                   VkImageUsageFlags usage,
                                   VkOpticalFlowUsageFlagsNV optical_usage)
{
    return format_usage_result(vk, format, usage, optical_usage) == VK_SUCCESS;
}

static PFN_vkGetPhysicalDeviceFormatProperties2
get_physical_device_format_properties2(pl_vulkan vk)
{
    if (!vk || !vk->get_proc_addr)
        return NULL;
    PFN_vkGetPhysicalDeviceFormatProperties2 get_properties =
        (PFN_vkGetPhysicalDeviceFormatProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceFormatProperties2");
    if (!get_properties)
        get_properties = (PFN_vkGetPhysicalDeviceFormatProperties2)
            vk->get_proc_addr(vk->instance,
                              "vkGetPhysicalDeviceFormatProperties2KHR");
    return get_properties;
}

static bool optical_vector_format_features_supported(pl_vulkan vk,
                                                       VkFormat format,
                                                       VkFormatFeatureFlags2 *features_out)
{
    if (features_out)
        *features_out = 0;
#if (defined(VK_VERSION_1_3) || defined(VK_KHR_format_feature_flags2)) && \
    defined(VK_FORMAT_FEATURE_2_OPTICAL_FLOW_VECTOR_BIT_NV)
    PFN_vkGetPhysicalDeviceFormatProperties2 get_properties =
        get_physical_device_format_properties2(vk);
    if (!get_properties)
        return false;
    VkFormatProperties3 properties3 = {
        .sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_3,
    };
    VkFormatProperties2 properties2 = {
        .sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2,
        .pNext = &properties3,
    };
    get_properties(vk->phys_device, format, &properties2);
    if (features_out)
        *features_out = properties3.optimalTilingFeatures;
    const VkFormatFeatureFlags2 required =
        VK_FORMAT_FEATURE_2_OPTICAL_FLOW_VECTOR_BIT_NV |
        VK_FORMAT_FEATURE_2_SAMPLED_IMAGE_BIT;
    return (properties3.optimalTilingFeatures & required) == required;
#else
    (void) vk;
    (void) format;
    return false;
#endif
}

static bool choose_vector_format(MPVFlowVulkanOptical *optical)
{
    struct Candidate {
        VkFormat format;
        const char *name;
        const char *libplacebo_name;
        enum pl_fmt_type type;
        int components;
    };
    static const struct Candidate candidates[] = {
        { FLOW_OPTICAL_VECTOR_FORMAT_SFIXED5, "R16G16_S10_5_NV",
          "rg16sfix5", PL_FMT_SINT, 2 },
    };
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        const struct Candidate *candidate = &candidates[i];
        pl_fmt mapped = pl_find_named_fmt(optical->gpu,
                                          candidate->libplacebo_name);
        VkFormatFeatureFlags2 format_features = 0;
        bool listed = format_present(optical->get_image_formats,
            optical->vk->phys_device, VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV,
            candidate->format);
        bool mapped_sampleable = mapped && mapped->type == candidate->type &&
            mapped->num_components == candidate->components &&
            (mapped->caps & PL_FMT_CAP_SAMPLEABLE);
        bool vector_features = optical_vector_format_features_supported(
            optical->vk, candidate->format, &format_features);
        VkResult image_result = format_usage_result(optical->vk,
            candidate->format, VK_IMAGE_USAGE_SAMPLED_BIT,
            VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV);
        bool accepted = listed && mapped_sampleable && vector_features &&
                        image_result == VK_SUCCESS;
        optical_log(optical->gpu, accepted ? PL_LOG_INFO : PL_LOG_DEBUG,
            "MPVFLOW_DIAGNOSTIC event=optical_flow_vector_candidate "
            "name=%s format=0x%x listed=%d mapped_sampleable=%d "
            "mapped_type=%d mapped_components=%d mapped_caps=0x%x "
            "format_features=0x%llx vector_features=%d sampled_usage_result=%d accepted=%d",
            candidate->name, (unsigned) candidate->format, listed,
            mapped_sampleable, mapped ? (int) mapped->type : -1,
            mapped ? mapped->num_components : 0,
            mapped ? (unsigned) mapped->caps : 0,
            (unsigned long long) format_features, vector_features,
            (int) image_result, accepted);
        if (accepted) {
            optical->vector_format = candidate->format;
            optical->vector_format_name = candidate->name;
            return true;
        }
    }
    if (format_present(optical->get_image_formats, optical->vk->phys_device,
                       VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV,
                       VK_FORMAT_R16G16B16A16_SFLOAT)) {
        optical_log(optical->gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC event=optical_flow_vector_format state=unsupported "
            "format=R16G16B16A16_SFLOAT reason=channel_semantics_unverified "
            "decoder=rg16sfix5_signed_sfixed5");
    }
    return false;
}

static void append_unique_family(uint32_t *families, uint32_t *count,
                                 uint32_t family)
{
    if (family == VK_QUEUE_FAMILY_IGNORED)
        return;
    for (uint32_t i = 0; i < *count; i++)
        if (families[i] == family)
            return;
    if (*count < 4)
        families[(*count)++] = family;
}

static bool queue_is_managed(pl_vulkan vk, uint32_t family)
{
    return vk && ((vk->queue_graphics.count && vk->queue_graphics.index == family) ||
                  (vk->queue_compute.count && vk->queue_compute.index == family) ||
                  (vk->queue_transfer.count && vk->queue_transfer.index == family));
}

static void optical_queue_lock(MPVFlowVulkanOptical *optical)
{
    if (optical->queue_is_managed && optical->vk->lock_queue)
        optical->vk->lock_queue(optical->vk, optical->queue_family, 0);
}

static void optical_queue_unlock(MPVFlowVulkanOptical *optical)
{
    if (optical->queue_is_managed && optical->vk->unlock_queue)
        optical->vk->unlock_queue(optical->vk, optical->queue_family, 0);
}

static void optical_wait_queue_idle(MPVFlowVulkanOptical *optical)
{
    if (!optical || !optical->queue)
        return;
    optical_queue_lock(optical);
    (void) vkQueueWaitIdle(optical->queue);
    optical_queue_unlock(optical);
}

static uint32_t image_memory_type(pl_vulkan vk, uint32_t bits)
{
    VkPhysicalDeviceMemoryProperties properties;
    vkGetPhysicalDeviceMemoryProperties(vk->phys_device, &properties);
    for (uint32_t i = 0; i < properties.memoryTypeCount; i++) {
        if ((bits & (1u << i)) &&
            (properties.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
            return i;
    }
    for (uint32_t i = 0; i < properties.memoryTypeCount; i++)
        if (bits & (1u << i))
            return i;
    return UINT32_MAX;
}

static void destroy_image(MPVFlowVulkanOptical *optical, FlowOpticalImage *image)
{
    if (!optical || !image)
        return;
    if (image->texture)
        pl_tex_destroy(optical->gpu, &image->texture);
    if (image->view)
        vkDestroyImageView(optical->vk->device, image->view, NULL);
    if (image->image)
        vkDestroyImage(optical->vk->device, image->image, NULL);
    if (image->memory)
        vkFreeMemory(optical->vk->device, image->memory, NULL);
    memset(image, 0, sizeof(*image));
}

static bool create_image(MPVFlowVulkanOptical *optical, FlowOpticalImage *image,
                         uint32_t width, uint32_t height, VkFormat format,
                         VkImageUsageFlags usage,
                         VkOpticalFlowUsageFlagsNV optical_usage)
{
    if (!format_usage_supported(optical->vk, format, usage, optical_usage))
        return false;
    uint32_t families[4] = {0};
    uint32_t family_count = 0;
    append_unique_family(families, &family_count,
                         optical->vk->queue_graphics.count
                             ? optical->vk->queue_graphics.index : VK_QUEUE_FAMILY_IGNORED);
    append_unique_family(families, &family_count,
                         optical->vk->queue_compute.count
                             ? optical->vk->queue_compute.index : VK_QUEUE_FAMILY_IGNORED);
    append_unique_family(families, &family_count,
                         optical->vk->queue_transfer.count
                             ? optical->vk->queue_transfer.index : VK_QUEUE_FAMILY_IGNORED);
    append_unique_family(families, &family_count, optical->queue_family);

    VkOpticalFlowImageFormatInfoNV flow_info = {
        .sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_INFO_NV,
        .usage = optical_usage,
    };
    VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &flow_info,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = { width, height, 1 },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = usage,
        .sharingMode = family_count > 1 ? VK_SHARING_MODE_CONCURRENT
                                        : VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = family_count > 1 ? family_count : 0,
        .pQueueFamilyIndices = family_count > 1 ? families : NULL,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (vkCreateImage(optical->vk->device, &image_info, NULL, &image->image) != VK_SUCCESS)
        goto error;

    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(optical->vk->device, image->image, &requirements);
    uint32_t memory_type = image_memory_type(optical->vk, requirements.memoryTypeBits);
    if (memory_type == UINT32_MAX)
        goto error;
    VkMemoryAllocateInfo memory_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = memory_type,
    };
    if (vkAllocateMemory(optical->vk->device, &memory_info, NULL, &image->memory) != VK_SUCCESS ||
        vkBindImageMemory(optical->vk->device, image->image, image->memory, 0) != VK_SUCCESS)
        goto error;

    VkImageViewCreateInfo view_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = image->image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format,
        .components = {
            VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
        },
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    if (vkCreateImageView(optical->vk->device, &view_info, NULL, &image->view) != VK_SUCCESS)
        goto error;

    image->texture = pl_vulkan_wrap(optical->gpu, pl_vulkan_wrap_params(
        .image = image->image,
        .width = (int) width,
        .height = (int) height,
        .format = format,
        .usage = usage));
    if (!image->texture)
        goto error;

    image->format = format;
    image->usage = usage;
    image->width = width;
    image->height = height;
    pl_vulkan_release_ex(optical->gpu, pl_vulkan_release_params(
        .tex = image->texture,
        .layout = VK_IMAGE_LAYOUT_UNDEFINED,
        .qf = VK_QUEUE_FAMILY_IGNORED));
    return true;

error:
    destroy_image(optical, image);
    return false;
}

static void destroy_resources(MPVFlowVulkanOptical *optical)
{
    if (!optical)
        return;
    optical_wait_queue_idle(optical);
    pl_gpu_finish(optical->gpu);
    for (int i = 0; i < 2; i++) {
        if (optical->sessions[i])
            optical->destroy_session(optical->vk->device, optical->sessions[i], NULL);
        optical->sessions[i] = VK_NULL_HANDLE;
    }
    for (int i = 0; i < 2; i++) {
        destroy_image(optical, &optical->input[i]);
        destroy_image(optical, &optical->vectors[i]);
    }
    optical->width = 0;
    optical->height = 0;
}

static bool bind_session_images(MPVFlowVulkanOptical *optical)
{
    const VkImageLayout layout = VK_IMAGE_LAYOUT_GENERAL;
    VkResult result = optical->bind_session_image(optical->vk->device,
        optical->sessions[0], VK_OPTICAL_FLOW_SESSION_BINDING_POINT_INPUT_NV,
        optical->input[0].view, layout);
    if (result != VK_SUCCESS)
        return false;
    result = optical->bind_session_image(optical->vk->device,
        optical->sessions[0], VK_OPTICAL_FLOW_SESSION_BINDING_POINT_REFERENCE_NV,
        optical->input[1].view, layout);
    if (result != VK_SUCCESS)
        return false;
    result = optical->bind_session_image(optical->vk->device,
        optical->sessions[0], VK_OPTICAL_FLOW_SESSION_BINDING_POINT_FLOW_VECTOR_NV,
        optical->vectors[0].view, layout);
    if (result != VK_SUCCESS)
        return false;

    result = optical->bind_session_image(optical->vk->device,
        optical->sessions[1], VK_OPTICAL_FLOW_SESSION_BINDING_POINT_INPUT_NV,
        optical->input[1].view, layout);
    if (result != VK_SUCCESS)
        return false;
    result = optical->bind_session_image(optical->vk->device,
        optical->sessions[1], VK_OPTICAL_FLOW_SESSION_BINDING_POINT_REFERENCE_NV,
        optical->input[0].view, layout);
    if (result != VK_SUCCESS)
        return false;
    return optical->bind_session_image(optical->vk->device,
        optical->sessions[1], VK_OPTICAL_FLOW_SESSION_BINDING_POINT_FLOW_VECTOR_NV,
        optical->vectors[1].view, layout) == VK_SUCCESS;
}

static bool create_sessions(MPVFlowVulkanOptical *optical)
{
    const VkOpticalFlowSessionCreateInfoNV create_info = {
        .sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_SESSION_CREATE_INFO_NV,
        .width = optical->width,
        .height = optical->height,
        .imageFormat = FLOW_OPTICAL_INPUT_FORMAT,
        .flowVectorFormat = optical->vector_format,
        .costFormat = VK_FORMAT_UNDEFINED,
        .outputGridSize = FLOW_OPTICAL_GRID,
        .hintGridSize = VK_OPTICAL_FLOW_GRID_SIZE_UNKNOWN_NV,
        .performanceLevel = VK_OPTICAL_FLOW_PERFORMANCE_LEVEL_MEDIUM_NV,
        .flags = 0,
    };
    if (optical->create_session(optical->vk->device, &create_info, NULL,
                                &optical->sessions[0]) != VK_SUCCESS)
        return false;
    if (optical->create_session(optical->vk->device, &create_info, NULL,
                                &optical->sessions[1]) != VK_SUCCESS)
        return false;
    return bind_session_images(optical);
}

static bool load_optical_functions(MPVFlowVulkanOptical *optical)
{
    PFN_vkGetInstanceProcAddr get_instance = optical->vk->get_proc_addr;
    if (!get_instance)
        return false;
    optical->get_image_formats = (PFN_vkGetPhysicalDeviceOpticalFlowImageFormatsNV)
        get_instance(optical->vk->instance, "vkGetPhysicalDeviceOpticalFlowImageFormatsNV");
    PFN_vkGetDeviceProcAddr get_device = (PFN_vkGetDeviceProcAddr)
        get_instance(optical->vk->instance, "vkGetDeviceProcAddr");
    if (!get_device)
        return false;
    optical->create_session = (PFN_vkCreateOpticalFlowSessionNV)
        get_device(optical->vk->device, "vkCreateOpticalFlowSessionNV");
    optical->destroy_session = (PFN_vkDestroyOpticalFlowSessionNV)
        get_device(optical->vk->device, "vkDestroyOpticalFlowSessionNV");
    optical->bind_session_image = (PFN_vkBindOpticalFlowSessionImageNV)
        get_device(optical->vk->device, "vkBindOpticalFlowSessionImageNV");
    optical->cmd_execute = (PFN_vkCmdOpticalFlowExecuteNV)
        get_device(optical->vk->device, "vkCmdOpticalFlowExecuteNV");
    return optical->get_image_formats && optical->create_session &&
           optical->destroy_session && optical->bind_session_image &&
           optical->cmd_execute;
}

static bool choose_optical_queue(MPVFlowVulkanOptical *optical)
{
    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(optical->vk->phys_device, &count, NULL);
    if (!count)
        return false;
    VkQueueFamilyProperties *families = calloc(count, sizeof(*families));
    if (!families)
        return false;
    vkGetPhysicalDeviceQueueFamilyProperties(optical->vk->phys_device, &count, families);
    uint32_t selected = UINT32_MAX;
    int selected_score = -1;
    for (uint32_t i = 0; i < count; i++) {
        if (!(families[i].queueFlags & VK_QUEUE_OPTICAL_FLOW_BIT_NV) ||
            !families[i].queueCount)
            continue;
        int score = (families[i].queueFlags &
                     (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT | VK_QUEUE_TRANSFER_BIT))
                        ? 0 : 1;
        if (score > selected_score) {
            selected = i;
            selected_score = score;
        }
    }
    free(families);
    if (selected == UINT32_MAX)
        return false;
    optical->queue_family = selected;
    optical->queue_is_managed = queue_is_managed(optical->vk, selected);
    vkGetDeviceQueue(optical->vk->device, selected, 0, &optical->queue);
    return optical->queue != VK_NULL_HANDLE;
}

static bool create_command_state(MPVFlowVulkanOptical *optical)
{
    VkCommandPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = optical->queue_family,
    };
    if (vkCreateCommandPool(optical->vk->device, &pool_info, NULL,
                            &optical->command_pool) != VK_SUCCESS)
        return false;
    VkCommandBuffer commands[FLOW_OPTICAL_COMMAND_SLOTS];
    VkCommandBufferAllocateInfo allocate_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = optical->command_pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = FLOW_OPTICAL_COMMAND_SLOTS,
    };
    if (vkAllocateCommandBuffers(optical->vk->device, &allocate_info, commands) != VK_SUCCESS)
        return false;
    for (uint32_t i = 0; i < FLOW_OPTICAL_COMMAND_SLOTS; i++) {
        optical->commands[i].command = commands[i];
        VkFenceCreateInfo fence_info = {
            .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
            .flags = VK_FENCE_CREATE_SIGNALED_BIT,
        };
        if (vkCreateFence(optical->vk->device, &fence_info, NULL,
                          &optical->commands[i].fence) != VK_SUCCESS)
            return false;
    }
    struct pl_vulkan_sem_params sem_params = {
        .type = VK_SEMAPHORE_TYPE_TIMELINE,
        .initial_value = 0,
    };
    optical->ready_semaphore = pl_vulkan_sem_create(optical->gpu, &sem_params);
    optical->done_semaphore = pl_vulkan_sem_create(optical->gpu, &sem_params);
    return optical->ready_semaphore && optical->done_semaphore;
}

static void release_held_images(MPVFlowVulkanOptical *optical,
                                FlowOpticalImage *images[4], int held_count,
                                uint64_t ready_value)
{
    for (int i = 0; i < held_count; i++) {
        pl_vulkan_release_ex(optical->gpu, pl_vulkan_release_params(
            .tex = images[i]->texture,
            .layout = VK_IMAGE_LAYOUT_GENERAL,
            .qf = VK_QUEUE_FAMILY_IGNORED,
            .semaphore = { optical->ready_semaphore, ready_value }));
    }
}

static FlowOpticalCommand *available_command(MPVFlowVulkanOptical *optical)
{
    for (uint32_t i = 0; i < FLOW_OPTICAL_COMMAND_SLOTS; i++) {
        uint32_t slot = (optical->next_command + i) % FLOW_OPTICAL_COMMAND_SLOTS;
        VkResult result = vkGetFenceStatus(optical->vk->device,
                                           optical->commands[slot].fence);
        if (result == VK_SUCCESS) {
            optical->next_command = (slot + 1) % FLOW_OPTICAL_COMMAND_SLOTS;
            return &optical->commands[slot];
        }
    }
    return NULL;
}

#else
struct MPVFlowVulkanOptical { int unavailable; };
#endif

MPVFlowVulkanOptical *mpvflow_vulkan_optical_create(pl_gpu gpu,
                                                    char *reason,
                                                    size_t reason_size)
{
#ifdef VK_NV_optical_flow
    if (reason && reason_size)
        reason[0] = '\0';
    if (!gpu) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "gpu_missing");
        return NULL;
    }
    pl_vulkan vk = pl_vulkan_get(gpu);
    if (!vk || !optical_extension_enabled(vk) || !optical_feature_enabled(vk)) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "extension_or_feature_not_enabled");
        return NULL;
    }
    if (!optical_timeline_enabled(vk)) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "timeline_semaphore_feature_not_enabled");
        return NULL;
    }
    MPVFlowVulkanOptical *optical = calloc(1, sizeof(*optical));
    if (!optical) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "out_of_memory");
        return NULL;
    }
    optical->gpu = gpu;
    optical->vk = vk;
    if (!load_optical_functions(optical)) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "extension_function_unavailable");
        free(optical);
        return NULL;
    }
    if (!choose_vector_format(optical)) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "no_documented_sampleable_vector_format");
        free(optical);
        return NULL;
    }
    if (!format_present(optical->get_image_formats, vk->phys_device,
                        VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV, FLOW_OPTICAL_INPUT_FORMAT) ||
        !format_present(optical->get_image_formats, vk->phys_device,
                        VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, optical->vector_format)) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "required_optical_flow_image_format_missing");
        free(optical);
        return NULL;
    }
    if (!choose_optical_queue(optical)) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "optical_flow_queue_unavailable");
        free(optical);
        return NULL;
    }
    if (!create_command_state(optical)) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "timeline_or_optical_command_state_failed");
        mpvflow_vulkan_optical_destroy(&optical);
        return NULL;
    }
    optical_log(gpu, PL_LOG_INFO,
                "MPVFLOW_DIAGNOSTIC event=optical_flow_backend state=ready "
                "api=VK_NV_optical_flow queue_family=%u queue_is_libplacebo_managed=%d "
                "vector_format=%s vector_format_sampleable=1 "
                "timeline_semaphore=1 cpu_readback=no",
                optical->queue_family, optical->queue_is_managed,
                optical->vector_format_name);
    return optical;
#else
    (void) gpu;
    if (reason && reason_size)
        snprintf(reason, reason_size, "vulkan_headers_without_VK_NV_optical_flow");
    return NULL;
#endif
}

bool mpvflow_vulkan_optical_ensure(MPVFlowVulkanOptical *optical,
                                   int width, int height)
{
#ifdef VK_NV_optical_flow
    if (!optical || optical->disabled || width <= 0 || height <= 0)
        return false;
    uint32_t target_width = (uint32_t) width;
    uint32_t target_height = (uint32_t) height;
    if (target_width < 256)
        target_width = 256;
    if (target_height < 256)
        target_height = 256;
    target_width = (target_width + 7u) & ~7u;
    target_height = (target_height + 7u) & ~7u;
    if (optical->width == target_width && optical->height == target_height)
        return true;
    if (optical->rejected_width == target_width &&
        optical->rejected_height == target_height)
        return false;

    VkPhysicalDeviceOpticalFlowPropertiesNV flow_properties = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_PROPERTIES_NV,
    };
    VkPhysicalDeviceProperties2 properties = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
        .pNext = &flow_properties,
    };
    PFN_vkGetPhysicalDeviceProperties2 get_properties =
        get_physical_device_properties2(optical->vk);
    if (!get_properties) {
        optical->rejected_width = target_width;
        optical->rejected_height = target_height;
        optical_log(optical->gpu, PL_LOG_WARN,
                    "MPVFLOW_DIAGNOSTIC event=optical_flow_backend state=fallback "
                    "reason=physical_device_properties2_unavailable");
        return false;
    }
    get_properties(optical->vk->phys_device, &properties);
    if (target_width < flow_properties.minWidth || target_height < flow_properties.minHeight ||
        target_width > flow_properties.maxWidth || target_height > flow_properties.maxHeight ||
        !(flow_properties.supportedOutputGridSizes & FLOW_OPTICAL_GRID)) {
        optical->rejected_width = target_width;
        optical->rejected_height = target_height;
        optical_log(optical->gpu, PL_LOG_WARN,
                    "MPVFLOW_DIAGNOSTIC event=optical_flow_backend state=fallback "
                    "reason=dimensions_or_8x8_grid_unsupported width=%u height=%u "
                    "min=%ux%u max=%ux%u output_grid_flags=0x%x",
                    target_width, target_height, flow_properties.minWidth,
                    flow_properties.minHeight, flow_properties.maxWidth,
                    flow_properties.maxHeight, flow_properties.supportedOutputGridSizes);
        return false;
    }
    const VkImageUsageFlags input_usage = VK_IMAGE_USAGE_STORAGE_BIT;
    const VkImageUsageFlags vector_usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    if (!format_usage_supported(optical->vk, FLOW_OPTICAL_INPUT_FORMAT, input_usage,
                                VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV) ||
        !format_usage_supported(optical->vk, optical->vector_format, vector_usage,
                                VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV)) {
        optical->rejected_width = target_width;
        optical->rejected_height = target_height;
        optical_log(optical->gpu, PL_LOG_WARN,
                    "MPVFLOW_DIAGNOSTIC event=optical_flow_backend state=fallback "
                    "reason=image_format_usage_not_supported input_usage=0x%x vector_usage=0x%x",
                    input_usage, vector_usage);
        return false;
    }

    if (optical->width || optical->height)
        destroy_resources(optical);
    optical->width = target_width;
    optical->height = target_height;
    const uint32_t vector_width = target_width / 8u;
    const uint32_t vector_height = target_height / 8u;
    if (!create_image(optical, &optical->input[0], target_width, target_height,
                      FLOW_OPTICAL_INPUT_FORMAT, input_usage,
                      VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV) ||
        !create_image(optical, &optical->input[1], target_width, target_height,
                      FLOW_OPTICAL_INPUT_FORMAT, input_usage,
                      VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV) ||
        !create_image(optical, &optical->vectors[0], vector_width, vector_height,
                      optical->vector_format, vector_usage,
                      VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV) ||
        !create_image(optical, &optical->vectors[1], vector_width, vector_height,
                      optical->vector_format, vector_usage,
                      VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV) ||
        !create_sessions(optical)) {
        optical_log(optical->gpu, PL_LOG_WARN,
                    "MPVFLOW_DIAGNOSTIC event=optical_flow_backend state=fallback "
                    "reason=resource_or_session_creation_failed width=%u height=%u "
                    "vector_grid=%ux%u",
                    target_width, target_height, vector_width, vector_height);
        destroy_resources(optical);
        optical->disabled = true;
        snprintf(optical->disabled_reason, sizeof(optical->disabled_reason),
                 "resource_or_session_creation_failed");
        return false;
    }
    optical_log(optical->gpu, PL_LOG_INFO,
                "MPVFLOW_DIAGNOSTIC event=optical_flow_session state=ready "
                "width=%u height=%u vector_grid=%ux%u directions=2 "
                "performance=medium luma_format=R8_UNORM vector_format=%s "
                "output_grid=8x8 cpu_readback=no",
                target_width, target_height, vector_width, vector_height,
                optical->vector_format_name);
    return true;
#else
    (void) optical;
    (void) width;
    (void) height;
    return false;
#endif
}

pl_tex mpvflow_vulkan_optical_input(MPVFlowVulkanOptical *optical, int index)
{
#ifdef VK_NV_optical_flow
    return optical && index >= 0 && index < 2 ? optical->input[index].texture : NULL;
#else
    (void) optical;
    (void) index;
    return NULL;
#endif
}

pl_tex mpvflow_vulkan_optical_vectors(MPVFlowVulkanOptical *optical, int index)
{
#ifdef VK_NV_optical_flow
    return optical && index >= 0 && index < 2 ? optical->vectors[index].texture : NULL;
#else
    (void) optical;
    (void) index;
    return NULL;
#endif
}

bool mpvflow_vulkan_optical_dispatch(MPVFlowVulkanOptical *optical)
{
#ifdef VK_NV_optical_flow
    if (!optical || optical->disabled || !optical->width || !optical->height)
        return false;
    FlowOpticalCommand *slot = available_command(optical);
    if (!slot) {
        if (optical->dispatch_count == 0)
            optical_log(optical->gpu, PL_LOG_WARN,
                        "MPVFLOW_DIAGNOSTIC event=optical_flow_dispatch state=fallback "
                        "reason=nonblocking_command_slots_busy slots=%d",
                        FLOW_OPTICAL_COMMAND_SLOTS);
        return false;
    }

    FlowOpticalImage *images[4] = {
        &optical->input[0], &optical->input[1],
        &optical->vectors[0], &optical->vectors[1],
    };
    pl_gpu_flush(optical->gpu);
    uint64_t ready_value = optical->ready_value;
    int held_count = 0;
    for (int i = 0; i < 4; i++) {
        if (ready_value == UINT64_MAX)
            goto hold_failed;
        uint64_t next_ready_value = ready_value + 1;
        if (!pl_vulkan_hold_ex(optical->gpu, pl_vulkan_hold_params(
                .tex = images[i]->texture,
                .layout = VK_IMAGE_LAYOUT_GENERAL,
                .qf = VK_QUEUE_FAMILY_IGNORED,
                .semaphore = { optical->ready_semaphore, next_ready_value })))
            goto hold_failed;
        ready_value = next_ready_value;
        held_count++;
    }
    optical->ready_value = ready_value;

    if (vkResetCommandBuffer(slot->command, 0) != VK_SUCCESS)
        goto submit_failed;
    VkCommandBufferBeginInfo begin_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    if (vkBeginCommandBuffer(slot->command, &begin_info) != VK_SUCCESS)
        goto submit_failed;
    const VkOpticalFlowExecuteInfoNV execute_info = {
        .sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_EXECUTE_INFO_NV,
        .flags = VK_OPTICAL_FLOW_EXECUTE_DISABLE_TEMPORAL_HINTS_BIT_NV,
        .regionCount = 0,
        .pRegions = NULL,
    };
    optical->cmd_execute(slot->command, optical->sessions[0], &execute_info);
    optical->cmd_execute(slot->command, optical->sessions[1], &execute_info);
    if (vkEndCommandBuffer(slot->command) != VK_SUCCESS)
        goto submit_failed;

    const uint64_t wait_value = ready_value;
    const uint64_t done_value = optical->done_value + 1;
    if (!done_value)
        goto submit_failed;
    const VkTimelineSemaphoreSubmitInfo timeline_info = {
        .sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO,
        .waitSemaphoreValueCount = 1,
        .pWaitSemaphoreValues = &wait_value,
        .signalSemaphoreValueCount = 1,
        .pSignalSemaphoreValues = &done_value,
    };
    const VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    const VkSubmitInfo submit_info = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .pNext = &timeline_info,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &optical->ready_semaphore,
        .pWaitDstStageMask = &wait_stage,
        .commandBufferCount = 1,
        .pCommandBuffers = &slot->command,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &optical->done_semaphore,
    };
    if (vkResetFences(optical->vk->device, 1, &slot->fence) != VK_SUCCESS)
        goto submit_failed;
    optical_queue_lock(optical);
    VkResult submit_result = vkQueueSubmit(optical->queue, 1, &submit_info, slot->fence);
    optical_queue_unlock(optical);
    if (submit_result != VK_SUCCESS) {
        optical_log(optical->gpu, PL_LOG_WARN,
                    "MPVFLOW_DIAGNOSTIC event=optical_flow_dispatch state=fallback "
                    "reason=vkQueueSubmit_failed vk_result=%d",
                    submit_result);
        optical->disabled = true;
        snprintf(optical->disabled_reason, sizeof(optical->disabled_reason),
                 "vkQueueSubmit_failed_%d", submit_result);
        release_held_images(optical, images, held_count, ready_value);
        return false;
    }
    optical->done_value = done_value;
    for (int i = 0; i < 4; i++) {
        pl_vulkan_release_ex(optical->gpu, pl_vulkan_release_params(
            .tex = images[i]->texture,
            .layout = VK_IMAGE_LAYOUT_GENERAL,
            .qf = VK_QUEUE_FAMILY_IGNORED,
            .semaphore = { optical->done_semaphore, done_value }));
    }
    optical->dispatch_count++;
    if (optical->dispatch_count == 1 || optical->dispatch_count % 120 == 0)
        optical_log(optical->gpu, PL_LOG_INFO,
                    "MPVFLOW_DIAGNOSTIC event=optical_flow_dispatch state=submitted "
                    "api=VK_NV_optical_flow count=%llu directions=2 width=%u height=%u "
                    "grid=8x8 queue_family=%u cpu_readback=no shader_quality_checks=enabled",
                    (unsigned long long) optical->dispatch_count,
                    optical->width, optical->height, optical->queue_family);
    return true;

hold_failed:
    optical->ready_value = ready_value;
    if (optical->fallback_count++ == 0)
        optical_log(optical->gpu, PL_LOG_WARN,
                    "MPVFLOW_DIAGNOSTIC event=optical_flow_dispatch state=fallback "
                    "reason=pl_vulkan_hold_failed held_images=%d",
                    held_count);
    release_held_images(optical, images, held_count, ready_value);
    return false;

submit_failed:
    if (optical->fallback_count++ == 0)
        optical_log(optical->gpu, PL_LOG_WARN,
                    "MPVFLOW_DIAGNOSTIC event=optical_flow_dispatch state=fallback "
                    "reason=command_buffer_or_fence_failed");
    optical->disabled = true;
    snprintf(optical->disabled_reason, sizeof(optical->disabled_reason),
             "command_buffer_or_fence_failed");
    release_held_images(optical, images, held_count, ready_value);
    return false;
#else
    (void) optical;
    return false;
#endif
}

void mpvflow_vulkan_optical_destroy(MPVFlowVulkanOptical **pointer)
{
#ifdef VK_NV_optical_flow
    if (!pointer || !*pointer)
        return;
    MPVFlowVulkanOptical *optical = *pointer;
    destroy_resources(optical);
    if (optical->ready_semaphore)
        pl_vulkan_sem_destroy(optical->gpu, &optical->ready_semaphore);
    if (optical->done_semaphore)
        pl_vulkan_sem_destroy(optical->gpu, &optical->done_semaphore);
    for (uint32_t i = 0; i < FLOW_OPTICAL_COMMAND_SLOTS; i++) {
        if (optical->commands[i].fence)
            vkDestroyFence(optical->vk->device, optical->commands[i].fence, NULL);
    }
    if (optical->command_pool)
        vkDestroyCommandPool(optical->vk->device, optical->command_pool, NULL);
    free(optical);
    *pointer = NULL;
#else
    if (pointer) {
        free(*pointer);
        *pointer = NULL;
    }
#endif
}
