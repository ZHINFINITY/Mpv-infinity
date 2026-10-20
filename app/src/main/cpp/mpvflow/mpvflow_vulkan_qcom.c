/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
#include "mpvflow_vulkan_qcom.h"

#include <libplacebo/gpu.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static void qcom_set_reason(char *reason, size_t size, const char *format, ...)
{
    if (!reason || !size)
        return;
    va_list args;
    va_start(args, format);
    vsnprintf(reason, size, format, args);
    va_end(args);
}

#if defined(VK_QCOM_image_processing) && defined(VK_QCOM_image_processing2)

#include "mpvflow_qcom_seed_spv.h"

#define QCOM_SEED_MAX_WINDOW 32u
#define QCOM_COMMAND_SLOTS 3u
#define QCOM_HELD_IMAGE_COUNT 4u
#define QCOM_PUSH_CONSTANT_BYTES 32u

struct QcomImage {
    VkImage image;
    VkImageView view;
    VkDeviceMemory memory;
    pl_tex texture;
};

struct QcomCommandSlot {
    VkCommandBuffer command;
    VkFence fence;
    VkQueryPool timestamp_queries;
    bool timestamp_pending;
};

struct QcomSeedPush {
    uint32_t image_grid[4];
    uint32_t search[4];
};

struct MPVFlowVulkanQcom {
    pl_gpu gpu;
    pl_vulkan vk;
    VkQueue queue;
    uint32_t queue_family;
    VkCommandPool command_pool;
    struct QcomCommandSlot slots[QCOM_COMMAND_SLOTS];
    VkDescriptorSetLayout descriptor_layout;
    VkDescriptorPool descriptor_pool;
    VkDescriptorSet descriptor_sets[2];
    VkPipelineLayout pipeline_layout;
    VkPipeline pipeline;
    VkSampler sampler;
    VkSemaphore ready_semaphore;
    VkSemaphore done_semaphore;
    uint64_t ready_value;
    uint64_t done_value;
    uint64_t dispatches;
    uint32_t slot_cursor;
    uint32_t timestamp_valid_bits;
    double timestamp_period_ns;
    bool timestamp_compute_and_graphics;
    bool timestamps_supported;
    uint64_t cpu_fence_poll_total_ns, cpu_fence_poll_max_ns, cpu_fence_poll_samples;
    uint64_t cpu_flush_total_ns, cpu_flush_max_ns, cpu_flush_samples;
    uint64_t cpu_record_total_ns, cpu_record_max_ns, cpu_record_samples;
    uint64_t cpu_queue_lock_total_ns, cpu_queue_lock_max_ns, cpu_queue_lock_samples;
    uint64_t cpu_queue_submit_total_ns, cpu_queue_submit_max_ns, cpu_queue_submit_samples;
    double gpu_dispatch_total_ns, gpu_dispatch_max_ns;
    uint64_t gpu_dispatch_samples, gpu_dispatch_unresolved;
    VkExtent2D max_region;
    VkExtent2D window;
    struct QcomImage stage_luma[2];
    struct QcomImage block_luma[2];
    struct QcomImage seed[2];
    VkImageLayout block_luma_layout[2];
    int width;
    int height;
    int grid_width;
    int grid_height;
    int block_size;
    bool disabled;
};

