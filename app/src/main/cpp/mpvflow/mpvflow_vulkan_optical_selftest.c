/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 *
 * Manually invoked, debug-build-only probe for the device's advertised
 * VK_NV_optical_flow output format. This is intentionally separate from the
 * playback adapter and never changes its signed-S10.5 production format gate.
 */
#include "mpvflow_vulkan_optical.h"

#include <libplacebo/gpu.h>

#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(MPVFLOW_ENABLE_NV_SELF_TEST) && defined(VK_NV_optical_flow)

#define NV_SELF_TEST_WIDTH 256u
#define NV_SELF_TEST_HEIGHT 256u
#define NV_SELF_TEST_SHIFT_X 16u
#define NV_SELF_TEST_SQUARE_X 64u
#define NV_SELF_TEST_SQUARE_Y 80u
#define NV_SELF_TEST_SQUARE_SIZE 64u
#define NV_SELF_TEST_GRID VK_OPTICAL_FLOW_GRID_SIZE_8X8_BIT_NV
#define NV_SELF_TEST_VECTOR_FORMAT VK_FORMAT_R16G16B16A16_SFLOAT
#define NV_SELF_TEST_INPUT_FORMAT VK_FORMAT_R8_UNORM

struct NVSelfTestImage {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    uint32_t width;
    uint32_t height;
    VkFormat format;
};

struct NVSelfTestBuffer {
    VkBuffer buffer;
    VkDeviceMemory memory;
    VkDeviceSize allocation_size;
    VkDeviceSize size;
    VkMemoryPropertyFlags memory_flags;
    void *mapped;
};

struct NVSelfTest {
    pl_gpu gpu;
    pl_vulkan vk;
    PFN_vkGetPhysicalDeviceOpticalFlowImageFormatsNV get_formats;
    PFN_vkCreateOpticalFlowSessionNV create_session;
    PFN_vkDestroyOpticalFlowSessionNV destroy_session;
    PFN_vkBindOpticalFlowSessionImageNV bind_session_image;
    PFN_vkCmdOpticalFlowExecuteNV cmd_execute;
    PFN_vkGetPhysicalDeviceImageFormatProperties2 get_image_properties;
    PFN_vkGetPhysicalDeviceProperties2 get_device_properties;
    PFN_vkGetPhysicalDeviceFormatProperties2 get_format_properties;
    VkQueue optical_queue;
    VkQueue copy_queue;
    uint32_t optical_family;
    uint32_t copy_family;
    VkCommandPool optical_pool;
    VkCommandPool copy_pool;
    VkCommandBuffer upload_command;
    VkCommandBuffer optical_command;
    VkCommandBuffer readback_command;
    VkSemaphore upload_complete;
    VkSemaphore optical_complete;
    VkFence readback_complete;
    VkOpticalFlowSessionNV session;
    struct NVSelfTestImage input[2];
    struct NVSelfTestImage output;
    struct NVSelfTestBuffer staging[2];
    struct NVSelfTestBuffer readback;
    bool work_submitted;
    bool readback_finished;
    bool clear_output;
};