static void qcom_log(pl_gpu gpu, enum pl_log_level level, const char *format, ...)
{
    if (!gpu || !gpu->log || !gpu->log->params.log_cb)
        return;
    char message[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    gpu->log->params.log_cb(gpu->log->params.log_priv, level, message);
}

static uint64_t qcom_clock_ns(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return (uint64_t) ts.tv_sec * 1000000000ull + (uint64_t) ts.tv_nsec;
}
static void qcom_add_elapsed(uint64_t start, uint64_t end,
                             uint64_t *total, uint64_t *maximum,
                             uint64_t *samples)
{
    if (!start || end <= start)
        return;
    const uint64_t elapsed = end - start;
    *total += elapsed;
    if (elapsed > *maximum)
        *maximum = elapsed;
    (*samples)++;
}
static void qcom_read_timestamp_caps(MPVFlowVulkanQcom *qcom)
{
    VkPhysicalDeviceProperties properties = {0};
    vkGetPhysicalDeviceProperties(qcom->vk->phys_device, &properties);
    qcom->timestamp_period_ns = properties.limits.timestampPeriod;
    qcom->timestamp_compute_and_graphics =
        properties.limits.timestampComputeAndGraphics == VK_TRUE;
    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(qcom->vk->phys_device, &count, NULL);
    if (count && qcom->queue_family < count) {
        VkQueueFamilyProperties *families = calloc(count, sizeof(*families));
        if (families) {
            vkGetPhysicalDeviceQueueFamilyProperties(qcom->vk->phys_device,
                                                     &count, families);
            const VkQueueFamilyProperties *family = &families[qcom->queue_family];
            if (family->queueFlags & VK_QUEUE_COMPUTE_BIT)
                qcom->timestamp_valid_bits = family->timestampValidBits;
            free(families);
        }
    }
    qcom->timestamps_supported = qcom->timestamp_period_ns > 0.0 &&
        qcom->timestamp_compute_and_graphics && qcom->timestamp_valid_bits > 0;
    qcom_log(qcom->gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=qcom_gpu_timing state=%s source=vkCmdWriteTimestamp "
             "timestamp_period_ns=%.6f timestamp_compute_and_graphics=%d queue_family=%u "
             "queue_timestamp_valid_bits=%u polling=nonblocking_after_slot_fence",
             qcom->timestamps_supported ? "supported_pending_query_pool" : "unsupported",
             qcom->timestamp_period_ns, qcom->timestamp_compute_and_graphics,
             qcom->queue_family, qcom->timestamp_valid_bits);
}
static void qcom_collect_timestamp(MPVFlowVulkanQcom *qcom,
                                  struct QcomCommandSlot *slot)
{
    if (!qcom->timestamps_supported || !slot->timestamp_queries ||
        !slot->timestamp_pending)
        return;
    uint64_t values[4] = {0};
    const VkResult result = vkGetQueryPoolResults(
        qcom->vk->device, slot->timestamp_queries, 0, 2, sizeof(values), values,
        sizeof(uint64_t) * 2,
        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
    slot->timestamp_pending = false;
    if (result != VK_SUCCESS || !values[1] || !values[3]) {
        qcom->gpu_dispatch_unresolved++;
        return;
    }
    const uint64_t mask = qcom->timestamp_valid_bits >= 64 ? UINT64_MAX :
        ((UINT64_C(1) << qcom->timestamp_valid_bits) - 1);
    const uint64_t ticks = (values[2] - values[0]) & mask;
    const double elapsed_ns = (double) ticks * qcom->timestamp_period_ns;
    qcom->gpu_dispatch_total_ns += elapsed_ns;
    if (elapsed_ns > qcom->gpu_dispatch_max_ns)
        qcom->gpu_dispatch_max_ns = elapsed_ns;
    qcom->gpu_dispatch_samples++;
}
static void qcom_report_dispatch_timing(MPVFlowVulkanQcom *qcom)
{
    qcom_log(qcom->gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=qcom_dispatch_timing window_success_dispatches=120 "
             "fence_poll_scope=all_nonblocking_slot_polls_since_previous_report "
             "fence_status_avg_us=%.2f fence_status_max_us=%.2f fence_status_samples=%llu "
             "pl_gpu_flush_avg_us=%.2f command_record_avg_us=%.2f command_record_max_us=%.2f "
             "queue_lock_avg_us=%.2f queue_submit_avg_us=%.2f queue_submit_max_us=%.2f "
             "queue_submit_samples=%llu vkQueueSubmit_result=VK_SUCCESS "
             "gpu_dispatch_avg_us=%.2f gpu_dispatch_max_us=%.2f gpu_timestamp_samples=%llu "
             "gpu_timestamp_unresolved=%llu timestamp_supported=%d timestamp_valid_bits=%u "
             "timestamp_period_ns=%.6f gpu_timestamp_scope=two_qcom2_block_match_commands_only "
             "gpu_timestamp_alignment=completed_slot_fence_results_lagged_nonblocking=1 "
             "metric=host_cpu_and_gpu_time_separate",
             qcom->cpu_fence_poll_samples ?
                 (double) qcom->cpu_fence_poll_total_ns / qcom->cpu_fence_poll_samples / 1000.0 : 0.0,
             qcom->cpu_fence_poll_max_ns / 1000.0,
             (unsigned long long) qcom->cpu_fence_poll_samples,
             qcom->cpu_flush_samples ?
                 (double) qcom->cpu_flush_total_ns / qcom->cpu_flush_samples / 1000.0 : 0.0,
             qcom->cpu_record_samples ?
                 (double) qcom->cpu_record_total_ns / qcom->cpu_record_samples / 1000.0 : 0.0,
             qcom->cpu_record_max_ns / 1000.0,
             qcom->cpu_queue_lock_samples ?
                 (double) qcom->cpu_queue_lock_total_ns / qcom->cpu_queue_lock_samples / 1000.0 : 0.0,
             qcom->cpu_queue_submit_samples ?
                 (double) qcom->cpu_queue_submit_total_ns / qcom->cpu_queue_submit_samples / 1000.0 : 0.0,
             qcom->cpu_queue_submit_max_ns / 1000.0,
             (unsigned long long) qcom->cpu_queue_submit_samples,
             qcom->gpu_dispatch_samples ?
                 qcom->gpu_dispatch_total_ns / qcom->gpu_dispatch_samples / 1000.0 : 0.0,
             qcom->gpu_dispatch_max_ns / 1000.0,
             (unsigned long long) qcom->gpu_dispatch_samples,
             (unsigned long long) qcom->gpu_dispatch_unresolved,
             qcom->timestamps_supported, qcom->timestamp_valid_bits,
             qcom->timestamp_period_ns);
    qcom->cpu_fence_poll_total_ns = qcom->cpu_fence_poll_max_ns = qcom->cpu_fence_poll_samples = 0;
    qcom->cpu_flush_total_ns = qcom->cpu_flush_max_ns = qcom->cpu_flush_samples = 0;
    qcom->cpu_record_total_ns = qcom->cpu_record_max_ns = qcom->cpu_record_samples = 0;
    qcom->cpu_queue_lock_total_ns = qcom->cpu_queue_lock_max_ns = qcom->cpu_queue_lock_samples = 0;
    qcom->cpu_queue_submit_total_ns = qcom->cpu_queue_submit_max_ns = qcom->cpu_queue_submit_samples = 0;
    qcom->gpu_dispatch_total_ns = qcom->gpu_dispatch_max_ns = 0.0;
    qcom->gpu_dispatch_samples = qcom->gpu_dispatch_unresolved = 0;
}
static bool qcom_extension_enabled(pl_vulkan vk, const char *name)
{
    if (!vk || !name)
        return false;
    for (int i = 0; i < vk->num_extensions; i++) {
        if (vk->extensions[i] && strcmp(vk->extensions[i], name) == 0)
            return true;
    }
    return false;
}

static bool qcom_features_enabled(pl_vulkan vk)
{
    if (!vk || !vk->features)
        return false;
    const VkBaseInStructure *feature =
        (const VkBaseInStructure *) vk->features->pNext;
    bool base = false, window = false;
    for (; feature; feature = (const VkBaseInStructure *) feature->pNext) {
        if (feature->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_FEATURES_QCOM) {
            const VkPhysicalDeviceImageProcessingFeaturesQCOM *qcom =
                (const VkPhysicalDeviceImageProcessingFeaturesQCOM *) feature;
            base = qcom->textureBlockMatch == VK_TRUE;
        } else if (feature->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_2_FEATURES_QCOM) {
            const VkPhysicalDeviceImageProcessing2FeaturesQCOM *qcom2 =
                (const VkPhysicalDeviceImageProcessing2FeaturesQCOM *) feature;
            window = qcom2->textureBlockMatch2 == VK_TRUE;
        }
    }
    return base && window;
}

static bool qcom_format_feature_flags2_available(pl_vulkan vk)
{
    if (!vk)
        return false;
    if (vk->api_version >= VK_API_VERSION_1_3)
        return true;
#ifdef VK_KHR_FORMAT_FEATURE_FLAGS_2_EXTENSION_NAME
    return qcom_extension_enabled(vk, VK_KHR_FORMAT_FEATURE_FLAGS_2_EXTENSION_NAME);
#else
    return false;
#endif
}

static PFN_vkGetPhysicalDeviceProperties2 qcom_get_properties2(pl_vulkan vk)
{
    if (!vk || !vk->get_proc_addr)
        return NULL;
    PFN_vkGetPhysicalDeviceProperties2 fn =
        (PFN_vkGetPhysicalDeviceProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceProperties2");
    if (!fn)
        fn = (PFN_vkGetPhysicalDeviceProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceProperties2KHR");
    return fn;
}

static PFN_vkGetPhysicalDeviceFormatProperties2 qcom_get_format_properties2(pl_vulkan vk)
{
    if (!vk || !vk->get_proc_addr)
        return NULL;
    PFN_vkGetPhysicalDeviceFormatProperties2 fn =
        (PFN_vkGetPhysicalDeviceFormatProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceFormatProperties2");
    if (!fn)
        fn = (PFN_vkGetPhysicalDeviceFormatProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceFormatProperties2KHR");
    return fn;
}

static PFN_vkGetPhysicalDeviceImageFormatProperties2 qcom_get_image_properties2(pl_vulkan vk)
{
    if (!vk || !vk->get_proc_addr)
        return NULL;
    PFN_vkGetPhysicalDeviceImageFormatProperties2 fn =
        (PFN_vkGetPhysicalDeviceImageFormatProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceImageFormatProperties2");
    if (!fn)
        fn = (PFN_vkGetPhysicalDeviceImageFormatProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceImageFormatProperties2KHR");
    return fn;
}

static VkResult qcom_query_image_usage(MPVFlowVulkanQcom *qcom,
                                       VkFormat format,
                                       VkImageUsageFlags usage,
                                       int width, int height,
                                       VkImageFormatProperties *out)
{
    PFN_vkGetPhysicalDeviceImageFormatProperties2 query =
        qcom_get_image_properties2(qcom->vk);
    if (!query)
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    VkPhysicalDeviceImageFormatInfo2 info = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2,
        .format = format,
        .type = VK_IMAGE_TYPE_2D,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = usage,
    };
    VkImageFormatProperties2 properties = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2,
    };
    VkResult result = query(qcom->vk->phys_device, &info, &properties);
    if (out)
        *out = properties.imageFormatProperties;
    if (result != VK_SUCCESS)
        return result;
    if (width > 0 && properties.imageFormatProperties.maxExtent.width < (uint32_t) width)
        return VK_ERROR_FORMAT_NOT_SUPPORTED;
    if (height > 0 && properties.imageFormatProperties.maxExtent.height < (uint32_t) height)
        return VK_ERROR_FORMAT_NOT_SUPPORTED;
    return VK_SUCCESS;
}

static bool qcom_format_features(MPVFlowVulkanQcom *qcom,
                                VkFormat format,
                                VkFormatFeatureFlags2 required,
                                VkFormatFeatureFlags2 *available)
{
    if (available)
        *available = 0;
    if (!qcom_format_feature_flags2_available(qcom->vk))
        return false;
    PFN_vkGetPhysicalDeviceFormatProperties2 query =
        qcom_get_format_properties2(qcom->vk);
    if (!query)
        return false;
    VkFormatProperties3 props3 = {
        .sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_3,
    };
    VkFormatProperties2 props2 = {
        .sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2,
        .pNext = &props3,
    };
    query(qcom->vk->phys_device, format, &props2);
    if (available)
        *available = props3.optimalTilingFeatures;
    return (props3.optimalTilingFeatures & required) == required;
}

static uint32_t qcom_queue_families(MPVFlowVulkanQcom *qcom, uint32_t out[3])
{
    const struct pl_vulkan_queue queues[] = {
        qcom->vk->queue_graphics,
        qcom->vk->queue_compute,
        qcom->vk->queue_transfer,
    };
    uint32_t count = 0;
    for (size_t i = 0; i < sizeof(queues) / sizeof(queues[0]); i++) {
        if (!queues[i].count)
            continue;
        bool duplicate = false;
        for (uint32_t j = 0; j < count; j++)
            duplicate |= out[j] == queues[i].index;
        if (!duplicate)
            out[count++] = queues[i].index;
    }
    return count;
}

static bool qcom_find_memory_type(MPVFlowVulkanQcom *qcom,
                                  uint32_t type_bits, uint32_t *index)
{
    VkPhysicalDeviceMemoryProperties properties = {0};
    vkGetPhysicalDeviceMemoryProperties(qcom->vk->phys_device, &properties);
    uint32_t fallback = UINT32_MAX;
    for (uint32_t i = 0; i < properties.memoryTypeCount; i++) {
        if (!(type_bits & (1u << i)))
            continue;
        if (fallback == UINT32_MAX)
            fallback = i;
        if (properties.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) {
            *index = i;
            return true;
        }
    }
    if (fallback == UINT32_MAX)
        return false;
    *index = fallback;
    return true;
}

static void qcom_destroy_image(MPVFlowVulkanQcom *qcom, struct QcomImage *image)
{
    if (!image)
        return;
    if (image->texture)
        pl_tex_destroy(qcom->gpu, &image->texture);
    if (image->view)
        vkDestroyImageView(qcom->vk->device, image->view, NULL);
    if (image->image)
        vkDestroyImage(qcom->vk->device, image->image, NULL);
    if (image->memory)
        vkFreeMemory(qcom->vk->device, image->memory, NULL);
    *image = (struct QcomImage) {0};
}

static bool qcom_create_image(MPVFlowVulkanQcom *qcom,
                              struct QcomImage *image,
                              int width, int height,
                              VkFormat format, VkImageUsageFlags usage,
                              const char *name, bool wrap_texture)
{
    uint32_t families[3] = {0};
    uint32_t family_count = 0;
    if (wrap_texture) {
        family_count = qcom_queue_families(qcom, families);
    } else {
        families[0] = qcom->queue_family;
        family_count = 1;
    }
    if (!family_count && qcom->vk->queue_compute.count) {
        families[0] = qcom->vk->queue_compute.index;
        family_count = 1;
    }
    if (!family_count)
        return false;

    VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = { (uint32_t) width, (uint32_t) height, 1 },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = usage,
        .sharingMode = family_count > 1 ? VK_SHARING_MODE_CONCURRENT : VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = family_count > 1 ? family_count : 0,
        .pQueueFamilyIndices = family_count > 1 ? families : NULL,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    VkResult result = vkCreateImage(qcom->vk->device, &image_info, NULL, &image->image);
    if (result != VK_SUCCESS)
        goto fail;

    VkMemoryRequirements requirements = {0};
    vkGetImageMemoryRequirements(qcom->vk->device, image->image, &requirements);
    uint32_t memory_type = 0;
    if (!qcom_find_memory_type(qcom, requirements.memoryTypeBits, &memory_type)) {
        result = VK_ERROR_FEATURE_NOT_PRESENT;
        goto fail;
    }
    VkMemoryAllocateInfo allocate = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = memory_type,
    };
    result = vkAllocateMemory(qcom->vk->device, &allocate, NULL, &image->memory);
    if (result != VK_SUCCESS)
        goto fail;
    result = vkBindImageMemory(qcom->vk->device, image->image, image->memory, 0);
    if (result != VK_SUCCESS)
        goto fail;

    VkImageViewCreateInfo view_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = image->image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format,
        .components = {
            .r = VK_COMPONENT_SWIZZLE_IDENTITY,
            .g = VK_COMPONENT_SWIZZLE_IDENTITY,
            .b = VK_COMPONENT_SWIZZLE_IDENTITY,
            .a = VK_COMPONENT_SWIZZLE_IDENTITY,
        },
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    result = vkCreateImageView(qcom->vk->device, &view_info, NULL, &image->view);
    if (result != VK_SUCCESS)
        goto fail;

    if (wrap_texture) {
        image->texture = pl_vulkan_wrap(qcom->gpu, pl_vulkan_wrap_params(
            .image = image->image,
            .aspect = VK_IMAGE_ASPECT_COLOR_BIT,
            .width = width,
            .height = height,
            .depth = 0,
            .format = format,
            .usage = usage));
        if (!image->texture) {
            result = VK_ERROR_FORMAT_NOT_SUPPORTED;
            goto fail;
        }
        pl_vulkan_release_ex(qcom->gpu, pl_vulkan_release_params(
            .tex = image->texture,
            .layout = VK_IMAGE_LAYOUT_UNDEFINED,
            .qf = VK_QUEUE_FAMILY_IGNORED));
    }
    qcom_log(qcom->gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=qcom_block_match_image state=created name=%s size=%dx%d format=%d usage=0x%x sharing=%s queue_families=%u wrapped=%d",
             name, width, height, format, usage,
             family_count > 1 ? "CONCURRENT" : "EXCLUSIVE", family_count,
             wrap_texture);
    return true;

fail:
    qcom_log(qcom->gpu, PL_LOG_WARN,
             "MPVFLOW_DIAGNOSTIC event=qcom_block_match_image state=failed name=%s result=%d size=%dx%d format=%d usage=0x%x",
             name, result, width, height, format, usage);
    qcom_destroy_image(qcom, image);
    return false;
}

static bool qcom_create_sampler(MPVFlowVulkanQcom *qcom)
{
    VkSamplerBlockMatchWindowCreateInfoQCOM window = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_BLOCK_MATCH_WINDOW_CREATE_INFO_QCOM,
        .windowExtent = qcom->window,
        .windowCompareMode = VK_BLOCK_MATCH_WINDOW_COMPARE_MODE_MIN_QCOM,
    };
    VkSamplerCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .pNext = &window,
        .flags = VK_SAMPLER_CREATE_IMAGE_PROCESSING_BIT_QCOM,
        .magFilter = VK_FILTER_NEAREST,
        .minFilter = VK_FILTER_NEAREST,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER,
        .mipLodBias = 0.0f,
        .anisotropyEnable = VK_FALSE,
        .compareEnable = VK_FALSE,
        .minLod = 0.0f,
        .maxLod = 0.0f,
        .borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK,
        .unnormalizedCoordinates = VK_TRUE,
    };
    VkResult result = vkCreateSampler(qcom->vk->device, &info, NULL, &qcom->sampler);
    if (result != VK_SUCCESS) {
        qcom_log(qcom->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=qcom_block_match_init state=disabled stage=sampler_create result=%d window=%ux%u",
                 result, qcom->window.width, qcom->window.height);
        return false;
    }
    return true;
}

static bool qcom_create_pipeline(MPVFlowVulkanQcom *qcom)
{
    const VkDescriptorSetLayoutBinding bindings[] = {
        { .binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_BLOCK_MATCH_IMAGE_QCOM,
          .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT },
        { .binding = 1, .descriptorType = VK_DESCRIPTOR_TYPE_BLOCK_MATCH_IMAGE_QCOM,
          .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT },
        { .binding = 2, .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER,
          .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT },
        { .binding = 3, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
          .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT },
    };
    VkDescriptorSetLayoutCreateInfo descriptor_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = sizeof(bindings) / sizeof(bindings[0]),
        .pBindings = bindings,
    };
    VkResult result = vkCreateDescriptorSetLayout(qcom->vk->device, &descriptor_info,
                                                   NULL, &qcom->descriptor_layout);
    if (result != VK_SUCCESS)
        goto fail;

    const VkDescriptorPoolSize pool_sizes[] = {
        { .type = VK_DESCRIPTOR_TYPE_BLOCK_MATCH_IMAGE_QCOM, .descriptorCount = 4 },
        { .type = VK_DESCRIPTOR_TYPE_SAMPLER, .descriptorCount = 2 },
        { .type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, .descriptorCount = 2 },
    };
    VkDescriptorPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = 2,
        .poolSizeCount = sizeof(pool_sizes) / sizeof(pool_sizes[0]),
        .pPoolSizes = pool_sizes,
    };
    result = vkCreateDescriptorPool(qcom->vk->device, &pool_info, NULL,
                                     &qcom->descriptor_pool);
    if (result != VK_SUCCESS)
        goto fail;
    VkDescriptorSetLayout layouts[2] = { qcom->descriptor_layout, qcom->descriptor_layout };
    VkDescriptorSetAllocateInfo allocate_sets = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = qcom->descriptor_pool,
        .descriptorSetCount = 2,
        .pSetLayouts = layouts,
    };
    result = vkAllocateDescriptorSets(qcom->vk->device, &allocate_sets,
                                      qcom->descriptor_sets);
    if (result != VK_SUCCESS)
        goto fail;

    VkPushConstantRange push_range = {
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0,
        .size = QCOM_PUSH_CONSTANT_BYTES,
    };
    VkPipelineLayoutCreateInfo layout_info = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &qcom->descriptor_layout,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &push_range,
    };
    result = vkCreatePipelineLayout(qcom->vk->device, &layout_info, NULL,
                                    &qcom->pipeline_layout);
    if (result != VK_SUCCESS)
        goto fail;

    VkShaderModuleCreateInfo shader_info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = MPVFLOW_QCOM_SEED_SPV_SIZE,
        .pCode = mpvflow_qcom_seed_spv,
    };
    VkShaderModule shader = VK_NULL_HANDLE;
    result = vkCreateShaderModule(qcom->vk->device, &shader_info, NULL, &shader);
    if (result != VK_SUCCESS)
        goto fail;
    VkComputePipelineCreateInfo pipeline_info = {
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_COMPUTE_BIT,
            .module = shader,
            .pName = "main",
        },
        .layout = qcom->pipeline_layout,
    };
    result = vkCreateComputePipelines(qcom->vk->device, VK_NULL_HANDLE, 1,
                                      &pipeline_info, NULL, &qcom->pipeline);
    vkDestroyShaderModule(qcom->vk->device, shader, NULL);
    if (result != VK_SUCCESS)
        goto fail;
    return true;

fail:
    qcom_log(qcom->gpu, PL_LOG_WARN,
             "MPVFLOW_DIAGNOSTIC event=qcom_block_match_init state=disabled stage=pipeline_or_descriptor_create result=%d",
             result);
    return false;
}

static bool qcom_create_commands(MPVFlowVulkanQcom *qcom)
{
    VkCommandPoolCreateInfo pool = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = qcom->queue_family,
    };
    VkResult result = vkCreateCommandPool(qcom->vk->device, &pool, NULL,
                                          &qcom->command_pool);
    if (result != VK_SUCCESS)
        goto fail;
    VkCommandBuffer commands[QCOM_COMMAND_SLOTS] = {0};
    VkCommandBufferAllocateInfo allocate = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = qcom->command_pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = QCOM_COMMAND_SLOTS,
    };
    result = vkAllocateCommandBuffers(qcom->vk->device, &allocate, commands);
    if (result != VK_SUCCESS)
        goto fail;
    for (uint32_t i = 0; i < QCOM_COMMAND_SLOTS; i++) {
        qcom->slots[i].command = commands[i];
        VkFenceCreateInfo fence = {
            .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
            .flags = VK_FENCE_CREATE_SIGNALED_BIT,
        };
        result = vkCreateFence(qcom->vk->device, &fence, NULL,
                               &qcom->slots[i].fence);
        if (result != VK_SUCCESS)
            goto fail;
    }
    if (qcom->timestamps_supported) {
        VkQueryPoolCreateInfo query_info = {
            .sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
            .queryType = VK_QUERY_TYPE_TIMESTAMP,
            .queryCount = 2,
        };
        for (uint32_t i = 0; i < QCOM_COMMAND_SLOTS; i++) {
            result = vkCreateQueryPool(qcom->vk->device, &query_info, NULL,
                                       &qcom->slots[i].timestamp_queries);
            if (result != VK_SUCCESS) {
                for (uint32_t j = 0; j < QCOM_COMMAND_SLOTS; j++) {
                    if (qcom->slots[j].timestamp_queries)
                        vkDestroyQueryPool(qcom->vk->device,
                            qcom->slots[j].timestamp_queries, NULL);
                    qcom->slots[j].timestamp_queries = VK_NULL_HANDLE;
                }
                qcom->timestamps_supported = false;
                qcom_log(qcom->gpu, PL_LOG_WARN,
                         "MPVFLOW_DIAGNOSTIC event=qcom_gpu_timing state=unsupported "
                         "reason=query_pool_creation_failed result=%d dispatch_behavior=unchanged",
                         result);
                break;
            }
        }
    }
    return true;