static void self_test_log(pl_gpu gpu, enum pl_log_level level,
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

static bool self_test_extension_enabled(pl_vulkan vk)
{
    for (int i = 0; vk && i < vk->num_extensions; i++) {
        if (vk->extensions[i] &&
            strcmp(vk->extensions[i], VK_NV_OPTICAL_FLOW_EXTENSION_NAME) == 0)
            return true;
    }
    return false;
}

static bool self_test_named_extension_enabled(pl_vulkan vk, const char *name)
{
    for (int i = 0; vk && i < vk->num_extensions; i++) {
        if (vk->extensions[i] && strcmp(vk->extensions[i], name) == 0)
            return true;
    }
    return false;
}

static bool self_test_feature_enabled(pl_vulkan vk)
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

static bool self_test_load_functions(struct NVSelfTest *test)
{
    if (!test->vk->get_proc_addr)
        return false;
    test->get_formats = (PFN_vkGetPhysicalDeviceOpticalFlowImageFormatsNV)
        test->vk->get_proc_addr(test->vk->instance,
            "vkGetPhysicalDeviceOpticalFlowImageFormatsNV");
    PFN_vkGetDeviceProcAddr get_device =
        (PFN_vkGetDeviceProcAddr) test->vk->get_proc_addr(
            test->vk->instance, "vkGetDeviceProcAddr");
    if (!get_device)
        return false;
    test->create_session = (PFN_vkCreateOpticalFlowSessionNV)
        get_device(test->vk->device, "vkCreateOpticalFlowSessionNV");
    test->destroy_session = (PFN_vkDestroyOpticalFlowSessionNV)
        get_device(test->vk->device, "vkDestroyOpticalFlowSessionNV");
    test->bind_session_image = (PFN_vkBindOpticalFlowSessionImageNV)
        get_device(test->vk->device, "vkBindOpticalFlowSessionImageNV");
    test->cmd_execute = (PFN_vkCmdOpticalFlowExecuteNV)
        get_device(test->vk->device, "vkCmdOpticalFlowExecuteNV");

    test->get_image_properties =
        (PFN_vkGetPhysicalDeviceImageFormatProperties2)
        test->vk->get_proc_addr(test->vk->instance,
            "vkGetPhysicalDeviceImageFormatProperties2");
    if (!test->get_image_properties)
        test->get_image_properties =
            (PFN_vkGetPhysicalDeviceImageFormatProperties2)
            test->vk->get_proc_addr(test->vk->instance,
                "vkGetPhysicalDeviceImageFormatProperties2KHR");
    test->get_device_properties = (PFN_vkGetPhysicalDeviceProperties2)
        test->vk->get_proc_addr(test->vk->instance,
            "vkGetPhysicalDeviceProperties2");
    if (!test->get_device_properties)
        test->get_device_properties = (PFN_vkGetPhysicalDeviceProperties2)
            test->vk->get_proc_addr(test->vk->instance,
                "vkGetPhysicalDeviceProperties2KHR");
    test->get_format_properties =
        (PFN_vkGetPhysicalDeviceFormatProperties2)
        test->vk->get_proc_addr(test->vk->instance,
            "vkGetPhysicalDeviceFormatProperties2");
    if (!test->get_format_properties)
        test->get_format_properties =
            (PFN_vkGetPhysicalDeviceFormatProperties2)
            test->vk->get_proc_addr(test->vk->instance,
                "vkGetPhysicalDeviceFormatProperties2KHR");
    return test->get_formats && test->create_session &&
           test->destroy_session && test->bind_session_image &&
           test->cmd_execute && test->get_image_properties &&
           test->get_device_properties;
}

static bool self_test_format_listed(struct NVSelfTest *test,
                                    VkOpticalFlowUsageFlagsNV usage,
                                    VkFormat format)
{
    VkOpticalFlowImageFormatInfoNV query = {
        .sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_INFO_NV,
        .usage = usage,
    };
    uint32_t count = 0;
    if (test->get_formats(test->vk->phys_device, &query, &count, NULL) != VK_SUCCESS ||
        count == 0)
        return false;
    VkOpticalFlowImageFormatPropertiesNV *formats =
        calloc(count, sizeof(*formats));
    if (!formats)
        return false;
    for (uint32_t i = 0; i < count; i++)
        formats[i].sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_PROPERTIES_NV;
    bool found = false;
    if (test->get_formats(test->vk->phys_device, &query, &count, formats) == VK_SUCCESS) {
        for (uint32_t i = 0; i < count; i++)
            found |= formats[i].format == format;
    }
    free(formats);
    return found;
}

static VkResult self_test_query_image_usage(struct NVSelfTest *test,
                                            VkFormat format,
                                            VkImageUsageFlags usage,
                                            VkOpticalFlowUsageFlagsNV flow_usage,
                                            VkImageFormatProperties *properties)
{
    if (!test->get_image_properties)
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    VkOpticalFlowImageFormatInfoNV flow_info = {
        .sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_INFO_NV,
        .usage = flow_usage,
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
    VkResult result = test->get_image_properties(test->vk->phys_device,
                                                   &image_info,
                                                   &image_properties);
    if (result == VK_SUCCESS && properties)
        *properties = image_properties.imageFormatProperties;
    return result;
}

static bool self_test_image_extent_supported(const VkImageFormatProperties *properties,
                                             uint32_t width, uint32_t height)
{
    return properties && width <= properties->maxExtent.width &&
           height <= properties->maxExtent.height &&
           properties->maxExtent.depth >= 1 && properties->maxMipLevels >= 1 &&
           properties->maxArrayLayers >= 1 &&
           (properties->sampleCounts & VK_SAMPLE_COUNT_1_BIT);
}

static bool self_test_query_format_features(struct NVSelfTest *test,
                                            VkFormat format,
                                            VkFormatFeatureFlags2 *flags_out)
{
    if (flags_out)
        *flags_out = 0;
#if defined(VK_VERSION_1_3) && defined(VK_NV_optical_flow)
    const bool core_feature_flags2 = test->vk->api_version >= VK_API_VERSION_1_3;
    if (!test->get_format_properties ||
        (!core_feature_flags2 &&
         !self_test_named_extension_enabled(test->vk,
             "VK_KHR_format_feature_flags2")))
        return false;
    VkFormatProperties3 properties3 = {
        .sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_3,
    };
    VkFormatProperties2 properties2 = {
        .sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2,
        .pNext = &properties3,
    };
    test->get_format_properties(test->vk->phys_device, format, &properties2);
    if (flags_out)
        *flags_out = properties3.optimalTilingFeatures;
    return true;
#else
    (void) test;
    (void) format;
    return false;
#endif
}

static bool self_test_query_vector_format_feature(struct NVSelfTest *test,
                                                  VkFormatFeatureFlags2 *flags_out,
                                                  bool *vector_feature_out)
{
    if (flags_out)
        *flags_out = 0;
    if (vector_feature_out)
        *vector_feature_out = false;
#if defined(VK_VERSION_1_3) && defined(VK_NV_optical_flow)
    VkFormatFeatureFlags2 features = 0;
    if (!self_test_query_format_features(test, NV_SELF_TEST_VECTOR_FORMAT,
                                         &features))
        return false;
    if (flags_out)
        *flags_out = features;
    const VkFormatFeatureFlags2 required =
        VK_FORMAT_FEATURE_2_OPTICAL_FLOW_VECTOR_BIT_NV |
        VK_FORMAT_FEATURE_2_SAMPLED_IMAGE_BIT;
    if (vector_feature_out)
        *vector_feature_out = (features & required) == required;
    return true;
#else
    (void) test;
    return false;
#endif
}

static bool self_test_managed_queue(pl_vulkan vk, uint32_t family)
{
    return vk && ((vk->queue_graphics.count && vk->queue_graphics.index == family) ||
                  (vk->queue_compute.count && vk->queue_compute.index == family) ||
                  (vk->queue_transfer.count && vk->queue_transfer.index == family));
}

static void self_test_queue_lock(pl_vulkan vk, uint32_t family)
{
    if (self_test_managed_queue(vk, family) && vk->lock_queue)
        vk->lock_queue(vk, family, 0);
}

static void self_test_queue_unlock(pl_vulkan vk, uint32_t family)
{
    if (self_test_managed_queue(vk, family) && vk->unlock_queue)
        vk->unlock_queue(vk, family, 0);
}

static bool self_test_choose_queues(struct NVSelfTest *test)
{
    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(test->vk->phys_device, &count, NULL);
    if (!count)
        return false;
    VkQueueFamilyProperties *families = calloc(count, sizeof(*families));
    if (!families)
        return false;
    vkGetPhysicalDeviceQueueFamilyProperties(test->vk->phys_device, &count, families);

    int best_score = -1;
    for (uint32_t i = 0; i < count; i++) {
        if (!families[i].queueCount ||
            !(families[i].queueFlags & VK_QUEUE_OPTICAL_FLOW_BIT_NV))
            continue;
        int score = (families[i].queueFlags &
                     (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT | VK_QUEUE_TRANSFER_BIT))
                        ? 0 : 1;
        if (score > best_score) {
            best_score = score;
            test->optical_family = i;
        }
    }
    if (best_score < 0) {
        free(families);
        return false;
    }
    vkGetDeviceQueue(test->vk->device, test->optical_family, 0,
                     &test->optical_queue);

    const struct pl_vulkan_queue candidates[] = {
        test->vk->queue_transfer,
        test->vk->queue_graphics,
        test->vk->queue_compute,
    };
    bool transfer_selected = false;
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        const struct pl_vulkan_queue candidate = candidates[i];
        if (!candidate.count || candidate.index >= count ||
            !families[candidate.index].queueCount)
            continue;
        const VkQueueFlags flags = families[candidate.index].queueFlags;
        if (!(flags & (VK_QUEUE_TRANSFER_BIT | VK_QUEUE_GRAPHICS_BIT |
                       VK_QUEUE_COMPUTE_BIT)))
            continue;
        test->copy_family = candidate.index;
        vkGetDeviceQueue(test->vk->device, test->copy_family, 0,
                         &test->copy_queue);
        transfer_selected = test->copy_queue != VK_NULL_HANDLE;
        if (transfer_selected)
            break;
    }
    free(families);
    return test->optical_queue != VK_NULL_HANDLE && transfer_selected;
}

static bool self_test_memory_type(pl_vulkan vk, uint32_t type_bits,
                                  uint32_t *type_index,
                                  VkMemoryPropertyFlags *flags_out)
{
    VkPhysicalDeviceMemoryProperties properties;
    vkGetPhysicalDeviceMemoryProperties(vk->phys_device, &properties);
    for (int pass = 0; pass < 2; pass++) {
        for (uint32_t i = 0; i < properties.memoryTypeCount; i++) {
            VkMemoryPropertyFlags flags = properties.memoryTypes[i].propertyFlags;
            if (!(type_bits & (1u << i)) ||
                !(flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT))
                continue;
            if (pass == 0 && !(flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
                continue;
            *type_index = i;
            *flags_out = flags;
            return true;
        }
    }
    return false;
}

static uint32_t self_test_device_memory_type(pl_vulkan vk, uint32_t type_bits)
{
    VkPhysicalDeviceMemoryProperties properties;
    vkGetPhysicalDeviceMemoryProperties(vk->phys_device, &properties);
    for (uint32_t i = 0; i < properties.memoryTypeCount; i++) {
        if ((type_bits & (1u << i)) &&
            (properties.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
            return i;
    }
    for (uint32_t i = 0; i < properties.memoryTypeCount; i++)
        if (type_bits & (1u << i))
            return i;
    return UINT32_MAX;
}

static bool self_test_create_image(struct NVSelfTest *test,
                                   struct NVSelfTestImage *image,
                                   uint32_t width, uint32_t height,
                                   VkFormat format, VkImageUsageFlags usage,
                                   VkOpticalFlowUsageFlagsNV flow_usage)
{
    VkResult usage_result = self_test_query_image_usage(test, format, usage,
                                                        flow_usage, NULL);
    if (usage_result != VK_SUCCESS)
        return false;

    uint32_t families[2] = { test->optical_family, test->copy_family };
    uint32_t family_count = test->optical_family == test->copy_family ? 1 : 2;
    VkOpticalFlowImageFormatInfoNV flow_info = {
        .sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_INFO_NV,
        .usage = flow_usage,
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
    VkResult result = vkCreateImage(test->vk->device, &image_info, NULL, &image->image);
    if (result != VK_SUCCESS)
        return false;

    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(test->vk->device, image->image, &requirements);
    uint32_t memory_type = self_test_device_memory_type(test->vk,
                                                        requirements.memoryTypeBits);
    if (memory_type == UINT32_MAX)
        return false;
    VkMemoryAllocateInfo memory_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = memory_type,
    };
    result = vkAllocateMemory(test->vk->device, &memory_info, NULL, &image->memory);
    if (result != VK_SUCCESS)
        return false;
    result = vkBindImageMemory(test->vk->device, image->image, image->memory, 0);
    if (result != VK_SUCCESS)
        return false;

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
    result = vkCreateImageView(test->vk->device, &view_info, NULL, &image->view);
    if (result != VK_SUCCESS)
        return false;
    image->width = width;
    image->height = height;
    image->format = format;
    return true;
}

static bool self_test_create_host_buffer(struct NVSelfTest *test,
                                         struct NVSelfTestBuffer *buffer,
                                         VkDeviceSize size,
                                         VkBufferUsageFlags usage)
{
    VkBufferCreateInfo buffer_info = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = size,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
    };
    if (vkCreateBuffer(test->vk->device, &buffer_info, NULL,
                       &buffer->buffer) != VK_SUCCESS)
        return false;
    VkMemoryRequirements requirements;
    vkGetBufferMemoryRequirements(test->vk->device, buffer->buffer, &requirements);
    uint32_t memory_type = 0;
    if (!self_test_memory_type(test->vk, requirements.memoryTypeBits,
                               &memory_type, &buffer->memory_flags))
        return false;
    VkMemoryAllocateInfo memory_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = memory_type,
    };
    if (vkAllocateMemory(test->vk->device, &memory_info, NULL,
                         &buffer->memory) != VK_SUCCESS)
        return false;
    buffer->allocation_size = requirements.size;
    buffer->size = size;
    if (vkBindBufferMemory(test->vk->device, buffer->buffer,
                           buffer->memory, 0) != VK_SUCCESS)
        return false;
    if (vkMapMemory(test->vk->device, buffer->memory, 0, VK_WHOLE_SIZE,
                    0, &buffer->mapped) != VK_SUCCESS)
        return false;
    return true;
}

static VkResult self_test_flush(struct NVSelfTest *test,
                                struct NVSelfTestBuffer *buffer)
{
    if (buffer->memory_flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        return VK_SUCCESS;
    VkMappedMemoryRange range = {
        .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
        .memory = buffer->memory,
        .offset = 0,
        .size = VK_WHOLE_SIZE,
    };
    return vkFlushMappedMemoryRanges(test->vk->device, 1, &range);
}

static VkResult self_test_invalidate(struct NVSelfTest *test,
                                     struct NVSelfTestBuffer *buffer)
{
    if (buffer->memory_flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        return VK_SUCCESS;
    VkMappedMemoryRange range = {
        .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
        .memory = buffer->memory,
        .offset = 0,
        .size = VK_WHOLE_SIZE,
    };
    return vkInvalidateMappedMemoryRanges(test->vk->device, 1, &range);
}

static void self_test_destroy_buffer(pl_vulkan vk,
                                     struct NVSelfTestBuffer *buffer)
{
    if (buffer->mapped)
        vkUnmapMemory(vk->device, buffer->memory);
    if (buffer->buffer)
        vkDestroyBuffer(vk->device, buffer->buffer, NULL);
    if (buffer->memory)
        vkFreeMemory(vk->device, buffer->memory, NULL);
    memset(buffer, 0, sizeof(*buffer));
}

static void self_test_destroy_image(pl_vulkan vk,
                                    struct NVSelfTestImage *image)
{
    if (image->view)
        vkDestroyImageView(vk->device, image->view, NULL);
    if (image->image)
        vkDestroyImage(vk->device, image->image, NULL);
    if (image->memory)
        vkFreeMemory(vk->device, image->memory, NULL);
    memset(image, 0, sizeof(*image));
}

static bool self_test_create_command_pool(pl_vulkan vk, uint32_t family,
                                          VkCommandPool *pool)
{
    VkCommandPoolCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT,
        .queueFamilyIndex = family,
    };
    return vkCreateCommandPool(vk->device, &info, NULL, pool) == VK_SUCCESS;
}

static bool self_test_allocate_command(pl_vulkan vk, VkCommandPool pool,
                                       VkCommandBuffer *command)
{
    VkCommandBufferAllocateInfo info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    return vkAllocateCommandBuffers(vk->device, &info, command) == VK_SUCCESS;
}

static void self_test_wait_idle(struct NVSelfTest *test)
{
    if (test->optical_queue) {
        self_test_queue_lock(test->vk, test->optical_family);
        (void) vkQueueWaitIdle(test->optical_queue);
        self_test_queue_unlock(test->vk, test->optical_family);
    }
    if (test->copy_queue && test->copy_queue != test->optical_queue) {
        self_test_queue_lock(test->vk, test->copy_family);
        (void) vkQueueWaitIdle(test->copy_queue);
        self_test_queue_unlock(test->vk, test->copy_family);
    }
}

static void self_test_cleanup(struct NVSelfTest *test)
{
    if (test->work_submitted && !test->readback_finished)
        self_test_wait_idle(test);
    if (test->session)
        test->destroy_session(test->vk->device, test->session, NULL);
    for (int i = 0; i < 2; i++)
        self_test_destroy_image(test->vk, &test->input[i]);
    self_test_destroy_image(test->vk, &test->output);
    for (int i = 0; i < 2; i++)
        self_test_destroy_buffer(test->vk, &test->staging[i]);
    self_test_destroy_buffer(test->vk, &test->readback);
    if (test->readback_complete)
        vkDestroyFence(test->vk->device, test->readback_complete, NULL);
    if (test->upload_complete)
        vkDestroySemaphore(test->vk->device, test->upload_complete, NULL);
    if (test->optical_complete)
        vkDestroySemaphore(test->vk->device, test->optical_complete, NULL);
    if (test->optical_pool)
        vkDestroyCommandPool(test->vk->device, test->optical_pool, NULL);
    if (test->copy_pool)
        vkDestroyCommandPool(test->vk->device, test->copy_pool, NULL);
}

static uint32_t self_test_hash(uint32_t x, uint32_t y)
{
    uint32_t value = x * 0x9e3779b9u + y * 0x85ebca6bu + 0x243f6a88u;
    value ^= value >> 16;
    value *= 0x7feb352du;
    value ^= value >> 15;
    value *= 0x846ca68bu;
    value ^= value >> 16;
    return value;
}

static uint8_t self_test_luma(uint32_t x, uint32_t y, unsigned frame)
{
    const uint32_t square_x = NV_SELF_TEST_SQUARE_X +
                              (frame ? NV_SELF_TEST_SHIFT_X : 0u);
    if (x >= square_x && x < square_x + NV_SELF_TEST_SQUARE_SIZE &&
        y >= NV_SELF_TEST_SQUARE_Y &&
        y < NV_SELF_TEST_SQUARE_Y + NV_SELF_TEST_SQUARE_SIZE) {
        uint32_t local_x = x - square_x;
        uint32_t local_y = y - NV_SELF_TEST_SQUARE_Y;
        if (local_x < 2 || local_y < 2 ||
            local_x >= NV_SELF_TEST_SQUARE_SIZE - 2 ||
            local_y >= NV_SELF_TEST_SQUARE_SIZE - 2)
            return 250;
        return (uint8_t) (64u + self_test_hash(local_x, local_y) % 176u);
    }
    return (uint8_t) (16u + self_test_hash(x, y) % 32u);
}

static void self_test_fill_frame(uint8_t *pixels, unsigned frame)
{
    for (uint32_t y = 0; y < NV_SELF_TEST_HEIGHT; y++) {
        for (uint32_t x = 0; x < NV_SELF_TEST_WIDTH; x++) {
            pixels[(size_t) y * NV_SELF_TEST_WIDTH + x] =
                self_test_luma(x, y, frame);
        }
    }
}

static bool self_test_record_upload(struct NVSelfTest *test)
{
    VkCommandBufferBeginInfo begin = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    if (vkBeginCommandBuffer(test->upload_command, &begin) != VK_SUCCESS)
        return false;

    VkBufferMemoryBarrier host_writes[2] = {
        {
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_HOST_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .buffer = test->staging[0].buffer,
            .offset = 0,
            .size = VK_WHOLE_SIZE,
        },
        {
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_HOST_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .buffer = test->staging[1].buffer,
            .offset = 0,
            .size = VK_WHOLE_SIZE,
        },
    };
    vkCmdPipelineBarrier(test->upload_command,
        VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
        0, NULL, 2, host_writes, 0, NULL);

    VkImageMemoryBarrier initial_layouts[3] = {
        {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = test->input[0].image,
            .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
        },
        {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = test->input[1].image,
            .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
        },
        {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = 0,
            .dstAccessMask = test->clear_output ? VK_ACCESS_TRANSFER_WRITE_BIT : 0,
            .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = test->clear_output ? VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                                            : VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = test->output.image,
            .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
        },
    };
    vkCmdPipelineBarrier(test->upload_command,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
        0, NULL, 0, NULL, 3, initial_layouts);

    for (uint32_t i = 0; i < 2; i++) {
        VkBufferImageCopy copy = {
            .bufferOffset = 0,
            .bufferRowLength = 0,
            .bufferImageHeight = 0,
            .imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
            .imageOffset = { 0, 0, 0 },
            .imageExtent = { NV_SELF_TEST_WIDTH, NV_SELF_TEST_HEIGHT, 1 },
        };
        vkCmdCopyBufferToImage(test->upload_command, test->staging[i].buffer,
            test->input[i].image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    }

    if (test->clear_output) {
        const VkClearColorValue zero = {
            .float32 = { 0.0f, 0.0f, 0.0f, 0.0f },
        };
        const VkImageSubresourceRange range = {
            VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1,
        };
        vkCmdClearColorImage(test->upload_command, test->output.image,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &zero, 1, &range);
        VkImageMemoryBarrier output_general = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = 0,
            .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = test->output.image,
            .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
        };
        vkCmdPipelineBarrier(test->upload_command,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
            0, NULL, 0, NULL, 1, &output_general);
    }

    VkImageMemoryBarrier input_general[2] = {
        {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = 0,
            .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = test->input[0].image,
            .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
        },
        {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = 0,
            .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = test->input[1].image,
            .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
        },
    };
    vkCmdPipelineBarrier(test->upload_command,
        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
        0, NULL, 0, NULL, 2, input_general);
    return vkEndCommandBuffer(test->upload_command) == VK_SUCCESS;
}

static bool self_test_record_optical(struct NVSelfTest *test)
{
    VkCommandBufferBeginInfo begin = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    if (vkBeginCommandBuffer(test->optical_command, &begin) != VK_SUCCESS)
        return false;
    VkOpticalFlowExecuteInfoNV execute = {
        .sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_EXECUTE_INFO_NV,
        .flags = VK_OPTICAL_FLOW_EXECUTE_DISABLE_TEMPORAL_HINTS_BIT_NV,
        .regionCount = 0,
        .pRegions = NULL,
    };
    test->cmd_execute(test->optical_command, test->session, &execute);
    return vkEndCommandBuffer(test->optical_command) == VK_SUCCESS;
}

static bool self_test_record_readback(struct NVSelfTest *test,
                                     uint32_t grid_width, uint32_t grid_height)
{
    VkCommandBufferBeginInfo begin = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    if (vkBeginCommandBuffer(test->readback_command, &begin) != VK_SUCCESS)
        return false;
    VkImageMemoryBarrier to_transfer_src = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = 0,
        .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = test->output.image,
        .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
    };
    vkCmdPipelineBarrier(test->readback_command,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
        0, NULL, 0, NULL, 1, &to_transfer_src);
    VkBufferImageCopy copy = {
        .bufferOffset = 0,
        .bufferRowLength = 0,
        .bufferImageHeight = 0,
        .imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
        .imageOffset = { 0, 0, 0 },
        .imageExtent = { grid_width, grid_height, 1 },
    };
    vkCmdCopyImageToBuffer(test->readback_command, test->output.image,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, test->readback.buffer, 1, &copy);
    VkBufferMemoryBarrier to_host = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .buffer = test->readback.buffer,
        .offset = 0,
        .size = VK_WHOLE_SIZE,
    };
    vkCmdPipelineBarrier(test->readback_command,
        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
        0, NULL, 1, &to_host, 0, NULL);
    return vkEndCommandBuffer(test->readback_command) == VK_SUCCESS;
}

static float self_test_half_to_float(uint16_t value)
{
    const uint32_t sign = ((uint32_t) value & 0x8000u) << 16;
    uint32_t exponent = ((uint32_t) value >> 10) & 0x1fu;
    uint32_t mantissa = (uint32_t) value & 0x03ffu;
    uint32_t bits;
    if (exponent == 0) {
        if (mantissa == 0) {
            bits = sign;
        } else {
            int unbiased_exponent = -14;
            while ((mantissa & 0x0400u) == 0) {
                mantissa <<= 1;
                unbiased_exponent--;
            }
            mantissa &= 0x03ffu;
            bits = sign | ((uint32_t) (unbiased_exponent + 127) << 23) |
                   (mantissa << 13);
        }
    } else if (exponent == 0x1fu) {
        bits = sign | 0x7f800000u | (mantissa << 13);
    } else {
        bits = sign | ((exponent + (127u - 15u)) << 23) |
               (mantissa << 13);
    }
    float result;
    memcpy(&result, &bits, sizeof(result));
    return result;
}

static bool self_test_bind_session(struct NVSelfTest *test)
{
    const VkImageLayout layout = VK_IMAGE_LAYOUT_GENERAL;
    VkResult result = test->bind_session_image(test->vk->device, test->session,
        VK_OPTICAL_FLOW_SESSION_BINDING_POINT_INPUT_NV,
        test->input[0].view, layout);
    if (result != VK_SUCCESS)
        return false;
    result = test->bind_session_image(test->vk->device, test->session,
        VK_OPTICAL_FLOW_SESSION_BINDING_POINT_REFERENCE_NV,
        test->input[1].view, layout);
    if (result != VK_SUCCESS)
        return false;
    result = test->bind_session_image(test->vk->device, test->session,
        VK_OPTICAL_FLOW_SESSION_BINDING_POINT_FLOW_VECTOR_NV,
        test->output.view, layout);
    return result == VK_SUCCESS;
}

static bool self_test_create_session(struct NVSelfTest *test)
{
    VkOpticalFlowSessionCreateInfoNV info = {
        .sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_SESSION_CREATE_INFO_NV,
        .width = NV_SELF_TEST_WIDTH,
        .height = NV_SELF_TEST_HEIGHT,
        .imageFormat = NV_SELF_TEST_INPUT_FORMAT,
        .flowVectorFormat = NV_SELF_TEST_VECTOR_FORMAT,
        .costFormat = VK_FORMAT_UNDEFINED,
        .outputGridSize = NV_SELF_TEST_GRID,
        .hintGridSize = VK_OPTICAL_FLOW_GRID_SIZE_UNKNOWN_NV,
        .performanceLevel = VK_OPTICAL_FLOW_PERFORMANCE_LEVEL_MEDIUM_NV,
        .flags = 0,
    };
    VkResult result = test->create_session(test->vk->device, &info, NULL,
                                            &test->session);
    if (result != VK_SUCCESS) {
        self_test_log(test->gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=session state=failed vk_result=%d",
            result);
        return false;
    }
    if (!self_test_bind_session(test)) {
        self_test_log(test->gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=session_bind state=failed reason=bind_image_failed");
        return false;
    }
    return true;
}

static bool self_test_setup_commands(struct NVSelfTest *test)
{
    if (!self_test_create_command_pool(test->vk, test->copy_family,
                                       &test->copy_pool) ||
        !self_test_create_command_pool(test->vk, test->optical_family,
                                       &test->optical_pool) ||
        !self_test_allocate_command(test->vk, test->copy_pool,
                                    &test->upload_command) ||
        !self_test_allocate_command(test->vk, test->copy_pool,
                                    &test->readback_command) ||
        !self_test_allocate_command(test->vk, test->optical_pool,
                                    &test->optical_command))
        return false;
    VkSemaphoreCreateInfo semaphore_info = {
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
    };
    if (vkCreateSemaphore(test->vk->device, &semaphore_info, NULL,
                          &test->upload_complete) != VK_SUCCESS ||
        vkCreateSemaphore(test->vk->device, &semaphore_info, NULL,
                          &test->optical_complete) != VK_SUCCESS)
        return false;
    VkFenceCreateInfo fence_info = {
        .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
    };
    return vkCreateFence(test->vk->device, &fence_info, NULL,
                         &test->readback_complete) == VK_SUCCESS;
}

static bool self_test_dump_grid(struct NVSelfTest *test,
                                uint32_t grid_width, uint32_t grid_height)
{
    VkResult result = self_test_invalidate(test, &test->readback);
    if (result != VK_SUCCESS) {
        self_test_log(test->gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=readback_invalidate state=failed vk_result=%d",
            result);
        return false;
    }
    const uint16_t *halves = (const uint16_t *) test->readback.mapped;
    self_test_log(test->gpu, PL_LOG_INFO,
        "MPVFLOW_DIAGNOSTIC component=nv-self-test event=grid state=readback format=R16G16B16A16_SFLOAT width=%u height=%u channels=RGBA half_float=1 expected_input_shift_px=(%u,0) square0=(%u,%u,%u,%u) square1=(%u,%u,%u,%u)",
        grid_width, grid_height, NV_SELF_TEST_SHIFT_X,
        NV_SELF_TEST_SQUARE_X, NV_SELF_TEST_SQUARE_Y,
        NV_SELF_TEST_SQUARE_SIZE, NV_SELF_TEST_SQUARE_SIZE,
        NV_SELF_TEST_SQUARE_X + NV_SELF_TEST_SHIFT_X,
        NV_SELF_TEST_SQUARE_Y, NV_SELF_TEST_SQUARE_SIZE,
        NV_SELF_TEST_SQUARE_SIZE);
    for (uint32_t y = 0; y < grid_height; y++) {
        for (uint32_t x = 0; x < grid_width; x++) {
            uint16_t raw[4];
            const size_t offset = ((size_t) y * grid_width + x) * 4;
            memcpy(raw, halves + offset, sizeof(raw));
            self_test_log(test->gpu, PL_LOG_INFO,
                "MPVFLOW_DIAGNOSTIC component=nv-self-test event=grid_cell x=%u y=%u rgba=(%.6g,%.6g,%.6g,%.6g) half_bits=(%04x,%04x,%04x,%04x) expected_vector_px_near_input_center=(%u,0)",
                x, y,
                self_test_half_to_float(raw[0]),
                self_test_half_to_float(raw[1]),
                self_test_half_to_float(raw[2]),
                self_test_half_to_float(raw[3]),
                raw[0], raw[1], raw[2], raw[3],
                NV_SELF_TEST_SHIFT_X);
        }
    }
    self_test_log(test->gpu, PL_LOG_INFO,
        "MPVFLOW_DIAGNOSTIC component=nv-self-test event=complete state=readback_complete dispatch=1 output_channels_zero_initialized=%d interpretation=manual_validation_required production_rgba16f_decoder_enabled=0",
        test->clear_output);
    return true;
}

static bool run_nv_self_test(pl_gpu gpu)
{
    if (!gpu) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=start state=unavailable reason=gpu_missing");
        return false;
    }
    struct NVSelfTest test = { .gpu = gpu, .vk = pl_vulkan_get(gpu) };
    if (!test.vk || !self_test_extension_enabled(test.vk) ||
        !self_test_feature_enabled(test.vk)) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=start state=unavailable reason=extension_or_feature_not_enabled");
        return false;
    }
    if (!self_test_load_functions(&test)) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=start state=unavailable reason=extension_function_unavailable");
        return false;
    }
    self_test_log(gpu, PL_LOG_INFO,
        "MPVFLOW_DIAGNOSTIC component=nv-self-test event=start state=running trigger=manual_debug_only normal_playback_adapter_unchanged=1 input=%ux%u expected_shift_px=(%u,0)",
        NV_SELF_TEST_WIDTH, NV_SELF_TEST_HEIGHT, NV_SELF_TEST_SHIFT_X);

    if (!self_test_format_listed(&test, VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV,
                                 NV_SELF_TEST_INPUT_FORMAT)) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=format_check state=unsupported reason=R8_UNORM_not_listed_for_input");
        return false;
    }
    const bool rgba16f_listed = self_test_format_listed(&test,
        VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, NV_SELF_TEST_VECTOR_FORMAT);
    if (!rgba16f_listed) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=format_check state=unsupported reason=RGBA16F_not_listed_for_optical_output format=0x%x",
            (unsigned) NV_SELF_TEST_VECTOR_FORMAT);
        return false;
    }

    VkPhysicalDeviceOpticalFlowPropertiesNV flow_properties = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_PROPERTIES_NV,
    };
    VkPhysicalDeviceProperties2 device_properties = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
        .pNext = &flow_properties,
    };
    test.get_device_properties(test.vk->phys_device, &device_properties);
    if (NV_SELF_TEST_WIDTH < flow_properties.minWidth ||
        NV_SELF_TEST_HEIGHT < flow_properties.minHeight ||
        NV_SELF_TEST_WIDTH > flow_properties.maxWidth ||
        NV_SELF_TEST_HEIGHT > flow_properties.maxHeight ||
        !(flow_properties.supportedOutputGridSizes & NV_SELF_TEST_GRID)) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=properties state=unsupported reason=test_extent_or_grid_unsupported width=%u height=%u min=%ux%u max=%ux%u output_grid_flags=0x%x",
            NV_SELF_TEST_WIDTH, NV_SELF_TEST_HEIGHT,
            flow_properties.minWidth, flow_properties.minHeight,
            flow_properties.maxWidth, flow_properties.maxHeight,
            flow_properties.supportedOutputGridSizes);
        return false;
    }

    VkImageFormatProperties input_props = {0};
    VkImageFormatProperties output_transfer_props = {0};
    VkImageFormatProperties output_sampled_props = {0};
    VkImageFormatProperties output_combined_props = {0};
    VkImageFormatProperties output_clear_props = {0};
    VkImageFormatProperties output_sampled_clear_props = {0};
    VkImageFormatProperties input_storage_props = {0};
    VkImageFormatProperties input_storage_transfer_props = {0};
    VkImageFormatProperties input_color_props = {0};
    VkImageFormatProperties input_color_transfer_props = {0};
    VkImageFormatProperties output_storage_props = {0};
    VkImageFormatProperties output_storage_transfer_src_props = {0};
    VkImageFormatProperties output_storage_transfer_dst_props = {0};
    VkImageFormatProperties output_storage_transfer_both_props = {0};
    const VkImageUsageFlags input_usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    const VkImageUsageFlags transfer_usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    const VkImageUsageFlags sampled_usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    const VkImageUsageFlags storage_usage = VK_IMAGE_USAGE_STORAGE_BIT;
    const VkImageUsageFlags color_attachment_usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    VkResult input_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_INPUT_FORMAT, input_usage,
        VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV, &input_props);
    VkResult transfer_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_VECTOR_FORMAT, transfer_usage,
        VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, &output_transfer_props);
    VkResult sampled_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_VECTOR_FORMAT, sampled_usage,
        VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, &output_sampled_props);
    VkResult combined_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_VECTOR_FORMAT, transfer_usage | sampled_usage,
        VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, &output_combined_props);
    VkResult clear_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_VECTOR_FORMAT,
        transfer_usage | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, &output_clear_props);
    VkResult sampled_clear_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_VECTOR_FORMAT,
        transfer_usage | sampled_usage | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, &output_sampled_clear_props);
    VkResult input_storage_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_INPUT_FORMAT, storage_usage,
        VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV, &input_storage_props);
    VkResult input_storage_transfer_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_INPUT_FORMAT, storage_usage | input_usage,
        VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV, &input_storage_transfer_props);
    VkResult input_color_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_INPUT_FORMAT, color_attachment_usage,
        VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV, &input_color_props);
    VkResult input_color_transfer_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_INPUT_FORMAT, color_attachment_usage | input_usage,
        VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV, &input_color_transfer_props);
    VkResult output_storage_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_VECTOR_FORMAT, storage_usage,
        VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, &output_storage_props);
    VkResult output_storage_transfer_src_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_VECTOR_FORMAT, storage_usage | transfer_usage,
        VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, &output_storage_transfer_src_props);
    VkResult output_storage_transfer_dst_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_VECTOR_FORMAT, storage_usage | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, &output_storage_transfer_dst_props);
    VkResult output_storage_transfer_both_result = self_test_query_image_usage(&test,
        NV_SELF_TEST_VECTOR_FORMAT,
        storage_usage | transfer_usage | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV, &output_storage_transfer_both_props);
    const uint32_t grid_width = NV_SELF_TEST_WIDTH / 8u;
    const uint32_t grid_height = NV_SELF_TEST_HEIGHT / 8u;
    const bool sampled_extent_usable = sampled_result == VK_SUCCESS &&
        self_test_image_extent_supported(&output_sampled_props,
                                         grid_width, grid_height);
    const bool sampled_transfer_extent_usable = combined_result == VK_SUCCESS &&
        self_test_image_extent_supported(&output_combined_props,
                                         grid_width, grid_height);
    const bool clear_extent_usable = clear_result == VK_SUCCESS &&
        self_test_image_extent_supported(&output_clear_props,
                                         grid_width, grid_height);
    const bool sampled_clear_extent_usable = sampled_clear_result == VK_SUCCESS &&
        self_test_image_extent_supported(&output_sampled_clear_props,
                                         grid_width, grid_height);
    const bool input_storage_extent_usable = input_storage_result == VK_SUCCESS &&
        self_test_image_extent_supported(&input_storage_props,
            NV_SELF_TEST_WIDTH, NV_SELF_TEST_HEIGHT);
    const bool input_storage_transfer_extent_usable =
        input_storage_transfer_result == VK_SUCCESS &&
        self_test_image_extent_supported(&input_storage_transfer_props,
            NV_SELF_TEST_WIDTH, NV_SELF_TEST_HEIGHT);
    const bool input_color_extent_usable = input_color_result == VK_SUCCESS &&
        self_test_image_extent_supported(&input_color_props,
            NV_SELF_TEST_WIDTH, NV_SELF_TEST_HEIGHT);
    const bool input_color_transfer_extent_usable =
        input_color_transfer_result == VK_SUCCESS &&
        self_test_image_extent_supported(&input_color_transfer_props,
            NV_SELF_TEST_WIDTH, NV_SELF_TEST_HEIGHT);
    const bool output_storage_extent_usable = output_storage_result == VK_SUCCESS &&
        self_test_image_extent_supported(&output_storage_props,
            grid_width, grid_height);
    const bool output_storage_transfer_src_extent_usable =
        output_storage_transfer_src_result == VK_SUCCESS &&
        self_test_image_extent_supported(&output_storage_transfer_src_props,
            grid_width, grid_height);
    const bool output_storage_transfer_dst_extent_usable =
        output_storage_transfer_dst_result == VK_SUCCESS &&
        self_test_image_extent_supported(&output_storage_transfer_dst_props,
            grid_width, grid_height);
    const bool output_storage_transfer_both_extent_usable =
        output_storage_transfer_both_result == VK_SUCCESS &&
        self_test_image_extent_supported(&output_storage_transfer_both_props,
            grid_width, grid_height);
    VkFormatFeatureFlags2 input_format_features = 0;
    VkFormatFeatureFlags2 vector_format_features = 0;
    const bool input_feature_query = self_test_query_format_features(
        &test, NV_SELF_TEST_INPUT_FORMAT, &input_format_features);
    bool vector_feature_supported = false;
    const bool vector_feature_query = self_test_query_vector_format_feature(
        &test, &vector_format_features, &vector_feature_supported);
    bool input_flow_image_feature = false;
    bool input_storage_image_feature = false;
    bool input_color_attachment_feature = false;
    bool output_flow_vector_feature = false;
    bool output_sampled_image_feature = false;
    bool output_storage_image_feature = false;
    bool storage_image_extended_formats_enabled = test.vk->features &&
        test.vk->features->features.shaderStorageImageExtendedFormats;