fail:
    qcom_log(qcom->gpu, PL_LOG_WARN,
             "MPVFLOW_DIAGNOSTIC event=qcom_block_match_init state=disabled stage=command_resources result=%d",
             result);
    return false;
}

static void qcom_release_held(MPVFlowVulkanQcom *qcom,
                              struct QcomImage *images[QCOM_HELD_IMAGE_COUNT],
                              const bool held[QCOM_HELD_IMAGE_COUNT],
                              const uint64_t values[QCOM_HELD_IMAGE_COUNT],
                              VkSemaphore semaphore)
{
    for (uint32_t i = 0; i < QCOM_HELD_IMAGE_COUNT; i++) {
        if (!held[i])
            continue;
        pl_vulkan_release_ex(qcom->gpu, pl_vulkan_release_params(
            .tex = images[i]->texture,
            .layout = VK_IMAGE_LAYOUT_GENERAL,
            .qf = VK_QUEUE_FAMILY_IGNORED,
            .semaphore = { semaphore, values[i] }));
    }
}

static bool qcom_update_descriptors(MPVFlowVulkanQcom *qcom)
{
    for (uint32_t direction = 0; direction < 2; direction++) {
        const uint32_t target = direction == 0 ? 1 : 0;
        const uint32_t reference = direction == 0 ? 0 : 1;
        VkDescriptorImageInfo target_info = {
            .imageView = qcom->block_luma[target].view,
            .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
        };
        VkDescriptorImageInfo reference_info = {
            .imageView = qcom->block_luma[reference].view,
            .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
        };
        VkDescriptorImageInfo sampler_info = { .sampler = qcom->sampler };
        VkDescriptorImageInfo seed_info = {
            .imageView = qcom->seed[direction].view,
            .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
        };
        VkWriteDescriptorSet writes[4] = {
            { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
              .dstSet = qcom->descriptor_sets[direction], .dstBinding = 0,
              .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_BLOCK_MATCH_IMAGE_QCOM,
              .pImageInfo = &target_info },
            { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
              .dstSet = qcom->descriptor_sets[direction], .dstBinding = 1,
              .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_BLOCK_MATCH_IMAGE_QCOM,
              .pImageInfo = &reference_info },
            { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
              .dstSet = qcom->descriptor_sets[direction], .dstBinding = 2,
              .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER,
              .pImageInfo = &sampler_info },
            { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
              .dstSet = qcom->descriptor_sets[direction], .dstBinding = 3,
              .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
              .pImageInfo = &seed_info },
        };
        vkUpdateDescriptorSets(qcom->vk->device, 4, writes, 0, NULL);
    }
    return true;
}

MPVFlowVulkanQcom *mpvflow_vulkan_qcom_create(pl_gpu gpu,
                                             char *reason,
                                             size_t reason_size)
{
    qcom_set_reason(reason, reason_size, "not_started");
    if (!gpu) {
        qcom_set_reason(reason, reason_size, "gpu_missing");
        return NULL;
    }
    MPVFlowVulkanQcom *qcom = calloc(1, sizeof(*qcom));
    if (!qcom) {
        qcom_set_reason(reason, reason_size, "out_of_memory");
        return NULL;
    }
    qcom->gpu = gpu;
    qcom->vk = pl_vulkan_get(gpu);
    if (!qcom->vk || !qcom->vk->device || !qcom->vk->phys_device) {
        qcom_set_reason(reason, reason_size, "vulkan_context_missing");
        goto fail;
    }
    if (!qcom_extension_enabled(qcom->vk, VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME) ||
        !qcom_extension_enabled(qcom->vk, VK_QCOM_IMAGE_PROCESSING_2_EXTENSION_NAME)) {
        qcom_set_reason(reason, reason_size, "qcom_extensions_not_enabled");
        goto fail;
    }
    if (!qcom_features_enabled(qcom->vk)) {
        qcom_set_reason(reason, reason_size, "qcom_block_match_features_not_enabled");
        goto fail;
    }
    if (!qcom->vk->queue_compute.count || !qcom->vk->lock_queue ||
        !qcom->vk->unlock_queue) {
        qcom_set_reason(reason, reason_size, "managed_compute_queue_unavailable");
        goto fail;
    }
    qcom->queue_family = qcom->vk->queue_compute.index;
    qcom_read_timestamp_caps(qcom);
    vkGetDeviceQueue(qcom->vk->device, qcom->queue_family, 0, &qcom->queue);
    if (!qcom->queue) {
        qcom_set_reason(reason, reason_size, "compute_queue_retrieval_failed");
        goto fail;
    }

    PFN_vkGetPhysicalDeviceProperties2 get_properties2 = qcom_get_properties2(qcom->vk);
    if (!get_properties2) {
        qcom_set_reason(reason, reason_size, "physical_device_properties2_unavailable");
        goto fail;
    }
    VkPhysicalDeviceImageProcessing2PropertiesQCOM window_properties = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_2_PROPERTIES_QCOM,
    };
    VkPhysicalDeviceImageProcessingPropertiesQCOM base_properties = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_PROPERTIES_QCOM,
        .pNext = &window_properties,
    };
    VkPhysicalDeviceProperties2 properties = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
        .pNext = &base_properties,
    };
    get_properties2(qcom->vk->phys_device, &properties);
    qcom->max_region = base_properties.maxBlockMatchRegion;
    qcom->window.width = window_properties.maxBlockMatchWindow.width < QCOM_SEED_MAX_WINDOW
        ? window_properties.maxBlockMatchWindow.width : QCOM_SEED_MAX_WINDOW;
    qcom->window.height = window_properties.maxBlockMatchWindow.height < QCOM_SEED_MAX_WINDOW
        ? window_properties.maxBlockMatchWindow.height : QCOM_SEED_MAX_WINDOW;
    if (!qcom->max_region.width || !qcom->max_region.height ||
        !qcom->window.width || !qcom->window.height) {
        qcom_set_reason(reason, reason_size, "qcom_block_match_limits_zero");
        goto fail;
    }
    if (!qcom_format_feature_flags2_available(qcom->vk)) {
        qcom_set_reason(reason, reason_size, "format_feature_flags2_unavailable");
        goto fail;
    }
    VkFormatFeatureFlags2 r8_features = 0;
    const VkFormatFeatureFlags2 required_r8 =
        VK_FORMAT_FEATURE_2_SAMPLED_IMAGE_BIT |
        VK_FORMAT_FEATURE_2_BLOCK_MATCHING_BIT_QCOM;
    if (!qcom_format_features(qcom, VK_FORMAT_R8_UNORM, required_r8, &r8_features)) {
        qcom_set_reason(reason, reason_size,
                        "r8_required_format_features_missing_available_0x%llx",
                        (unsigned long long) r8_features);
        goto fail;
    }
    VkFormatFeatureFlags2 r8_stage_features = 0;
    if (!qcom_format_features(qcom, VK_FORMAT_R8_UNORM,
                              VK_FORMAT_FEATURE_2_STORAGE_IMAGE_BIT,
                              &r8_stage_features)) {
        qcom_set_reason(reason, reason_size,
                        "r8_storage_staging_feature_missing_available_0x%llx",
                        (unsigned long long) r8_stage_features);
        goto fail;
    }
    VkFormatFeatureFlags2 output_features = 0;
    if (!qcom_format_features(qcom, VK_FORMAT_R16G16B16A16_SFLOAT,
                              VK_FORMAT_FEATURE_2_STORAGE_IMAGE_BIT,
                              &output_features)) {
        qcom_set_reason(reason, reason_size,
                        "rgba16f_storage_feature_missing_available_0x%llx",
                        (unsigned long long) output_features);
        goto fail;
    }
    if (!qcom_create_sampler(qcom) || !qcom_create_pipeline(qcom) ||
        !qcom_create_commands(qcom)) {
        qcom_set_reason(reason, reason_size, "qcom_pipeline_or_sampler_initialization_failed");
        goto fail;
    }
    qcom->ready_semaphore = pl_vulkan_sem_create(gpu, pl_vulkan_sem_params(
        .type = VK_SEMAPHORE_TYPE_TIMELINE,
        .initial_value = 0));
    qcom->done_semaphore = pl_vulkan_sem_create(gpu, pl_vulkan_sem_params(
        .type = VK_SEMAPHORE_TYPE_TIMELINE,
        .initial_value = 0));
    if (!qcom->ready_semaphore || !qcom->done_semaphore) {
        qcom_set_reason(reason, reason_size, "timeline_semaphore_unavailable");
        goto fail;
    }
    qcom_set_reason(reason, reason_size, "pipeline_ready_exact_extent_usage_pending");
    qcom_log(gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=qcom_block_match_init state=pipeline_ready backend=VK_QCOM_image_processing2 "
             "queue_family=%u queue_index=0 max_region=%ux%u window=%ux%u "
             "r8_block_match_features=0x%llx r8_storage_features=0x%llx output_features=0x%llx seed_validation=existing_full_sad_search",
             qcom->queue_family, qcom->max_region.width, qcom->max_region.height,
             qcom->window.width, qcom->window.height,
             (unsigned long long) r8_features,
             (unsigned long long) r8_stage_features,
             (unsigned long long) output_features);
    return qcom;