#if defined(VK_VERSION_1_3) && defined(VK_NV_optical_flow)
    input_flow_image_feature = input_feature_query &&
        (input_format_features & VK_FORMAT_FEATURE_2_OPTICAL_FLOW_IMAGE_BIT_NV);
    input_storage_image_feature = input_feature_query &&
        (input_format_features & VK_FORMAT_FEATURE_2_STORAGE_IMAGE_BIT);
    input_color_attachment_feature = input_feature_query &&
        (input_format_features & VK_FORMAT_FEATURE_2_COLOR_ATTACHMENT_BIT);
    output_flow_vector_feature = vector_feature_query &&
        (vector_format_features & VK_FORMAT_FEATURE_2_OPTICAL_FLOW_VECTOR_BIT_NV);
    output_sampled_image_feature = vector_feature_query &&
        (vector_format_features & VK_FORMAT_FEATURE_2_SAMPLED_IMAGE_BIT);
    output_storage_image_feature = vector_feature_query &&
        (vector_format_features & VK_FORMAT_FEATURE_2_STORAGE_IMAGE_BIT);
#endif
    self_test_log(gpu, (input_result == VK_SUCCESS && transfer_result == VK_SUCCESS)
                      ? PL_LOG_INFO : PL_LOG_WARN,
        "MPVFLOW_DIAGNOSTIC component=nv-self-test event=image_usage state=queried format=R16G16B16A16_SFLOAT listed=%d input_R8_transfer_dst_result=%d output_transfer_src_result=%d output_sampled_result=%d output_sampled_transfer_src_result=%d output_transfer_src_dst_result=%d output_sampled_transfer_src_dst_result=%d sampled_extent_usable=%d sampled_transfer_extent_usable=%d output_zero_init_supported=%d sampled_zero_init_supported=%d output_grid=8x8",
        rgba16f_listed, input_result, transfer_result, sampled_result,
        combined_result, clear_result, sampled_clear_result,
        sampled_extent_usable, sampled_transfer_extent_usable,
        clear_extent_usable, sampled_clear_extent_usable);
    self_test_log(gpu,
        (input_storage_result == VK_SUCCESS || input_color_result == VK_SUCCESS ||
         output_storage_result == VK_SUCCESS) ? PL_LOG_INFO : PL_LOG_WARN,
        "MPVFLOW_DIAGNOSTIC component=nv-self-test event=alternate_usage state=queried role=input format=R8_UNORM storage_result=%d storage_extent_usable=%d storage_transfer_dst_result=%d storage_transfer_dst_extent_usable=%d color_attachment_result=%d color_attachment_extent_usable=%d color_attachment_transfer_dst_result=%d color_attachment_transfer_dst_extent_usable=%d optical_flow_image_feature=%d storage_image_feature=%d color_attachment_feature=%d format_features=0x%llx storage_extended_formats_enabled=%d",
        input_storage_result, input_storage_extent_usable,
        input_storage_transfer_result, input_storage_transfer_extent_usable,
        input_color_result, input_color_extent_usable,
        input_color_transfer_result, input_color_transfer_extent_usable,
        input_flow_image_feature, input_storage_image_feature,
        input_color_attachment_feature,
        (unsigned long long) input_format_features,
        storage_image_extended_formats_enabled);
    self_test_log(gpu,
        (output_storage_result == VK_SUCCESS) ? PL_LOG_INFO : PL_LOG_WARN,
        "MPVFLOW_DIAGNOSTIC component=nv-self-test event=alternate_usage state=queried role=output format=R16G16B16A16_SFLOAT storage_result=%d storage_extent_usable=%d storage_transfer_src_result=%d storage_transfer_src_extent_usable=%d storage_transfer_dst_result=%d storage_transfer_dst_extent_usable=%d storage_transfer_src_dst_result=%d storage_transfer_src_dst_extent_usable=%d optical_flow_vector_feature=%d sampled_image_feature=%d storage_image_feature=%d format_features=0x%llx",
        output_storage_result, output_storage_extent_usable,
        output_storage_transfer_src_result,
        output_storage_transfer_src_extent_usable,
        output_storage_transfer_dst_result,
        output_storage_transfer_dst_extent_usable,
        output_storage_transfer_both_result,
        output_storage_transfer_both_extent_usable,
        output_flow_vector_feature, output_sampled_image_feature,
        output_storage_image_feature,
        (unsigned long long) vector_format_features);
    self_test_log(gpu,
        (input_feature_query && vector_feature_query) ? PL_LOG_INFO : PL_LOG_WARN,
        "MPVFLOW_DIAGNOSTIC component=nv-self-test event=format_feature state=%s input_optical_flow_image_feature=%d output_optical_flow_vector_feature=%d output_flow_vector_and_sampled_feature=%d input_optimal_tiling_features=0x%llx output_optimal_tiling_features=0x%llx dispatch_feature_gate=%d",
        (input_feature_query && vector_feature_query) ? "queried" : "unavailable",
        input_flow_image_feature, output_flow_vector_feature,
        vector_feature_supported,
        (unsigned long long) input_format_features,
        (unsigned long long) vector_format_features,
        input_flow_image_feature && output_flow_vector_feature);
    if (!input_feature_query || !vector_feature_query ||
        !input_flow_image_feature || !output_flow_vector_feature) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=dispatch state=not_attempted reason=required_nv_optical_format_feature_missing input_feature_query=%d input_image_feature=%d output_feature_query=%d output_vector_feature=%d",
            input_feature_query, input_flow_image_feature,
            vector_feature_query, output_flow_vector_feature);
        return false;
    }
    if (input_result != VK_SUCCESS || transfer_result != VK_SUCCESS ||
        !self_test_image_extent_supported(&input_props,
            NV_SELF_TEST_WIDTH, NV_SELF_TEST_HEIGHT) ||
        !self_test_image_extent_supported(&output_transfer_props,
            NV_SELF_TEST_WIDTH / 8u, NV_SELF_TEST_HEIGHT / 8u)) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=image_usage state=unsupported reason=required_transfer_usage_or_extent_unavailable input_result=%d output_transfer_src_result=%d",
            input_result, transfer_result);
        return false;
    }

    if (!self_test_choose_queues(&test)) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=queue state=unavailable reason=optical_or_libplacebo_transfer_queue_unavailable");
        return false;
    }
    VkImageUsageFlags output_usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    test.clear_output = clear_extent_usable;
    if (sampled_clear_extent_usable)
        output_usage |= VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    else if (test.clear_output)
        output_usage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    else if (sampled_transfer_extent_usable)
        output_usage |= VK_IMAGE_USAGE_SAMPLED_BIT;
    self_test_log(gpu, PL_LOG_INFO,
        "MPVFLOW_DIAGNOSTIC component=nv-self-test event=queue state=selected optical_family=%u transfer_family=%u output_created_usage=0x%x output_channels_zero_initialized=%d sampled_only_result=%d sampled_transfer_result=%d transfer_clear_result=%d sampled_transfer_clear_result=%d",
        test.optical_family, test.copy_family, output_usage,
        test.clear_output, sampled_result, combined_result, clear_result,
        sampled_clear_result);

    const size_t frame_bytes = (size_t) NV_SELF_TEST_WIDTH * NV_SELF_TEST_HEIGHT;
    uint8_t *frames[2] = { malloc(frame_bytes), malloc(frame_bytes) };
    if (!frames[0] || !frames[1]) {
        free(frames[0]);
        free(frames[1]);
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=setup state=failed reason=host_frame_allocation_failed");
        return false;
    }
    self_test_fill_frame(frames[0], 0);
    self_test_fill_frame(frames[1], 1);

    bool success = false;
    if (!self_test_create_image(&test, &test.input[0],
            NV_SELF_TEST_WIDTH, NV_SELF_TEST_HEIGHT,
            NV_SELF_TEST_INPUT_FORMAT, input_usage,
            VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV) ||
        !self_test_create_image(&test, &test.input[1],
            NV_SELF_TEST_WIDTH, NV_SELF_TEST_HEIGHT,
            NV_SELF_TEST_INPUT_FORMAT, input_usage,
            VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV) ||
        !self_test_create_image(&test, &test.output,
            grid_width, grid_height, NV_SELF_TEST_VECTOR_FORMAT,
            output_usage, VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV)) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=setup state=failed reason=vk_image_or_view_creation_failed");
        goto cleanup;
    }
    if (!self_test_create_host_buffer(&test, &test.staging[0],
            (VkDeviceSize) frame_bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT) ||
        !self_test_create_host_buffer(&test, &test.staging[1],
            (VkDeviceSize) frame_bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT) ||
        !self_test_create_host_buffer(&test, &test.readback,
            (VkDeviceSize) grid_width * grid_height * 4u * sizeof(uint16_t),
            VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=setup state=failed reason=host_visible_transfer_buffer_creation_failed");
        goto cleanup;
    }
    memcpy(test.staging[0].mapped, frames[0], frame_bytes);
    memcpy(test.staging[1].mapped, frames[1], frame_bytes);
    if (self_test_flush(&test, &test.staging[0]) != VK_SUCCESS ||
        self_test_flush(&test, &test.staging[1]) != VK_SUCCESS) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=setup state=failed reason=staging_flush_failed");
        goto cleanup;
    }
    if (!self_test_create_session(&test))
        goto cleanup;
    if (!self_test_setup_commands(&test)) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=setup state=failed reason=command_semaphore_or_fence_creation_failed");
        goto cleanup;
    }
    if (!self_test_record_upload(&test) || !self_test_record_optical(&test) ||
        !self_test_record_readback(&test, grid_width, grid_height)) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=record state=failed reason=command_buffer_recording_failed");
        goto cleanup;
    }

    VkSubmitInfo upload_submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1,
        .pCommandBuffers = &test.upload_command,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &test.upload_complete,
    };
    self_test_queue_lock(test.vk, test.copy_family);
    VkResult result = vkQueueSubmit(test.copy_queue, 1, &upload_submit, VK_NULL_HANDLE);
    self_test_queue_unlock(test.vk, test.copy_family);
    if (result != VK_SUCCESS) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=upload state=failed vk_result=%d",
            result);
        goto cleanup;
    }
    test.work_submitted = true;

    const VkPipelineStageFlags all_commands = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo optical_submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &test.upload_complete,
        .pWaitDstStageMask = &all_commands,
        .commandBufferCount = 1,
        .pCommandBuffers = &test.optical_command,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &test.optical_complete,
    };
    self_test_queue_lock(test.vk, test.optical_family);
    result = vkQueueSubmit(test.optical_queue, 1, &optical_submit, VK_NULL_HANDLE);
    self_test_queue_unlock(test.vk, test.optical_family);
    if (result != VK_SUCCESS) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=dispatch state=failed vk_result=%d",
            result);
        goto cleanup;
    }

    VkSubmitInfo readback_submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &test.optical_complete,
        .pWaitDstStageMask = &all_commands,
        .commandBufferCount = 1,
        .pCommandBuffers = &test.readback_command,
    };
    self_test_queue_lock(test.vk, test.copy_family);
    result = vkQueueSubmit(test.copy_queue, 1, &readback_submit,
                           test.readback_complete);
    self_test_queue_unlock(test.vk, test.copy_family);
    if (result != VK_SUCCESS) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=readback state=submit_failed vk_result=%d",
            result);
        goto cleanup;
    }
    if (vkWaitForFences(test.vk->device, 1, &test.readback_complete,
                        VK_TRUE, UINT64_MAX) != VK_SUCCESS) {
        self_test_log(gpu, PL_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=nv-self-test event=readback state=wait_failed");
        goto cleanup;
    }
    test.readback_finished = true;
    success = self_test_dump_grid(&test, grid_width, grid_height);

cleanup:
    self_test_cleanup(&test);
    free(frames[0]);
    free(frames[1]);
    return success;
}
#endif

bool mpvflow_vulkan_optical_debug_self_test(pl_gpu gpu)
{
#if defined(MPVFLOW_ENABLE_NV_SELF_TEST) && defined(VK_NV_optical_flow)
    return run_nv_self_test(gpu);
#else
    if (gpu && gpu->log && gpu->log->params.log_cb) {
        char message[] = "MPVFLOW_DIAGNOSTIC component=nv-self-test event=start state=unavailable reason=debug_self_test_not_compiled";
        gpu->log->params.log_cb(gpu->log->params.log_priv, PL_LOG_WARN, message);
    }
    (void) gpu;
    return false;
#endif
}