fail:
    qcom_log(gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=qcom_block_match_init state=disabled reason=%s fallback=validated_vulkan_sad",
             reason && reason_size ? reason : "unavailable");
    mpvflow_vulkan_qcom_destroy(&qcom);
    return NULL;
}

bool mpvflow_vulkan_qcom_ensure(MPVFlowVulkanQcom *qcom,
                                int width, int height,
                                int grid_width, int grid_height,
                                int block_size)
{
    if (!qcom || qcom->disabled || width <= 0 || height <= 0 ||
        grid_width <= 0 || grid_height <= 0 || block_size <= 0)
        return false;
    if ((uint32_t) block_size > qcom->max_region.width ||
        (uint32_t) block_size > qcom->max_region.height ||
        width < block_size + (int) qcom->window.width - 1 ||
        height < block_size + (int) qcom->window.height - 1) {
        qcom_log(qcom->gpu, PL_LOG_DEBUG,
                 "MPVFLOW_DIAGNOSTIC event=qcom_block_match_image state=unavailable reason=image_or_region_too_small "
                 "input=%dx%d grid=%dx%d block=%d window=%ux%u max_region=%ux%u",
                 width, height, grid_width, grid_height, block_size,
                 qcom->window.width, qcom->window.height,
                 qcom->max_region.width, qcom->max_region.height);
        return false;
    }
    if (qcom->width == width && qcom->height == height &&
        qcom->grid_width == grid_width && qcom->grid_height == grid_height &&
        qcom->block_size == block_size)
        return true;
    const uint64_t setup_start_ns = qcom_clock_ns();
    uint64_t pl_finish_ns = 0, queue_idle_ns = 0;
    VkImageFormatProperties block_properties = {0};
    VkImageUsageFlags block_usage = VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLE_BLOCK_MATCH_BIT_QCOM;
    VkResult block_result = qcom_query_image_usage(qcom, VK_FORMAT_R8_UNORM,
        block_usage, width, height, &block_properties);
    VkImageFormatProperties stage_properties = {0};
    VkImageUsageFlags stage_usage = VK_IMAGE_USAGE_STORAGE_BIT |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    VkResult stage_result = qcom_query_image_usage(qcom, VK_FORMAT_R8_UNORM,
        stage_usage, width, height, &stage_properties);
    VkImageFormatProperties output_properties = {0};
    VkResult output_result = qcom_query_image_usage(qcom,
        VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT,
        grid_width, grid_height, &output_properties);
    if (block_result != VK_SUCCESS || stage_result != VK_SUCCESS ||
        output_result != VK_SUCCESS) {
        qcom_log(qcom->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=qcom_block_match_image state=unavailable reason=exact_image_usage_not_supported "
                 "block_format=R8_UNORM block_usage=0x%x block_result=%d stage_usage=0x%x stage_result=%d input_size=%dx%d "
                 "output_format=RGBA16F output_usage=STORAGE output_result=%d output_size=%dx%d",
                 block_usage, block_result, stage_usage, stage_result, width, height, output_result,
                 grid_width, grid_height);
        return false;
    }

    const uint64_t pl_finish_start_ns = qcom_clock_ns();
    pl_gpu_finish(qcom->gpu);
    pl_finish_ns = qcom_clock_ns() - pl_finish_start_ns;
    const uint64_t queue_idle_start_ns = qcom_clock_ns();
    qcom->vk->lock_queue(qcom->vk, qcom->queue_family, 0);
    VkResult idle_result = vkQueueWaitIdle(qcom->queue);
    qcom->vk->unlock_queue(qcom->vk, qcom->queue_family, 0);
    queue_idle_ns = qcom_clock_ns() - queue_idle_start_ns;
    if (idle_result != VK_SUCCESS) {
        qcom_log(qcom->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=qcom_block_match_image state=unavailable reason=resize_queue_idle_failed result=%d",
                 idle_result);
        return false;
    }
    for (int i = 0; i < 2; i++) {
        qcom_destroy_image(qcom, &qcom->stage_luma[i]);
        qcom_destroy_image(qcom, &qcom->block_luma[i]);
        qcom_destroy_image(qcom, &qcom->seed[i]);
    }
    for (int i = 0; i < 2; i++) {
        if (!qcom_create_image(qcom, &qcom->stage_luma[i], width, height,
                               VK_FORMAT_R8_UNORM, stage_usage,
                               i == 0 ? "luma_stage_frame0" : "luma_stage_frame1", true) ||
            !qcom_create_image(qcom, &qcom->block_luma[i], width, height,
                               VK_FORMAT_R8_UNORM, block_usage,
                               i == 0 ? "block_luma_frame0" : "block_luma_frame1", false) ||
            !qcom_create_image(qcom, &qcom->seed[i], grid_width, grid_height,
                               VK_FORMAT_R16G16B16A16_SFLOAT,
                               VK_IMAGE_USAGE_STORAGE_BIT,
                               i == 0 ? "seed_forward" : "seed_backward", true)) {
            for (int j = 0; j < 2; j++) {
                qcom_destroy_image(qcom, &qcom->stage_luma[j]);
                qcom_destroy_image(qcom, &qcom->block_luma[j]);
                qcom_destroy_image(qcom, &qcom->seed[j]);
            }
            qcom->width = qcom->height = qcom->grid_width = qcom->grid_height = 0;
            qcom->block_size = 0;
            return false;
        }
        qcom->block_luma_layout[i] = VK_IMAGE_LAYOUT_UNDEFINED;
    }
    qcom->width = width;
    qcom->height = height;
    qcom->grid_width = grid_width;
    qcom->grid_height = grid_height;
    qcom->block_size = block_size;
    qcom_update_descriptors(qcom);
    qcom_log(qcom->gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=qcom_block_match_image state=ready input=%dx%d grid=%dx%d block=%d window=%ux%u block_usage=0x%x stage_usage=0x%x output_usage=0x%x "
             "setup_cpu_ms=%.3f pl_gpu_finish_ms=%.3f resize_queue_wait_ms=%.3f blocking_resize_only=1",
             width, height, grid_width, grid_height, block_size,
             qcom->window.width, qcom->window.height, block_usage, stage_usage,
             VK_IMAGE_USAGE_STORAGE_BIT,
             (qcom_clock_ns() - setup_start_ns) / 1000000.0,
             pl_finish_ns / 1000000.0, queue_idle_ns / 1000000.0);
    return true;
}

pl_tex mpvflow_vulkan_qcom_luma(MPVFlowVulkanQcom *qcom, int index)
{
    return qcom && index >= 0 && index < 2 ? qcom->stage_luma[index].texture : NULL;
}

pl_tex mpvflow_vulkan_qcom_seed(MPVFlowVulkanQcom *qcom, int index)
{
    return qcom && index >= 0 && index < 2 ? qcom->seed[index].texture : NULL;
}

bool mpvflow_vulkan_qcom_dispatch(MPVFlowVulkanQcom *qcom)
{
    if (!qcom || qcom->disabled || !qcom->pipeline || !qcom->width ||
        !qcom->grid_width || !qcom->stage_luma[0].texture ||
        !qcom->stage_luma[1].texture || !qcom->block_luma[0].image ||
        !qcom->block_luma[1].image ||
        !qcom->seed[0].texture || !qcom->seed[1].texture)
        return false;
    const uint32_t slot_index = qcom->slot_cursor++ % QCOM_COMMAND_SLOTS;
    struct QcomCommandSlot *slot = &qcom->slots[slot_index];
    const uint64_t fence_poll_start_ns = qcom_clock_ns();
    VkResult result = vkGetFenceStatus(qcom->vk->device, slot->fence);
    qcom_add_elapsed(fence_poll_start_ns, qcom_clock_ns(),
        &qcom->cpu_fence_poll_total_ns, &qcom->cpu_fence_poll_max_ns,
        &qcom->cpu_fence_poll_samples);
    if (result == VK_NOT_READY) {
        qcom_log(qcom->gpu, PL_LOG_DEBUG,
                 "MPVFLOW_DIAGNOSTIC event=qcom_block_match_dispatch state=fallback reason=command_slot_busy slot=%u dispatches=%llu",
                 slot_index, (unsigned long long) qcom->dispatches);
        return false;
    }
    if (result != VK_SUCCESS) {
        qcom->disabled = true;
        qcom_log(qcom->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=qcom_block_match_dispatch state=disabled reason=fence_status_failed result=%d",
                 result);
        return false;
    }
    qcom_collect_timestamp(qcom, slot);
    result = vkResetCommandBuffer(slot->command, 0);
    if (result != VK_SUCCESS) {
        qcom->disabled = true;
        qcom_log(qcom->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=qcom_block_match_dispatch state=disabled reason=command_reset_failed result=%d",
                 result);
        return false;
    }

    struct QcomImage *images[QCOM_HELD_IMAGE_COUNT] = {
        &qcom->stage_luma[0], &qcom->stage_luma[1], &qcom->seed[0], &qcom->seed[1],
    };
    bool held[QCOM_HELD_IMAGE_COUNT] = {false};
    uint64_t values[QCOM_HELD_IMAGE_COUNT] = {0};
    const uint64_t flush_start_ns = qcom_clock_ns();
    pl_gpu_flush(qcom->gpu);
    qcom_add_elapsed(flush_start_ns, qcom_clock_ns(),
        &qcom->cpu_flush_total_ns, &qcom->cpu_flush_max_ns,
        &qcom->cpu_flush_samples);
    if (qcom->ready_value > UINT64_MAX - QCOM_HELD_IMAGE_COUNT ||
        qcom->done_value == UINT64_MAX) {
        qcom->disabled = true;
        qcom_log(qcom->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=qcom_block_match_dispatch state=disabled reason=timeline_value_overflow");
        return false;
    }
    for (uint32_t i = 0; i < QCOM_HELD_IMAGE_COUNT; i++) {
        values[i] = ++qcom->ready_value;
        if (!pl_vulkan_hold_ex(qcom->gpu, pl_vulkan_hold_params(
                .tex = images[i]->texture,
                .layout = VK_IMAGE_LAYOUT_GENERAL,
                .qf = VK_QUEUE_FAMILY_IGNORED,
                .semaphore = { qcom->ready_semaphore, values[i] }))) {
            qcom_release_held(qcom, images, held, values, qcom->ready_semaphore);
            qcom_log(qcom->gpu, PL_LOG_WARN,
                     "MPVFLOW_DIAGNOSTIC event=qcom_block_match_dispatch state=fallback reason=libplacebo_hold_failed held=%u",
                     i);
            return false;
        }
        held[i] = true;
    }

    VkCommandBufferBeginInfo begin = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    const uint64_t command_record_start_ns = qcom_clock_ns();
    bool command_record_timed = false;
    result = vkBeginCommandBuffer(slot->command, &begin);
    if (result != VK_SUCCESS)
        goto command_fail;

    VkImageMemoryBarrier barriers[QCOM_HELD_IMAGE_COUNT] = {0};
    for (uint32_t i = 0; i < QCOM_HELD_IMAGE_COUNT; i++) {
        barriers[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barriers[i].srcAccessMask = i < 2 ? VK_ACCESS_SHADER_WRITE_BIT :
            (VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        barriers[i].dstAccessMask = i < 2 ? VK_ACCESS_TRANSFER_READ_BIT :
            VK_ACCESS_SHADER_WRITE_BIT;
        barriers[i].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        barriers[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barriers[i].image = images[i]->image;
        barriers[i].subresourceRange = (VkImageSubresourceRange) {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0, .levelCount = 1,
            .baseArrayLayer = 0, .layerCount = 1,
        };
    }
    vkCmdPipelineBarrier(slot->command,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, NULL, 0, NULL, QCOM_HELD_IMAGE_COUNT, barriers);
    VkImageMemoryBarrier block_to_copy[2] = {0};
    for (uint32_t i = 0; i < 2; i++) {
        block_to_copy[i] = (VkImageMemoryBarrier) {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = qcom->block_luma_layout[i] == VK_IMAGE_LAYOUT_UNDEFINED
                ? 0 : VK_ACCESS_SHADER_READ_BIT,
            .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .oldLayout = qcom->block_luma_layout[i],
            .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = qcom->block_luma[i].image,
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0, .levelCount = 1,
                .baseArrayLayer = 0, .layerCount = 1,
            },
        };
    }
    vkCmdPipelineBarrier(slot->command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 2, block_to_copy);
    VkImageCopy copy = {
        .srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
        .dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
        .extent = { (uint32_t) qcom->width, (uint32_t) qcom->height, 1 },
    };
    for (uint32_t i = 0; i < 2; i++)
        vkCmdCopyImage(slot->command, qcom->stage_luma[i].image,
            VK_IMAGE_LAYOUT_GENERAL, qcom->block_luma[i].image,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    VkImageMemoryBarrier block_for_compute[2] = {0};
    for (uint32_t i = 0; i < 2; i++) {
        block_for_compute[i] = (VkImageMemoryBarrier) {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = qcom->block_luma[i].image,
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0, .levelCount = 1,
                .baseArrayLayer = 0, .layerCount = 1,
            },
        };
    }
    vkCmdPipelineBarrier(slot->command, VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, NULL, 0, NULL, 2,
        block_for_compute);
    if (slot->timestamp_queries) {
        vkCmdResetQueryPool(slot->command, slot->timestamp_queries, 0, 2);
        vkCmdWriteTimestamp(slot->command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            slot->timestamp_queries, 0);
    }
    vkCmdBindPipeline(slot->command, VK_PIPELINE_BIND_POINT_COMPUTE, qcom->pipeline);
    struct QcomSeedPush push = {
        .image_grid = {
            (uint32_t) qcom->width, (uint32_t) qcom->height,
            (uint32_t) qcom->grid_width, (uint32_t) qcom->grid_height,
        },
        .search = {
            (uint32_t) qcom->block_size,
            qcom->window.width, qcom->window.height, 0,
        },
    };
    for (uint32_t direction = 0; direction < 2; direction++) {
        vkCmdBindDescriptorSets(slot->command, VK_PIPELINE_BIND_POINT_COMPUTE,
            qcom->pipeline_layout, 0, 1, &qcom->descriptor_sets[direction], 0, NULL);
        vkCmdPushConstants(slot->command, qcom->pipeline_layout,
            VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
        vkCmdDispatch(slot->command,
            ((uint32_t) qcom->grid_width + 7u) / 8u,
            ((uint32_t) qcom->grid_height + 7u) / 8u, 1);
    }
    if (slot->timestamp_queries)
        vkCmdWriteTimestamp(slot->command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            slot->timestamp_queries, 1);
    result = vkEndCommandBuffer(slot->command);
    qcom_add_elapsed(command_record_start_ns, qcom_clock_ns(),
        &qcom->cpu_record_total_ns, &qcom->cpu_record_max_ns,
        &qcom->cpu_record_samples);
    command_record_timed = true;
    if (result != VK_SUCCESS)
        goto command_fail;

    result = vkResetFences(qcom->vk->device, 1, &slot->fence);
    if (result != VK_SUCCESS)
        goto command_fail;
    const uint64_t wait_value = values[QCOM_HELD_IMAGE_COUNT - 1];
    const uint64_t signal_value = ++qcom->done_value;
    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_TRANSFER_BIT |
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    VkTimelineSemaphoreSubmitInfo timeline = {
        .sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO,
        .waitSemaphoreValueCount = 1,
        .pWaitSemaphoreValues = &wait_value,
        .signalSemaphoreValueCount = 1,
        .pSignalSemaphoreValues = &signal_value,
    };
    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .pNext = &timeline,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &qcom->ready_semaphore,
        .pWaitDstStageMask = &wait_stage,
        .commandBufferCount = 1,
        .pCommandBuffers = &slot->command,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &qcom->done_semaphore,
    };
    const uint64_t queue_lock_start_ns = qcom_clock_ns();
    qcom->vk->lock_queue(qcom->vk, qcom->queue_family, 0);
    const uint64_t queue_submit_start_ns = qcom_clock_ns();
    result = vkQueueSubmit(qcom->queue, 1, &submit, slot->fence);
    const uint64_t queue_submit_end_ns = qcom_clock_ns();
    qcom->vk->unlock_queue(qcom->vk, qcom->queue_family, 0);
    qcom_add_elapsed(queue_lock_start_ns, queue_submit_start_ns,
        &qcom->cpu_queue_lock_total_ns, &qcom->cpu_queue_lock_max_ns,
        &qcom->cpu_queue_lock_samples);
    qcom_add_elapsed(queue_submit_start_ns, queue_submit_end_ns,
        &qcom->cpu_queue_submit_total_ns, &qcom->cpu_queue_submit_max_ns,
        &qcom->cpu_queue_submit_samples);
    if (result != VK_SUCCESS) {
        qcom_release_held(qcom, images, held, values, qcom->ready_semaphore);
        qcom->disabled = true;
        qcom_log(qcom->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=qcom_block_match_dispatch state=disabled reason=vkQueueSubmit_failed result=%d",
                 result);
        return false;
    }
    slot->timestamp_pending = slot->timestamp_queries != VK_NULL_HANDLE;
    for (uint32_t i = 0; i < QCOM_HELD_IMAGE_COUNT; i++) {
        pl_vulkan_release_ex(qcom->gpu, pl_vulkan_release_params(
            .tex = images[i]->texture,
            .layout = VK_IMAGE_LAYOUT_GENERAL,
            .qf = VK_QUEUE_FAMILY_IGNORED,
            .semaphore = { qcom->done_semaphore, signal_value }));
    }
    qcom->block_luma_layout[0] = VK_IMAGE_LAYOUT_GENERAL;
    qcom->block_luma_layout[1] = VK_IMAGE_LAYOUT_GENERAL;
    qcom->dispatches++;
    if (qcom->dispatches == 1 || qcom->dispatches % 120 == 0) {
        qcom_log(qcom->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC event=qcom_block_match_dispatch state=active dispatches=%llu input=%dx%d grid=%dx%d block=%d window=%ux%u refinement=full_baseline_sad_search",
                 (unsigned long long) qcom->dispatches, qcom->width, qcom->height,
                 qcom->grid_width, qcom->grid_height, qcom->block_size,
                 qcom->window.width, qcom->window.height);
        if (qcom->dispatches % 120 == 0)
            qcom_report_dispatch_timing(qcom);
    }
    return true;

command_fail:
    if (!command_record_timed)
        qcom_add_elapsed(command_record_start_ns, qcom_clock_ns(),
            &qcom->cpu_record_total_ns, &qcom->cpu_record_max_ns,
            &qcom->cpu_record_samples);
    qcom_release_held(qcom, images, held, values, qcom->ready_semaphore);
    qcom_log(qcom->gpu, PL_LOG_WARN,
             "MPVFLOW_DIAGNOSTIC event=qcom_block_match_dispatch state=fallback reason=command_record_failed result=%d",
             result);
    return false;
}

void mpvflow_vulkan_qcom_destroy(MPVFlowVulkanQcom **qcom_ptr)
{
    if (!qcom_ptr || !*qcom_ptr)
        return;
    MPVFlowVulkanQcom *qcom = *qcom_ptr;
    *qcom_ptr = NULL;
    if (qcom->gpu)
        pl_gpu_finish(qcom->gpu);
    if (qcom->vk && qcom->queue) {
        qcom->vk->lock_queue(qcom->vk, qcom->queue_family, 0);
        vkQueueWaitIdle(qcom->queue);
        qcom->vk->unlock_queue(qcom->vk, qcom->queue_family, 0);
    }
    if (qcom->vk && qcom->vk->device) {
        for (int i = 0; i < 2; i++) {
            qcom_destroy_image(qcom, &qcom->stage_luma[i]);
            qcom_destroy_image(qcom, &qcom->block_luma[i]);
            qcom_destroy_image(qcom, &qcom->seed[i]);
        }
        for (uint32_t i = 0; i < QCOM_COMMAND_SLOTS; i++) {
            if (qcom->slots[i].timestamp_queries)
                vkDestroyQueryPool(qcom->vk->device,
                    qcom->slots[i].timestamp_queries, NULL);
            if (qcom->slots[i].fence)
                vkDestroyFence(qcom->vk->device, qcom->slots[i].fence, NULL);
        }
        if (qcom->command_pool)
            vkDestroyCommandPool(qcom->vk->device, qcom->command_pool, NULL);
        if (qcom->pipeline)
            vkDestroyPipeline(qcom->vk->device, qcom->pipeline, NULL);
        if (qcom->pipeline_layout)
            vkDestroyPipelineLayout(qcom->vk->device, qcom->pipeline_layout, NULL);
        if (qcom->descriptor_pool)
            vkDestroyDescriptorPool(qcom->vk->device, qcom->descriptor_pool, NULL);
        if (qcom->descriptor_layout)
            vkDestroyDescriptorSetLayout(qcom->vk->device, qcom->descriptor_layout, NULL);
        if (qcom->sampler)
            vkDestroySampler(qcom->vk->device, qcom->sampler, NULL);
        if (qcom->ready_semaphore)
            pl_vulkan_sem_destroy(qcom->gpu, &qcom->ready_semaphore);
        if (qcom->done_semaphore)
            pl_vulkan_sem_destroy(qcom->gpu, &qcom->done_semaphore);
    }
    free(qcom);
}

#else

struct MPVFlowVulkanQcom { int unavailable; };

MPVFlowVulkanQcom *mpvflow_vulkan_qcom_create(pl_gpu gpu,
                                             char *reason,
                                             size_t reason_size)
{
    (void) gpu;
    qcom_set_reason(reason, reason_size, "qcom_image_processing_headers_unavailable");
    return NULL;
}

bool mpvflow_vulkan_qcom_ensure(MPVFlowVulkanQcom *qcom,
                                int width, int height,
                                int grid_width, int grid_height,
                                int block_size)
{
    (void) qcom; (void) width; (void) height;
    (void) grid_width; (void) grid_height; (void) block_size;
    return false;
}

pl_tex mpvflow_vulkan_qcom_luma(MPVFlowVulkanQcom *qcom, int index)
{
    (void) qcom; (void) index;
    return NULL;
}

pl_tex mpvflow_vulkan_qcom_seed(MPVFlowVulkanQcom *qcom, int index)
{
    (void) qcom; (void) index;
    return NULL;
}

bool mpvflow_vulkan_qcom_dispatch(MPVFlowVulkanQcom *qcom)
{
    (void) qcom;
    return false;
}

void mpvflow_vulkan_qcom_destroy(MPVFlowVulkanQcom **qcom)
{
    if (qcom)
        *qcom = NULL;
}

#endif
