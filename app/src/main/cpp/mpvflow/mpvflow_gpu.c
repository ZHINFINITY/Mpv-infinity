/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 *
 * GPU-resident, clean-room block-motion interpolation prototype. All pixel
 * processing is expressed as libplacebo compute passes; no CPU texture
 * transfers, pixel reads, or CPU-side synthesis occur here.
 */
#include "mpvflow_gpu.h"
#include "mpvflow_visibility_resolver_shader.h"
#include "mpvflow_opencl.h"
#include "mpvflow_opencl_vk_bridge.h"
#include "mpvflow_vulkan_optical.h"
#include "mpvflow_vulkan_qcom.h"
#include "mpvflow_capture_io.h"

#include <libplacebo/vulkan.h>

#include <math.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define FLOW_LEVELS 3
#define FLOW_BLOCK MPVFLOW_GPU_MOTION_BLOCK_SIZE
#define FLOW_FINE_BLOCK MPVFLOW_GPU_MOTION_FINE_BLOCK_SIZE
#define FLOW_STEP MPVFLOW_GPU_MOTION_GRID_STEP
#define FLOW_REDUCTIONS 8
#define FLOW_DEFAULT_MAX_DIM 640
#define FLOW_DEFAULT_RADIUS 8
#define MPVFLOW_CAPTURE_GPU_MAX_PAIRS 4

enum FlowTimerStage {
    FLOW_TIMER_PREPROCESS = MPVFLOW_GPU_STAGE_PREPROCESS,
    FLOW_TIMER_ANALYSIS = MPVFLOW_GPU_STAGE_ANALYSIS,
    FLOW_TIMER_MOTION_FINE = MPVFLOW_GPU_STAGE_MOTION_FINE,
    FLOW_TIMER_MOTION_MIDDLE = MPVFLOW_GPU_STAGE_MOTION_MIDDLE,
    FLOW_TIMER_MOTION_COARSE = MPVFLOW_GPU_STAGE_MOTION_COARSE,
    FLOW_TIMER_SYNTHESIS = MPVFLOW_GPU_STAGE_SYNTHESIS,
    FLOW_TIMER_COUNT = MPVFLOW_GPU_STAGE_COUNT,
};

struct FlowTimerStats {
    pl_timer timer;
    uint64_t dispatches;
    uint64_t completed_samples;
    uint64_t lifetime_total_ns;
    uint64_t lifetime_max_ns;
    uint64_t samples;
    uint64_t total_ns;
    uint64_t max_ns;
};

enum {
    FLOW_DIAG_TEXTURE_ALLOC = 1u << 0,
    FLOW_DIAG_DISPATCH = 1u << 1,
    FLOW_DIAG_PREPARE_INPUT = 1u << 2,
    FLOW_DIAG_ANALYZE_PAIR = 1u << 3,
    FLOW_DIAG_SYNTHESIZE = 1u << 4,
    FLOW_DIAG_VISIBILITY = 1u << 5,
};

struct FlowLevel {
    int w, h;
    pl_tex luma[2];
    pl_tex forward;
    pl_tex backward;
};

struct MPVFlowGPUContext {
    pl_gpu gpu;                 /* borrowed */
    enum MPVFlowGPUComputeAPI requested_compute_api;
    enum MPVFlowGPUComputeAPI compute_api;
    MPVFlowOpenCLContext *opencl;
    MPVFlowOpenCLVkBridge *opencl_bridge;
    int max_dimension;
    int search_radius;
    uint32_t compute_queue_family;
    uint32_t compute_timestamp_valid_bits;
    bool compute_timestamps_supported;
    bool timestamp_compute_and_graphics;
    double timestamp_period_ns;
    int motion_log_w;
    int motion_log_h;
    uint32_t diagnostic_log_mask;
    pl_fmt fmt_rgba16f;
    pl_fmt fmt_rgba8;
    pl_pass pass_preprocess;
    pl_pass pass_luma;
    pl_pass pass_downsample;
    pl_pass pass_reduce_first;
    pl_pass pass_reduce;
    pl_pass pass_flow;
    pl_pass pass_optical_luma;
    pl_pass pass_optical_seed;
    pl_pass pass_pair_gate_first;
    pl_pass pass_pair_gate_reduce;
    pl_pass pass_pair_gate_merge;
    pl_pass pass_synthesize;
    pl_pass pass_synthesize_rgba16f;
    pl_pass pass_visibility_resolver;
    pl_pass pass_visibility_resolver_rgba16f;
    bool visibility_resolver_attempted;
    bool visibility_resolver_rgba16f_attempted;
    bool visibility_resolver_active_logged;
    MPVFlowVulkanOptical *optical;
    MPVFlowVulkanQcom *qcom;
    char diagnostic_engine[32];
    char qcom_seed_reason[160];
    char qcom_last_state[24];
    char qcom_last_reason[160];
    uint64_t qcom_pair_attempts;
    uint64_t qcom_dispatches;
    uint64_t qcom_fallbacks;
    struct FlowTimerStats timers[FLOW_TIMER_COUNT];
    uint64_t timing_window_start_ns;
    pl_fmt capture_rgba8;
    pl_pass pass_capture_color;
    pl_pass pass_capture_maps;
    pl_pass pass_capture_decisions;
    pl_tex capture_decision_output;
    pl_tex capture_hard_output;
    pl_tex capture_color_atlas;
    pl_tex capture_map_atlas;
    struct MPVFlowCaptureSession *capture_session;
    char capture_base_dir[PATH_MAX];
    char capture_session_name[81];
    char capture_engine[24];
    char capture_settings[4097];
    double capture_pair_pts0;
    double capture_pair_pts1;
    int capture_pair_index;
    int capture_sample_index;
    int capture_pair_count;
    bool capture_pair_ready;
    bool capture_paired_output;
    bool capture_armed;
    bool capture_terminal_written;
};

struct MPVFlowGPUPair {
    MPVFlowGPUContext *owner;
    MPVFlowOpenCLPair *opencl_pair;
    MPVFlowOpenCLVkBuffer *opencl_analysis[2];
    MPVFlowOpenCLVkBuffer *opencl_source[2];
    MPVFlowOpenCLVkBuffer *opencl_output;
    pl_tex opencl_source_identity[2]; /* borrowed; pair-scoped immutable frames */
    bool opencl_source_cache_ready;
    pl_tex input[2];            /* borrowed from caller */
    pl_tex output;              /* owned, reused for each timestep */
    pl_tex visibility_output;   /* optional owned resolver output */
    pl_tex visibility_mask;     /* optional owned resolver diagnostic mask */
    pl_tex optical_seed[2];     /* optional, pair-scoped coarse motion priors */
    bool optical_seed_ready;
    int w, h;
    int output_w, output_h;
    int levels;
    struct FlowLevel level[FLOW_LEVELS];
    int reductions;
    pl_tex scene[FLOW_REDUCTIONS];
    pl_tex pair_gate[2];
    pl_tex pair_scene_original; /* scene-cut scalar retained until pair release */
};

static void flow_log(pl_gpu gpu, enum pl_log_level level, const char *format, ...)
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

#ifdef VK_NV_optical_flow
static bool flow_vk_extension_enabled(pl_vulkan vk, const char *name)
{
    if (!vk || !name)
        return false;
    for (int i = 0; i < vk->num_extensions; i++) {
        if (vk->extensions[i] && strcmp(vk->extensions[i], name) == 0)
            return true;
    }
    return false;
}

static bool flow_vk_optical_feature_enabled(pl_vulkan vk)
{
    if (!vk || !vk->features)
        return false;
    const VkBaseInStructure *feature =
        (const VkBaseInStructure *) vk->features->pNext;
    for (; feature; feature = (const VkBaseInStructure *) feature->pNext) {
        if (feature->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV) {
            const VkPhysicalDeviceOpticalFlowFeaturesNV *optical =
                (const VkPhysicalDeviceOpticalFlowFeaturesNV *) feature;
            return optical->opticalFlow == VK_TRUE;
        }
    }
    return false;
}

static uint32_t flow_vk_optical_formats(
    PFN_vkGetPhysicalDeviceOpticalFlowImageFormatsNV get_formats,
    VkPhysicalDevice physical_device, VkOpticalFlowUsageFlagsNV usage,
    char *list, size_t list_size, VkResult *query_result)
{
    if (query_result)
        *query_result = VK_ERROR_EXTENSION_NOT_PRESENT;
    if (list && list_size)
        snprintf(list, list_size, "not_queried");
    if (!get_formats || !physical_device || !list || !list_size)
        return 0;

    VkOpticalFlowImageFormatInfoNV info = {
        .sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_INFO_NV,
        .usage = usage,
    };
    uint32_t count = 0;
    VkResult result = get_formats(physical_device, &info, &count, NULL);
    if (query_result)
        *query_result = result;
    if (result != VK_SUCCESS || !count) {
        snprintf(list, list_size, "none");
        return 0;
    }

    enum { FLOW_OPTICAL_FORMAT_LOG_LIMIT = 12 };
    VkOpticalFlowImageFormatPropertiesNV formats[FLOW_OPTICAL_FORMAT_LOG_LIMIT] = {0};
    uint32_t written = count < FLOW_OPTICAL_FORMAT_LOG_LIMIT
        ? count : FLOW_OPTICAL_FORMAT_LOG_LIMIT;
    for (uint32_t i = 0; i < written; i++)
        formats[i].sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_PROPERTIES_NV;
    result = get_formats(physical_device, &info, &written, formats);
    if (query_result)
        *query_result = result;
    if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
        snprintf(list, list_size, "query_failed");
        return 0;
    }

    size_t used = 0;
    for (uint32_t i = 0; i < written; i++) {
        int length = snprintf(list + used, list_size - used, "%s0x%x",
                              i ? "," : "", (unsigned) formats[i].format);
        if (length < 0 || (size_t) length >= list_size - used) {
            list[list_size - 1] = '\0';
            break;
        }
        used += (size_t) length;
    }
    return written;
}

static void flow_log_optical_flow_capability(pl_vulkan vk)
{
    if (!vk || !vk->gpu || !vk->phys_device)
        return;
    const bool extension_enabled = flow_vk_extension_enabled(
        vk, VK_NV_OPTICAL_FLOW_EXTENSION_NAME);
    const bool feature_enabled = flow_vk_optical_feature_enabled(vk);
    uint32_t family_count = 0;
    uint32_t family_queues = 0;
    uint32_t queue_families = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(vk->phys_device, &family_count, NULL);
    VkQueueFamilyProperties *families = family_count
        ? calloc(family_count, sizeof(*families)) : NULL;
    if (families) {
        uint32_t written = family_count;
        vkGetPhysicalDeviceQueueFamilyProperties(vk->phys_device, &written, families);
        for (uint32_t i = 0; i < written; i++) {
            if (families[i].queueFlags & VK_QUEUE_OPTICAL_FLOW_BIT_NV) {
                queue_families++;
                family_queues += families[i].queueCount;
            }
        }
    }
    free(families);

    VkPhysicalDeviceOpticalFlowPropertiesNV optical = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_PROPERTIES_NV,
    };
    bool properties_queried = false;
    PFN_vkGetPhysicalDeviceProperties2 get_properties2 = vk->get_proc_addr
        ? (PFN_vkGetPhysicalDeviceProperties2) vk->get_proc_addr(
              vk->instance, "vkGetPhysicalDeviceProperties2") : NULL;
    if (!get_properties2 && vk->get_proc_addr)
        get_properties2 = (PFN_vkGetPhysicalDeviceProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceProperties2KHR");
    if (extension_enabled && get_properties2) {
        VkPhysicalDeviceProperties2 properties = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
            .pNext = &optical,
        };
        get_properties2(vk->phys_device, &properties);
        properties_queried = true;
    }

    PFN_vkGetPhysicalDeviceOpticalFlowImageFormatsNV get_formats = vk->get_proc_addr
        ? (PFN_vkGetPhysicalDeviceOpticalFlowImageFormatsNV) vk->get_proc_addr(
              vk->instance, "vkGetPhysicalDeviceOpticalFlowImageFormatsNV") : NULL;
    char input_formats[192] = "not_queried";
    char output_formats[192] = "not_queried";
    VkResult input_result = VK_ERROR_EXTENSION_NOT_PRESENT;
    VkResult output_result = VK_ERROR_EXTENSION_NOT_PRESENT;
    uint32_t input_count = 0;
    uint32_t output_count = 0;
    if (extension_enabled && get_formats) {
        input_count = flow_vk_optical_formats(
            get_formats, vk->phys_device, VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV,
            input_formats, sizeof(input_formats), &input_result);
        output_count = flow_vk_optical_formats(
            get_formats, vk->phys_device, VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV,
            output_formats, sizeof(output_formats), &output_result);
    }

    const char *state = extension_enabled && feature_enabled && queue_families &&
        properties_queried && input_count && output_count
        ? "format_and_queue_ready" : extension_enabled ? "partial" : "not_exposed";
    flow_log(vk->gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=optical_flow_capability state=%s api_version=%u "
             "extension_enabled=%d feature_enabled=%d optical_queue_families=%u optical_queue_count=%u "
             "properties_queried=%d min_size=%ux%u max_size=%ux%u output_grid_flags=0x%x "
             "hint_grid_flags=0x%x bidirectional=%d hint=%d cost=%d "
             "input_reference_format_count=%u input_reference_formats=%s input_query_result=%d "
             "output_format_count=%u output_formats=%s output_query_result=%d "
             "session_execution=optional_adapter_initialization_follows",
             state, vk->api_version, extension_enabled, feature_enabled,
             queue_families, family_queues, properties_queried,
             optical.minWidth, optical.minHeight, optical.maxWidth, optical.maxHeight,
             (unsigned) optical.supportedOutputGridSizes,
             (unsigned) optical.supportedHintGridSizes,
             optical.bidirectionalFlowSupported == VK_TRUE,
             optical.hintSupported == VK_TRUE, optical.costSupported == VK_TRUE,
             input_count, input_formats, input_result,
             output_count, output_formats, output_result);
}
#else
static void flow_log_optical_flow_capability(pl_vulkan vk)
{
    if (vk && vk->gpu)
        flow_log(vk->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC event=optical_flow_capability state=not_compiled "
                 "session_execution=unavailable_without_extension_headers");
}
#endif

#if defined(VK_QCOM_image_processing) && defined(VK_QCOM_image_processing2)
static bool flow_vk_device_extension_supported(pl_vulkan vk, const char *name)
{
    if (!vk || !vk->phys_device || !vk->get_proc_addr || !name)
        return false;
    PFN_vkEnumerateDeviceExtensionProperties enumerate =
        (PFN_vkEnumerateDeviceExtensionProperties) vk->get_proc_addr(
            vk->instance, "vkEnumerateDeviceExtensionProperties");
    if (!enumerate)
        return false;

    uint32_t count = 0;
    VkResult result = enumerate(vk->phys_device, NULL, &count, NULL);
    if ((result != VK_SUCCESS && result != VK_INCOMPLETE) || !count)
        return false;
    VkExtensionProperties *extensions = calloc(count, sizeof(*extensions));
    if (!extensions)
        return false;
    uint32_t written = count;
    result = enumerate(vk->phys_device, NULL, &written, extensions);
    bool found = false;
    if (result == VK_SUCCESS || result == VK_INCOMPLETE) {
        for (uint32_t i = 0; i < written; i++) {
            if (strcmp(extensions[i].extensionName, name) == 0) {
                found = true;
                break;
            }
        }
    }
    free(extensions);
    return found;
}

static bool flow_vk_device_extension_enabled(pl_vulkan vk, const char *name)
{
    if (!vk || !name)
        return false;
    for (int i = 0; i < vk->num_extensions; i++) {
        if (vk->extensions[i] && strcmp(vk->extensions[i], name) == 0)
            return true;
    }
    return false;
}

static void flow_log_qcom_block_match_capability(pl_vulkan vk)
{
    if (!vk || !vk->gpu || !vk->phys_device)
        return;

    const bool base_extension = flow_vk_device_extension_supported(
        vk, VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME);
    const bool window_extension = base_extension && flow_vk_device_extension_supported(
        vk, VK_QCOM_IMAGE_PROCESSING_2_EXTENSION_NAME);
    const bool base_enabled = flow_vk_device_extension_enabled(
        vk, VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME);
    const bool window_enabled = flow_vk_device_extension_enabled(
        vk, VK_QCOM_IMAGE_PROCESSING_2_EXTENSION_NAME);
    VkPhysicalDeviceImageProcessingFeaturesQCOM base_features = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_FEATURES_QCOM,
    };
    VkPhysicalDeviceImageProcessing2FeaturesQCOM window_features = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_2_FEATURES_QCOM,
    };
    bool features_queried = false;
    PFN_vkGetPhysicalDeviceFeatures2 get_features2 = vk->get_proc_addr
        ? (PFN_vkGetPhysicalDeviceFeatures2) vk->get_proc_addr(
              vk->instance, "vkGetPhysicalDeviceFeatures2") : NULL;
    if (!get_features2 && vk->get_proc_addr)
        get_features2 = (PFN_vkGetPhysicalDeviceFeatures2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceFeatures2KHR");
    if (base_extension && get_features2) {
        base_features.pNext = window_extension ? &window_features : NULL;
        VkPhysicalDeviceFeatures2 features = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
            .pNext = &base_features,
        };
        get_features2(vk->phys_device, &features);
        features_queried = true;
    }

    VkPhysicalDeviceImageProcessingPropertiesQCOM base_properties = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_PROPERTIES_QCOM,
    };
    VkPhysicalDeviceImageProcessing2PropertiesQCOM window_properties = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_2_PROPERTIES_QCOM,
    };
    bool properties_queried = false;
    PFN_vkGetPhysicalDeviceProperties2 get_properties2 = vk->get_proc_addr
        ? (PFN_vkGetPhysicalDeviceProperties2) vk->get_proc_addr(
              vk->instance, "vkGetPhysicalDeviceProperties2") : NULL;
    if (!get_properties2 && vk->get_proc_addr)
        get_properties2 = (PFN_vkGetPhysicalDeviceProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceProperties2KHR");
    if (base_extension && get_properties2) {
        base_properties.pNext = window_extension ? &window_properties : NULL;
        VkPhysicalDeviceProperties2 properties = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
            .pNext = &base_properties,
        };
        get_properties2(vk->phys_device, &properties);
        properties_queried = true;
    }

    bool format_queried = false;
    bool r8_block_match_format = false;
    PFN_vkGetPhysicalDeviceFormatProperties2 get_format_properties2 = vk->get_proc_addr
        ? (PFN_vkGetPhysicalDeviceFormatProperties2) vk->get_proc_addr(
              vk->instance, "vkGetPhysicalDeviceFormatProperties2") : NULL;
    if (!get_format_properties2 && vk->get_proc_addr)
        get_format_properties2 = (PFN_vkGetPhysicalDeviceFormatProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceFormatProperties2KHR");
    if (base_extension && get_format_properties2) {
        VkFormatProperties3 format3 = {
            .sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_3,
        };
        VkFormatProperties2 format2 = {
            .sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2,
            .pNext = &format3,
        };
        get_format_properties2(vk->phys_device, VK_FORMAT_R8_UNORM, &format2);
        format_queried = true;
        r8_block_match_format =
            (format3.optimalTilingFeatures & VK_FORMAT_FEATURE_2_BLOCK_MATCHING_BIT_QCOM) != 0;
    }

    VkResult image_usage_result = VK_ERROR_EXTENSION_NOT_PRESENT;
    VkResult stage_usage_result = VK_ERROR_EXTENSION_NOT_PRESENT;
    VkResult seed_usage_result = VK_ERROR_EXTENSION_NOT_PRESENT;
    VkImageFormatProperties2 image_properties = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2,
    };
    PFN_vkGetPhysicalDeviceImageFormatProperties2 get_image_properties2 = vk->get_proc_addr
        ? (PFN_vkGetPhysicalDeviceImageFormatProperties2) vk->get_proc_addr(
              vk->instance, "vkGetPhysicalDeviceImageFormatProperties2") : NULL;
    if (!get_image_properties2 && vk->get_proc_addr)
        get_image_properties2 = (PFN_vkGetPhysicalDeviceImageFormatProperties2) vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceImageFormatProperties2KHR");
    if (base_extension && get_image_properties2) {
        VkPhysicalDeviceImageFormatInfo2 image_info = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2,
            .format = VK_FORMAT_R8_UNORM,
            .type = VK_IMAGE_TYPE_2D,
            .tiling = VK_IMAGE_TILING_OPTIMAL,
            .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                VK_IMAGE_USAGE_SAMPLE_BLOCK_MATCH_BIT_QCOM,
        };
        image_usage_result = get_image_properties2(vk->phys_device, &image_info,
                                                    &image_properties);
        image_info.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        stage_usage_result = get_image_properties2(vk->phys_device, &image_info,
                                                     &image_properties);
        image_info.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        image_info.usage = VK_IMAGE_USAGE_STORAGE_BIT;
        seed_usage_result = get_image_properties2(vk->phys_device, &image_info,
                                                    &image_properties);
    }

    const bool block_match = features_queried && base_features.textureBlockMatch == VK_TRUE;
    const bool window_match = features_queried && window_extension &&
        window_features.textureBlockMatch2 == VK_TRUE;
    const char *state = !base_extension ? "not_supported" :
        !window_extension ? "window_extension_not_supported" :
        !features_queried ? "query_unavailable" :
            block_match && window_match && properties_queried &&
                base_properties.maxBlockMatchRegion.width &&
                base_properties.maxBlockMatchRegion.height &&
                window_properties.maxBlockMatchWindow.width &&
                window_properties.maxBlockMatchWindow.height &&
                format_queried && r8_block_match_format &&
                image_usage_result == VK_SUCCESS && stage_usage_result == VK_SUCCESS &&
                seed_usage_result == VK_SUCCESS ? "exact_usage_capabilities_present" : "partial";
    flow_log(vk->gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=qcom_block_match_capability state=%s "
             "base_extension_supported=%d window_extension_supported=%d "
             "base_extension_enabled=%d window_extension_enabled=%d features_queried=%d "
             "texture_block_match_supported=%d texture_block_match2_supported=%d properties_queried=%d "
             "max_block_match_region=%ux%u max_block_match_window=%ux%u "
             "r8_format_queried=%d r8_block_match_format=%d block_input_usage_result=%d "
             "storage_stage_usage_result=%d rgba16f_seed_usage_result=%d "
             "shader_execution=optional_qcom2_custom_pass_initialization_follows",
             state, base_extension, window_extension, base_enabled, window_enabled, features_queried,
             block_match, window_match, properties_queried,
             base_properties.maxBlockMatchRegion.width,
             base_properties.maxBlockMatchRegion.height,
             window_properties.maxBlockMatchWindow.width,
             window_properties.maxBlockMatchWindow.height,
             format_queried, r8_block_match_format, image_usage_result,
             stage_usage_result, seed_usage_result);
}
#else
static void flow_log_qcom_block_match_capability(pl_vulkan vk)
{
    if (vk && vk->gpu)
        flow_log(vk->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC event=qcom_block_match_capability state=not_compiled "
                 "shader_execution=unavailable_headers_missing");
}
#endif

static void flow_log_once(MPVFlowGPUContext *ctx, uint32_t bit,
                          enum pl_log_level level, const char *format, ...)
{
    if (!ctx || (ctx->diagnostic_log_mask & bit))
        return;
    ctx->diagnostic_log_mask |= bit;
    char message[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    flow_log(ctx->gpu, level, "%s", message);
}

static void flow_opencl_log_import_stage(
    pl_gpu gpu, const char *stage_name,
    const struct MPVFlowOpenCLVkImportStageDiagnostics *diagnostics)
{
    if (!diagnostics)
        return;
    flow_log(gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=opencl_ahb_import_probe stage=%s state=properties "
             "status=%d padding_bytes=%zu request_format=0x21 request_width_bytes=%u "
             "request_height=1 request_stride=0 request_layers=1 request_usage=0x1000000 "
             "actual_format=%u actual_width=%u actual_height=%u actual_stride=%u "
             "actual_layers=%u actual_usage=0x%llx frame=32x32 row_stride=128 "
             "ahb_allocate_result=%d vk_ahb_properties_attempted=%d "
             "vk_ahb_properties_result=%d vk_allocation_size=%llu "
             "vk_memory_type_bits=0x%x vk_external_features=0x%x vk_compatible_handles=0x%x "
             "vk_export_from_imported_handles=0x%x",
             stage_name, diagnostics->bridge_status,
             diagnostics->qcom_padding_bytes, diagnostics->requested_width_bytes,
             diagnostics->actual_format, diagnostics->actual_width,
             diagnostics->actual_height, diagnostics->actual_stride,
             diagnostics->actual_layers,
             (unsigned long long)diagnostics->actual_usage,
             diagnostics->ahb_allocate_result,
             diagnostics->vk_ahb_properties_attempted,
             diagnostics->vk_ahb_properties_result,
             (unsigned long long)diagnostics->vk_ahb_allocation_size,
             diagnostics->vk_ahb_memory_type_bits,
             diagnostics->vk_external_memory_features,
             diagnostics->vk_external_memory_compatible_handle_types,
             diagnostics->vk_external_memory_export_from_imported_handle_types);
    flow_log(gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=opencl_ahb_import_probe stage=%s call=clCreateBuffer "
             "context=non_null flags=0x%llx flag_names=USE_HOST_PTR|EXT_HOST_PTR_QCOM "
             "size=%zu host_ptr=QCOM_AHB_descriptor err_out=non_null "
             "desc_allocation_type=0x%x desc_cache_policy=0x%x desc_ahb=non_null "
             "wrapper_access=%d access_qualifier=none attempted=%d wrapper_status=%d "
             "cl_error_available=%d cl_error=%d cl_release_error=%d",
             stage_name, (unsigned long long)diagnostics->qcom_flags,
             diagnostics->qcom_size_arg, diagnostics->qcom_allocation_type,
             diagnostics->qcom_cache_policy, diagnostics->wrapper_access,
             diagnostics->cl_create_buffer_attempted,
             diagnostics->opencl_wrapper_status,
             diagnostics->cl_create_buffer_attempted,
             diagnostics->cl_create_buffer_error,
             diagnostics->cl_release_mem_object_error);
    flow_log(gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=opencl_ahb_import_probe stage=%s "
             "vkCreateBuffer_attempted=%d result=%d size=%llu usage=0x%x external_handle_type=0x%x "
             "sharing_mode=EXCLUSIVE dedicated_import=1 ahb_import=1 "
             "requirements_size=%llu requirements_alignment=%llu requirements_memory_type_bits=0x%x "
             "selected_memory_type=%u vkAllocateMemory_attempted=%d size=%llu result=%d "
             "vkBindBufferMemory_attempted=%d offset=%llu result=%d",
             stage_name, diagnostics->vk_create_buffer_attempted,
             diagnostics->vk_create_buffer_result,
             (unsigned long long)diagnostics->vk_create_buffer_size,
             diagnostics->vk_create_buffer_usage,
             diagnostics->vk_external_handle_type,
             (unsigned long long)diagnostics->vk_buffer_requirement_size,
             (unsigned long long)diagnostics->vk_buffer_requirement_alignment,
             diagnostics->vk_buffer_requirement_memory_type_bits,
             diagnostics->vk_selected_memory_type,
             diagnostics->vk_allocate_memory_attempted,
             (unsigned long long)diagnostics->vk_allocate_memory_size,
             diagnostics->vk_allocate_memory_result,
             diagnostics->vk_bind_buffer_memory_attempted,
             (unsigned long long)diagnostics->vk_bind_offset,
             diagnostics->vk_bind_buffer_memory_result);
    flow_log(gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=opencl_ahb_import_probe stage=%s cleanup="
             "cl_object_released:%d,vk_buffer_destroyed:%d,vk_memory_freed:%d,ahb_released:%d",
             stage_name, diagnostics->cl_object_released,
             diagnostics->vk_buffer_destroyed, diagnostics->vk_memory_freed,
             diagnostics->ahb_released);
}

static uint64_t flow_monotonic_ns(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return (uint64_t) ts.tv_sec * 1000000000ull + (uint64_t) ts.tv_nsec;
}

static bool flow_compute_timestamp_capabilities(pl_gpu gpu,
                                                uint32_t *valid_bits,
                                                double *period_ns,
                                                bool *compute_and_graphics)
{
    if (valid_bits)
        *valid_bits = 0;
    if (period_ns)
        *period_ns = 0.0;
    if (compute_and_graphics)
        *compute_and_graphics = false;
    pl_vulkan vk = pl_vulkan_get(gpu);
    if (!vk || !vk->phys_device)
        return false;

    VkPhysicalDeviceProperties device_properties = {0};
    vkGetPhysicalDeviceProperties(vk->phys_device, &device_properties);
    const double timestamp_period_ns = device_properties.limits.timestampPeriod;
    const bool timestamp_compute_and_graphics =
        device_properties.limits.timestampComputeAndGraphics == VK_TRUE;
    if (period_ns)
        *period_ns = timestamp_period_ns;
    if (compute_and_graphics)
        *compute_and_graphics = timestamp_compute_and_graphics;

    uint32_t queue_family_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(vk->phys_device, &queue_family_count, NULL);
    if (!queue_family_count)
        return timestamp_period_ns > 0.0 && timestamp_compute_and_graphics;
    VkQueueFamilyProperties *queue_families =
        calloc(queue_family_count, sizeof(*queue_families));
    if (!queue_families)
        return false;
    vkGetPhysicalDeviceQueueFamilyProperties(vk->phys_device, &queue_family_count, queue_families);

    uint32_t compute_valid_bits = 0;
    if (vk->queue_compute.index < queue_family_count) {
        const VkQueueFamilyProperties *compute_family =
            &queue_families[vk->queue_compute.index];
        if (compute_family->queueFlags & VK_QUEUE_COMPUTE_BIT)
            compute_valid_bits = compute_family->timestampValidBits;
    }
    free(queue_families);
    if (valid_bits)
        *valid_bits = compute_valid_bits;
    return timestamp_period_ns > 0.0 &&
           (timestamp_compute_and_graphics || compute_valid_bits > 0);
}

static enum FlowTimerStage flow_timer_stage(const char *stage)
{
    if (strstr(stage, "preprocess"))
        return FLOW_TIMER_PREPROCESS;
    if (strstr(stage, "motion_search") && strstr(stage, "level_0"))
        return FLOW_TIMER_MOTION_FINE;
    if (strstr(stage, "motion_search") && strstr(stage, "level_1"))
        return FLOW_TIMER_MOTION_MIDDLE;
    if (strstr(stage, "motion_search") && strstr(stage, "level_2"))
        return FLOW_TIMER_MOTION_COARSE;
    if (strstr(stage, "synthesize"))
        return FLOW_TIMER_SYNTHESIS;
    return FLOW_TIMER_ANALYSIS;
}

static void flow_timer_poll_and_report(MPVFlowGPUContext *ctx)
{
    if (!ctx)
        return;
    static const char *const names[FLOW_TIMER_COUNT] = {
        "preprocess", "analysis", "motion_fine", "motion_middle",
        "motion_coarse", "synthesis",
    };
    for (int i = 0; i < FLOW_TIMER_COUNT; i++) {
        if (ctx->compute_timestamps_supported && ctx->timers[i].timer) {
            for (int n = 0; n < 32; n++) {
                uint64_t elapsed = pl_timer_query(ctx->gpu, ctx->timers[i].timer);
                if (!elapsed)
                    break;
                ctx->timers[i].samples++;
                ctx->timers[i].total_ns += elapsed;
                if (elapsed > ctx->timers[i].max_ns)
                    ctx->timers[i].max_ns = elapsed;
                ctx->timers[i].completed_samples++;
                ctx->timers[i].lifetime_total_ns += elapsed;
                if (elapsed > ctx->timers[i].lifetime_max_ns)
                    ctx->timers[i].lifetime_max_ns = elapsed;
            }
        }
    }

    const uint64_t now = flow_monotonic_ns();
    if (!ctx->timing_window_start_ns)
        ctx->timing_window_start_ns = now;
    if (!now || now - ctx->timing_window_start_ns < 1000000000ull)
        return;
    const double window_ms = (now - ctx->timing_window_start_ns) / 1000000.0;
    for (int i = 0; i < FLOW_TIMER_COUNT; i++) {
        struct FlowTimerStats *stats = &ctx->timers[i];
        if (stats->dispatches || stats->completed_samples) {
            const uint64_t unresolved = stats->dispatches > stats->completed_samples
                ? stats->dispatches - stats->completed_samples : 0;
            flow_log(ctx->gpu, PL_LOG_INFO,
                     "MPVFLOW_DIAGNOSTIC component=mpvflow event=gpu_timing api=Vulkan timer_source=%s "
                     "stage=%s timer_created=%d result_status=%s dispatches_total=%llu "
                     "completed_total=%llu unresolved_dispatches_total=%llu samples=%llu "
                     "average_us=%.1f maximum_us=%.1f window_ms=%.1f compute_queue_family=%u "
                     "queue_timestamp_valid_bits=%u timestamp_period_ns=%.6f "
                     "timestamp_compute_and_graphics=%d timestamp_supported=%d nonblocking=1",
                     ctx->compute_timestamps_supported && stats->timer
                         ? "libplacebo_timestamp" : "unsupported",
                     names[i], stats->timer != NULL,
                     !ctx->compute_timestamps_supported || !stats->timer ? "unsupported" :
                         stats->samples ? "sampled" : "no_ready_result_this_window",
                     (unsigned long long) stats->dispatches,
                     (unsigned long long) stats->completed_samples,
                     (unsigned long long) unresolved,
                     (unsigned long long) stats->samples,
                     stats->samples ? (double) stats->total_ns / stats->samples / 1000.0 : 0.0,
                     stats->samples ? (double) stats->max_ns / 1000.0 : 0.0,
                     window_ms, ctx->compute_queue_family, ctx->compute_timestamp_valid_bits,
                     ctx->timestamp_period_ns, ctx->timestamp_compute_and_graphics,
                     ctx->compute_timestamps_supported);
        }
        stats->samples = stats->total_ns = stats->max_ns = 0;
    }
    ctx->timing_window_start_ns = now;
}

/* Vulkan preprocessing and Vulkan-fallback passes target libplacebo's
 * Vulkan/SPIR-V backend. The selected OpenCL path lives in mpvflow_opencl.c.
 * Constants use one std430-compatible push-constant block; descriptor bindings
 * are explicit and unique within each Vulkan pass. */
struct FlowPushConstants {
    int32_t cfg[4];
    float timestep;
};
_Static_assert(sizeof(struct FlowPushConstants) == 20, "Flow push-constant ABI");
static const char shader_preprocess[] =
    "#version 450\n"
    "layout(set=0,binding=0) uniform sampler2D inTex;\n"
    "layout(set=0,binding=1,rgba8) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,pc.cfg.xy))) return; vec2 uv=(vec2(p)+vec2(0.5))/vec2(pc.cfg.xy); imageStore(outTex,p,texture(inTex,uv)); }\n"
;
static const char shader_luma[] =
    "#version 450\n"
    "layout(set=0,binding=0) uniform sampler2D inTex;\n"
    "layout(set=0,binding=1,rgba16f) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,pc.cfg.xy))) return; vec3 c=texelFetch(inTex,p,0).rgb; float y=dot(c,vec3(0.299,0.587,0.114)); imageStore(outTex,p,vec4(y,0.0,0.0,1.0)); }\n"
;

static const char shader_downsample[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D inTex;\n"
    "layout(set=0,binding=1,rgba16f) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,pc.cfg.xy))) return; ivec2 sz=imageSize(inTex); float s=0.0; float n=0.0; for(int y=0;y<2;y++) for(int x=0;x<2;x++){ ivec2 q=p*2+ivec2(x,y); if(all(lessThan(q,sz))){s+=imageLoad(inTex,q).r;n+=1.0;}} imageStore(outTex,p,vec4(s/max(n,1.0),0.0,0.0,1.0)); }\n"
;

static const char shader_reduce_first[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D aTex;\n"
    "layout(set=0,binding=1,rgba16f) readonly uniform image2D bTex;\n"
    "layout(set=0,binding=2,rgba16f) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,pc.cfg.xy))) return; ivec2 sz=imageSize(aTex); float s=0.0; float n=0.0; for(int y=0;y<16;y++) for(int x=0;x<16;x++){ ivec2 q=p*16+ivec2(x,y); if(all(lessThan(q,sz))){ s+=abs(imageLoad(aTex,q).r-imageLoad(bTex,q).r); n+=1.0; }} imageStore(outTex,p,vec4(s/max(n,1.0),0.0,0.0,1.0)); }\n"
;

static const char shader_reduce[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D inTex;\n"
    "layout(set=0,binding=1,rgba16f) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,pc.cfg.xy))) return; ivec2 sz=imageSize(inTex); float s=0.0; float n=0.0; for(int y=0;y<16;y++) for(int x=0;x<16;x++){ ivec2 q=p*16+ivec2(x,y); if(all(lessThan(q,sz))){s+=imageLoad(inTex,q).r;n+=1.0;}} imageStore(outTex,p,vec4(s/max(n,1.0),0.0,0.0,1.0)); }\n"
;

static const char shader_optical_luma[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D inTex;\n"
    "layout(set=0,binding=1,r8) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy),outSize=imageSize(outTex),srcSize=imageSize(inTex); if(any(greaterThanEqual(p,outSize))) return; ivec2 q=min(p,srcSize-1); imageStore(outTex,p,vec4(imageLoad(inTex,q).r,0.0,0.0,1.0)); }\n"
;

static const char shader_optical_seed[] =
    "#version 450\n"
    "layout(set=0,binding=0) uniform isampler2D vectorTex;\n"
    "layout(set=0,binding=1,rgba16f) writeonly uniform image2D seedTex;\n"
    "layout(set=0,binding=2,rgba16f) readonly uniform image2D sceneTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 g=ivec2(gl_GlobalInvocationID.xy),grid=imageSize(seedTex); if(any(greaterThanEqual(g,grid))) return; if(imageLoad(sceneTex,ivec2(0)).r>0.1882353){ imageStore(seedTex,g,vec4(0.0,0.0,1.0,0.0)); return; } int scale=max(pc.cfg.z,1); ivec2 p=min(g*4,pc.cfg.xy-ivec2(8)); vec2 center=(vec2(p)+vec2(4.0))*float(scale); ivec2 vectorSize=textureSize(vectorTex,0),q=clamp(ivec2(floor(center/8.0)),ivec2(0),vectorSize-1); ivec2 fixedVector=texelFetch(vectorTex,q,0).rg; vec2 flow=(vec2(fixedVector)/32.0)/float(scale); imageStore(seedTex,g,vec4(flow,0.0,1.0)); }\n"
;

/* Pyramid flow samples represent block centers, not origins. Mapping a child
 * center into a half-sized parent level and back to its 8x8 block origin is
 * p/2 + childBlock/4 - 4: -3 for fine 4x4 and -2 for middle 8x8. Sampling p/2
 * instead spatially leads the prediction near independently moving boundaries.
 * Hardware seeds are already indexed by their own grid and are not remapped. */
static const char shader_flow[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D srcTex;\n"
    "layout(set=0,binding=1,rgba16f) readonly uniform image2D dstTex;\n"
    "layout(set=0,binding=2,rgba16f) readonly uniform image2D parentTex;\n"
    "layout(set=0,binding=3,rgba16f) writeonly uniform image2D flowTex;\n"
    "layout(set=0,binding=4,rgba16f) readonly uniform image2D sceneTex;\n"
    "layout(set=0,binding=5,rgba8) readonly uniform image2D srcColorTex;\n"
    "layout(set=0,binding=6,rgba8) readonly uniform image2D dstColorTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "float flowGridAxis(float p,float extent,int blockSize,int gridCount){if(gridCount<=1)return 0.0;float lastAnchor=max(extent-float(blockSize),0.0),previousAnchor=min(float(gridCount-2)*4.0,lastAnchor),lastIndex=float(gridCount-1);if(p<=previousAnchor)return clamp(p/4.0,0.0,lastIndex);if(lastAnchor<=previousAnchor)return lastIndex;return clamp(lastIndex-1.0+(p-previousAnchor)/(lastAnchor-previousAnchor),0.0,lastIndex);}\n"
    "int sadAt(ivec2 p,ivec2 d,ivec2 sz,int cutoff,int blockSize,bool colorAware){ int lumaCost=0,colorCost=0; for(int y=0;y<blockSize;y++) for(int x=0;x<blockSize;x++){ ivec2 sp=p+ivec2(x,y),dp=sp+d; float a=imageLoad(srcTex,sp).r; float b=imageLoad(dstTex,dp).r; lumaCost+=int(abs(a-b)*255.0+0.5); if(colorAware&&((x&1)==0)&&((y&1)==0)){ vec3 ca=imageLoad(srcColorTex,sp).rgb,cb=imageLoad(dstColorTex,dp).rgb; float colorDelta=(abs(ca.r-cb.r)+abs(ca.g-cb.g)+abs(ca.b-cb.b))/3.0; colorCost+=int(colorDelta*255.0+0.5); } int cost=colorAware?(3*lumaCost+4*colorCost+2)/4:lumaCost; if(cost>cutoff) return cost; } return colorAware?(3*lumaCost+4*colorCost+2)/4:lumaCost; }\n"
    "float subpixelOffset(float minusCost,float centerCost,float plusCost){ float curvature=minusCost-2.0*centerCost+plusCost; if(centerCost>minusCost||centerCost>plusCost||curvature<=0.0001) return 0.0; return clamp(0.5*(minusCost-plusCost)/curvature,-0.5,0.5); }\n"
    /* An exact integer match is already the minimum of nonnegative SAD. Fitting
     * a parabola to asymmetric neighboring costs can move it by half a pixel,
     * while retaining the old zero residual. Do not manufacture that error. */
    "float refineAxis(ivec2 p,ivec2 v,ivec2 axis,ivec2 sz,int blockSize,int centerCost,bool colorAware){ if(centerCost==0) return 0.0; ivec2 m=v-axis,q=v+axis,block=ivec2(blockSize); if(any(lessThan(p+m,ivec2(0)))||any(lessThan(p+q,ivec2(0)))||any(greaterThan(p+m,sz-block))||any(greaterThan(p+q,sz-block))) return 0.0; int cm=sadAt(p,m,sz,2147483647,blockSize,colorAware),cp=sadAt(p,q,sz,2147483647,blockSize,colorAware); return subpixelOffset(float(cm),float(centerCost),float(cp)); }\n"
    "void main(){ ivec2 g=ivec2(gl_GlobalInvocationID.xy); ivec2 grid=imageSize(flowTex); if(any(greaterThanEqual(g,grid))) return; if(imageLoad(sceneTex,ivec2(0)).r>0.1882353){ imageStore(flowTex,g,vec4(0.0,0.0,1.0,0.0)); return; } ivec2 sz=pc.cfg.xy; int blockSize=(pc.cfg.w>>8); if(blockSize!=4) blockSize=8; ivec2 block=ivec2(blockSize),p=min(g*4,sz-block); bool opticalSeed=(pc.cfg.w&2)!=0,qcomSeed=(pc.cfg.w&4)!=0,hasParent=(pc.cfg.w&1)!=0||opticalSeed||qcomSeed,colorAware=(pc.cfg.w&8)!=0; ivec2 center=ivec2(0),qcomCandidate=ivec2(0); bool qcomCandidateValid=false; float parentResidual=0.0,parentUniqueness=1.0; if(qcomSeed){ vec4 qcomFlow=imageLoad(parentTex,g); qcomCandidate=ivec2(round(qcomFlow.rg)); } else if(hasParent){ vec4 parentFlow; if(opticalSeed){ parentFlow=imageLoad(parentTex,g); } else { ivec2 pg=imageSize(parentTex),parentSize=(sz+ivec2(1))/2; vec2 parentOrigin=vec2(float(blockSize)*0.25-4.0); vec2 parentPos=vec2(flowGridAxis(float(p.x)*0.5+parentOrigin.x,float(parentSize.x),8,pg.x),flowGridAxis(float(p.y)*0.5+parentOrigin.y,float(parentSize.y),8,pg.y)); ivec2 a=clamp(ivec2(floor(parentPos)),ivec2(0),pg-1); ivec2 b=min(a+1,pg-1); vec2 f=fract(parentPos); vec4 top=mix(imageLoad(parentTex,a),imageLoad(parentTex,ivec2(b.x,a.y)),f.x); vec4 bot=mix(imageLoad(parentTex,ivec2(a.x,b.y)),imageLoad(parentTex,b),f.x); parentFlow=mix(top,bot,f.y); } center=ivec2(round(parentFlow.rg*(opticalSeed?1.0:2.0))); parentResidual=parentFlow.b; parentUniqueness=parentFlow.a; } center=clamp(center,-p,sz-block-p); int radius=pc.cfg.z; if(hasParent&&!qcomSeed&&parentResidual>0.12&&parentUniqueness>0.0005)radius=min(radius*2,16); ivec2 lo=max(-p,center-ivec2(radius)); ivec2 hi=min(sz-block-p,center+ivec2(radius)); if(qcomSeed) qcomCandidateValid=all(greaterThanEqual(qcomCandidate,lo))&&all(lessThanEqual(qcomCandidate,hi)); ivec2 bestVector=center; int best=sadAt(p,bestVector,sz,2147483647,blockSize,colorAware),second=2147483647; bool stopOnCenterTie=false; if(qcomCandidateValid&&!all(equal(qcomCandidate,bestVector))){ int c=sadAt(p,qcomCandidate,sz,best==0?0:second,blockSize,colorAware); if(best==0){if(c==0){second=0; if(!all(equal(bestVector,center))&&(qcomCandidate.y<bestVector.y||(qcomCandidate.y==bestVector.y&&qcomCandidate.x<bestVector.x))) bestVector=qcomCandidate;} else if(second!=0) second=1;} else if(c<best){second=best;best=c;bestVector=qcomCandidate;} else if(c==best){if(second>c) second=c; if(!all(equal(bestVector,center))&&(qcomCandidate.y<bestVector.y||(qcomCandidate.y==bestVector.y&&qcomCandidate.x<bestVector.x))) bestVector=qcomCandidate;} else if(c<second) second=c; }\n"
    "for(int ring=1;ring<=radius&&!stopOnCenterTie;ring++) for(int dy=-ring;dy<=ring&&!stopOnCenterTie;dy++) for(int dx=-ring;dx<=ring&&!stopOnCenterTie;dx++){ if(max(abs(dx),abs(dy))!=ring) continue; ivec2 d=center+ivec2(dx,dy); if(any(lessThan(d,lo))||any(greaterThan(d,hi))) continue; if(all(equal(d,bestVector))) continue; if(qcomCandidateValid&&all(equal(d,qcomCandidate))) continue; if(best==0&&second==0){ if(all(equal(bestVector,center))){stopOnCenterTie=true;continue;} if(d.y>bestVector.y||(d.y==bestVector.y&&d.x>=bestVector.x)) continue; } int c=sadAt(p,d,sz,best==0?0:second,blockSize,colorAware); if(best==0){if(c==0){second=0; if(!all(equal(bestVector,center))&&(d.y<bestVector.y||(d.y==bestVector.y&&d.x<bestVector.x))) bestVector=d;} else if(second!=0) second=1;} else if(c<best){second=best;best=c;bestVector=d;} else if(c==best){if(second>c) second=c; if(!all(equal(bestVector,center))&&(d.y<bestVector.y||(d.y==bestVector.y&&d.x<bestVector.x))) bestVector=d;} else if(c<second) second=c; }\n"
    "vec2 refined=vec2(bestVector); refined.x+=refineAxis(p,bestVector,ivec2(1,0),sz,blockSize,best,colorAware); refined.y+=refineAxis(p,bestVector,ivec2(0,1),sz,blockSize,best,colorAware); float uniqueness=second<2147483647?clamp(float(second-best)/max(float(second),1.0),0.0,1.0):0.0; imageStore(flowTex,g,vec4(refined,float(best)/(float(blockSize*blockSize)*255.0),uniqueness)); }\n"
;

/* Synthesis must solve and validate the SAME spatially continuous vector field.
 * Do not change from bilinear motion to a nearest-luma-guided selector after the
 * inverse iteration: at reduced analysis resolution that selector introduces
 * multi-output-pixel steps and islands of rejected/repositioned outline pixels.
 * Confidence controls common warp trust, never the vector's sampling position or
 * temporal alpha. When either endpoint is unsupported, fade to a complete source
 * crossfade instead of normalizing the other endpoint to an opaque patch.
 * Scene cuts remain endpoint cuts; the 0.75 OUTPUT-pixel inverse ceiling remains.
 * Reusing the bilinear field also removes four edge-guide evaluations (20 luma
 * loads and 16 exponentials) per moving output pixel, without extra GPU passes. */
/* Soft Blend must not switch coordinate/compositing policies at confidence .28
 * or a binary boundaryConflict contour. Keep the established bounded temporal
 * fallback for conflicts, then fade continuously to the visibility-normalized
 * endpoint warp using the SAME boundary trust as the default policy. The .15
 * shortcut is exact: both endpoint visibility weights are zero below it, so the
 * full expression would also return fallback. No extra pass or search is added. */
#if MPVFLOW_PAIR_GHOST_EXPERIMENT
/* Experimental pair-wide abstention. Cached once after both fine motion fields.
 * The entire pair uses endpoint crossfade only when broad ambiguity AND broad
 * photometric mismatch are present. Low-cost aperture/flat pans are not rejected
 * merely for low uniqueness. No CPU readback, per-pixel fallback mask, flow edit,
 * quality/resolution/FPS change, or Soft Blend setting change is introduced.
 * The second evidence plane adds spatially supported low-residual gap rescue
 * without another dispatch or any cross-pair/CPU state. */
static const char shader_pair_gate_first[] =
    "#version 450\n"
    "// MPVFLOW_PAIR_GHOST_SPATIAL_GAP_V3: normalized fine-grid evidence, not pixel masks.\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D forwardTex;\n"
    "layout(set=0,binding=1,rgba16f) readonly uniform image2D backwardTex;\n"
    "layout(set=0,binding=2,rgba16f) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8,local_size_y=8) in;\n"
    "bool finite4(vec4 v){return !any(isnan(v))&&!any(isinf(v));}\n"
    "void main(){\n"
    " ivec2 tile=ivec2(gl_GlobalInvocationID.xy),sz=imageSize(forwardTex);\n"
    " ivec2 tiles=(sz+15)/16;if(any(greaterThanEqual(tile,tiles)))return;\n"
    " vec4 counts=vec4(0.0),support=vec4(0.0);bool invalid=false;\n"
    " for(int y=0;y<16;y++)for(int x=0;x<16;x++){\n"
    "  ivec2 q=tile*16+ivec2(x,y);if(any(greaterThanEqual(q,sz)))continue;\n"
    "  vec4 f=imageLoad(forwardTex,q),b=imageLoad(backwardTex,q);\n"
    "  if(!finite4(f)||!finite4(b)){invalid=true;continue;}\n"
    "  counts+=vec4((f.z>=.04||f.w<=.08)?1.:0.,(b.z>=.04||b.w<=.08)?1.:0.,f.z>=.04?1.:0.,b.z>=.04?1.:0.);\n"
    "  ivec2 votes=ivec2(0);\n"
    "  if(f.z>=.003||b.z>=.003)for(int ny=-1;ny<=1;ny++)for(int nx=-1;nx<=1;nx++){\n"
    "   ivec2 n=q+ivec2(nx,ny);if(any(lessThan(n,ivec2(0)))||any(greaterThanEqual(n,sz)))continue;\n"
    "   vec4 nf=imageLoad(forwardTex,n),nb=imageLoad(backwardTex,n);\n"
    "   votes+=ivec2(finite4(nf)&&nf.z>=.003?1:0,finite4(nb)&&nb.z>=.003?1:0);\n"
    "  }\n"
    "  support.r+=f.z>=.003&&votes.x>=5?1.:0.;support.g+=b.z>=.003&&votes.y>=5?1.:0.;\n"
    " }\n"
    " // Divide by all fine-grid cells BEFORE half storage: every sum stays <=1.\n"
    " // Edge tiles retain their actual count; no unweighted tile means or half overflow.\n"
    " vec4 value=counts/float(sz.x*sz.y);\n"
    " vec4 spatial=support/float(sz.x*sz.y);\n"
    " if(invalid){value=vec4(uintBitsToFloat(0x7fc00000u));spatial=value;}\n"
    " imageStore(outTex,tile,value);imageStore(outTex,tile+ivec2(0,tiles.y),spatial);\n"
    "}\n"
;

static const char shader_pair_gate_reduce[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D inTex;\n"
    "layout(set=0,binding=1,rgba16f) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8,local_size_y=8) in;\n"
    "void main(){ivec2 p=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(p,pc.cfg.xy)))return;\n"
    " ivec2 sz=imageSize(inTex);int bandH=sz.y/2;vec4 s=vec4(0.0);\n"
    " for(int y=0;y<16;y++)for(int x=0;x<16;x++){if(x<sz.x&&y<bandH)s+=imageLoad(inTex,ivec2(x,y+p.y*bandH));}\n"
    " imageStore(outTex,p,s);\n"
    "}\n"
;

static const char shader_pair_gate_merge[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D sceneTex;\n"
    "layout(set=0,binding=1,rgba16f) readonly uniform image2D gateTex;\n"
    "layout(set=0,binding=2,rgba16f) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8,local_size_y=8) in;\n"
    "void main(){ivec2 p=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(p,pc.cfg.xy)))return;\n"
    " vec4 scene=imageLoad(sceneTex,ivec2(0)),g=imageLoad(gateTex,ivec2(0));\n"
    " bool valid=!any(isnan(g))&&!any(isinf(g))&&all(greaterThanEqual(g,vec4(0.0)))&&all(lessThanEqual(g,vec4(1.01)));\n"
    " float weak=valid?min(g.r,g.g):0.0,bad=valid?min(g.b,g.a):0.0;\n"
    " vec4 spatial=imageLoad(gateTex,ivec2(0,1));\n"
    " bool spatialValid=!any(isnan(spatial))&&!any(isinf(spatial))&&all(greaterThanEqual(spatial,vec4(0.0)))&&all(lessThanEqual(spatial,vec4(1.01)));\n"
    " float supported=spatialValid?min(spatial.r,spatial.g):0.0;\n"
    " // Preserve V2. Rescue localized ambiguity only with bilateral 5-of-9 support.\n"
    " // Pair-local evidence: no history can leak across cuts, seeks or discontinuities.\n"
    " bool original=weak>=.10&&bad>=.01;\n"
    " bool gap=spatialValid&&scene.r>=.003&&weak>=.04&&weak<=.25&&supported>=.008;\n"
    " bool ghost=valid&&scene.r<=.1882353&&(original||gap);\n"
    " imageStore(outTex,p,vec4(scene.r,ghost?1.:0.,weak,bad));\n"
    "}\n"
;

#endif

static const char shader_synthesize[] =
    "#version 450\n"
    "layout(set=0,binding=0) uniform sampler2D frame0; layout(set=0,binding=1) uniform sampler2D frame1;\n"
    "layout(set=0,binding=2,rgba16f) readonly uniform image2D fwdTex;\n"
    "layout(set=0,binding=3,rgba16f) readonly uniform image2D backTex;\n"
    "layout(set=0,binding=4,rgba16f) readonly uniform image2D sceneTex;\n"
    "layout(set=0,binding=5,rgba16f) readonly uniform image2D luma0Tex;\n"
    "layout(set=0,binding=6,rgba16f) readonly uniform image2D luma1Tex;\n"
    "layout(set=0,binding=7,rgba8) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "#ifdef MPVFLOW_CAPTURE_DECISIONS\n"
    "layout(set=0,binding=8,rgba8) writeonly uniform image2D decisionTex;\n"
    "vec4 flowCaptureResult(vec4 value,int id,float confidence,int reasons){ imageStore(decisionTex,ivec2(gl_GlobalInvocationID.xy),vec4(float(id)/255.0,clamp(confidence,0.0,1.0),float(reasons)/255.0,1.0)); return value; }\n"
    "#define FLOW_BEGIN int captureReason=0;\n"
    "#define FLOW_REASON(bits) captureReason|=(bits);\n"
    "#define FLOW_RESULT(value,id,confidence,reasons) flowCaptureResult((value),(id),(confidence),(reasons))\n"
    "#else\n"
    "#define FLOW_BEGIN\n"
    "#define FLOW_REASON(bits)\n"
    "#define FLOW_RESULT(value,id,confidence,reasons) value\n"
    "#endif\n"
    "vec4 flowImageAt(ivec2 p,int direction){ return direction==0?imageLoad(fwdTex,p):imageLoad(backTex,p); }\n"
    "float lumaAt(vec2 p,int direction){ ivec2 sz=direction==0?imageSize(luma0Tex):imageSize(luma1Tex); ivec2 q=clamp(ivec2(round(p)),ivec2(0),sz-1); return direction==0?imageLoad(luma0Tex,q).r:imageLoad(luma1Tex,q).r; }\n"
    "float flowGridAxis(float p,float extent,int blockSize,int gridCount){if(gridCount<=1)return 0.0;float lastAnchor=max(extent-float(blockSize),0.0),previousAnchor=min(float(gridCount-2)*4.0,lastAnchor),lastIndex=float(gridCount-1);if(p<=previousAnchor)return clamp(p/4.0,0.0,lastIndex);if(lastAnchor<=previousAnchor)return lastIndex;return clamp(lastIndex-1.0+(p-previousAnchor)/(lastAnchor-previousAnchor),0.0,lastIndex); }\n"
    /* Convert fine-block center positions to the origin coordinates expected
     * by flowGridAxis: SAD samples pixel indices 0..3, whose centroid is 1.5.
     * flowP and colorAt use texel-index coordinates, not texel-corner positions.
     * Apply this same offset to the Jacobian below; a half-analysis-pixel shift
     * otherwise biases varying motion and can trigger source-crossfade fallback. */
    "vec2 flowGridCoord(vec2 p,int direction){ivec2 extent=direction==0?imageSize(luma0Tex):imageSize(luma1Tex),grid=imageSize(fwdTex);vec2 center=p-vec2(1.5);return vec2(flowGridAxis(center.x,float(extent.x),4,grid.x),flowGridAxis(center.y,float(extent.y),4,grid.y));}\n"
    "vec4 flowAtBilinear(vec2 p,int direction){ ivec2 sz=imageSize(fwdTex); vec2 g=flowGridCoord(p,direction); ivec2 a=ivec2(floor(g)); ivec2 b=min(a+1,sz-1); vec2 t=fract(g); vec4 v00=flowImageAt(a,direction),v10=flowImageAt(ivec2(b.x,a.y),direction); vec4 v01=flowImageAt(ivec2(a.x,b.y),direction),v11=flowImageAt(b,direction); return mix(mix(v00,v10,t.x),mix(v01,v11,t.x),t.y); }\n"
    /* Fixed-point inversion can oscillate for an invertible zoom/rotation. Use
     * the same four flow texels to differentiate the bilinear field analytically,
     * including its irregular terminal interval and clamped exterior. Two bounded
     * Newton updates replace (not supplement) the two fixed-point updates below.
     * A correction is limited to one grid cell per axis; unsafe Jacobians retain
     * the old update. Final output-pixel residual/cycle/bounds checks still decide
     * support. Coherent aperture support is likewise validated in BOTH policies:
     * excluding Soft Blend reintroduced unwarped old/new silhouette ghosts. */
    "float flowGridAxisDerivative(float p,float extent,int blockSize,int gridCount){if(gridCount<=1)return 0.0;float lastAnchor=max(extent-float(blockSize),0.0),previousAnchor=min(float(gridCount-2)*4.0,lastAnchor);if(p<0.0||p>lastAnchor)return 0.0;if(p<previousAnchor)return 0.25;if(lastAnchor<=previousAnchor)return 0.0;return 1.0/(lastAnchor-previousAnchor); }\n"
    "vec4 flowAtBilinearJacobian(vec2 p,int direction,out mat2 df){ ivec2 sz=imageSize(fwdTex),extent=direction==0?imageSize(luma0Tex):imageSize(luma1Tex); vec2 center=p-vec2(1.5),g=flowGridCoord(p,direction); ivec2 a=ivec2(floor(g)),b=min(a+1,sz-1); vec2 t=fract(g); vec4 v00=flowImageAt(a,direction),v10=flowImageAt(ivec2(b.x,a.y),direction),v01=flowImageAt(ivec2(a.x,b.y),direction),v11=flowImageAt(b,direction); vec2 x0=(v10-v00).xy,x1=(v11-v01).xy,y0=(v01-v00).xy,y1=(v11-v10).xy; vec2 dfdx=mix(x0,x1,t.y),dfdy=mix(y0,y1,t.x); float sx=flowGridAxisDerivative(center.x,float(extent.x),4,sz.x),sy=flowGridAxisDerivative(center.y,float(extent.y),4,sz.y); df=mat2(vec2(dfdx.x*sx,dfdx.y*sx),vec2(dfdy.x*sy,dfdy.y*sy)); return mix(mix(v00,v10,t.x),mix(v01,v11,t.x),t.y); }\n"
    "bool flowMatFinite(mat2 m){return !any(isnan(m[0]))&&!any(isinf(m[0]))&&!any(isnan(m[1]))&&!any(isinf(m[1]));}\n"
    "vec4 colorAt(sampler2D s,vec2 p){ ivec2 sz=textureSize(s,0); p=clamp(p,vec2(0.0),vec2(sz-1)); return textureLod(s,(p+vec2(0.5))/vec2(sz),0.0); }\n"
    /* Keep unsupported coordinates rejected, but fade common warp support over
     * the last analysis pixel inside the frame. A binary gate here bypasses
     * continuous warp trust when a source or cycle crosses a pan boundary. */
    "float pointInBounds(vec2 p,ivec2 sz){ float margin=min(min(p.x,p.y),min(float(sz.x-1)-p.x,float(sz.y-1)-p.y)); return smoothstep(0.0,1.0,margin); }\n"
    "float reliability(vec4 flow,float cycle,float valid){ float cycleQuality=1.0-smoothstep(0.75,4.0,cycle); float matchQuality=1.0-smoothstep(0.025,0.22,flow.z); float uniquenessQuality=smoothstep(0.08,0.30,flow.w); return cycleQuality*matchQuality*uniquenessQuality*valid; }\n"
    "bool flowFinite(vec4 v){return !any(isnan(v))&&!any(isinf(v));}\n"
    /* A 4x4 straight edge has an aperture tie along its tangent: raw uniqueness
     * is not evidence that an otherwise exact pan vector is wrong. Recover only
     * tightly matching, interior, cycle-consistent trajectories whose existing
     * full-resolution samples agree. Reuse the inverse/cycle samples: no new
     * texture reads, search work, passes, resolution changes or temporal weights.
     * This does not solve genuinely ambiguous repeated imagery or occlusions. */
    "float apertureQuality(float residual,float cycle,float coherence,vec3 difference){ float colorError=max(max(abs(difference.r),abs(difference.g)),abs(difference.b)); return (1.0-smoothstep(0.005,0.025,residual))*(1.0-smoothstep(0.25,0.75,cycle))*(1.0-smoothstep(0.125,0.5,coherence))*(1.0-smoothstep(0.01,0.04,colorError)); }\n"
    "vec4 softBlendColor(ivec2 p,vec4 ca,vec4 cb,float confidence,bool preserveMotionBlend){ float softBlendAlpha=clamp(0.5+(pc.timestep-0.5)*1.35,0.0,1.0); vec4 sourceBlend=mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),softBlendAlpha),warpBlend=mix(ca,cb,pc.timestep); float warpWeight=smoothstep(0.04,0.48,confidence); if(preserveMotionBlend) warpWeight=min(warpWeight,0.40); return mix(sourceBlend,warpBlend,warpWeight); }\n"
    "float neighborhoodQuality(vec2 p,int direction,vec2 center){ vec2 d=vec2(2.0); vec2 n0=flowAtBilinear(p-d,direction).xy,n1=flowAtBilinear(p+vec2(d.x,-d.y),direction).xy,n2=flowAtBilinear(p+vec2(-d.x,d.y),direction).xy,n3=flowAtBilinear(p+d,direction).xy; float spread=(length(n0-center)+length(n1-center)+length(n2-center)+length(n3-center))*0.25; return 1.0-smoothstep(0.65,2.8,spread); }\n"
    /* Default-only quality rescue: a unique low-SAD forward match may qualify
     * despite weak reverse match quality, never despite invalid reverse geometry.
     * Reuse both inverse/cycle trajectories and full-resolution color agreement.
     * Elevate only by the complete smooth certificate, not endpoint max trust.
     * The .30-.75 uniqueness fade is guarded by spatial/half-float pixel probes,
     * a narrower .30-.45 fade recreates a sharp support contour. */
/* EXPERIMENTAL region3 transition taper: attenuation only, no new samples,
 * motion changes, or reliability bypass. Reuse local RGB and neighborhood
 * evidence to approach endpoint crossfade before a hard rejection contour.
 * Fold attenuation into the single boundaryTrust certificate so the existing
 * hard-boundary negative control still removes the complete certificate.
 * The app's Soft Blend path remains unchanged. */
    "float region3TransitionTrust(float lumaDelta,float colorDelta,float chromaDelta,float uniqueness){ float edgeTrust=1.0-smoothstep(0.025,0.08,lumaDelta); float chromaTrust=1.0-smoothstep(0.06,0.12,colorDelta)*smoothstep(0.025,0.06,chromaDelta); float ambiguousTrust=1.0-(1.0-smoothstep(0.04,0.08,uniqueness))*smoothstep(0.06,0.10,colorDelta); return edgeTrust*chromaTrust*ambiguousTrust; }\n"
    "void main(){ FLOW_BEGIN ivec2 p=ivec2(gl_GlobalInvocationID.xy); ivec2 outSize=imageSize(outTex),motionSize=pc.cfg.zw; if(any(greaterThanEqual(p,outSize))) return; vec2 srcP=vec2(p),uv=(srcP+vec2(0.5))/vec2(outSize),flowP=clamp(uv*vec2(motionSize)-vec2(0.5),vec2(0.0),vec2(motionSize-1)); float cut=imageLoad(sceneTex,ivec2(0)).r; if(cut>0.1882353){ vec4 c=pc.timestep<=0.5?texelFetch(frame0,p,0):texelFetch(frame1,p,0); imageStore(outTex,p,FLOW_RESULT(c,1,0.0,0)); return; } bool pairGhost=(pc.cfg.x&0x40000000)==0&&imageLoad(sceneTex,ivec2(0)).g>0.5; if(pairGhost){ vec4 c=mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),pc.timestep); c.a=1.0; imageStore(outTex,p,FLOW_RESULT(c,11,0.0,0)); return; } { float staticLumaDelta=abs(lumaAt(flowP,0)-lumaAt(flowP,1)); if(staticLumaDelta<=0.0039216){ vec4 fastForward=flowAtBilinear(flowP,0),fastBackward=flowAtBilinear(flowP,1); if(dot(fastForward.xy,fastForward.xy)<=0.0025&&dot(fastBackward.xy,fastBackward.xy)<=0.0025){ vec4 static0=texelFetch(frame0,p,0),static1=texelFetch(frame1,p,0); float colorDelta=max(max(abs(static0.r-static1.r),abs(static0.g-static1.g)),abs(static0.b-static1.b)); bool staticPixel=colorDelta<=0.0039216; if(staticPixel){ vec4 outc=mix(static0,static1,pc.timestep); outc.a=1.0; imageStore(outTex,p,FLOW_RESULT(outc,2,1.0,0)); return; } } } } /* One continuous field for inverse iteration, final samples and cycles. */ vec4 f=flowAtBilinear(flowP,0),b=flowAtBilinear(flowP,1); if(!flowFinite(f)||!flowFinite(b)){ vec4 c=mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),pc.timestep); imageStore(outTex,p,FLOW_RESULT(c,(pc.timestep<0.5?3:4),0.0,0)); return; } vec2 initialForward=f.xy,initialBackward=b.xy; vec2 a=flowP-pc.timestep*f.xy,z=flowP-(1.0-pc.timestep)*b.xy; for(int i=0;i<2;i++){ mat2 dfA,dfZ; f=flowAtBilinearJacobian(a,0,dfA); b=flowAtBilinearJacobian(z,1,dfZ); vec2 rA=a+pc.timestep*f.xy-flowP,rZ=z+(1.0-pc.timestep)*b.xy-flowP; mat2 jA=mat2(1.0)+pc.timestep*dfA,jZ=mat2(1.0)+(1.0-pc.timestep)*dfZ; float detA=determinant(jA),detZ=determinant(jZ); bool newtonA=flowFinite(f)&&flowMatFinite(dfA)&&flowMatFinite(jA)&&!isnan(detA)&&!isinf(detA)&&detA>0.05; bool newtonZ=flowFinite(b)&&flowMatFinite(dfZ)&&flowMatFinite(jZ)&&!isnan(detZ)&&!isinf(detZ)&&detZ>0.05; vec2 dA=newtonA?inverse(jA)*rA:vec2(0.0),dZ=newtonZ?inverse(jZ)*rZ:vec2(0.0); newtonA=newtonA&&flowFinite(vec4(dA,0.0,0.0))&&all(lessThanEqual(abs(dA),vec2(4.0))); newtonZ=newtonZ&&flowFinite(vec4(dZ,0.0,0.0))&&all(lessThanEqual(abs(dZ),vec2(4.0))); a=newtonA?a-dA:flowP-pc.timestep*f.xy; z=newtonZ?z-dZ:flowP-(1.0-pc.timestep)*b.xy; } f=flowAtBilinear(a,0); b=flowAtBilinear(z,1); if(!flowFinite(f)||!flowFinite(b)||any(isnan(a))||any(isinf(a))||any(isnan(z))||any(isinf(z))){ vec4 c=mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),pc.timestep); imageStore(outTex,p,FLOW_RESULT(c,(pc.timestep<0.5?3:4),0.0,0)); return; }vec2 cycle0=a+f.xy,cycle1=z+b.xy; vec4 backAt0=flowAtBilinear(cycle0,1); vec4 forwardAt1=flowAtBilinear(cycle1,0); float cycleError0=flowFinite(backAt0)?length(f.xy+backAt0.xy):1e20; float cycleError1=flowFinite(forwardAt1)?length(b.xy+forwardAt1.xy):1e20; vec2 scale=vec2(outSize)/vec2(motionSize); float inverseResidual0=length((a+pc.timestep*f.xy-flowP)*scale),inverseResidual1=length((z+(1.0-pc.timestep)*b.xy-flowP)*scale); bool cycleSamplesFinite=flowFinite(backAt0)&&flowFinite(forwardAt1); float inverseResidual0Valid=1.0-smoothstep(0.25,0.75,inverseResidual0), inverseResidual1Valid=1.0-smoothstep(0.25,0.75,inverseResidual1); vec2 srcA=srcP+(a-flowP)*scale,srcZ=srcP+(z-flowP)*scale; vec4 ca=colorAt(frame0,srcA); vec4 cb=colorAt(frame1,srcZ); float valid0=pointInBounds(a,motionSize)*pointInBounds(cycle0,motionSize)*(cycleSamplesFinite?inverseResidual0Valid:0.0); float valid1=pointInBounds(z,motionSize)*pointInBounds(cycle1,motionSize)*(cycleSamplesFinite?inverseResidual1Valid:0.0); float confidence0=reliability(f,cycleError0,valid0); float confidence1=reliability(b,cycleError1,valid1); bool softBlend=(pc.cfg.x&0x40000000)!=0,preserveMotionBlend=(pc.cfg.x&0x20000000)!=0; if(cycleSamplesFinite&&min(f.w,b.w)<0.30&&min(valid0,valid1)>0.90&&max(f.z,b.z)<0.025&&max(cycleError0,cycleError1)<0.75){ float trajectorySpread=max(max(length(f.xy-initialForward),length(b.xy-initialBackward)),max(length(f.xy-forwardAt1.xy),length(b.xy-backAt0.xy))); float aperture=apertureQuality(max(f.z,b.z),max(cycleError0,cycleError1),trajectorySpread,ca.rgb-cb.rgb)*smoothstep(0.90,1.0,min(valid0,valid1)); confidence0=max(confidence0,aperture*valid0); confidence1=max(confidence1,aperture*valid1); } bool oneSidedRescue=cycleSamplesFinite&&min(valid0,valid1)>0.75&&f.w>=0.20&&f.z<=0.05&&max(cycleError0,cycleError1)<1.0; float rescueQuality=0.0; if(!softBlend&&oneSidedRescue){ float rescueSpread=max(max(length(f.xy-initialForward),length(b.xy-initialBackward)),max(length(f.xy-forwardAt1.xy),length(b.xy-backAt0.xy))); rescueQuality=reliability(f,cycleError0,valid0)*apertureQuality(f.z,max(cycleError0,cycleError1),rescueSpread,ca.rgb-cb.rgb)*smoothstep(0.90,1.0,min(valid0,valid1))*smoothstep(0.30,0.75,f.w); } float confidence=softBlend?max(confidence0,confidence1):min(confidence0,confidence1); if(!softBlend) confidence=max(confidence,rescueQuality); vec4 outc; if(confidence<=0.0||(softBlend&&confidence<=0.15)){ outc=softBlend?softBlendColor(p,ca,cb,confidence,preserveMotionBlend):FLOW_RESULT(mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),pc.timestep),11,confidence,0); } else { float lumaDelta=abs(dot(ca.rgb,vec3(0.299,0.587,0.114))-dot(cb.rgb,vec3(0.299,0.587,0.114))); float colorDelta=max(max(abs(ca.r-cb.r),abs(ca.g-cb.g)),abs(ca.b-cb.b)); float chromaDelta=max(abs((ca.r-ca.g)-(cb.r-cb.g)),abs((ca.b-ca.g)-(cb.b-cb.g))); float forwardBackwardError=length(f.xy+b.xy); float region3LocalTrust=1.0; float boundaryTrust=1.0-smoothstep(0.04,0.08,lumaDelta); boundaryTrust*=1.0-smoothstep(0.08,0.12,colorDelta)*smoothstep(0.03,0.06,chromaDelta); boundaryTrust*=1.0-(1.0-smoothstep(0.04,0.08,min(f.w,b.w)))*smoothstep(0.06,0.10,colorDelta); bool chromaBoundary=colorDelta>0.12&&chromaDelta>0.06; bool boundaryConflict=lumaDelta>0.08||chromaBoundary||(min(f.w,b.w)<0.08&&colorDelta>0.10); FLOW_REASON((lumaDelta>0.08?1:0)|(chromaBoundary?2:0)|((min(f.w,b.w)<0.08&&colorDelta>0.10)?4:0)) if(boundaryTrust>0.0&&(lumaDelta>0.04||colorDelta>0.06||forwardBackwardError>2.0)){ float neighborhood0=neighborhoodQuality(a,0,f.xy),neighborhood1=neighborhoodQuality(z,1,b.xy),neighborhoodMinimum=min(neighborhood0,neighborhood1); bool neighborhoodConflict=(neighborhoodMinimum<0.30&&(lumaDelta>0.06||colorDelta>0.08))||(forwardBackwardError>3.0&&neighborhoodMinimum<0.45); float neighborhoodActivation=max(max(smoothstep(0.04,0.06,lumaDelta),smoothstep(0.06,0.08,colorDelta)),smoothstep(2.0,3.0,forwardBackwardError)); boundaryTrust*=mix(1.0,smoothstep(0.45,0.70,neighborhoodMinimum),neighborhoodActivation); region3LocalTrust=mix(1.0,smoothstep(0.30,0.75,neighborhoodMinimum),neighborhoodActivation); boundaryConflict=neighborhoodConflict; FLOW_REASON(neighborhoodConflict?8:0) } if(!softBlend) boundaryTrust*=region3LocalTrust*region3TransitionTrust(lumaDelta,colorDelta,chromaDelta,min(f.w,b.w)); if(softBlend){ vec4 fallback=softBlendColor(p,ca,cb,confidence,preserveMotionBlend); float visibility0=smoothstep(0.15,0.45,confidence0),visibility1=smoothstep(0.15,0.45,confidence1); float weight0=(1.0-pc.timestep)*visibility0,weight1=pc.timestep*visibility1,total=weight0+weight1; vec4 visibilityWarped=(ca*weight0+cb*weight1+fallback*max(0.0001-total,0.0))/max(total,0.0001); float softWarpTrust=smoothstep(0.0,0.60,confidence)*boundaryTrust; outc=FLOW_RESULT(mix(fallback,visibilityWarped,softWarpTrust),boundaryConflict?12:0,confidence,boundaryConflict?captureReason:0); } else if(boundaryConflict) outc=FLOW_RESULT(mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),pc.timestep),12,confidence,captureReason); else { vec4 warped=mix(ca,cb,pc.timestep); float warpTrust=smoothstep(0.0,0.60,confidence)*boundaryTrust; outc=warped; if(warpTrust<1.0){ vec4 sourceBlend=mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),pc.timestep); outc=mix(sourceBlend,warped,warpTrust); } outc=FLOW_RESULT(outc,0,confidence,0); } } outc.a=1.0; imageStore(outTex,p,outc); }\n";

static pl_pass make_pass(pl_gpu gpu, const char *glsl,
                         struct pl_desc *descs, int ndesc)
{
    return pl_pass_create(gpu, pl_pass_params(
        .type = PL_PASS_COMPUTE,
        .push_constants_size = sizeof(struct FlowPushConstants),
        .descriptors = descs,
        .num_descriptors = ndesc,
        .glsl_shader = glsl));
}

static char *shader_replace_once(const char *source, const char *needle,
                                  const char *replacement)
{
    if (!source || !needle || !replacement || !needle[0])
        return NULL;
    const char *match = strstr(source, needle);
    if (!match || strstr(match + strlen(needle), needle))
        return NULL;
    const size_t source_len = strlen(source);
    const size_t needle_len = strlen(needle);
    const size_t replacement_len = strlen(replacement);
    const size_t prefix_len = (size_t) (match - source);
    const size_t output_len = source_len - needle_len + replacement_len;
    char *output = malloc(output_len + 1);
    if (!output)
        return NULL;
    memcpy(output, source, prefix_len);
    memcpy(output + prefix_len, replacement, replacement_len);
    memcpy(output + prefix_len + replacement_len, match + needle_len,
           source_len - prefix_len - needle_len + 1);
    return output;
}

static bool ensure_visibility_resolver_pass(MPVFlowGPUContext *ctx, bool rgba16f)
{
    if (!ctx || ctx->compute_api != MPVFLOW_GPU_COMPUTE_VULKAN)
        return false;

    pl_pass *pass = rgba16f ? &ctx->pass_visibility_resolver_rgba16f
                            : &ctx->pass_visibility_resolver;
    bool *attempted = rgba16f ? &ctx->visibility_resolver_rgba16f_attempted
                              : &ctx->visibility_resolver_attempted;
    if (*attempted)
        return *pass != NULL;
    *attempted = true;

    struct pl_desc resolver_desc[] = {
        { .name = "sceneTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "fwdTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "backTex", .type = PL_DESC_STORAGE_IMG, .binding = 2,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "luma0Tex", .type = PL_DESC_STORAGE_IMG, .binding = 3,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "luma1Tex", .type = PL_DESC_STORAGE_IMG, .binding = 4,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "frame0", .type = PL_DESC_SAMPLED_TEX, .binding = 5 },
        { .name = "frame1", .type = PL_DESC_SAMPLED_TEX, .binding = 6 },
        { .name = "motionWarped", .type = PL_DESC_SAMPLED_TEX, .binding = 7 },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 8,
          .access = PL_DESC_ACCESS_WRITEONLY },
        { .name = "maskTex", .type = PL_DESC_STORAGE_IMG, .binding = 9,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    const char *glsl = shader_visibility_resolver;
    char *rgba16f_shader = NULL;
    if (rgba16f) {
        rgba16f_shader = shader_replace_once(
            glsl,
            "layout(set=0,binding=8,rgba8) writeonly uniform image2D outTex;",
            "layout(set=0,binding=8,rgba16f) writeonly uniform image2D outTex;");
        glsl = rgba16f_shader;
    }
    if (glsl)
        *pass = make_pass(ctx->gpu, glsl, resolver_desc, 10);
    free(rgba16f_shader);
    if (!*pass) {
        flow_log_once(ctx, FLOW_DIAG_VISIBILITY, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=visibility_resolver state=unavailable "
                      "backend=vulkan format=%s reason=shader_compile_or_pass_creation_failed "
                      "fallback=production_output",
                      rgba16f ? "rgba16f" : "rgba8");
    }
    return *pass != NULL;
}

static pl_tex create_texture_ex(MPVFlowGPUContext *ctx, int w, int h, pl_fmt fmt,
                                bool sampleable, bool blit_src, bool blit_dst,
                                const char *stage)
{
    pl_tex texture = pl_tex_create(ctx->gpu, pl_tex_params(
        .w = w, .h = h, .d = 0, .format = fmt,
        .sampleable = sampleable, .storable = true,
        .blit_src = blit_src, .blit_dst = blit_dst));
    if (!texture)
        flow_log_once(ctx, FLOW_DIAG_TEXTURE_ALLOC, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=compute_runtime state=failed stage=%s "
                      "reason=texture_allocation width=%d height=%d format=%s sampleable=%d storable=1",
                      stage, w, h, fmt ? fmt->name : "missing", sampleable);
    return texture;
}

static pl_tex create_texture(MPVFlowGPUContext *ctx, int w, int h, pl_fmt fmt,
                             bool sampleable, const char *stage)
{
    return create_texture_ex(ctx, w, h, fmt, sampleable, false, false, stage);
}

static void destroy_tex(MPVFlowGPUContext *ctx, pl_tex *tex)
{
    if (tex && *tex)
        pl_tex_destroy(ctx->gpu, tex);
}

static bool run_pass(MPVFlowGPUContext *ctx, pl_pass pass,
                     struct pl_desc_binding *bindings, int nbindings,
                     const int cfg[4], float t, int w, int h,
                     const char *stage)
{
    if (!pass || w <= 0 || h <= 0) {
        flow_log_once(ctx, FLOW_DIAG_DISPATCH, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=compute_dispatch state=failed stage=%s "
                      "pass_available=%d width=%d height=%d reason=%s",
                      stage, !!pass, w, h, !pass ? "pass_missing" : "invalid_dimensions");
        return false;
    }
    struct FlowPushConstants push = {
        .cfg = { cfg[0], cfg[1], cfg[2], cfg[3] },
        .timestep = t,
    };
    enum FlowTimerStage timer_stage = flow_timer_stage(stage);
    pl_timer timer = ctx->timers[timer_stage].timer;
    struct pl_pass_run_params run = {
        .pass = pass,
        .desc_bindings = bindings,
        .push_constants = &push,
        .compute_groups = { (w + 7) / 8, (h + 7) / 8, 1 },
        .timer = timer,
    };
    (void) nbindings; /* descriptor array length is fixed by the pass */
    ctx->timers[timer_stage].dispatches++;
    pl_pass_run(ctx->gpu, &run);
    return true;
}

#if MPVFLOW_PAIR_GHOST_EXPERIMENT
static bool analyze_pair_ghost(MPVFlowGPUContext *ctx, MPVFlowGPUPair *pair)
{
    /* All supported analysis dimensions <=640 give a fine grid <=160x160.
     * Thus one 16x16 tile pass and one sum pass suffice. */
    if (ctx->compute_api != MPVFLOW_GPU_COMPUTE_VULKAN)
        return true;
    struct FlowLevel *fine = &pair->level[0];
    const int gw = (int) fine->forward->params.w;
    const int gh = (int) fine->forward->params.h;
    const int tw = (gw + 15) / 16, th = (gh + 15) / 16;
    if (tw > 16 || th > 16 || !ctx->pass_pair_gate_first ||
        !ctx->pass_pair_gate_reduce || !ctx->pass_pair_gate_merge)
        return false;
    pair->pair_gate[0] = create_texture(ctx, tw, 2 * th, ctx->fmt_rgba16f, false,
                                       "pair_ghost_tile_evidence");
    pair->pair_gate[1] = create_texture(ctx, 1, 2, ctx->fmt_rgba16f, false,
                                       "pair_ghost_pair_evidence");
    pl_tex merged = create_texture(ctx, 1, 1, ctx->fmt_rgba16f, false,
                                   "pair_ghost_scene_metadata");
    if (!pair->pair_gate[0] || !pair->pair_gate[1] || !merged) {
        destroy_tex(ctx, &merged);
        return false;
    }
    int cfg[4] = { tw, th, 0, 0 };
    struct pl_desc_binding first[3] = {
        { .object = fine->forward }, { .object = fine->backward },
        { .object = pair->pair_gate[0] },
    };
    bool ok = run_pass(ctx, ctx->pass_pair_gate_first, first, 3, cfg, 0.0f,
                       tw, th, "pair_ghost_gate_first");
    cfg[0] = 1; cfg[1] = 2;
    struct pl_desc_binding reduce[2] = {
        { .object = pair->pair_gate[0] }, { .object = pair->pair_gate[1] },
    };
    ok = ok && run_pass(ctx, ctx->pass_pair_gate_reduce, reduce, 2, cfg, 0.0f,
                        1, 2, "pair_ghost_gate_reduce");
    cfg[1] = 1;
    struct pl_desc_binding merge[3] = {
        { .object = pair->scene[pair->reductions - 1] },
        { .object = pair->pair_gate[1] }, { .object = merged },
    };
    ok = ok && run_pass(ctx, ctx->pass_pair_gate_merge, merge, 3, cfg, 0.0f,
                        1, 1, "pair_ghost_scene_merge");
    if (!ok) {
        destroy_tex(ctx, &merged);
        return false;
    }
    /* Retain both textures until pair release; no in-flight resource destruction. */
    pair->pair_scene_original = pair->scene[pair->reductions - 1];
    pair->scene[pair->reductions - 1] = merged;
    return true;
}
#endif

static bool allocate_pair_textures(MPVFlowGPUContext *ctx,
                                   MPVFlowGPUPair *pair)
{
    int w = pair->w, h = pair->h;
    for (int i = 0; i < FLOW_LEVELS && w >= FLOW_BLOCK && h >= FLOW_BLOCK; i++) {
        struct FlowLevel *lv = &pair->level[i];
        lv->w = w; lv->h = h;
        lv->luma[0] = create_texture(ctx, w, h, ctx->fmt_rgba16f, false, "pyramid_luma_frame0");
        lv->luma[1] = create_texture(ctx, w, h, ctx->fmt_rgba16f, false, "pyramid_luma_frame1");
        const int block_size = i == 0 ? FLOW_FINE_BLOCK : FLOW_BLOCK;
        const int grid_w = (w - block_size + FLOW_STEP - 1) / FLOW_STEP + 1;
        const int grid_h = (h - block_size + FLOW_STEP - 1) / FLOW_STEP + 1;
        lv->forward = create_texture(ctx, grid_w, grid_h,
                                     ctx->fmt_rgba16f, false, "motion_forward_field");
        lv->backward = create_texture(ctx, grid_w, grid_h,
                                      ctx->fmt_rgba16f, false, "motion_backward_field");
        if (!lv->luma[0] || !lv->luma[1] || !lv->forward || !lv->backward)
            return false;
        pair->levels++;
        w = (w + 1) / 2;
        h = (h + 1) / 2;
    }
    if (!pair->levels)
        return false;

    /* First reduction computes the frame-wide average absolute luma delta in
     * 16x16 tiles. Further passes reduce those tile means to one scalar. */
    w = (pair->w + 15) / 16;
    h = (pair->h + 15) / 16;
    for (int i = 0; i < FLOW_REDUCTIONS; i++) {
        pair->scene[i] = create_texture(ctx, w, h, ctx->fmt_rgba16f, false,
                                        "scene_cut_reduction_texture");
        if (!pair->scene[i])
            return false;
        pair->reductions++;
        if (w == 1 && h == 1)
            break;
        w = (w + 15) / 16;
        h = (h + 15) / 16;
    }
    return w == 1 && h == 1;
}

static void destroy_pair_textures(MPVFlowGPUContext *ctx,
                                  MPVFlowGPUPair *pair)
{
    if (!pair)
        return;
    if (ctx && ctx->opencl && ctx->opencl_bridge &&
        (pair->opencl_pair || pair->opencl_analysis[0] || pair->opencl_analysis[1] ||
         pair->opencl_source[0] || pair->opencl_source[1] || pair->opencl_output)) {
        enum MPVFlowOpenCLStatus finished = mpvflow_opencl_finish(ctx->opencl);
        if (finished == MPVFLOW_OPENCL_OK) {
            MPVFlowOpenCLVkBuffer *buffers[] = {
                pair->opencl_analysis[0], pair->opencl_analysis[1],
                pair->opencl_source[0], pair->opencl_source[1], pair->opencl_output,
            };
            for (size_t i = 0; i < sizeof(buffers) / sizeof(buffers[0]); i++) {
                if (buffers[i])
                    (void) mpvflow_opencl_vk_buffer_opencl_finished(buffers[i]);
            }
            if (pair->opencl_pair)
                mpvflow_opencl_pair_release(ctx->opencl, pair->opencl_pair);
            MPVFlowOpenCLVkBuffer **owned[] = {
                &pair->opencl_analysis[0], &pair->opencl_analysis[1],
                &pair->opencl_source[0], &pair->opencl_source[1], &pair->opencl_output,
            };
            for (size_t i = 0; i < sizeof(owned) / sizeof(owned[0]); i++) {
                if (*owned[i]) {
                    enum MPVFlowOpenCLVkBridgeStatus status =
                        mpvflow_opencl_vk_buffer_destroy(*owned[i]);
                    if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
                        flow_log(ctx->gpu, PL_LOG_ERR,
                                 "MPVFLOW_DIAGNOSTIC event=opencl_buffer_release state=failed status=%d",
                                 status);
                    } else {
                        *owned[i] = NULL;
                    }
                }
            }
        } else {
            flow_log(ctx->gpu, PL_LOG_ERR,
                     "MPVFLOW_DIAGNOSTIC event=opencl_buffer_release state=deferred reason=queue_finish_failed status=%d",
                     finished);
        }
        if (finished == MPVFLOW_OPENCL_OK)
            pair->opencl_pair = NULL;
    }
    destroy_tex(ctx, &pair->output);
    destroy_tex(ctx, &pair->visibility_output);
    destroy_tex(ctx, &pair->visibility_mask);
    destroy_tex(ctx, &pair->optical_seed[0]);
    destroy_tex(ctx, &pair->optical_seed[1]);
    for (int i = 0; i < FLOW_LEVELS; i++) {
        destroy_tex(ctx, &pair->level[i].luma[0]);
        destroy_tex(ctx, &pair->level[i].luma[1]);
        destroy_tex(ctx, &pair->level[i].forward);
        destroy_tex(ctx, &pair->level[i].backward);
    }
    for (int i = 0; i < FLOW_REDUCTIONS; i++)
        destroy_tex(ctx, &pair->scene[i]);
    for (int i = 0; i < 2; i++)
        destroy_tex(ctx, &pair->pair_gate[i]);
    destroy_tex(ctx, &pair->pair_scene_original);
}

static enum MPVFlowOpenCLVkBridgeStatus flow_opencl_interop_probe(
    MPVFlowGPUContext *ctx)
{
    const uint32_t probe_dimension = 64;
    const uint32_t probe_pattern = 0xff808080u;
    MPVFlowOpenCLVkBuffer *input[2] = {NULL, NULL};
    MPVFlowOpenCLVkBuffer *output = NULL;
    MPVFlowOpenCLPair *pair = NULL;
    pl_tex output_texture = NULL;
    enum MPVFlowOpenCLVkBridgeStatus status =
        MPVFLOW_OPENCL_VK_BRIDGE_OPENCL_ERROR;
    enum MPVFlowOpenCLStatus opencl_status = MPVFLOW_OPENCL_INVALID;
    int opencl_status_valid = 0;
    const char *stage = "buffer_allocation";

    if (!ctx || !ctx->opencl || !ctx->opencl_bridge)
        return MPVFLOW_OPENCL_VK_BRIDGE_INVALID;

    for (int i = 0; i < 2; i++) {
        status = mpvflow_opencl_vk_buffer_create(
            ctx->opencl_bridge, probe_dimension, probe_dimension,
            MPVFLOW_OPENCL_READ_ONLY, &input[i]);
        if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK || !input[i])
            goto done;
    }
    status = mpvflow_opencl_vk_buffer_create(
        ctx->opencl_bridge, probe_dimension, probe_dimension,
        MPVFLOW_OPENCL_WRITE_ONLY, &output);
    if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK || !output)
        goto done;

    stage = "vulkan_fill_input";
    for (int i = 0; i < 2; i++) {
        status = mpvflow_opencl_vk_buffer_opencl_finished(input[i]);
        if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
            goto done;
        status = mpvflow_opencl_vk_fill_buffer(input[i], probe_pattern);
        if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
            goto done;
        status = mpvflow_opencl_vk_buffer_opencl_started(input[i]);
        if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
            goto done;
    }

    stage = "opencl_pair_analysis";
    opencl_status = mpvflow_opencl_analyze_pair(
        ctx->opencl,
        mpvflow_opencl_vk_buffer_get_opencl(input[0]),
        mpvflow_opencl_vk_buffer_get_opencl(input[1]), &pair);
    opencl_status_valid = 1;
    if (opencl_status != MPVFLOW_OPENCL_OK) {
        status = MPVFLOW_OPENCL_VK_BRIDGE_OPENCL_ERROR;
        goto done;
    }
    opencl_status = mpvflow_opencl_finish(ctx->opencl);
    opencl_status_valid = 1;
    if (opencl_status != MPVFLOW_OPENCL_OK) {
        status = MPVFLOW_OPENCL_VK_BRIDGE_OPENCL_ERROR;
        goto done;
    }
    for (int i = 0; i < 2; i++) {
        status = mpvflow_opencl_vk_buffer_opencl_finished(input[i]);
        if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
            goto done;
    }

    stage = "opencl_synthesis";
    status = mpvflow_opencl_vk_buffer_opencl_finished(output);
    if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
        goto done;
    for (int i = 0; i < 2; i++) {
        status = mpvflow_opencl_vk_buffer_opencl_started(input[i]);
        if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
            goto done;
    }
    status = mpvflow_opencl_vk_buffer_opencl_started(output);
    if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
        goto done;
    opencl_status = mpvflow_opencl_synthesize(
        ctx->opencl, pair,
        mpvflow_opencl_vk_buffer_get_opencl(input[0]),
        mpvflow_opencl_vk_buffer_get_opencl(input[1]),
        mpvflow_opencl_vk_buffer_get_opencl(output), 0.5f);
    opencl_status_valid = 1;
    if (opencl_status != MPVFLOW_OPENCL_OK) {
        status = MPVFLOW_OPENCL_VK_BRIDGE_OPENCL_ERROR;
        goto done;
    }
    opencl_status = mpvflow_opencl_finish(ctx->opencl);
    opencl_status_valid = 1;
    if (opencl_status != MPVFLOW_OPENCL_OK) {
        status = MPVFLOW_OPENCL_VK_BRIDGE_OPENCL_ERROR;
        goto done;
    }
    for (int i = 0; i < 2; i++) {
        status = mpvflow_opencl_vk_buffer_opencl_finished(input[i]);
        if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
            goto done;
    }
    status = mpvflow_opencl_vk_buffer_opencl_finished(output);
    if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
        goto done;

    stage = "vulkan_copyback";
    output_texture = create_texture_ex(ctx, probe_dimension, probe_dimension,
                                       ctx->fmt_rgba8, true, false, true,
                                       "opencl_interop_probe_output");
    if (!output_texture) {
        status = MPVFLOW_OPENCL_VK_BRIDGE_VULKAN_ERROR;
        goto done;
    }
    status = mpvflow_opencl_vk_copy_buffer_to_texture(
        ctx->opencl_bridge, output, output_texture);

done:
    if (ctx->opencl && mpvflow_opencl_finish(ctx->opencl) == MPVFLOW_OPENCL_OK) {
        for (int i = 0; i < 2; i++)
            if (input[i])
                (void) mpvflow_opencl_vk_buffer_opencl_finished(input[i]);
        if (output)
            (void) mpvflow_opencl_vk_buffer_opencl_finished(output);
    }
    if (pair)
        mpvflow_opencl_pair_release(ctx->opencl, pair);
    destroy_tex(ctx, &output_texture);
    for (int i = 0; i < 2; i++) {
        if (input[i]) {
            enum MPVFlowOpenCLVkBridgeStatus destroy_status =
                mpvflow_opencl_vk_buffer_destroy(input[i]);
            if (destroy_status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
                flow_log(ctx->gpu, PL_LOG_ERR,
                         "MPVFLOW_DIAGNOSTIC event=compute_backend state=probe_cleanup_failed "
                         "buffer=input index=%d status=%d reason=%s",
                         i, destroy_status,
                         mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge));
                if (status == MPVFLOW_OPENCL_VK_BRIDGE_OK)
                    status = destroy_status;
            }
        }
    }
    if (output) {
        enum MPVFlowOpenCLVkBridgeStatus destroy_status =
            mpvflow_opencl_vk_buffer_destroy(output);
        if (destroy_status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
            flow_log(ctx->gpu, PL_LOG_ERR,
                     "MPVFLOW_DIAGNOSTIC event=compute_backend state=probe_cleanup_failed "
                     "buffer=output status=%d reason=%s",
                     destroy_status,
                     mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge));
            if (status == MPVFLOW_OPENCL_VK_BRIDGE_OK)
                status = destroy_status;
        }
    }
    if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
        const char *reason = mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge);
        flow_log(ctx->gpu, PL_LOG_ERR,
                 "MPVFLOW_DIAGNOSTIC event=compute_backend state=probe_failed "
                 "stage=%s status=%d reason=%s opencl_status_valid=%d opencl_status=%d size=%ux%u cpu_readback=no",
                 stage, status, reason ? reason : "none", opencl_status_valid,
                 opencl_status,
                 probe_dimension, probe_dimension);
    } else {
        flow_log(ctx->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC event=compute_backend state=probe_passed "
                 "probe=kernel_interop_roundtrip size=%ux%u stages=vulkan_fill,opencl_analyze_synthesize,vulkan_copyback "
                 "cpu_readback=no",
                 probe_dimension, probe_dimension);
    }
    return status;
}

MPVFlowGPUContext *mpvflow_gpu_create(pl_gpu gpu,
                                      const struct MPVFlowGPUConfig *config,
                                      enum MPVFlowGPUResult *status,
                                      struct MPVFlowGPUCreateDiagnostics *diagnostics)
{
    if (diagnostics)
        *diagnostics = (struct MPVFlowGPUCreateDiagnostics) { .stage = "not_started" };
    if (status)
        *status = MPVFLOW_GPU_INVALID;
    if (!gpu) {
        if (diagnostics) diagnostics->stage = "gpu_missing";
        return NULL;
    }
    flow_log_optical_flow_capability(pl_vulkan_get(gpu));
    flow_log_qcom_block_match_capability(pl_vulkan_get(gpu));
    const enum MPVFlowGPUComputeAPI requested_api = config
        ? config->compute_api : MPVFLOW_GPU_COMPUTE_AUTO;
    if (requested_api < MPVFLOW_GPU_COMPUTE_AUTO ||
        requested_api > MPVFLOW_GPU_COMPUTE_VULKAN) {
        if (diagnostics) diagnostics->stage = "compute_api_validation";
        if (status) *status = MPVFLOW_GPU_INVALID;
        return NULL;
    }
    if (diagnostics) {
        diagnostics->max_pushc_size = gpu->limits.max_pushc_size;
        diagnostics->required_pushc_size = sizeof(struct FlowPushConstants);
    }
    flow_log(gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=compute_capabilities backend=%s glsl_compute=%d "
             "glsl_vulkan=%d glsl_version=%d max_pushc_size=%zu required_pushc_size=%zu "
             "max_tex_2d_dim=%u max_group_threads=%u max_group_size=%ux%ux%u compute_queues=%u",
             gpu->glsl.vulkan ? "vulkan" : "non_vulkan", gpu->glsl.compute,
             gpu->glsl.vulkan, gpu->glsl.version, gpu->limits.max_pushc_size,
             sizeof(struct FlowPushConstants), gpu->limits.max_tex_2d_dim,
             gpu->glsl.max_group_threads, gpu->glsl.max_group_size[0],
             gpu->glsl.max_group_size[1], gpu->glsl.max_group_size[2],
             gpu->limits.compute_queues);
    if (!gpu->glsl.compute || !gpu->glsl.vulkan || gpu->glsl.version < 450) {
        if (diagnostics) diagnostics->stage = "compute_api_capability";
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_setup state=failed stage=compute_api_capability "
                 "failed_compute=%d failed_vulkan=%d failed_glsl_version=%d",
                 !gpu->glsl.compute, !gpu->glsl.vulkan, gpu->glsl.version < 450);
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED_API;
        return NULL;
    }
    if (gpu->limits.max_pushc_size < sizeof(struct FlowPushConstants)) {
        if (diagnostics) diagnostics->stage = "push_constant_limit";
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_setup state=failed stage=push_constant_limit "
                 "max_pushc_size=%zu required_pushc_size=%zu",
                 gpu->limits.max_pushc_size, sizeof(struct FlowPushConstants));
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED_API;
        return NULL;
    }
    MPVFlowGPUContext *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) {
        if (diagnostics) diagnostics->stage = "context_allocation";
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_setup state=failed stage=context_allocation reason=out_of_memory");
        if (status) *status = MPVFLOW_GPU_ERROR;
        return NULL;
    }
    ctx->gpu = gpu;
    ctx->requested_compute_api = requested_api;
    ctx->compute_api = MPVFLOW_GPU_COMPUTE_VULKAN;
    pl_vulkan vk = pl_vulkan_get(gpu);
    ctx->compute_queue_family = vk ? vk->queue_compute.index : UINT32_MAX;
    ctx->compute_timestamps_supported = flow_compute_timestamp_capabilities(
        gpu, &ctx->compute_timestamp_valid_bits, &ctx->timestamp_period_ns,
        &ctx->timestamp_compute_and_graphics);
    ctx->max_dimension = config && config->max_dimension > 0
        ? config->max_dimension : FLOW_DEFAULT_MAX_DIM;
    if (ctx->max_dimension > FLOW_DEFAULT_MAX_DIM)
        ctx->max_dimension = FLOW_DEFAULT_MAX_DIM;
    ctx->search_radius = config && config->search_radius > 0
        ? config->search_radius : FLOW_DEFAULT_RADIUS;
    if (ctx->search_radius > FLOW_DEFAULT_RADIUS)
        ctx->search_radius = FLOW_DEFAULT_RADIUS;
    if (ctx->search_radius < 1)
        ctx->search_radius = 1;
    if (ctx->max_dimension < FLOW_BLOCK ||
        ctx->max_dimension > (int) gpu->limits.max_tex_2d_dim) {
        if (diagnostics) diagnostics->stage = "texture_dimension_limit";
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_setup state=failed stage=texture_dimension_limit "
                 "requested_dimension=%d max_tex_2d_dim=%u min_required=%d",
                 ctx->max_dimension, gpu->limits.max_tex_2d_dim, FLOW_BLOCK);
        free(ctx);
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED_DIMENSION;
        return NULL;
    }

    ctx->fmt_rgba16f = pl_find_named_fmt(gpu, "rgba16f");
    ctx->fmt_rgba8 = pl_find_named_fmt(gpu, "rgba8");
    if (diagnostics) {
        diagnostics->rgba16f_found = ctx->fmt_rgba16f != NULL;
        diagnostics->rgba8_found = ctx->fmt_rgba8 != NULL;
        diagnostics->rgba16f_caps = ctx->fmt_rgba16f ? (uint32_t) ctx->fmt_rgba16f->caps : 0;
        diagnostics->rgba8_caps = ctx->fmt_rgba8 ? (uint32_t) ctx->fmt_rgba8->caps : 0;
    }
    if (!ctx->fmt_rgba16f || !ctx->fmt_rgba8 ||
        !(ctx->fmt_rgba16f->caps & PL_FMT_CAP_STORABLE) ||
        !(ctx->fmt_rgba8->caps & PL_FMT_CAP_STORABLE) ||
        !(ctx->fmt_rgba8->caps & PL_FMT_CAP_SAMPLEABLE) ||
        !ctx->fmt_rgba16f->glsl_format ||
        !ctx->fmt_rgba8->glsl_format ||
        strcmp(ctx->fmt_rgba16f->glsl_format, "rgba16f") ||
        strcmp(ctx->fmt_rgba8->glsl_format, "rgba8")) {
        if (diagnostics) diagnostics->stage = "required_storage_formats";
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_setup state=failed stage=required_storage_formats "
                 "rgba16f_found=%d rgba16f_caps=0x%x rgba16f_glsl=%s rgba8_found=%d "
                 "rgba8_caps=0x%x rgba8_glsl=%s required_rgba16f_storable=1 "
                 "required_rgba8_storable=1 required_rgba8_sampleable=1",
                 ctx->fmt_rgba16f != NULL,
                 diagnostics ? diagnostics->rgba16f_caps : (ctx->fmt_rgba16f ? (uint32_t) ctx->fmt_rgba16f->caps : 0),
                 ctx->fmt_rgba16f && ctx->fmt_rgba16f->glsl_format ? ctx->fmt_rgba16f->glsl_format : "missing",
                 ctx->fmt_rgba8 != NULL,
                 diagnostics ? diagnostics->rgba8_caps : (ctx->fmt_rgba8 ? (uint32_t) ctx->fmt_rgba8->caps : 0),
                 ctx->fmt_rgba8 && ctx->fmt_rgba8->glsl_format ? ctx->fmt_rgba8->glsl_format : "missing");
        free(ctx);
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED_FORMAT;
        return NULL;
    }

    char qcom_reason[160] = {0};
    ctx->qcom = mpvflow_vulkan_qcom_create(gpu, qcom_reason,
                                           sizeof(qcom_reason));
    snprintf(ctx->qcom_seed_reason, sizeof(ctx->qcom_seed_reason), "%s",
             qcom_reason[0] ? qcom_reason : "unavailable");
    snprintf(ctx->diagnostic_engine, sizeof(ctx->diagnostic_engine), "%s",
             config && config->diagnostic_engine ? config->diagnostic_engine : "unknown");
    char optical_reason[160] = {0};
    const bool optical_attempted = !ctx->qcom;
    if (optical_attempted)
        ctx->optical = mpvflow_vulkan_optical_create(gpu, optical_reason,
                                                     sizeof(optical_reason));
    if (!ctx->optical && !ctx->qcom)
        flow_log(gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC event=optical_flow_backend state=disabled "
                 "reason=%s fallback=validated_vulkan_block_matching",
                 optical_reason[0] ? optical_reason : ctx->qcom_seed_reason);

    /* Match the SVPlayer architecture: keep the normal path entirely inside
     * Vulkan. OpenCL remains dynamically loaded and available when explicitly
     * requested, but AUTO must not pay the per-frame Vulkan/OpenCL ownership
     * transfers and full-resolution copies. */
    if (requested_api == MPVFLOW_GPU_COMPUTE_OPENCL) {
        struct MPVFlowOpenCLConfig opencl_config = {
            .search_radius = ctx->search_radius,
            .max_dimension = ctx->max_dimension,
        };
        enum MPVFlowOpenCLStatus opencl_status = MPVFLOW_OPENCL_ERROR;
        const char *build_log = NULL;
        char bridge_error[256] = {0};
        ctx->opencl = mpvflow_opencl_create(&opencl_config, &opencl_status, &build_log);
        if (ctx->opencl) {
            const char *runtime_info = mpvflow_opencl_get_runtime_info(ctx->opencl);
            const char *extension_info =
                mpvflow_opencl_get_extension_presence_info(ctx->opencl);
            flow_log(gpu, PL_LOG_INFO,
                     "MPVFLOW_DIAGNOSTIC event=opencl_device %s",
                     runtime_info ? runtime_info : "details=unavailable");
            flow_log(gpu, PL_LOG_INFO,
                     "MPVFLOW_DIAGNOSTIC event=opencl_extension_presence %s",
                     extension_info ? extension_info : "details=unavailable");
            ctx->opencl_bridge = mpvflow_opencl_vk_bridge_create(
                gpu, ctx->opencl, bridge_error, sizeof(bridge_error));
        }
        enum MPVFlowOpenCLVkBridgeStatus probe_status = MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
        if (ctx->opencl_bridge) {
            struct MPVFlowOpenCLVkImportDiagnostics import_diagnostics;
            mpvflow_opencl_vk_bridge_probe_import_stages(
                ctx->opencl_bridge, &import_diagnostics);
            flow_opencl_log_import_stage(
                gpu, "before_vk_bind", &import_diagnostics.before_vk_bind);
            flow_opencl_log_import_stage(
                gpu, "after_vk_bind", &import_diagnostics.after_vk_bind);
            probe_status = flow_opencl_interop_probe(ctx);
        }
        if (ctx->opencl && ctx->opencl_bridge &&
            probe_status == MPVFLOW_OPENCL_VK_BRIDGE_OK) {
            ctx->compute_api = MPVFLOW_GPU_COMPUTE_OPENCL;
            flow_log(gpu, PL_LOG_INFO,
                     "MPVFLOW_DIAGNOSTIC event=compute_backend state=ready requested_api=%d "
                     "effective_api=opencl interop=qcom_ahardwarebuffer_host_ptr "
                     "shared_storage=android_hardware_buffer probe=kernel_interop_roundtrip "
                     "probe_size=64x64 cpu_readback=no mode=explicit",
                     requested_api);
        } else {
            flow_log(gpu, requested_api == MPVFLOW_GPU_COMPUTE_AUTO ? PL_LOG_WARN : PL_LOG_ERR,
                     "MPVFLOW_DIAGNOSTIC event=compute_backend state=unavailable requested_api=%d "
                     "candidate=opencl opencl_status=%d bridge_status=%d bridge_error=%s "
                     "build_log=%s action=%s",
                     requested_api, opencl_status, probe_status,
                     bridge_error[0] ? bridge_error : "none",
                     build_log ? build_log : "none",
                     requested_api == MPVFLOW_GPU_COMPUTE_AUTO ? "fallback_vulkan" : "fail_initialization");
            if (ctx->opencl_bridge) {
                if (mpvflow_opencl_vk_bridge_destroy(ctx->opencl_bridge) ==
                    MPVFLOW_OPENCL_VK_BRIDGE_OK)
                    ctx->opencl_bridge = NULL;
            }
            if (ctx->opencl && !ctx->opencl_bridge) {
                mpvflow_opencl_destroy(ctx->opencl);
                ctx->opencl = NULL;
            }
            if (requested_api == MPVFLOW_GPU_COMPUTE_OPENCL) {
                if (diagnostics) diagnostics->stage = "opencl_initialization";
                mpvflow_gpu_destroy(ctx);
                if (status) *status = MPVFLOW_GPU_UNSUPPORTED_API;
                return NULL;
            }
        }
    } else {
        flow_log(gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC event=compute_backend state=ready requested_api=%d "
                 "effective_api=vulkan reason=%s",
                 requested_api,
                 requested_api == MPVFLOW_GPU_COMPUTE_AUTO
                     ? "auto_prefers_vulkan_native_pipeline_opencl_optional"
                     : "explicit_vulkan");
    }

    struct pl_desc preprocess_desc[] = {
        { .name = "inTex", .type = PL_DESC_SAMPLED_TEX, .binding = 0 },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc luma_desc[] = {
        { .name = "inTex", .type = PL_DESC_SAMPLED_TEX, .binding = 0 },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc down_desc[] = {
        { .name = "inTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc first_desc[] = {
        { .name = "aTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "bTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 2,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc reduce_desc[] = {
        { .name = "inTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc optical_luma_desc[] = {
        { .name = "inTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc optical_seed_desc[] = {
        { .name = "vectorTex", .type = PL_DESC_SAMPLED_TEX, .binding = 0 },
        { .name = "seedTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_WRITEONLY },
        { .name = "sceneTex", .type = PL_DESC_STORAGE_IMG, .binding = 2,
          .access = PL_DESC_ACCESS_READONLY },
    };
    struct pl_desc flow_desc[] = {
        { .name = "srcTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "dstTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "parentTex", .type = PL_DESC_STORAGE_IMG, .binding = 2,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "flowTex", .type = PL_DESC_STORAGE_IMG, .binding = 3,
          .access = PL_DESC_ACCESS_WRITEONLY },
        { .name = "sceneTex", .type = PL_DESC_STORAGE_IMG, .binding = 4,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "srcColorTex", .type = PL_DESC_STORAGE_IMG, .binding = 5,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "dstColorTex", .type = PL_DESC_STORAGE_IMG, .binding = 6,
          .access = PL_DESC_ACCESS_READONLY },
    };
    struct pl_desc synth_desc[] = {
        { .name = "frame0", .type = PL_DESC_SAMPLED_TEX, .binding = 0 },
        { .name = "frame1", .type = PL_DESC_SAMPLED_TEX, .binding = 1 },
        { .name = "fwdTex", .type = PL_DESC_STORAGE_IMG, .binding = 2,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "backTex", .type = PL_DESC_STORAGE_IMG, .binding = 3,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "sceneTex", .type = PL_DESC_STORAGE_IMG, .binding = 4,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "luma0Tex", .type = PL_DESC_STORAGE_IMG, .binding = 5,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "luma1Tex", .type = PL_DESC_STORAGE_IMG, .binding = 6,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 7,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    ctx->pass_preprocess = make_pass(gpu, shader_preprocess, preprocess_desc, 2);
    ctx->pass_luma = make_pass(gpu, shader_luma, luma_desc, 2);
    ctx->pass_downsample = make_pass(gpu, shader_downsample, down_desc, 2);
    ctx->pass_reduce_first = make_pass(gpu, shader_reduce_first, first_desc, 3);
    ctx->pass_reduce = make_pass(gpu, shader_reduce, reduce_desc, 2);
    ctx->pass_flow = make_pass(gpu, shader_flow, flow_desc, 7);
#if MPVFLOW_PAIR_GHOST_EXPERIMENT
    struct pl_desc gate_first_desc[] = {
        { .name="forwardTex", .type=PL_DESC_STORAGE_IMG, .binding=0, .access=PL_DESC_ACCESS_READONLY },
        { .name="backwardTex", .type=PL_DESC_STORAGE_IMG, .binding=1, .access=PL_DESC_ACCESS_READONLY },
        { .name="outTex", .type=PL_DESC_STORAGE_IMG, .binding=2, .access=PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc gate_merge_desc[] = {
        { .name="sceneTex", .type=PL_DESC_STORAGE_IMG, .binding=0, .access=PL_DESC_ACCESS_READONLY },
        { .name="gateTex", .type=PL_DESC_STORAGE_IMG, .binding=1, .access=PL_DESC_ACCESS_READONLY },
        { .name="outTex", .type=PL_DESC_STORAGE_IMG, .binding=2, .access=PL_DESC_ACCESS_WRITEONLY },
    };
    ctx->pass_pair_gate_first = make_pass(gpu, shader_pair_gate_first, gate_first_desc, 3);
    ctx->pass_pair_gate_reduce = make_pass(gpu, shader_pair_gate_reduce, reduce_desc, 2);
    ctx->pass_pair_gate_merge = make_pass(gpu, shader_pair_gate_merge, gate_merge_desc, 3);
    if (!ctx->pass_pair_gate_first || !ctx->pass_pair_gate_reduce || !ctx->pass_pair_gate_merge) {
        uint32_t gate_failed_pass_mask = 0;
        if (!ctx->pass_pair_gate_first)
            gate_failed_pass_mask |= MPVFLOW_GPU_PASS_PAIR_GATE_FIRST;
        if (!ctx->pass_pair_gate_reduce)
            gate_failed_pass_mask |= MPVFLOW_GPU_PASS_PAIR_GATE_REDUCE;
        if (!ctx->pass_pair_gate_merge)
            gate_failed_pass_mask |= MPVFLOW_GPU_PASS_PAIR_GATE_MERGE;
        if (diagnostics) {
            diagnostics->stage = "pair_ghost_pass_creation";
            diagnostics->failed_pass_mask = gate_failed_pass_mask;
        }
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=shader_setup state=failed "
                 "stage=pair_ghost_pass_creation missing_pass_mask=0x%x "
                 "pair_gate_first=%d pair_gate_reduce=%d pair_gate_merge=%d",
                 gate_failed_pass_mask, !!ctx->pass_pair_gate_first,
                 !!ctx->pass_pair_gate_reduce, !!ctx->pass_pair_gate_merge);
        mpvflow_gpu_destroy(ctx);
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED_SHADER;
        return NULL;
    }
    flow_log(gpu, PL_LOG_INFO, "MPVFLOW_DIAGNOSTIC event=pair_ghost_experiment state=enabled "
             "marker=MPVFLOW_PAIR_GHOST_SPATIAL_GAP_V3 weak_min=0.10 residual_min=0.01 gap_support_min=0.008 neighborhood=5_of_9 "
             "backend=vulkan scope=whole_pair cpu_readback=no");
#endif
    ctx->pass_synthesize = make_pass(gpu, shader_synthesize, synth_desc, 8);
    const unsigned int rgba16f_pass_caps = PL_FMT_CAP_STORABLE |
        PL_FMT_CAP_SAMPLEABLE | PL_FMT_CAP_LINEAR;
    if ((ctx->fmt_rgba16f->caps & rgba16f_pass_caps) == rgba16f_pass_caps) {
        char *rgba16f_shader = shader_replace_once(
            shader_synthesize,
            "layout(set=0,binding=7,rgba8) writeonly uniform image2D outTex;",
            "layout(set=0,binding=7,rgba16f) writeonly uniform image2D outTex;");
        if (rgba16f_shader) {
            ctx->pass_synthesize_rgba16f = make_pass(gpu, rgba16f_shader, synth_desc, 8);
            free(rgba16f_shader);
        }
    }
    if (ctx->optical || ctx->qcom) {
        ctx->pass_optical_luma = make_pass(gpu, shader_optical_luma,
                                           optical_luma_desc, 2);
        if (ctx->optical)
            ctx->pass_optical_seed = make_pass(gpu, shader_optical_seed,
                                               optical_seed_desc, 3);
        if (!ctx->pass_optical_luma || (ctx->optical && !ctx->pass_optical_seed)) {
            flow_log(gpu, PL_LOG_WARN,
                     "MPVFLOW_DIAGNOSTIC event=hardware_seed_backend state=disabled "
                     "reason=optional_luma_or_seed_shader_compile_failed fallback=validated_vulkan_sad "
                     "luma_pass=%d seed_pass=%d",
                     !!ctx->pass_optical_luma, !!ctx->pass_optical_seed);
            if (ctx->qcom)
                snprintf(ctx->qcom_seed_reason, sizeof(ctx->qcom_seed_reason),
                         "optional_luma_shader_compile_failed");
            if (ctx->optical)
                snprintf(optical_reason, sizeof(optical_reason),
                         "optional_luma_or_seed_shader_compile_failed");
            mpvflow_vulkan_optical_destroy(&ctx->optical);
            mpvflow_vulkan_qcom_destroy(&ctx->qcom);
            if (ctx->pass_optical_luma)
                pl_pass_destroy(gpu, &ctx->pass_optical_luma);
            if (ctx->pass_optical_seed)
                pl_pass_destroy(gpu, &ctx->pass_optical_seed);
        }
    }
    if (ctx->qcom && ctx->pass_optical_luma) {
        snprintf(ctx->qcom_seed_reason, sizeof(ctx->qcom_seed_reason), "ready");
        snprintf(ctx->qcom_last_state, sizeof(ctx->qcom_last_state), "ready");
        snprintf(ctx->qcom_last_reason, sizeof(ctx->qcom_last_reason), "awaiting_pair_analysis");
    } else {
        snprintf(ctx->qcom_last_state, sizeof(ctx->qcom_last_state), "disabled");
        snprintf(ctx->qcom_last_reason, sizeof(ctx->qcom_last_reason), "%s",
                 ctx->qcom_seed_reason[0] ? ctx->qcom_seed_reason : "unavailable");
    }
    if (diagnostics) {
        diagnostics->optical_flow_attempted = optical_attempted;
        diagnostics->optical_flow_ready = ctx->optical != NULL;
        diagnostics->qcom_seed_adapter_ready = ctx->qcom != NULL;
        diagnostics->qcom_seed_luma_pass_ready = ctx->qcom && ctx->pass_optical_luma;
        snprintf(diagnostics->qcom_seed_reason,
                 sizeof(diagnostics->qcom_seed_reason), "%s",
                 ctx->qcom_seed_reason[0] ? ctx->qcom_seed_reason : "unavailable");
        const char *optical_status_reason = ctx->qcom
            ? "qcom2_seed_backend_preferred" : ctx->optical
            ? "ready" : optical_reason[0] ? optical_reason
            : ctx->qcom_seed_reason[0] ? ctx->qcom_seed_reason : "unavailable";
        snprintf(diagnostics->optical_flow_reason,
                 sizeof(diagnostics->optical_flow_reason), "%s", optical_status_reason);
    }
    flow_log(gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=qcom_seed_backend engine=%s state=%s "
             "adapter_ready=%d luma_pass_ready=%d reason=%s",
             ctx->diagnostic_engine, ctx->qcom ? "ready" : "disabled",
             !!ctx->qcom, !!(ctx->qcom && ctx->pass_optical_luma),
             ctx->qcom_seed_reason[0] ? ctx->qcom_seed_reason : "unavailable");
    uint32_t failed_pass_mask = 0;
    if (!ctx->pass_preprocess) failed_pass_mask |= MPVFLOW_GPU_PASS_PREPROCESS;
    if (!ctx->pass_luma) failed_pass_mask |= MPVFLOW_GPU_PASS_LUMA;
    if (!ctx->pass_downsample) failed_pass_mask |= MPVFLOW_GPU_PASS_DOWNSAMPLE;
    if (!ctx->pass_reduce_first) failed_pass_mask |= MPVFLOW_GPU_PASS_REDUCE_FIRST;
    if (!ctx->pass_reduce) failed_pass_mask |= MPVFLOW_GPU_PASS_REDUCE;
    if (!ctx->pass_flow) failed_pass_mask |= MPVFLOW_GPU_PASS_MOTION;
    if (!ctx->pass_synthesize) failed_pass_mask |= MPVFLOW_GPU_PASS_SYNTHESIZE;
    if (diagnostics) {
        diagnostics->stage = failed_pass_mask ? "compute_pass_creation" : "ready";
        diagnostics->failed_pass_mask = failed_pass_mask;
    }
    if (!ctx->pass_preprocess || !ctx->pass_luma || !ctx->pass_downsample || !ctx->pass_reduce_first ||
        !ctx->pass_reduce || !ctx->pass_flow || !ctx->pass_synthesize) {
        flow_log(gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=shader_setup state=failed stage=compute_pass_creation "
                 "missing_pass_mask=0x%x preprocess=%d luma=%d downsample=%d reduce_first=%d "
                 "reduce=%d motion=%d synthesize=%d",
                 failed_pass_mask, !!ctx->pass_preprocess, !!ctx->pass_luma,
                 !!ctx->pass_downsample, !!ctx->pass_reduce_first, !!ctx->pass_reduce,
                 !!ctx->pass_flow, !!ctx->pass_synthesize);
        mpvflow_gpu_destroy(ctx);
        if (status) *status = MPVFLOW_GPU_UNSUPPORTED_SHADER;
        return NULL;
    }
    static const char *const timer_names[FLOW_TIMER_COUNT] = {
        "preprocess", "analysis", "motion_fine", "motion_middle",
        "motion_coarse", "synthesis",
    };
    int timer_count = 0;
    if (ctx->compute_timestamps_supported) {
        for (int i = 0; i < FLOW_TIMER_COUNT; i++) {
            ctx->timers[i].timer = pl_timer_create(gpu);
            timer_count += ctx->timers[i].timer != NULL;
        }
    }
    ctx->timing_window_start_ns = flow_monotonic_ns();
    flow_log(gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC component=mpvflow event=gpu_timing state=%s "
             "api=Vulkan timer_source=libplacebo_timestamp_timer_objects timer_objects=%d "
             "compute_queue_family=%u queue_timestamp_valid_bits=%u timestamp_period_ns=%.6f "
             "timestamp_compute_and_graphics=%d timer_categories=%d timestamp_results=%s "
             "poll_policy=nonblocking_frame_operation_boundary nonblocking=1 "
             "fallback=CPU_submission_and_output_cadence",
             !ctx->compute_timestamps_supported ? "unsupported" :
                 timer_count == FLOW_TIMER_COUNT ? "supported" : "partial",
             timer_count, ctx->compute_queue_family, ctx->compute_timestamp_valid_bits,
             ctx->timestamp_period_ns, ctx->timestamp_compute_and_graphics,
             FLOW_TIMER_COUNT,
             ctx->compute_timestamps_supported ? "awaiting_compute_dispatch" :
                 ctx->timestamp_period_ns <= 0.0 ? "device_timestamp_period_zero" :
                 !ctx->timestamp_compute_and_graphics && !ctx->compute_timestamp_valid_bits
                     ? "compute_queue_timestamp_valid_bits_zero" : "timestamp_capability_unavailable");
    (void) timer_names;
    if (status)
        *status = MPVFLOW_GPU_OK;
    flow_log(gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=shader_setup state=ready stage=compute_pass_creation "
             "pass_mask=0x7f rgba16f_synthesis_pass=%d max_dimension=%d search_radius=%d",
             !!ctx->pass_synthesize_rgba16f, ctx->max_dimension, ctx->search_radius);
    return ctx;
}

void mpvflow_gpu_destroy(MPVFlowGPUContext *ctx)
{
    if (!ctx)
        return;
    mpvflow_gpu_capture_stop(ctx);
    if (ctx->capture_color_atlas)
        pl_tex_destroy(ctx->gpu, &ctx->capture_color_atlas);
    if (ctx->capture_map_atlas)
        pl_tex_destroy(ctx->gpu, &ctx->capture_map_atlas);
    if (ctx->pass_capture_color)
        pl_pass_destroy(ctx->gpu, &ctx->pass_capture_color);
    if (ctx->pass_capture_maps)
        pl_pass_destroy(ctx->gpu, &ctx->pass_capture_maps);
    if (ctx->pass_capture_decisions)
        pl_pass_destroy(ctx->gpu, &ctx->pass_capture_decisions);
    if (ctx->capture_decision_output)
        pl_tex_destroy(ctx->gpu, &ctx->capture_decision_output);
    if (ctx->capture_hard_output)
        pl_tex_destroy(ctx->gpu, &ctx->capture_hard_output);
    if (ctx->pass_preprocess) pl_pass_destroy(ctx->gpu, &ctx->pass_preprocess);
    if (ctx->pass_luma) pl_pass_destroy(ctx->gpu, &ctx->pass_luma);
    if (ctx->pass_downsample) pl_pass_destroy(ctx->gpu, &ctx->pass_downsample);
    if (ctx->pass_reduce_first) pl_pass_destroy(ctx->gpu, &ctx->pass_reduce_first);
    if (ctx->pass_reduce) pl_pass_destroy(ctx->gpu, &ctx->pass_reduce);
    if (ctx->pass_flow) pl_pass_destroy(ctx->gpu, &ctx->pass_flow);
    if (ctx->pass_optical_luma) pl_pass_destroy(ctx->gpu, &ctx->pass_optical_luma);
    if (ctx->pass_optical_seed) pl_pass_destroy(ctx->gpu, &ctx->pass_optical_seed);
    if (ctx->pass_pair_gate_first) pl_pass_destroy(ctx->gpu, &ctx->pass_pair_gate_first);
    if (ctx->pass_pair_gate_reduce) pl_pass_destroy(ctx->gpu, &ctx->pass_pair_gate_reduce);
    if (ctx->pass_pair_gate_merge) pl_pass_destroy(ctx->gpu, &ctx->pass_pair_gate_merge);
    if (ctx->pass_synthesize) pl_pass_destroy(ctx->gpu, &ctx->pass_synthesize);
    if (ctx->pass_synthesize_rgba16f) pl_pass_destroy(ctx->gpu, &ctx->pass_synthesize_rgba16f);
    if (ctx->pass_visibility_resolver) pl_pass_destroy(ctx->gpu, &ctx->pass_visibility_resolver);
    if (ctx->pass_visibility_resolver_rgba16f) pl_pass_destroy(ctx->gpu, &ctx->pass_visibility_resolver_rgba16f);
    mpvflow_vulkan_optical_destroy(&ctx->optical);
    mpvflow_vulkan_qcom_destroy(&ctx->qcom);
    for (int i = 0; i < FLOW_TIMER_COUNT; i++)
        pl_timer_destroy(ctx->gpu, &ctx->timers[i].timer);
    if (ctx->opencl_bridge) {
        enum MPVFlowOpenCLVkBridgeStatus bridge_status =
            mpvflow_opencl_vk_bridge_destroy(ctx->opencl_bridge);
        if (bridge_status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
            flow_log(ctx->gpu, PL_LOG_ERR,
                     "MPVFLOW_DIAGNOSTIC event=opencl_context_release state=deferred "
                     "reason=bridge_busy status=%d resources_retained=yes",
                     bridge_status);
            return;
        }
        ctx->opencl_bridge = NULL;
    }
    if (ctx->opencl)
        mpvflow_opencl_destroy(ctx->opencl);
    free(ctx);
}

enum MPVFlowGPUComputeAPI mpvflow_gpu_get_compute_api(MPVFlowGPUContext *ctx)
{
    return ctx ? ctx->compute_api : MPVFLOW_GPU_COMPUTE_VULKAN;
}

bool mpvflow_gpu_rgba16f_synthesis_supported(MPVFlowGPUContext *ctx)
{
    const unsigned int required_caps = PL_FMT_CAP_STORABLE |
        PL_FMT_CAP_SAMPLEABLE | PL_FMT_CAP_LINEAR;
    return ctx && ctx->compute_api == MPVFLOW_GPU_COMPUTE_VULKAN &&
        ctx->pass_synthesize_rgba16f && ctx->fmt_rgba16f &&
        (ctx->fmt_rgba16f->caps & required_caps) == required_caps;
}

const char *mpvflow_gpu_get_compute_api_name(MPVFlowGPUContext *ctx)
{
    if (!ctx)
        return "unavailable";
    switch (ctx->compute_api) {
    case MPVFLOW_GPU_COMPUTE_OPENCL: return "opencl-qcom";
    case MPVFLOW_GPU_COMPUTE_VULKAN: return "vulkan-spirv";
    case MPVFLOW_GPU_COMPUTE_AUTO: break;
    }
    return "unresolved";
}

void mpvflow_gpu_get_timing_stats(MPVFlowGPUContext *ctx,
                                  struct MPVFlowGPUTimingStats *stats)
{
    if (!stats)
        return;
    memset(stats, 0, sizeof(*stats));
    stats->stage_count = MPVFLOW_GPU_STAGE_COUNT;
    if (!ctx)
        return;
    stats->compute_queue_family = ctx->compute_queue_family;
    stats->compute_timestamp_valid_bits = ctx->compute_timestamp_valid_bits;
    stats->compute_timestamps_supported = ctx->compute_timestamps_supported;
    stats->timestamp_compute_and_graphics = ctx->timestamp_compute_and_graphics;
    stats->timestamp_period_ns = ctx->timestamp_period_ns;
    flow_timer_poll_and_report(ctx);
    for (int i = 0; i < FLOW_TIMER_COUNT; i++) {
        const struct FlowTimerStats *timer = &ctx->timers[i];
        stats->stages[i] = (struct MPVFlowGPUStageTiming) {
            .timer_created = timer->timer != NULL,
            .dispatches = timer->dispatches,
            .completed_samples = timer->completed_samples,
            .total_ns = timer->lifetime_total_ns,
            .maximum_ns = timer->lifetime_max_ns,
        };
    }
}

void mpvflow_gpu_get_qcom_diagnostics(MPVFlowGPUContext *ctx,
                                      struct MPVFlowGPUQcomDiagnostics *stats)
{
    if (!stats)
        return;
    memset(stats, 0, sizeof(*stats));
    if (!ctx) {
        snprintf(stats->state, sizeof(stats->state), "unavailable");
        snprintf(stats->reason, sizeof(stats->reason), "context_missing");
        return;
    }
    stats->adapter_ready = ctx->qcom != NULL;
    stats->luma_pass_ready = ctx->qcom && ctx->pass_optical_luma;
    stats->pair_attempts = ctx->qcom_pair_attempts;
    stats->dispatches = ctx->qcom_dispatches;
    stats->fallbacks = ctx->qcom_fallbacks;
    snprintf(stats->state, sizeof(stats->state), "%s",
             ctx->qcom_last_state[0] ? ctx->qcom_last_state : "not_attempted");
    snprintf(stats->reason, sizeof(stats->reason), "%s",
             ctx->qcom_last_reason[0] ? ctx->qcom_last_reason : "not_attempted");
}

enum MPVFlowGPUResult mpvflow_gpu_prepare_input(MPVFlowGPUContext *ctx,
                                                pl_tex input,
                                                pl_tex *prepared_out)
{
    if (prepared_out)
        *prepared_out = NULL;
    if (!ctx || !input || !prepared_out)
        return MPVFLOW_GPU_INVALID;
    flow_timer_poll_and_report(ctx);
    if (!input->params.sampleable || input->params.w < FLOW_BLOCK ||
        input->params.h < FLOW_BLOCK) {
        flow_log_once(ctx, FLOW_DIAG_PREPARE_INPUT, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=prepare_input state=failed stage=input_texture_validation "
                      "width=%u height=%u sampleable=%d format=%s format_caps=0x%x min_dimension=%d",
                      input->params.w, input->params.h, input->params.sampleable,
                      input->params.format ? input->params.format->name : "missing",
                      input->params.format ? (unsigned int) input->params.format->caps : 0,
                      FLOW_BLOCK);
        return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
    }

    int source_w = (int) input->params.w;
    int source_h = (int) input->params.h;
    double scale = fmin(1.0, (double) ctx->max_dimension /
                              fmax((double) source_w, (double) source_h));
    int w = (int) lround(source_w * scale);
    int h = (int) lround(source_h * scale);
    if (w < FLOW_BLOCK || h < FLOW_BLOCK) {
        flow_log_once(ctx, FLOW_DIAG_PREPARE_INPUT, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=prepare_input state=failed stage=scaled_input_dimensions "
                      "source=%dx%d scaled=%dx%d max_dimension=%d min_dimension=%d",
                      source_w, source_h, w, h, ctx->max_dimension, FLOW_BLOCK);
        return MPVFLOW_GPU_UNSUPPORTED_DIMENSION;
    }

    pl_tex prepared = create_texture_ex(ctx, w, h, ctx->fmt_rgba8, true,
                                        ctx->compute_api == MPVFLOW_GPU_COMPUTE_OPENCL,
                                        false, "prepared_input_rgba8");
    if (!prepared) {
        flow_log_once(ctx, FLOW_DIAG_PREPARE_INPUT, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=prepare_input state=failed stage=prepared_texture_allocation "
                      "width=%d height=%d format=rgba8 sampleable=1 storable=1",
                      w, h);
        return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
    }
    struct pl_desc_binding bindings[] = {
        { .object = input, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_LINEAR },
        { .object = prepared },
    };
    int cfg[4] = { w, h, 0, 0 };
    if (!run_pass(ctx, ctx->pass_preprocess, bindings, 2, cfg, 0.0f, w, h,
                  "preprocess_input")) {
        destroy_tex(ctx, &prepared);
        flow_log_once(ctx, FLOW_DIAG_PREPARE_INPUT, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=prepare_input state=failed stage=preprocess_dispatch "
                      "width=%d height=%d status=%d",
                      w, h, MPVFLOW_GPU_ERROR);
        return MPVFLOW_GPU_ERROR;
    }
    *prepared_out = prepared;
    return MPVFLOW_GPU_OK;
}

static enum MPVFlowGPUResult flow_opencl_analyze_pair(MPVFlowGPUContext *ctx,
                                                       MPVFlowGPUPair *pair,
                                                       pl_tex frame0, pl_tex frame1)
{
    const uint32_t width = (uint32_t) frame0->params.w;
    const uint32_t height = (uint32_t) frame0->params.h;
    for (int i = 0; i < 2; i++) {
        enum MPVFlowOpenCLVkBridgeStatus create_status =
            mpvflow_opencl_vk_buffer_create(ctx->opencl_bridge, width, height,
                MPVFLOW_OPENCL_READ_ONLY, &pair->opencl_analysis[i]);
        if (create_status != MPVFLOW_OPENCL_VK_BRIDGE_OK ||
            !pair->opencl_analysis[i]) {
            const char *bridge_error =
                mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge);
            flow_log(ctx->gpu, PL_LOG_WARN,
                     "MPVFLOW_DIAGNOSTIC event=compute_backend state=runtime_fallback "
                     "stage=opencl_analysis_buffer_allocation frame=%d width=%u height=%u status=%d reason=%s",
                     i, width, height, create_status,
                     bridge_error ? bridge_error : "none");
            return MPVFLOW_GPU_ERROR;
        }
    }
    for (int i = 0; i < 2; i++) {
        enum MPVFlowOpenCLVkBridgeStatus ready_status =
            mpvflow_opencl_vk_buffer_opencl_finished(pair->opencl_analysis[i]);
        if (ready_status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
            return MPVFLOW_GPU_ERROR;
    }

    enum MPVFlowOpenCLVkBridgeStatus bridge_status =
        mpvflow_opencl_vk_copy_texture_to_buffer(ctx->opencl_bridge, frame0,
                                                  pair->opencl_analysis[0]);
    if (bridge_status == MPVFLOW_OPENCL_VK_BRIDGE_OK)
        bridge_status = mpvflow_opencl_vk_copy_texture_to_buffer(
            ctx->opencl_bridge, frame1, pair->opencl_analysis[1]);
    if (bridge_status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
        flow_log(ctx->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_backend state=runtime_fallback "
                 "stage=opencl_analysis_upload status=%d reason=%s",
                 bridge_status, mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge));
        return MPVFLOW_GPU_ERROR;
    }

    for (int i = 0; i < 2; i++) {
        bridge_status = mpvflow_opencl_vk_buffer_opencl_started(
            pair->opencl_analysis[i]);
        if (bridge_status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
            return MPVFLOW_GPU_ERROR;
    }

    enum MPVFlowOpenCLStatus opencl_status = mpvflow_opencl_analyze_pair(
        ctx->opencl,
        mpvflow_opencl_vk_buffer_get_opencl(pair->opencl_analysis[0]),
        mpvflow_opencl_vk_buffer_get_opencl(pair->opencl_analysis[1]),
        &pair->opencl_pair);
    if (opencl_status != MPVFLOW_OPENCL_OK) {
        (void) mpvflow_opencl_finish(ctx->opencl);
        flow_log(ctx->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_backend state=runtime_fallback "
                 "stage=opencl_pair_analysis status=%d cpu_readback=no",
                 opencl_status);
        return MPVFLOW_GPU_ERROR;
    }
    /* analyze_pair already completes its queue before returning success. */
    for (int i = 0; i < 2; i++) {
        bridge_status = mpvflow_opencl_vk_buffer_opencl_finished(
            pair->opencl_analysis[i]);
        if (bridge_status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
            flow_log(ctx->gpu, PL_LOG_ERR,
                     "MPVFLOW_DIAGNOSTIC event=compute_backend state=runtime_error "
                     "stage=opencl_analysis_handoff frame=%d status=%d",
                     i, bridge_status);
            return MPVFLOW_GPU_ERROR;
        }
    }
    pair->w = (int) width;
    pair->h = (int) height;
    if (ctx->motion_log_w != pair->w || ctx->motion_log_h != pair->h) {
        flow_log(ctx->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC component=mpvflow event=motion_geometry state=ready "
                 "backend=opencl-qcom processing=%dx%d analysis_max_dimension=%d "
                 "motion_search_passes_per_pair=bidirectional_pyramid "
                 "adaptive_parent=2x_if_sad_gt_0.12_and_uniqueness_gt_0.0005 "
                 "source_pts_preserved=caller_owned cpu_readback=no",
                 pair->w, pair->h, ctx->max_dimension);
        ctx->motion_log_w = pair->w;
        ctx->motion_log_h = pair->h;
    }
    return MPVFLOW_GPU_OK;
}

enum MPVFlowGPUResult mpvflow_gpu_analyze_pair(MPVFlowGPUContext *ctx,
                                               pl_tex frame0, pl_tex frame1,
                                               MPVFlowGPUPair **pair_out)
{
    if (pair_out)
        *pair_out = NULL;
    if (!ctx || !frame0 || !frame1 || !pair_out)
        return MPVFLOW_GPU_INVALID;
    flow_timer_poll_and_report(ctx);
    const struct pl_tex_params *p0 = &frame0->params;
    const struct pl_tex_params *p1 = &frame1->params;
    if (p0->d != 0 || p1->d != 0 || p0->w != p1->w || p0->h != p1->h ||
        p0->w < FLOW_BLOCK || p0->h < FLOW_BLOCK ||
        p0->w > ctx->max_dimension || p0->h > ctx->max_dimension ||
        frame0->sampler_type != PL_SAMPLER_NORMAL ||
        frame1->sampler_type != PL_SAMPLER_NORMAL ||
        !p0->sampleable || !p1->sampleable || !p0->format || !p1->format ||
        p0->format->num_planes != 0 || p1->format->num_planes != 0 ||
        p0->format->num_components < 3 || p1->format->num_components < 3 ||
        !(p0->format->caps & PL_FMT_CAP_SAMPLEABLE) ||
        !(p1->format->caps & PL_FMT_CAP_SAMPLEABLE)) {
        flow_log_once(ctx, FLOW_DIAG_ANALYZE_PAIR, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=analyze_pair state=failed stage=input_pair_validation "
                      "frame0=%ux%u depth=%u sampler=%d sampleable=%d format=%s caps=0x%x planes=%d components=%d "
                      "frame1=%ux%u depth=%u sampler=%d sampleable=%d format=%s caps=0x%x planes=%d components=%d "
                      "max_dimension=%d required_sampleable_rgb=1",
                      p0->w, p0->h, p0->d, frame0->sampler_type, p0->sampleable,
                      p0->format ? p0->format->name : "missing",
                      p0->format ? (unsigned int) p0->format->caps : 0,
                      p0->format ? p0->format->num_planes : -1,
                      p0->format ? p0->format->num_components : -1,
                      p1->w, p1->h, p1->d, frame1->sampler_type, p1->sampleable,
                      p1->format ? p1->format->name : "missing",
                      p1->format ? (unsigned int) p1->format->caps : 0,
                      p1->format ? p1->format->num_planes : -1,
                      p1->format ? p1->format->num_components : -1,
                      ctx->max_dimension);
        return MPVFLOW_GPU_UNSUPPORTED;
    }

    MPVFlowGPUPair *pair = calloc(1, sizeof(*pair));
    if (!pair) {
        flow_log_once(ctx, FLOW_DIAG_ANALYZE_PAIR, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=analyze_pair state=failed stage=pair_allocation reason=out_of_memory");
        return MPVFLOW_GPU_ERROR;
    }
    pair->owner = ctx;
    pair->input[0] = frame0;
    pair->input[1] = frame1;
    pair->w = p0->w;
    pair->h = p0->h;
    if (ctx->compute_api == MPVFLOW_GPU_COMPUTE_OPENCL) {
        enum MPVFlowGPUResult opencl_result =
            flow_opencl_analyze_pair(ctx, pair, frame0, frame1);
        if (opencl_result == MPVFLOW_GPU_OK) {
            *pair_out = pair;
            return MPVFLOW_GPU_OK;
        }
        destroy_pair_textures(ctx, pair);
        if (pair->opencl_pair || pair->opencl_analysis[0] || pair->opencl_analysis[1]) {
            free(pair);
            return MPVFLOW_GPU_ERROR;
        }
        if (ctx->requested_compute_api != MPVFLOW_GPU_COMPUTE_AUTO) {
            free(pair);
            return opencl_result;
        }
        flow_log(ctx->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_backend state=fallback "
                 "requested_api=auto from=opencl-qcom to=vulkan-spirv "
                 "stage=pair_analysis reason=interop_or_compute_failure");
        ctx->compute_api = MPVFLOW_GPU_COMPUTE_VULKAN;
    }
    if (!allocate_pair_textures(ctx, pair)) {
        flow_log_once(ctx, FLOW_DIAG_ANALYZE_PAIR, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=analyze_pair state=failed stage=pair_scratch_texture_allocation "
                      "width=%d height=%d levels=%d reductions=%d",
                      pair->w, pair->h, pair->levels, pair->reductions);
        destroy_pair_textures(ctx, pair);
        free(pair);
        return MPVFLOW_GPU_ERROR;
    }
    if (ctx->optical && ctx->pass_optical_luma && ctx->pass_optical_seed &&
        mpvflow_vulkan_optical_ensure(ctx->optical, pair->w, pair->h)) {
        struct FlowLevel *coarse = &pair->level[pair->levels - 1];
        pair->optical_seed[0] = create_texture(ctx,
            (int) coarse->forward->params.w, (int) coarse->forward->params.h,
            ctx->fmt_rgba16f, false, "optical_forward_seed");
        pair->optical_seed[1] = create_texture(ctx,
            (int) coarse->backward->params.w, (int) coarse->backward->params.h,
            ctx->fmt_rgba16f, false, "optical_backward_seed");
        if (!pair->optical_seed[0] || !pair->optical_seed[1]) {
            destroy_tex(ctx, &pair->optical_seed[0]);
            destroy_tex(ctx, &pair->optical_seed[1]);
        }
    }

    int cfg[4] = { pair->w, pair->h, 0, 0 };
    struct pl_desc_binding lb[2] = {
        { .object = frame0, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_NEAREST },
        { .object = pair->level[0].luma[0] },
    };
    if (!run_pass(ctx, ctx->pass_luma, lb, 2, cfg, 0.0f, pair->w, pair->h,
                  "luma_frame0"))
        goto failure;
    lb[0].object = frame1;
    lb[1].object = pair->level[0].luma[1];
    if (!run_pass(ctx, ctx->pass_luma, lb, 2, cfg, 0.0f, pair->w, pair->h,
                  "luma_frame1"))
        goto failure;

    bool optical_dispatched = false;
    if (pair->optical_seed[0] && pair->optical_seed[1]) {
        pl_tex optical_input0 = mpvflow_vulkan_optical_input(ctx->optical, 0);
        pl_tex optical_input1 = mpvflow_vulkan_optical_input(ctx->optical, 1);
        int optical_w = optical_input0 ? (int) optical_input0->params.w : 0;
        int optical_h = optical_input0 ? (int) optical_input0->params.h : 0;
        struct pl_desc_binding optical_luma_bindings[2] = {
            { .object = pair->level[0].luma[0] }, { .object = optical_input0 },
        };
        int optical_luma_cfg[4] = { pair->w, pair->h, 0, 0 };
        bool optical_inputs_ready = optical_input0 && optical_input1 &&
            optical_w > 0 && optical_h > 0 &&
            run_pass(ctx, ctx->pass_optical_luma, optical_luma_bindings, 2,
                     optical_luma_cfg, 0.0f, optical_w, optical_h,
                     "motion_coarse_optical_luma_frame0");
        optical_luma_bindings[0].object = pair->level[0].luma[1];
        optical_luma_bindings[1].object = optical_input1;
        optical_inputs_ready = optical_inputs_ready &&
            run_pass(ctx, ctx->pass_optical_luma, optical_luma_bindings, 2,
                     optical_luma_cfg, 0.0f, optical_w, optical_h,
                     "motion_coarse_optical_luma_frame1");
        optical_dispatched = optical_inputs_ready &&
            mpvflow_vulkan_optical_dispatch(ctx->optical);
    }

    for (int i = 1; i < pair->levels; i++) {
        struct FlowLevel *prev = &pair->level[i - 1];
        struct FlowLevel *lv = &pair->level[i];
        int dc[4] = { lv->w, lv->h, 0, 0 };
        struct pl_desc_binding db[2] = { { .object = prev->luma[0] },
                                         { .object = lv->luma[0] } };
        if (!run_pass(ctx, ctx->pass_downsample, db, 2, dc, 0.0f, lv->w, lv->h,
                      "pyramid_downsample_frame0"))
            goto failure;
        db[0].object = prev->luma[1];
        db[1].object = lv->luma[1];
        if (!run_pass(ctx, ctx->pass_downsample, db, 2, dc, 0.0f, lv->w, lv->h,
                      "pyramid_downsample_frame1"))
            goto failure;
    }

    bool qcom_dispatched = false;
    if (ctx->qcom && ctx->pass_optical_luma) {
        ctx->qcom_pair_attempts++;
        struct FlowLevel *coarse = &pair->level[pair->levels - 1];
        const int block_size = pair->levels == 1 ? FLOW_FINE_BLOCK : FLOW_BLOCK;
        const int grid_width = (int) coarse->forward->params.w;
        const int grid_height = (int) coarse->forward->params.h;
        int qcom_luma_cfg[4] = { coarse->w, coarse->h, 0, 0 };
        bool inputs_ready = mpvflow_vulkan_qcom_ensure(ctx->qcom,
            coarse->w, coarse->h, grid_width, grid_height, block_size);
        struct pl_desc_binding qcom_luma_bindings[2] = {
            { .object = coarse->luma[0] },
            { .object = mpvflow_vulkan_qcom_luma(ctx->qcom, 0) },
        };
        inputs_ready = inputs_ready && run_pass(ctx, ctx->pass_optical_luma,
            qcom_luma_bindings, 2, qcom_luma_cfg, 0.0f,
            coarse->w, coarse->h, "qcom_block_match_luma_frame0");
        qcom_luma_bindings[0].object = coarse->luma[1];
        qcom_luma_bindings[1].object = mpvflow_vulkan_qcom_luma(ctx->qcom, 1);
        inputs_ready = inputs_ready && run_pass(ctx, ctx->pass_optical_luma,
            qcom_luma_bindings, 2, qcom_luma_cfg, 0.0f,
            coarse->w, coarse->h, "qcom_block_match_luma_frame1");
        qcom_dispatched = inputs_ready && mpvflow_vulkan_qcom_dispatch(ctx->qcom);
        if (qcom_dispatched) {
            ctx->qcom_dispatches++;
            snprintf(ctx->qcom_last_state, sizeof(ctx->qcom_last_state), "active");
            snprintf(ctx->qcom_last_reason, sizeof(ctx->qcom_last_reason), "none");
        } else {
            ctx->qcom_fallbacks++;
            const char *reason = inputs_ready ? "optional_qcom_dispatch_unavailable" :
                "optional_qcom_luma_or_image_setup_unavailable";
            snprintf(ctx->qcom_last_state, sizeof(ctx->qcom_last_state), "fallback");
            snprintf(ctx->qcom_last_reason, sizeof(ctx->qcom_last_reason), "%s", reason);
            flow_log(ctx->gpu, PL_LOG_DEBUG,
                     "MPVFLOW_DIAGNOSTIC event=qcom_block_match_dispatch state=fallback "
                     "reason=%s fallback=full_baseline_sad_search",
                     reason);
        }
    } else if (ctx->qcom) {
        ctx->qcom_fallbacks++;
        snprintf(ctx->qcom_last_state, sizeof(ctx->qcom_last_state), "fallback");
        snprintf(ctx->qcom_last_reason, sizeof(ctx->qcom_last_reason), "luma_pass_unavailable");
    }

    int rw = (pair->w + 15) / 16, rh = (pair->h + 15) / 16;
    int rc[4] = { rw, rh, 0, 0 };
    struct pl_desc_binding rb[3] = {
        { .object = pair->level[0].luma[0] },
        { .object = pair->level[0].luma[1] },
        { .object = pair->scene[0] },
    };
    if (!run_pass(ctx, ctx->pass_reduce_first, rb, 3, rc, 0.0f, rw, rh,
                  "scene_cut_reduce_first"))
        goto failure;
    for (int i = 1; i < pair->reductions; i++) {
        int prevw = (rw + 15) / 16, prevh = (rh + 15) / 16;
        if (rw == 1 && rh == 1)
            break;
        rw = prevw; rh = prevh;
        rc[0] = rw; rc[1] = rh;
        struct pl_desc_binding r2[2] = {
            { .object = pair->scene[i - 1] }, { .object = pair->scene[i] },
        };
        if (!run_pass(ctx, ctx->pass_reduce, r2, 2, rc, 0.0f, rw, rh,
                      "scene_cut_reduce"))
            goto failure;
    }

    pl_tex scene_delta = pair->scene[pair->reductions - 1];
    if (optical_dispatched) {
        struct FlowLevel *coarse = &pair->level[pair->levels - 1];
        int scale = 1 << (pair->levels - 1);
        int seed_cfg[4] = { coarse->w, coarse->h, scale, 0 };
        struct pl_desc_binding seed_bindings[3] = {
            { .object = mpvflow_vulkan_optical_vectors(ctx->optical, 0),
              .sample_mode = PL_TEX_SAMPLE_NEAREST },
            { .object = pair->optical_seed[0] }, { .object = scene_delta },
        };
        pair->optical_seed_ready = run_pass(ctx, ctx->pass_optical_seed,
            seed_bindings, 3, seed_cfg, 0.0f,
            (int) pair->optical_seed[0]->params.w,
            (int) pair->optical_seed[0]->params.h,
            "motion_coarse_optical_seed_forward");
        seed_bindings[0].object = mpvflow_vulkan_optical_vectors(ctx->optical, 1);
        seed_bindings[1].object = pair->optical_seed[1];
        pair->optical_seed_ready = pair->optical_seed_ready &&
            run_pass(ctx, ctx->pass_optical_seed, seed_bindings, 3, seed_cfg,
                0.0f, (int) pair->optical_seed[1]->params.w,
                (int) pair->optical_seed[1]->params.h,
                "motion_coarse_optical_seed_backward");
    }
    for (int dir = 0; dir < 2; dir++) {
        for (int li = pair->levels - 1; li >= 0; li--) {
            struct FlowLevel *lv = &pair->level[li];
            struct FlowLevel *parent = li + 1 < pair->levels
                ? &pair->level[li + 1] : NULL;
            const bool optical_parent = pair->optical_seed_ready &&
                                        li == pair->levels - 1;
            const bool qcom_parent = qcom_dispatched &&
                                     li == pair->levels - 1;
            pl_tex src = lv->luma[dir ? 1 : 0];
            pl_tex dst = lv->luma[dir ? 0 : 1];
            pl_tex flow = dir ? lv->backward : lv->forward;
            pl_tex parent_flow = qcom_parent
                ? mpvflow_vulkan_qcom_seed(ctx->qcom, dir) : optical_parent
                ? pair->optical_seed[dir] : parent
                ? (dir ? parent->backward : parent->forward)
                : (dir ? lv->forward : lv->backward);
            const bool has_parent = parent || optical_parent || qcom_parent;
            int radius = parent ? 2 : ctx->search_radius;
            const int block_size = li == 0 ? FLOW_FINE_BLOCK : FLOW_BLOCK;
            int fc[4] = { lv->w, lv->h, radius,
                          (has_parent ? 1 : 0) | (optical_parent ? 2 : 0) |
                          (qcom_parent ? 4 : 0) | (li == 0 ? 8 : 0) |
                          (block_size << 8) };
            struct pl_desc_binding fb[7] = {
                { .object = src }, { .object = dst }, { .object = parent_flow },
                { .object = flow }, { .object = scene_delta },
                { .object = pair->input[dir ? 1 : 0] },
                { .object = pair->input[dir ? 0 : 1] },
            };
            const char *stage = dir
                ? (li == 0 ? "motion_search_backward_level_0" :
                   li == 1 ? "motion_search_backward_level_1" : "motion_search_backward_level_2")
                : (li == 0 ? "motion_search_forward_level_0" :
                   li == 1 ? "motion_search_forward_level_1" : "motion_search_forward_level_2");
            if (!run_pass(ctx, ctx->pass_flow, fb, 7, fc, 0.0f,
                          flow->params.w, flow->params.h,
                          stage))
                goto failure;
        }
    }
#if MPVFLOW_PAIR_GHOST_EXPERIMENT
    if (!analyze_pair_ghost(ctx, pair))
        goto failure;
#endif
    if (ctx->motion_log_w != pair->w || ctx->motion_log_h != pair->h) {
        flow_log(ctx->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC component=mpvflow event=motion_geometry state=ready "
                 "processing=%dx%d pyramid_levels=%d motion_search_passes_per_pair=6 "
                 "motion_grid=%ux%u grid_step=%d fine_block_size=%d coarse_block_size=%d "
                 "search_radius=%d adaptive_parent=2x_if_sad_gt_0.12_and_uniqueness_gt_0.0005 search_order=center_out_exact_legacy_tie_break "
                 "source_pts_preserved=caller_owned",
                 pair->w, pair->h, pair->levels,
                 pair->level[0].forward->params.w, pair->level[0].forward->params.h,
                 FLOW_STEP, FLOW_FINE_BLOCK, FLOW_BLOCK, ctx->search_radius);
        ctx->motion_log_w = pair->w;
        ctx->motion_log_h = pair->h;
    }
    *pair_out = pair;
    return MPVFLOW_GPU_OK;

failure:
    flow_log_once(ctx, FLOW_DIAG_ANALYZE_PAIR, PL_LOG_WARN,
                  "MPVFLOW_DIAGNOSTIC event=analyze_pair state=failed stage=motion_analysis_dispatch "
                  "width=%d height=%d levels=%d reductions=%d status=%d; see preceding compute_dispatch stage",
                  pair->w, pair->h, pair->levels, pair->reductions, MPVFLOW_GPU_ERROR);
    destroy_pair_textures(ctx, pair);
    free(pair);
    return MPVFLOW_GPU_ERROR;
}

void mpvflow_gpu_pair_release(MPVFlowGPUContext *ctx, MPVFlowGPUPair *pair)
{
    if (!pair)
        return;
    if (!ctx)
        ctx = pair->owner;
    if (ctx && ctx == pair->owner)
        destroy_pair_textures(ctx, pair);
    free(pair);
}

static bool flow_opencl_clear_source_cache(MPVFlowGPUContext *ctx,
                                           MPVFlowGPUPair *pair)
{
    if (!pair)
        return false;
    if (mpvflow_opencl_finish(ctx->opencl) != MPVFLOW_OPENCL_OK)
        return false;
    for (int i = 0; i < 2; i++) {
        if (pair->opencl_source[i]) {
            enum MPVFlowOpenCLVkBridgeStatus status =
                mpvflow_opencl_vk_buffer_opencl_finished(pair->opencl_source[i]);
            if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
                flow_log(ctx->gpu, PL_LOG_ERR,
                         "MPVFLOW_DIAGNOSTIC event=opencl_source_cache state=handoff_failed index=%d status=%d",
                         i, status);
                return false;
            }
            status = mpvflow_opencl_vk_buffer_destroy(pair->opencl_source[i]);
            if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
                flow_log(ctx->gpu, PL_LOG_ERR,
                         "MPVFLOW_DIAGNOSTIC event=opencl_source_cache state=release_failed index=%d status=%d",
                         i, status);
                return false;
            }
            pair->opencl_source[i] = NULL;
        }
        pair->opencl_source_identity[i] = NULL;
    }
    pair->opencl_source_cache_ready = false;
    return true;
}

static enum MPVFlowGPUResult flow_opencl_upload_full_resolution_source(
    MPVFlowGPUContext *ctx, pl_tex source, MPVFlowOpenCLVkBuffer *destination,
    const char *stage)
{
    const int width = (int) source->params.w;
    const int height = (int) source->params.h;
    /* The normal prepared input is already RGBA8. Avoid an extra full-resolution
     * preprocess shader and temporary image before the shared-buffer handoff. */
    VkFormat source_format = VK_FORMAT_UNDEFINED;
    VkImageUsageFlags source_usage = 0;
    VkImage source_image = pl_vulkan_unwrap(ctx->gpu, source, &source_format,
                                            &source_usage);
    if (source_image != VK_NULL_HANDLE &&
        source_format == VK_FORMAT_R8G8B8A8_UNORM &&
        (source_usage & VK_IMAGE_USAGE_TRANSFER_SRC_BIT)) {
        enum MPVFlowOpenCLVkBridgeStatus direct_status =
            mpvflow_opencl_vk_copy_texture_to_buffer(ctx->opencl_bridge,
                                                     source, destination);
        if (direct_status == MPVFLOW_OPENCL_VK_BRIDGE_OK)
            return MPVFLOW_GPU_OK;
    }
    pl_tex rgba8 = create_texture_ex(ctx, width, height, ctx->fmt_rgba8,
                                     true, true, false, stage);
    if (!rgba8)
        return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
    struct pl_desc_binding bindings[] = {
        { .object = source, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_LINEAR },
        { .object = rgba8 },
    };
    int cfg[4] = { width, height, 0, 0 };
    bool dispatched = run_pass(ctx, ctx->pass_preprocess, bindings, 2, cfg,
                               0.0f, width, height, "preprocess_full_resolution_source");
    enum MPVFlowOpenCLVkBridgeStatus status = dispatched
        ? mpvflow_opencl_vk_copy_texture_to_buffer(ctx->opencl_bridge, rgba8, destination)
        : MPVFLOW_OPENCL_VK_BRIDGE_VULKAN_ERROR;
    destroy_tex(ctx, &rgba8);
    if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
        flow_log(ctx->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_backend state=runtime_fallback "
                 "stage=%s status=%d reason=%s cpu_readback=no",
                 stage, status,
                 mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge)
                    ? mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge) : "none");
        return MPVFLOW_GPU_ERROR;
    }
    return MPVFLOW_GPU_OK;
}

static enum MPVFlowGPUResult flow_opencl_synthesize(
    MPVFlowGPUContext *ctx, MPVFlowGPUPair *pair,
    pl_tex source0, pl_tex source1, float timestep)
{
    const int width = (int) source0->params.w;
    const int height = (int) source0->params.h;
    if (width > 4096 || height > 4096)
        return MPVFLOW_GPU_UNSUPPORTED_DIMENSION;

    if (!pair->opencl_source_cache_ready ||
        pair->opencl_source_identity[0] != source0 ||
        pair->opencl_source_identity[1] != source1 ||
        !pair->opencl_source[0] || !pair->opencl_source[1] ||
        mpvflow_opencl_vk_buffer_width(pair->opencl_source[0]) != (uint32_t) width ||
        mpvflow_opencl_vk_buffer_height(pair->opencl_source[0]) != (uint32_t) height) {
        if (!flow_opencl_clear_source_cache(ctx, pair))
            return MPVFLOW_GPU_ERROR;
        pair->opencl_source[0] = NULL;
        pair->opencl_source[1] = NULL;
        enum MPVFlowOpenCLVkBridgeStatus create_status =
            mpvflow_opencl_vk_buffer_create(ctx->opencl_bridge, (uint32_t) width,
                (uint32_t) height, MPVFLOW_OPENCL_READ_ONLY, &pair->opencl_source[0]);
        if (create_status == MPVFLOW_OPENCL_VK_BRIDGE_OK)
            create_status = mpvflow_opencl_vk_buffer_create(ctx->opencl_bridge,
                (uint32_t) width, (uint32_t) height, MPVFLOW_OPENCL_READ_ONLY,
                &pair->opencl_source[1]);
        if (create_status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
            flow_log(ctx->gpu, PL_LOG_WARN,
                     "MPVFLOW_DIAGNOSTIC event=compute_backend state=runtime_fallback "
                     "stage=opencl_full_resolution_source_allocation status=%d reason=%s",
                     create_status,
                     mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge)
                        ? mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge) : "none");
            return MPVFLOW_GPU_ERROR;
        }
        for (int i = 0; i < 2; i++) {
            enum MPVFlowOpenCLVkBridgeStatus ready_status =
                mpvflow_opencl_vk_buffer_opencl_finished(pair->opencl_source[i]);
            if (ready_status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
                return MPVFLOW_GPU_ERROR;
        }
        enum MPVFlowGPUResult upload = flow_opencl_upload_full_resolution_source(
            ctx, source0, pair->opencl_source[0], "opencl_full_resolution_frame0");
        if (upload == MPVFLOW_GPU_OK)
            upload = flow_opencl_upload_full_resolution_source(
                ctx, source1, pair->opencl_source[1], "opencl_full_resolution_frame1");
        if (upload != MPVFLOW_GPU_OK)
            return upload;
        pair->opencl_source_identity[0] = source0;
        pair->opencl_source_identity[1] = source1;
        pair->opencl_source_cache_ready = true;
    }

    if (pair->opencl_output &&
        (mpvflow_opencl_vk_buffer_width(pair->opencl_output) != (uint32_t) width ||
         mpvflow_opencl_vk_buffer_height(pair->opencl_output) != (uint32_t) height)) {
        if (mpvflow_opencl_finish(ctx->opencl) != MPVFLOW_OPENCL_OK ||
            mpvflow_opencl_vk_buffer_opencl_finished(pair->opencl_output) !=
                MPVFLOW_OPENCL_VK_BRIDGE_OK)
            return MPVFLOW_GPU_ERROR;
        enum MPVFlowOpenCLVkBridgeStatus destroy_status =
            mpvflow_opencl_vk_buffer_destroy(pair->opencl_output);
        if (destroy_status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
            return MPVFLOW_GPU_ERROR;
        pair->opencl_output = NULL;
    }
    if (!pair->opencl_output) {
        enum MPVFlowOpenCLVkBridgeStatus create_status =
            mpvflow_opencl_vk_buffer_create(ctx->opencl_bridge, (uint32_t) width,
                (uint32_t) height, MPVFLOW_OPENCL_WRITE_ONLY, &pair->opencl_output);
        if (create_status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
            flow_log(ctx->gpu, PL_LOG_WARN,
                     "MPVFLOW_DIAGNOSTIC event=compute_backend state=runtime_fallback "
                     "stage=opencl_full_resolution_output_allocation status=%d reason=%s",
                     create_status,
                     mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge)
                        ? mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge) : "none");
            return MPVFLOW_GPU_ERROR;
        }
        enum MPVFlowOpenCLVkBridgeStatus ready_status =
            mpvflow_opencl_vk_buffer_opencl_finished(pair->opencl_output);
        if (ready_status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
            flow_log(ctx->gpu, PL_LOG_WARN,
                     "MPVFLOW_DIAGNOSTIC event=compute_backend state=runtime_fallback "
                     "stage=opencl_full_resolution_output_initial_handoff status=%d reason=%s",
                     ready_status,
                     mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge)
                        ? mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge) : "none");
            return MPVFLOW_GPU_ERROR;
        }
    }

    for (int i = 0; i < 2; i++) {
        enum MPVFlowOpenCLVkBridgeStatus start_status =
            mpvflow_opencl_vk_buffer_opencl_started(pair->opencl_source[i]);
        if (start_status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
            return MPVFLOW_GPU_ERROR;
    }
    enum MPVFlowOpenCLVkBridgeStatus output_start_status =
        mpvflow_opencl_vk_buffer_opencl_started(pair->opencl_output);
    if (output_start_status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
        return MPVFLOW_GPU_ERROR;

    enum MPVFlowOpenCLStatus opencl_status = mpvflow_opencl_synthesize(
        ctx->opencl, pair->opencl_pair,
        mpvflow_opencl_vk_buffer_get_opencl(pair->opencl_source[0]),
        mpvflow_opencl_vk_buffer_get_opencl(pair->opencl_source[1]),
        mpvflow_opencl_vk_buffer_get_opencl(pair->opencl_output), timestep);
    if (opencl_status != MPVFLOW_OPENCL_OK) {
        if (mpvflow_opencl_finish(ctx->opencl) == MPVFLOW_OPENCL_OK) {
            for (int i = 0; i < 2; i++)
                (void) mpvflow_opencl_vk_buffer_opencl_finished(pair->opencl_source[i]);
            (void) mpvflow_opencl_vk_buffer_opencl_finished(pair->opencl_output);
        }
        flow_log(ctx->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_backend state=runtime_fallback "
                 "stage=opencl_synthesis status=%d cpu_readback=no",
                 opencl_status);
        return MPVFLOW_GPU_ERROR;
    }
    /* synthesize already completes its queue before returning success. */
    for (int i = 0; i < 2; i++) {
        enum MPVFlowOpenCLVkBridgeStatus status =
            mpvflow_opencl_vk_buffer_opencl_finished(pair->opencl_source[i]);
        if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
            return MPVFLOW_GPU_ERROR;
    }
    enum MPVFlowOpenCLVkBridgeStatus bridge_status =
        mpvflow_opencl_vk_buffer_opencl_finished(pair->opencl_output);
    if (bridge_status != MPVFLOW_OPENCL_VK_BRIDGE_OK)
        return MPVFLOW_GPU_ERROR;
    bridge_status = mpvflow_opencl_vk_copy_buffer_to_texture(
        ctx->opencl_bridge, pair->opencl_output, pair->output);
    if (bridge_status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
        flow_log(ctx->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_backend state=runtime_fallback "
                 "stage=opencl_output_download status=%d reason=%s cpu_readback=no",
                 bridge_status,
                 mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge)
                    ? mpvflow_opencl_vk_bridge_last_error(ctx->opencl_bridge) : "none");
        return MPVFLOW_GPU_ERROR;
    }
    return MPVFLOW_GPU_OK;
}

static bool run_visibility_resolver(MPVFlowGPUContext *ctx,
                                    MPVFlowGPUPair *pair,
                                    pl_tex source0, pl_tex source1,
                                    float t, pl_fmt output_fmt,
                                    int output_w, int output_h,
                                    pl_tex *resolved)
{
    if (resolved)
        *resolved = NULL;
    if (!ctx || !pair || !source0 || !source1 || !resolved)
        return false;
    if (ctx->compute_api != MPVFLOW_GPU_COMPUTE_VULKAN || pair->opencl_pair) {
        flow_log_once(ctx, FLOW_DIAG_VISIBILITY, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=visibility_resolver state=skipped "
                      "reason=backend_not_vulkan fallback=production_output");
        return false;
    }

    const bool rgba16f = output_fmt == ctx->fmt_rgba16f;
    if (!ensure_visibility_resolver_pass(ctx, rgba16f))
        return false;
    if (!pair->visibility_output || !pair->visibility_mask ||
        pair->visibility_output->params.w != (unsigned int) output_w ||
        pair->visibility_output->params.h != (unsigned int) output_h ||
        pair->visibility_output->params.format != output_fmt ||
        pair->visibility_mask->params.w != (unsigned int) output_w ||
        pair->visibility_mask->params.h != (unsigned int) output_h ||
        pair->visibility_mask->params.format != ctx->fmt_rgba8) {
        destroy_tex(ctx, &pair->visibility_output);
        destroy_tex(ctx, &pair->visibility_mask);
        pair->visibility_output = create_texture_ex(
            ctx, output_w, output_h, output_fmt, true, true, false,
            "visibility_resolver_output");
        pair->visibility_mask = create_texture_ex(
            ctx, output_w, output_h, ctx->fmt_rgba8, false, false, false,
            "visibility_resolver_mask");
        if (!pair->visibility_output || !pair->visibility_mask) {
            destroy_tex(ctx, &pair->visibility_output);
            destroy_tex(ctx, &pair->visibility_mask);
            flow_log_once(ctx, FLOW_DIAG_VISIBILITY, PL_LOG_WARN,
                          "MPVFLOW_DIAGNOSTIC event=visibility_resolver state=skipped "
                          "reason=resolver_texture_allocation_failed fallback=production_output");
            return false;
        }
    }

    struct FlowLevel *full = &pair->level[0];
    struct pl_desc_binding bindings[10] = {
        { .object = pair->scene[pair->reductions - 1] },
        { .object = full->forward },
        { .object = full->backward },
        { .object = full->luma[0] },
        { .object = full->luma[1] },
        { .object = source0, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_LINEAR },
        { .object = source1, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_LINEAR },
        { .object = pair->output, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_NEAREST },
        { .object = pair->visibility_output },
        { .object = pair->visibility_mask },
    };
    int cfg[4] = { output_w, output_h, pair->w, pair->h };
    pl_pass pass = rgba16f ? ctx->pass_visibility_resolver_rgba16f
                           : ctx->pass_visibility_resolver;
    if (!run_pass(ctx, pass, bindings, 10, cfg, t, output_w, output_h,
                  "synthesize_visibility_resolver")) {
        flow_log_once(ctx, FLOW_DIAG_VISIBILITY, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=visibility_resolver state=skipped "
                      "reason=resolver_dispatch_setup_failed fallback=production_output");
        return false;
    }
    if (!ctx->visibility_resolver_active_logged) {
        flow_log(ctx->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC event=visibility_resolver state=active backend=vulkan "
                 "patch=5x5 correction=0.25 output=%dx%d fallback=production_output",
                 output_w, output_h);
        ctx->visibility_resolver_active_logged = true;
    }
    *resolved = pair->visibility_output;
    return true;
}

static enum MPVFlowGPUResult mpvflow_gpu_synthesize_internal(
    MPVFlowGPUContext *ctx, MPVFlowGPUPair *pair,
    pl_tex source0, pl_tex source1, float t,
    bool enable_visibility_resolver, bool enable_soft_blend,
    bool preserve_motion_blend_on_conflict, pl_tex *output)
{
    if (output)
        *output = NULL;
    if (!ctx || !pair || !source0 || !source1 || !output || pair->owner != ctx ||
        !isfinite(t) || t <= 0.0f || t >= 1.0f) {
        flow_log_once(ctx, FLOW_DIAG_SYNTHESIZE, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=synthesize state=failed stage=input_validation "
                      "pair_present=%d source0_present=%d source1_present=%d output_pointer_present=%d "
                      "timestep=%.9g reason=invalid_context_pair_source_or_timestep",
                      pair != NULL, source0 != NULL, source1 != NULL, output != NULL, t);
        return MPVFLOW_GPU_INVALID;
    }
    flow_timer_poll_and_report(ctx);
    const struct pl_tex_params *p0 = &source0->params;
    const struct pl_tex_params *p1 = &source1->params;
    if (p0->d != 0 || p1->d != 0 || p0->w != p1->w || p0->h != p1->h ||
        p0->w < FLOW_BLOCK || p0->h < FLOW_BLOCK ||
        (uint32_t) p0->w > ctx->gpu->limits.max_tex_2d_dim ||
        (uint32_t) p0->h > ctx->gpu->limits.max_tex_2d_dim ||
        source0->sampler_type != PL_SAMPLER_NORMAL ||
        source1->sampler_type != PL_SAMPLER_NORMAL ||
        !p0->sampleable || !p1->sampleable || !p0->format || !p1->format ||
        p0->format->num_planes != 0 || p1->format->num_planes != 0 ||
        p0->format->num_components < 3 || p1->format->num_components < 3 ||
        !(p0->format->caps & PL_FMT_CAP_SAMPLEABLE) ||
        !(p1->format->caps & PL_FMT_CAP_SAMPLEABLE) ||
        !(p0->format->caps & PL_FMT_CAP_LINEAR) ||
        !(p1->format->caps & PL_FMT_CAP_LINEAR)) {
        flow_log_once(ctx, FLOW_DIAG_SYNTHESIZE, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=synthesize state=failed stage=source_pair_validation "
                      "source0=%ux%u sampleable=%d linear_filterable=%d format=%s "
                      "source1=%ux%u sampleable=%d linear_filterable=%d format=%s "
                      "max_texture_dimension=%u reason=full_resolution_rgb_linear_sources_required",
                      p0->w, p0->h, p0->sampleable,
                      p0->format ? !!(p0->format->caps & PL_FMT_CAP_LINEAR) : 0,
                      p0->format ? p0->format->name : "missing",
                      p1->w, p1->h, p1->sampleable,
                      p1->format ? !!(p1->format->caps & PL_FMT_CAP_LINEAR) : 0,
                      p1->format ? p1->format->name : "missing",
                      ctx->gpu->limits.max_tex_2d_dim);
        return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
    }

    const char *format0 = p0->format->name ? p0->format->name : "";
    const char *format1 = p1->format->name ? p1->format->name : "";
    const bool rgba8_pair = !strcmp(format0, "rgba8") && !strcmp(format1, "rgba8");
    const bool rgba16f_pair = !strcmp(format0, "rgba16f") && !strcmp(format1, "rgba16f");
    if ((!rgba8_pair && !rgba16f_pair) ||
        (rgba16f_pair && !mpvflow_gpu_rgba16f_synthesis_supported(ctx))) {
        flow_log_once(ctx, FLOW_DIAG_SYNTHESIZE, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=synthesize state=failed stage=source_pair_format "
                      "source0_format=%s source1_format=%s hdr_pass_ready=%d reason=unsupported_precision_or_backend",
                      format0, format1, mpvflow_gpu_rgba16f_synthesis_supported(ctx));
        return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
    }
    pl_fmt output_fmt = rgba16f_pair ? ctx->fmt_rgba16f : ctx->fmt_rgba8;
    pl_pass synthesis_pass = rgba16f_pair ? ctx->pass_synthesize_rgba16f : ctx->pass_synthesize;
    if (!output_fmt || !synthesis_pass)
        return MPVFLOW_GPU_UNSUPPORTED_FORMAT;

    const int output_w = (int) p0->w;
    const int output_h = (int) p0->h;
    if (!pair->output || pair->output_w != output_w || pair->output_h != output_h ||
        pair->output->params.format != output_fmt) {
        destroy_tex(ctx, &pair->output);
        pair->output = create_texture_ex(ctx, output_w, output_h, output_fmt,
                                         true, true,
                                         ctx->compute_api == MPVFLOW_GPU_COMPUTE_OPENCL,
                                         "full_resolution_pair_output");
        pair->output_w = pair->output ? output_w : 0;
        pair->output_h = pair->output ? output_h : 0;
        if (!pair->output) {
            flow_log_once(ctx, FLOW_DIAG_SYNTHESIZE, PL_LOG_WARN,
                          "MPVFLOW_DIAGNOSTIC event=synthesize state=failed stage=full_resolution_output_allocation "
                          "width=%d height=%d format=%s motion_width=%d motion_height=%d",
                          output_w, output_h, output_fmt->name, pair->w, pair->h);
            return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
        }
    }
    if (pair->opencl_pair) {
        if (rgba16f_pair)
            return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
        enum MPVFlowGPUResult result =
            flow_opencl_synthesize(ctx, pair, source0, source1, t);
        if (result == MPVFLOW_GPU_OK) {
            if (enable_soft_blend)
                flow_log_once(ctx, FLOW_DIAG_SYNTHESIZE, PL_LOG_WARN,
                              "MPVFLOW_DIAGNOSTIC event=soft_blend state=skipped "
                              "reason=backend_not_vulkan fallback=production_output");
            if (enable_visibility_resolver)
                flow_log_once(ctx, FLOW_DIAG_VISIBILITY, PL_LOG_WARN,
                              "MPVFLOW_DIAGNOSTIC event=visibility_resolver state=skipped "
                              "reason=backend_not_vulkan fallback=production_output");
            *output = pair->output;
            return MPVFLOW_GPU_OK;
        }
        if (ctx->requested_compute_api != MPVFLOW_GPU_COMPUTE_AUTO)
            return result;

        const pl_tex analysis0 = pair->input[0];
        const pl_tex analysis1 = pair->input[1];
        flow_log(ctx->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC event=compute_backend state=fallback "
                 "requested_api=auto from=opencl-qcom to=vulkan-spirv "
                 "stage=full_resolution_synthesis reason=interop_or_compute_failure");
        ctx->compute_api = MPVFLOW_GPU_COMPUTE_VULKAN;
        destroy_pair_textures(ctx, pair);
        if (pair->opencl_pair || pair->opencl_analysis[0] || pair->opencl_analysis[1] ||
            pair->opencl_source[0] || pair->opencl_source[1] || pair->opencl_output)
            return result;

        MPVFlowGPUPair *vulkan_pair = NULL;
        enum MPVFlowGPUResult analysis_status =
            mpvflow_gpu_analyze_pair(ctx, analysis0, analysis1, &vulkan_pair);
        if (analysis_status != MPVFLOW_GPU_OK || !vulkan_pair)
            return analysis_status;
        *pair = *vulkan_pair;
        free(vulkan_pair);
        return mpvflow_gpu_synthesize_internal(ctx, pair, source0, source1, t,
                                               enable_visibility_resolver, enable_soft_blend,
                                               preserve_motion_blend_on_conflict, output);
    }
    pl_tex out = pair->output;
    struct FlowLevel *full = (struct FlowLevel *) &pair->level[0];
    int cfg[4] = { output_w, output_h, pair->w, pair->h };
    if (enable_soft_blend)
        cfg[0] |= 0x40000000;
    if (enable_soft_blend && preserve_motion_blend_on_conflict)
        cfg[0] |= 0x20000000;
    struct pl_desc_binding sb[8] = {
        { .object = source0, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_LINEAR },
        { .object = source1, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_LINEAR },
        { .object = full->forward },
        { .object = full->backward },
        { .object = pair->scene[pair->reductions - 1] },
        { .object = full->luma[0] },
        { .object = full->luma[1] },
        { .object = out },
    };
    if (!run_pass(ctx, synthesis_pass, sb, 8, cfg, t, output_w, output_h,
                  "synthesize_output")) {
        flow_log_once(ctx, FLOW_DIAG_SYNTHESIZE, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=synthesize state=failed stage=synthesis_dispatch "
                      "output=%dx%d motion=%dx%d timestep=%.9g status=%d; see preceding compute_dispatch stage",
                      output_w, output_h, pair->w, pair->h, t, MPVFLOW_GPU_ERROR);
        return MPVFLOW_GPU_ERROR;
    }
    *output = out;
    if (enable_visibility_resolver && !enable_soft_blend) {
        pl_tex resolved = NULL;
        if (run_visibility_resolver(ctx, pair, source0, source1, t, output_fmt,
                                    output_w, output_h, &resolved) && resolved)
            *output = resolved;
    }
    return MPVFLOW_GPU_OK;
}

enum MPVFlowGPUResult mpvflow_gpu_synthesize(MPVFlowGPUContext *ctx,
                                             MPVFlowGPUPair *pair,
                                             pl_tex source0,
                                             pl_tex source1,
                                             float t, pl_tex *output)
{
    return mpvflow_gpu_synthesize_internal(ctx, pair, source0, source1, t,
                                           false, false, false, output);
}
enum MPVFlowGPUResult mpvflow_gpu_synthesize_with_visibility(
    MPVFlowGPUContext *ctx, MPVFlowGPUPair *pair,
    pl_tex source0, pl_tex source1, float t,
    bool enable_visibility_resolver, bool enable_soft_blend, pl_tex *output)
{
    return mpvflow_gpu_synthesize_internal(ctx, pair, source0, source1, t,
                                           enable_visibility_resolver, enable_soft_blend,
                                           false, output);
}
enum MPVFlowGPUResult mpvflow_gpu_synthesize_with_policy(
    MPVFlowGPUContext *ctx, MPVFlowGPUPair *pair,
    pl_tex source0, pl_tex source1, float t,
    bool enable_visibility_resolver, bool enable_soft_blend,
    bool preserve_motion_blend_on_conflict, pl_tex *output)
{
    return mpvflow_gpu_synthesize_internal(ctx, pair, source0, source1, t,
                                           enable_visibility_resolver, enable_soft_blend,
                                           preserve_motion_blend_on_conflict, output);
}
static const char shader_capture_color[] =
    "#version 450\n"
    "layout(set=0,binding=0) uniform sampler2D frame0;\n"
    "layout(set=0,binding=1) uniform sampler2D frame1;\n"
    "layout(set=0,binding=2) uniform sampler2D synthesized;\n"
    "layout(set=0,binding=3,rgba8) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); int sw=pc.cfg.x,sh=pc.cfg.y; "
    "int ow=pc.cfg.z,oh=pc.cfg.w,atlasW=sw*2+ow,atlasH=max(sh,oh); "
    "if(p.x>=atlasW||p.y>=atlasH)return; if(p.x<sw*2){ int tile=p.x/sw; "
    "ivec2 q=ivec2(p.x-tile*sw,p.y); vec2 uv=(vec2(q)+vec2(0.5))/vec2(sw,sh); "
    "vec4 c=tile==0?textureLod(frame0,uv,0.0):textureLod(frame1,uv,0.0); "
    "imageStore(outTex,p,vec4(c.rgb,1.0)); } else { ivec2 q=ivec2(p.x-sw*2,p.y); "
    "vec2 uv=(vec2(q)+vec2(0.5))/vec2(ow,oh); "
    "vec4 c=textureLod(synthesized,uv,0.0); imageStore(outTex,p,vec4(c.rgb,1.0)); } }\n";

static const char shader_capture_maps[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D fwdTex;\n"
    "layout(set=0,binding=1,rgba16f) readonly uniform image2D backTex;\n"
    "layout(set=0,binding=2,rgba8) writeonly uniform image2D outTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "vec3 hsv(vec3 c){ vec3 p=abs(fract(c.xxx+vec3(0.0,0.6666667,0.3333333))*6.0-3.0); "
    "return c.z*mix(vec3(1.0),clamp(p-1.0,0.0,1.0),c.y); }\n"
    "float reliability(vec4 f,float cycle,float valid){ float cq=1.0-smoothstep(0.75,4.0,cycle); "
    "float rq=1.0-smoothstep(0.025,0.22,f.z); float uq=smoothstep(0.08,0.30,f.w); "
    "return cq*rq*uq*valid; }\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); int gw=imageSize(fwdTex).x,gh=imageSize(fwdTex).y; "
    "int tileW=gw; if(p.x>=tileW*7||p.y>=gh)return; int panel=p.x/tileW; ivec2 q=ivec2(p.x-panel*tileW,p.y); "
    "vec4 f=imageLoad(fwdTex,q),b=imageLoad(backTex,q); vec2 g=vec2(q); float stepSize=max(float(pc.cfg.z),1.0); "
    "vec2 e0=g+f.xy/stepSize,e1=g+b.xy/stepSize; float v0=all(greaterThanEqual(e0,vec2(0.0)))&&all(lessThanEqual(e0,vec2(gw-1,gh-1)))?1.0:0.0; "
    "float v1=all(greaterThanEqual(e1,vec2(0.0)))&&all(lessThanEqual(e1,vec2(gw-1,gh-1)))?1.0:0.0; "
    "ivec2 r0=clamp(ivec2(round(e0)),ivec2(0),ivec2(gw-1,gh-1)),r1=clamp(ivec2(round(e1)),ivec2(0),ivec2(gw-1,gh-1)); "
    "float c0=length(f.xy+imageLoad(backTex,r0).xy),c1=length(b.xy+imageLoad(fwdTex,r1).xy); "
    "float conf=max(reliability(f,c0,v0),reliability(b,c1,v1)); vec3 color=vec3(0.0); "
    "if(panel==0){float mag=clamp(length(f.xy)/16.0,0.0,1.0);color=hsv(vec3(atan(f.y,f.x)/6.2831853+0.5,1.0,0.25+0.75*mag));} "
    "else if(panel==1){float mag=clamp(length(b.xy)/16.0,0.0,1.0);color=hsv(vec3(atan(b.y,b.x)/6.2831853+0.5,1.0,0.25+0.75*mag));} "
    "else if(panel==2){float v=clamp(max(f.z,b.z),0.0,1.0);color=vec3(v);} "
    "else if(panel==3){float v=clamp(min(f.w,b.w),0.0,1.0);color=vec3(v);} "
    "else if(panel==4){color=vec3(conf);} else if(panel==5){float err=clamp(max(c0,c1)/4.0,0.0,1.0);color=vec3(err,0.0,0.0);} "
    "else {color=vec3(1.0-conf);} imageStore(outTex,p,vec4(color,1.0)); }\n";

static bool ensure_capture_passes(MPVFlowGPUContext *ctx)
{
    if (!ctx || ctx->compute_api != MPVFLOW_GPU_COMPUTE_VULKAN)
        return false;
    if (ctx->pass_capture_color && ctx->pass_capture_maps && ctx->capture_rgba8)
        return true;

    const unsigned int caps = PL_FMT_CAP_STORABLE | PL_FMT_CAP_HOST_READABLE;
    ctx->capture_rgba8 = pl_find_fmt(ctx->gpu, PL_FMT_UNORM, 4, 8, 8, caps);
    if (!ctx->capture_rgba8 || !ctx->capture_rgba8->glsl_format ||
        strcmp(ctx->capture_rgba8->glsl_format, "rgba8"))
        return false;

    struct pl_desc color_desc[] = {
        { .name = "frame0", .type = PL_DESC_SAMPLED_TEX, .binding = 0 },
        { .name = "frame1", .type = PL_DESC_SAMPLED_TEX, .binding = 1 },
        { .name = "synthesized", .type = PL_DESC_SAMPLED_TEX, .binding = 2 },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 3,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    struct pl_desc maps_desc[] = {
        { .name = "fwdTex", .type = PL_DESC_STORAGE_IMG, .binding = 0,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "backTex", .type = PL_DESC_STORAGE_IMG, .binding = 1,
          .access = PL_DESC_ACCESS_READONLY },
        { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 2,
          .access = PL_DESC_ACCESS_WRITEONLY },
    };
    ctx->pass_capture_color = make_pass(ctx->gpu, shader_capture_color,
                                        color_desc, 4);
    ctx->pass_capture_maps = make_pass(ctx->gpu, shader_capture_maps,
                                       maps_desc, 3);
    if (!ctx->pass_capture_color || !ctx->pass_capture_maps) {
        if (ctx->pass_capture_color)
            pl_pass_destroy(ctx->gpu, &ctx->pass_capture_color);
        if (ctx->pass_capture_maps)
            pl_pass_destroy(ctx->gpu, &ctx->pass_capture_maps);
        ctx->capture_rgba8 = NULL;
        return false;
    }
    return true;
}

static bool ensure_capture_staging(MPVFlowGPUContext *ctx, pl_tex *texture,
                                   int width, int height)
{
    if (!ctx || !texture || width <= 0 || height <= 0 || !ctx->capture_rgba8)
        return false;
    if (*texture && (*texture)->params.w == width &&
        (*texture)->params.h == height &&
        (*texture)->params.format == ctx->capture_rgba8 &&
        (*texture)->params.host_readable && (*texture)->params.storable)
        return true;
    if (*texture)
        pl_tex_destroy(ctx->gpu, texture);
    *texture = pl_tex_create(ctx->gpu, pl_tex_params(
        .w = width, .h = height, .d = 0, .format = ctx->capture_rgba8,
        .storable = true, .host_readable = true));
    return *texture != NULL;
}

static bool dispatch_capture_pass(MPVFlowGPUContext *ctx, pl_pass pass,
                                  struct pl_desc_binding *bindings,
                                  int cfg0, int cfg1, int cfg2, int cfg3,
                                  float timestep,
                                  int width, int height)
{
    if (!ctx || !pass || !bindings || width <= 0 || height <= 0)
        return false;
    struct FlowPushConstants push = {
        .cfg = { cfg0, cfg1, cfg2, cfg3 },
        .timestep = timestep,
    };
    const struct pl_pass_run_params run = {
        .pass = pass,
        .desc_bindings = bindings,
        .push_constants = &push,
        .compute_groups = { (width + 7) / 8, (height + 7) / 8, 1 },
    };
    pl_pass_run(ctx->gpu, &run);
    return true;
}

static uint8_t *download_capture_atlas(MPVFlowGPUContext *ctx, pl_tex texture,
                                       int width, int height)
{
    if (!ctx || !texture || width <= 0 || height <= 0 ||
        (size_t) width > SIZE_MAX / 4 ||
        (size_t) height > SIZE_MAX / ((size_t) width * 4))
        return NULL;
    const size_t row_pitch = (size_t) width * 4;
    uint8_t *pixels = malloc(row_pitch * (size_t) height);
    if (!pixels)
        return NULL;
    const bool ok = pl_tex_download(ctx->gpu, pl_tex_transfer_params(
        .tex = texture,
        .rc = { .x0 = 0, .y0 = 0, .z0 = 0,
                .x1 = width, .y1 = height, .z1 = 1 },
        .row_pitch = row_pitch,
        .ptr = pixels));
    if (!ok) {
        free(pixels);
        return NULL;
    }
    return pixels;
}

static void capture_write_missing(struct MPVFlowCaptureSession *session,
                                  int pair_index, int sample_index,
                                  const char *channel, const char *stage,
                                  const char *reason)
{
    if (session)
        mpvflow_capture_io_missing(session, pair_index, sample_index,
                                   channel, stage, reason);
}

static bool capture_write_panel(MPVFlowGPUContext *ctx,
                                const struct MPVFlowCaptureMeta *meta,
                                const uint8_t *atlas, int atlas_width,
                                int atlas_height, int panel_x, int panel_width,
                                int panel_height,
                                int pair_index, int sample_index,
                                const char *channel)
{
    if (!ctx || !ctx->capture_session || !meta || !atlas || panel_x < 0 ||
        panel_width <= 0 || panel_height <= 0 || panel_x > atlas_width - panel_width ||
        panel_height > atlas_height)
        return false;
    if ((size_t) panel_width > SIZE_MAX / 4 ||
        (size_t) panel_height > SIZE_MAX / ((size_t) panel_width * 4))
        return false;
    const size_t row_bytes = (size_t) panel_width * 4;
    uint8_t *pixels = malloc(row_bytes * (size_t) panel_height);
    if (!pixels)
        return false;
    for (int y = 0; y < panel_height; y++) {
        const uint8_t *source = atlas +
            ((size_t) y * (size_t) atlas_width + (size_t) panel_x) * 4;
        memcpy(pixels + (size_t) y * row_bytes, source, row_bytes);
    }
    char reason[128] = {0};
    const bool ok = mpvflow_capture_io_write(
        ctx->capture_session, pair_index, sample_index, channel, meta,
        pixels, panel_width, panel_height, reason, sizeof(reason)) == 0;
    free(pixels);
    if (!ok) {
        flow_log(ctx->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC component=capture event=image_write state=failed "
                 "channel=%s reason=%s", channel,
                 reason[0] ? reason : "capture_writer_error");
        capture_write_missing(ctx->capture_session, pair_index, sample_index,
                              channel, "png_write",
                              reason[0] ? reason : "capture_writer_error");
    }
    return ok;
}

#if defined(MPVFLOW_DEBUG_CAPTURE)
static bool dispatch_capture_decisions(MPVFlowGPUContext *ctx, MPVFlowGPUPair *pair,
                                       pl_tex source0, pl_tex source1,
                                       int width, int height, float timestep)
{
    if (!ctx || !pair || pair->owner != ctx || !pair->levels ||
        pair->reductions <= 0 || !source0 || !source1 ||
        !source0->params.sampleable || !source1->params.sampleable ||
        source0->params.w != source1->params.w ||
        source0->params.h != source1->params.h ||
        width <= 0 || height <= 0 ||
        width > MPVFLOW_CAPTURE_DECISION_MAX_DIMENSION ||
        height > MPVFLOW_CAPTURE_DECISION_MAX_DIMENSION ||
        (int64_t) width * height > MPVFLOW_CAPTURE_DECISION_MAX_PIXELS ||
        source0->params.format != ctx->fmt_rgba8 ||
        source1->params.format != ctx->fmt_rgba8)
        return false;
    if (!ctx->pass_capture_decisions) {
        struct pl_desc desc[] = {
            { .name = "frame0", .type = PL_DESC_SAMPLED_TEX, .binding = 0 },
            { .name = "frame1", .type = PL_DESC_SAMPLED_TEX, .binding = 1 },
            { .name = "fwdTex", .type = PL_DESC_STORAGE_IMG, .binding = 2, .access = PL_DESC_ACCESS_READONLY },
            { .name = "backTex", .type = PL_DESC_STORAGE_IMG, .binding = 3, .access = PL_DESC_ACCESS_READONLY },
            { .name = "sceneTex", .type = PL_DESC_STORAGE_IMG, .binding = 4, .access = PL_DESC_ACCESS_READONLY },
            { .name = "luma0Tex", .type = PL_DESC_STORAGE_IMG, .binding = 5, .access = PL_DESC_ACCESS_READONLY },
            { .name = "luma1Tex", .type = PL_DESC_STORAGE_IMG, .binding = 6, .access = PL_DESC_ACCESS_READONLY },
            { .name = "outTex", .type = PL_DESC_STORAGE_IMG, .binding = 7, .access = PL_DESC_ACCESS_WRITEONLY },
            { .name = "decisionTex", .type = PL_DESC_STORAGE_IMG, .binding = 8, .access = PL_DESC_ACCESS_WRITEONLY },
        };
        char *shader = shader_replace_once(shader_synthesize, "#version 450\n",
            "#version 450\n#define MPVFLOW_CAPTURE_DECISIONS 1\n");
        if (!shader)
            return false;
        ctx->pass_capture_decisions = make_pass(ctx->gpu, shader, desc, 9);
        free(shader);
    }
    if (!ctx->pass_capture_decisions ||
        !ensure_capture_staging(ctx, &ctx->capture_decision_output, width, height))
        return false;
    if (ctx->capture_hard_output &&
        (ctx->capture_hard_output->params.w != (unsigned int) width ||
         ctx->capture_hard_output->params.h != (unsigned int) height))
        pl_tex_destroy(ctx->gpu, &ctx->capture_hard_output);
    if (!ctx->capture_hard_output)
        ctx->capture_hard_output = pl_tex_create(ctx->gpu, pl_tex_params(
            .w = width, .h = height, .d = 0, .format = ctx->fmt_rgba8,
            .storable = true, .sampleable = true));
    if (!ctx->capture_hard_output)
        return false;
    struct FlowLevel *level = &pair->level[0];
    struct pl_desc_binding bindings[] = {
        { .object = source0, .address_mode = PL_TEX_ADDRESS_CLAMP, .sample_mode = PL_TEX_SAMPLE_LINEAR },
        { .object = source1, .address_mode = PL_TEX_ADDRESS_CLAMP, .sample_mode = PL_TEX_SAMPLE_LINEAR },
        { .object = level->forward }, { .object = level->backward },
        { .object = pair->scene[pair->reductions - 1] },
        { .object = level->luma[0] }, { .object = level->luma[1] },
        { .object = ctx->capture_hard_output },
        { .object = ctx->capture_decision_output },
    };
    /* Write color and its branch label in the SAME invocation: no approximate
     * proxy, separate mask replay, extra estimator pass, or production output alias. */
    return dispatch_capture_pass(ctx, ctx->pass_capture_decisions, bindings,
        width, height, pair->w, pair->h, timestep, width, height);
}

static bool capture_write_decisions(MPVFlowGPUContext *ctx,
                                    const struct MPVFlowCaptureMeta *meta,
                                    uint8_t *raw, int width, int height)
{
    if (!capture_write_panel(ctx, meta, raw, width, height, 0, width, height,
                             ctx->capture_pair_index, 0, "decision_raw"))
        return false;
    static const uint8_t palette[13][3] = {
        { 0, 180, 0 }, { 128, 128, 128 }, { 0, 80, 255 },
        { 255, 0, 0 }, { 255, 128, 0 }, { 0, 255, 255 },
        { 255, 255, 0 }, { 255, 0, 255 }, { 160, 0, 255 },
        { 128, 64, 0 }, { 255, 255, 255 },
        { 0, 128, 255 }, { 255, 192, 64 },
    };
    for (size_t i = 0; i < (size_t) width * height; i++) {
        unsigned id = raw[i * 4];
        if (id > 12)
            return false;
        memcpy(raw + i * 4, palette[id], 3);
    }
    return capture_write_panel(ctx, meta, raw, width, height, 0, width, height,
                                ctx->capture_pair_index, 0, "decision_mask");
}
#endif

static void capture_release_session(MPVFlowGPUContext *ctx)
{
    if (!ctx)
        return;
    if (ctx->capture_session) {
        const int result = mpvflow_capture_io_stop(ctx->capture_session);
        if (result)
            flow_log(ctx->gpu, PL_LOG_WARN,
                     "MPVFLOW_DIAGNOSTIC component=capture event=manifest_write state=failed reason=stop_failed");
        mpvflow_capture_io_destroy(ctx->capture_session);
        ctx->capture_session = NULL;
    }
    ctx->capture_armed = false;
    ctx->capture_pair_ready = false;
    /* The larger debug images are capture-owned; do not retain their VRAM
     * after the bounded session. No decoder, pair, or presentation texture is freed. */
    if (ctx->capture_paired_output) {
        if (ctx->capture_color_atlas)
            pl_tex_destroy(ctx->gpu, &ctx->capture_color_atlas);
        if (ctx->capture_map_atlas)
            pl_tex_destroy(ctx->gpu, &ctx->capture_map_atlas);
        if (ctx->capture_decision_output)
            pl_tex_destroy(ctx->gpu, &ctx->capture_decision_output);
        if (ctx->capture_hard_output)
            pl_tex_destroy(ctx->gpu, &ctx->capture_hard_output);
    }
}

static void capture_write_terminal(MPVFlowGPUContext *ctx, const char *reason)
{
    if (!ctx || ctx->capture_terminal_written || !ctx->capture_session_name[0])
        return;
    const char *backend = mpvflow_gpu_get_compute_api_name(ctx);
    backend = mpvflow_capture_io_backend_label(mpvflow_capture_io_backend_kind(backend));
    if (mpvflow_capture_io_write_terminal_manifest(
            ctx->capture_base_dir, ctx->capture_session_name,
            ctx->capture_engine, backend, ctx->capture_settings,
            ctx->capture_paired_output,
            reason ? reason : "no_synthesized_frames") == 0)
        ctx->capture_terminal_written = true;
}

static bool capture_valid_token(const char *value, size_t max_length)
{
    if (!value || !*value || strlen(value) > max_length)
        return false;
    for (const unsigned char *p = (const unsigned char *) value; *p; p++)
        if (!( (*p >= 'A' && *p <= 'Z') || (*p >= 'a' && *p <= 'z') ||
               (*p >= '0' && *p <= '9') || *p == '_' || *p == '-' ))
            return false;
    return true;
}

enum MPVFlowGPUResult mpvflow_gpu_capture_start(
    MPVFlowGPUContext *ctx, const char *base_dir, const char *session_name,
    const char *engine, const char *effective_settings_json, bool paired_output)
{
    if (!ctx || !base_dir || !*base_dir || strlen(base_dir) >= sizeof(ctx->capture_base_dir) ||
        !capture_valid_token(session_name, 80) || !capture_valid_token(engine, 23) ||
        (strcmp(engine, "mpv") && strcmp(engine, "native-media3")) ||
        !effective_settings_json || !*effective_settings_json ||
        strlen(effective_settings_json) >= sizeof(ctx->capture_settings))
        return MPVFLOW_GPU_INVALID;
    if (ctx->capture_armed)
        return MPVFLOW_GPU_INVALID;

    snprintf(ctx->capture_base_dir, sizeof(ctx->capture_base_dir), "%s", base_dir);
    snprintf(ctx->capture_session_name, sizeof(ctx->capture_session_name), "%s", session_name);
    snprintf(ctx->capture_engine, sizeof(ctx->capture_engine), "%s", engine);
    snprintf(ctx->capture_settings, sizeof(ctx->capture_settings), "%s", effective_settings_json);
    ctx->capture_paired_output = paired_output;
#if !defined(MPVFLOW_DEBUG_CAPTURE)
    if (paired_output) {
        capture_write_terminal(ctx, "decision_capture_requires_debug_build");
        return MPVFLOW_GPU_UNSUPPORTED_API;
    }
#endif
    ctx->capture_terminal_written = false;
    ctx->capture_pair_count = 0;
    ctx->capture_sample_index = 0;
    ctx->capture_pair_ready = false;

    const char *backend = mpvflow_gpu_get_compute_api_name(ctx);
    if (!mpvflow_capture_io_backend_supports_vulkan_readback(backend)) {
        const enum MPVFlowCaptureBackendKind backend_kind =
            mpvflow_capture_io_backend_kind(backend);
        const char *manifest_backend =
            mpvflow_capture_io_backend_label(backend_kind);
        if (backend_kind == MPVFLOW_CAPTURE_BACKEND_UNKNOWN)
            manifest_backend = "unavailable";
        (void) mpvflow_capture_io_write_terminal_manifest(
            ctx->capture_base_dir, ctx->capture_session_name, ctx->capture_engine,
            manifest_backend,
            ctx->capture_settings, ctx->capture_paired_output,
            "capture_readback_requires_vulkan");
        ctx->capture_terminal_written = true;
        flow_log(ctx->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC component=capture event=start state=failed "
                 "reason=capture_readback_requires_vulkan compute_api=%s",
                 backend ? backend : "unavailable");
        return MPVFLOW_GPU_UNSUPPORTED_API;
    }
    if (!ensure_capture_passes(ctx)) {
        (void) mpvflow_capture_io_write_terminal_manifest(
            ctx->capture_base_dir, ctx->capture_session_name, ctx->capture_engine,
            "vulkan", ctx->capture_settings, ctx->capture_paired_output,
            "capture_readback_pass_or_host_format_unavailable");
        ctx->capture_terminal_written = true;
        return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
    }
    ctx->capture_armed = true;
    flow_log(ctx->gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC component=capture event=start state=armed engine=%s backend=vulkan compute_api=%s "
             "limits=pairs_%d_samples_per_pair_%d_bytes_64MiB readback=explicit_user_opt_in paired=%d",
             ctx->capture_engine, backend,
             ctx->capture_paired_output ? MPVFLOW_CAPTURE_PAIRED_MAX_PAIRS : MPVFLOW_CAPTURE_GPU_MAX_PAIRS,
             ctx->capture_paired_output ? MPVFLOW_CAPTURE_PAIRED_SAMPLES_PER_PAIR : MPVFLOW_CAPTURE_MAX_SAMPLES_PER_PAIR,
             ctx->capture_paired_output);
    return MPVFLOW_GPU_OK;
}

bool mpvflow_gpu_capture_active(MPVFlowGPUContext *ctx)
{
    return ctx && ctx->capture_armed;
}

bool mpvflow_gpu_capture_is_paired(MPVFlowGPUContext *ctx)
{
    return ctx && ctx->capture_armed && ctx->capture_paired_output;
}

void mpvflow_gpu_capture_stop(MPVFlowGPUContext *ctx)
{
    if (!ctx)
        return;
    if (ctx->capture_session) {
        capture_release_session(ctx);
        flow_log(ctx->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC component=capture event=stop state=manifest_written engine=%s",
                 ctx->capture_engine);
    } else if (ctx->capture_armed) {
        capture_write_terminal(ctx, "no_synthesized_frames");
        ctx->capture_armed = false;
        flow_log(ctx->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC component=capture event=stop state=no_synthesized_frames engine=%s",
                 ctx->capture_engine);
    }
}

void mpvflow_gpu_capture_fail(MPVFlowGPUContext *ctx, const char *reason)
{
    if (!ctx || !ctx->capture_armed)
        return;
    const char *failure = reason && *reason ? reason : "capture_failed";
    if (ctx->capture_session) {
        const int max_samples = ctx->capture_paired_output
            ? MPVFLOW_CAPTURE_PAIRED_SAMPLES_PER_PAIR
            : MPVFLOW_CAPTURE_MAX_SAMPLES_PER_PAIR;
        const int sample = ctx->capture_sample_index >= 0 &&
            ctx->capture_sample_index < max_samples
            ? ctx->capture_sample_index : 0;
        mpvflow_capture_io_missing(ctx->capture_session,
            ctx->capture_pair_ready ? ctx->capture_pair_index : 0,
            sample, "synthesized", "capture", failure);
        capture_release_session(ctx);
    } else {
        capture_write_terminal(ctx, failure);
        ctx->capture_armed = false;
    }
    flow_log(ctx->gpu, PL_LOG_WARN,
             "MPVFLOW_DIAGNOSTIC component=capture event=failed state=stopped reason=%s",
             failure);
}

enum MPVFlowGPUResult mpvflow_gpu_capture_sample(
    MPVFlowGPUContext *ctx, MPVFlowGPUPair *pair,
    pl_tex source0, pl_tex source1, pl_tex synthesized_output, float timestep,
    double source_pts0, double source_pts1, double output_pts,
    bool visibility_resolver, bool soft_blend,
    bool preserve_motion_blend_on_conflict)
{
    if (!mpvflow_gpu_capture_active(ctx))
        return MPVFLOW_GPU_INVALID;
    if (!pair || pair->owner != ctx || !source0 || !source1 || !synthesized_output ||
        !source0->params.sampleable || !source1->params.sampleable ||
        !synthesized_output->params.sampleable || !pair->levels ||
        !pair->level[0].forward || !pair->level[0].backward ||
        !isfinite(timestep) || !isfinite(source_pts0) ||
        !isfinite(source_pts1) || !isfinite(output_pts)) {
        mpvflow_gpu_capture_fail(ctx, "capture_input_or_pair_unavailable");
        return MPVFLOW_GPU_INVALID;
    }
    const int source_w = (int) source0->params.w;
    const int source_h = (int) source0->params.h;
    const int source1_w = (int) source1->params.w;
    const int source1_h = (int) source1->params.h;
    const int output_w = (int) synthesized_output->params.w;
    const int output_h = (int) synthesized_output->params.h;
    if (source_w <= 0 || source_h <= 0 || source1_w != source_w ||
        source1_h != source_h) {
        flow_log(ctx->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC component=capture event=dimensions state=failed "
                 "reason=capture_source_pair_dimensions_mismatch source0=%dx%d source1=%dx%d synthesized=%dx%d",
                 source_w, source_h, source1_w, source1_h, output_w, output_h);
        mpvflow_gpu_capture_fail(ctx, "capture_source_pair_dimensions_mismatch");
        return MPVFLOW_GPU_UNSUPPORTED_DIMENSION;
    }
    if (output_w <= 0 || output_h <= 0) {
        flow_log(ctx->gpu, PL_LOG_WARN,
                 "MPVFLOW_DIAGNOSTIC component=capture event=dimensions state=failed "
                 "reason=capture_output_dimensions_invalid source=%dx%d synthesized=%dx%d",
                 source_w, source_h, output_w, output_h);
        mpvflow_gpu_capture_fail(ctx, "capture_output_dimensions_invalid");
        return MPVFLOW_GPU_UNSUPPORTED_DIMENSION;
    }

    const bool new_pair = !ctx->capture_pair_ready ||
        source_pts0 != ctx->capture_pair_pts0 || source_pts1 != ctx->capture_pair_pts1;
    if (new_pair) {
        const int max_pairs = ctx->capture_paired_output
            ? MPVFLOW_CAPTURE_PAIRED_MAX_PAIRS : MPVFLOW_CAPTURE_GPU_MAX_PAIRS;
        if (ctx->capture_pair_count >= max_pairs) {
            mpvflow_gpu_capture_stop(ctx);
            return MPVFLOW_GPU_OK;
        }
        ctx->capture_pair_index = ctx->capture_pair_count++;
        ctx->capture_pair_pts0 = source_pts0;
        ctx->capture_pair_pts1 = source_pts1;
        ctx->capture_sample_index = 0;
        ctx->capture_pair_ready = true;
    }
    const int max_samples = ctx->capture_paired_output
        ? MPVFLOW_CAPTURE_PAIRED_SAMPLES_PER_PAIR
        : MPVFLOW_CAPTURE_MAX_SAMPLES_PER_PAIR;
    if (ctx->capture_sample_index >= max_samples)
        return MPVFLOW_GPU_OK;

    if (ctx->capture_paired_output &&
        (source0->params.format != ctx->fmt_rgba8 ||
         source1->params.format != ctx->fmt_rgba8 ||
         synthesized_output->params.format != ctx->fmt_rgba8)) {
        mpvflow_gpu_capture_fail(ctx, "decision_capture_requires_sdr_rgba8");
        return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
    }
    if (ctx->capture_paired_output &&
        (source_w > MPVFLOW_CAPTURE_DECISION_MAX_DIMENSION ||
         source_h > MPVFLOW_CAPTURE_DECISION_MAX_DIMENSION ||
         output_w > MPVFLOW_CAPTURE_DECISION_MAX_DIMENSION ||
         output_h > MPVFLOW_CAPTURE_DECISION_MAX_DIMENSION ||
         (int64_t) source_w * source_h > MPVFLOW_CAPTURE_DECISION_MAX_PIXELS ||
         (int64_t) output_w * output_h > MPVFLOW_CAPTURE_DECISION_MAX_PIXELS)) {
        mpvflow_gpu_capture_fail(ctx, "decision_capture_native_size_limit");
        return MPVFLOW_GPU_UNSUPPORTED_DIMENSION;
    }
    const int max_dimension = ctx->capture_paired_output
        ? MPVFLOW_CAPTURE_DECISION_MAX_DIMENSION : MPVFLOW_CAPTURE_IMAGE_MAX_DIMENSION;
    int source_tile_w = 0, source_tile_h = 0;
    int output_tile_w = 0, output_tile_h = 0;
    if (!mpvflow_capture_io_scaled_dimensions(source_w, source_h, max_dimension,
                                               &source_tile_w, &source_tile_h) ||
        !mpvflow_capture_io_scaled_dimensions(output_w, output_h, max_dimension,
                                               &output_tile_w, &output_tile_h) ||
        source_tile_w > (INT_MAX - output_tile_w) / 2) {
        mpvflow_gpu_capture_fail(ctx, "capture_color_tile_dimensions_invalid");
        return MPVFLOW_GPU_UNSUPPORTED_DIMENSION;
    }
    const int color_atlas_w = source_tile_w * 2 + output_tile_w;
    const int color_atlas_h = source_tile_h > output_tile_h ? source_tile_h : output_tile_h;
    struct FlowLevel *level = &pair->level[0];
    const int grid_w = (int) level->forward->params.w;
    const int grid_h = (int) level->forward->params.h;
    if (source_tile_w < 1 || source_tile_h < 1 || output_tile_w < 1 ||
        output_tile_h < 1 || grid_w < 1 || grid_h < 1 ||
        grid_w > INT_MAX / 7 ||
        !ensure_capture_staging(ctx, &ctx->capture_color_atlas,
                                color_atlas_w, color_atlas_h) ||
        !ensure_capture_staging(ctx, &ctx->capture_map_atlas, grid_w * 7, grid_h)) {
        mpvflow_gpu_capture_fail(ctx, "capture_staging_texture_unavailable");
        return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
    }

    struct MPVFlowCaptureMeta meta = {
        .engine = ctx->capture_engine,
        .backend = "vulkan",
        .source_width = source_w,
        .source_height = source_h,
        .output_width = output_w,
        .output_height = output_h,
        .analysis_width = pair->w,
        .analysis_height = pair->h,
        .grid_width = grid_w,
        .grid_height = grid_h,
        .pts0 = source_pts0,
        .pts1 = source_pts1,
        .output_pts = output_pts,
        .timestep = timestep,
        .effective_settings_json = ctx->capture_settings,
        .paired_output = ctx->capture_paired_output,
        .soft_blend_enabled = soft_blend,
        .visibility_resolver_enabled = visibility_resolver,
        .preserve_motion_blend_on_conflict = preserve_motion_blend_on_conflict,
    };
    if (!ctx->capture_session) {
        flow_log(ctx->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC component=capture event=dimensions state=ready engine=%s "
                 "source=%dx%d synthesized=%dx%d source_tile=%dx%d synthesized_tile=%dx%d",
                 ctx->capture_engine, source_w, source_h, output_w, output_h,
                 source_tile_w, source_tile_h, output_tile_w, output_tile_h);
        char reason[128] = {0};
        ctx->capture_session = mpvflow_capture_io_start(
            ctx->capture_base_dir, ctx->capture_session_name, &meta,
            reason, sizeof(reason));
        if (!ctx->capture_session) {
            flow_log(ctx->gpu, PL_LOG_WARN,
                     "MPVFLOW_DIAGNOSTIC component=capture event=session_create state=failed reason=%s",
                     reason[0] ? reason : "capture_io_start_failed");
            mpvflow_gpu_capture_fail(ctx,
                reason[0] ? reason : "capture_io_start_failed");
            return MPVFLOW_GPU_ERROR;
        }
    }

    struct pl_desc_binding color_bindings[4] = {
        { .object = source0, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_LINEAR },
        { .object = source1, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_LINEAR },
        { .object = synthesized_output, .address_mode = PL_TEX_ADDRESS_CLAMP,
          .sample_mode = PL_TEX_SAMPLE_LINEAR },
        { .object = ctx->capture_color_atlas },
    };
    if (!dispatch_capture_pass(ctx, ctx->pass_capture_color, color_bindings,
                               source_tile_w, source_tile_h,
                               output_tile_w, output_tile_h, timestep,
                               color_atlas_w, color_atlas_h)) {
        mpvflow_gpu_capture_fail(ctx, "capture_color_copy_pass_failed");
        return MPVFLOW_GPU_ERROR;
    }

    const bool capture_new_pair = ctx->capture_sample_index == 0;
    struct pl_desc_binding map_bindings[3] = {
        { .object = level->forward }, { .object = level->backward },
        { .object = ctx->capture_map_atlas },
    };
    if (capture_new_pair && !dispatch_capture_pass(
            ctx, ctx->pass_capture_maps, map_bindings,
            grid_w, grid_h, MPVFLOW_GPU_MOTION_GRID_STEP, 0, timestep,
            grid_w * 7, grid_h)) {
        mpvflow_gpu_capture_fail(ctx, "capture_motion_map_pass_failed");
        return MPVFLOW_GPU_ERROR;
    }

    bool capture_decisions = false;
#if defined(MPVFLOW_DEBUG_CAPTURE)
    capture_decisions = ctx->capture_paired_output && capture_new_pair && !soft_blend;
    if (capture_decisions && !ctx->capture_decision_output) {
        mpvflow_gpu_capture_fail(ctx, "decision_capture_mask_unavailable");
        return MPVFLOW_GPU_ERROR;
    }
#endif
    /* This explicitly opted-in debug path waits on the borrowed GPU queue
     * only after the capture-owned copies have been queued; it never maps or
     * destroys a caller-owned source/synthesis texture. */
    pl_gpu_finish(ctx->gpu);
    uint8_t *color_atlas = download_capture_atlas(
        ctx, ctx->capture_color_atlas, color_atlas_w, color_atlas_h);
    uint8_t *map_atlas = capture_new_pair ? download_capture_atlas(
        ctx, ctx->capture_map_atlas, grid_w * 7, grid_h) : NULL;
    uint8_t *decision_pixels = capture_decisions ? download_capture_atlas(
        ctx, ctx->capture_decision_output, output_w, output_h) : NULL;
    if (!color_atlas || (capture_new_pair && !map_atlas) ||
        (capture_decisions && !decision_pixels)) {
        free(decision_pixels);
        free(color_atlas);
        free(map_atlas);
        mpvflow_gpu_capture_fail(ctx, "capture_texture_download_failed");
        return MPVFLOW_GPU_ERROR;
    }

    bool writes_ok = true;
    if (capture_new_pair) {
        writes_ok = capture_write_panel(ctx, &meta, color_atlas, color_atlas_w,
            color_atlas_h, 0, source_tile_w, source_tile_h,
            ctx->capture_pair_index, -1, "source0") &&
            capture_write_panel(ctx, &meta, color_atlas, color_atlas_w,
            color_atlas_h, source_tile_w, source_tile_w, source_tile_h,
            ctx->capture_pair_index, -1, "source1") &&
            capture_write_panel(ctx, &meta, map_atlas, grid_w * 7,
            grid_h, 0, grid_w, grid_h, ctx->capture_pair_index, -1, "motion_forward") &&
            capture_write_panel(ctx, &meta, map_atlas, grid_w * 7,
            grid_h, 0, grid_w, grid_h, ctx->capture_pair_index, -1, "motion") &&
            capture_write_panel(ctx, &meta, map_atlas, grid_w * 7,
            grid_h, grid_w, grid_w, grid_h, ctx->capture_pair_index, -1, "motion_backward") &&
            capture_write_panel(ctx, &meta, map_atlas, grid_w * 7,
            grid_h, grid_w * 2, grid_w, grid_h, ctx->capture_pair_index, -1, "residual") &&
            capture_write_panel(ctx, &meta, map_atlas, grid_w * 7,
            grid_h, grid_w * 3, grid_w, grid_h, ctx->capture_pair_index, -1, "uniqueness") &&
            capture_write_panel(ctx, &meta, map_atlas, grid_w * 7,
            grid_h, grid_w * 4, grid_w, grid_h, ctx->capture_pair_index, -1, "confidence") &&
            capture_write_panel(ctx, &meta, map_atlas, grid_w * 7,
            grid_h, grid_w * 5, grid_w, grid_h, ctx->capture_pair_index, -1, "cycle_consistency") &&
            capture_write_panel(ctx, &meta, map_atlas, grid_w * 7,
            grid_h, grid_w * 5, grid_w, grid_h, ctx->capture_pair_index, -1, "cycle_error") &&
            capture_write_panel(ctx, &meta, map_atlas, grid_w * 7,
            grid_h, grid_w * 6, grid_w, grid_h,
            ctx->capture_pair_index, -1, "visibility_support");
    }
    writes_ok = writes_ok && capture_write_panel(
        ctx, &meta, color_atlas, color_atlas_w, color_atlas_h,
        source_tile_w * 2, output_tile_w, output_tile_h,
        ctx->capture_pair_index, ctx->capture_sample_index, "synthesized");
#if defined(MPVFLOW_DEBUG_CAPTURE)
    if (capture_decisions)
        writes_ok = capture_write_decisions(ctx, &meta, decision_pixels,
                                            output_w, output_h) && writes_ok;
#endif
    free(decision_pixels);
    free(color_atlas);
    free(map_atlas);
    if (!writes_ok) {
        mpvflow_gpu_capture_fail(ctx, "capture_png_write_failed_or_size_cap");
        return MPVFLOW_GPU_ERROR;
    }

    ctx->capture_sample_index++;
    if (ctx->capture_sample_index >= max_samples)
        flow_log(ctx->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC component=capture event=pair_complete state=ready pair=%d samples=%d",
                 ctx->capture_pair_index, ctx->capture_sample_index);
    if (ctx->capture_paired_output &&
        ctx->capture_pair_index + 1 >= MPVFLOW_CAPTURE_PAIRED_MAX_PAIRS &&
        ctx->capture_sample_index >= MPVFLOW_CAPTURE_PAIRED_SAMPLES_PER_PAIR) {
        flow_log(ctx->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC component=capture event=session_limit state=complete "
                 "pairs=%d samples_per_pair=%d paired_soft_blend=1",
                 MPVFLOW_CAPTURE_PAIRED_MAX_PAIRS,
                 MPVFLOW_CAPTURE_PAIRED_SAMPLES_PER_PAIR);
        mpvflow_gpu_capture_stop(ctx);
    } else if (!ctx->capture_paired_output &&
        ctx->capture_pair_index + 1 >= MPVFLOW_CAPTURE_GPU_MAX_PAIRS &&
        ctx->capture_sample_index >= MPVFLOW_CAPTURE_MAX_SAMPLES_PER_PAIR) {
        flow_log(ctx->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC component=capture event=session_limit state=complete pairs=%d samples_per_pair=%d",
                 MPVFLOW_CAPTURE_GPU_MAX_PAIRS, MPVFLOW_CAPTURE_MAX_SAMPLES_PER_PAIR);
        mpvflow_gpu_capture_stop(ctx);
    }
    return MPVFLOW_GPU_OK;
}

enum MPVFlowGPUResult mpvflow_gpu_capture_paired_sample(
    MPVFlowGPUContext *ctx, MPVFlowGPUPair *pair,
    pl_tex source0, pl_tex source1, float timestep,
    double source_pts0, double source_pts1, double output_pts,
    bool preserve_motion_blend_on_conflict,
    bool production_visibility_resolver, bool production_soft_blend,
    bool production_preserve_motion_blend_on_conflict, pl_tex *production_output)
{
    if (production_output)
        *production_output = NULL;
    if (!mpvflow_gpu_capture_is_paired(ctx) || !production_output)
        return MPVFLOW_GPU_INVALID;

    (void) output_pts; /* Fixed-pair output uses the midpoint, never a fabricated live PTS. */
    const float capture_timestep = 0.5f;
    const double capture_pts = source_pts0 + (source_pts1 - source_pts0) * 0.5;
    const bool blend_variants[MPVFLOW_CAPTURE_PAIRED_SAMPLES_PER_PAIR] = {
        false, true,
    };
    for (int sample = 0; sample < MPVFLOW_CAPTURE_PAIRED_SAMPLES_PER_PAIR; sample++) {
        if (!mpvflow_gpu_capture_active(ctx))
            break;
        pl_tex variant_output = NULL;
        const bool soft_blend = blend_variants[sample];
        enum MPVFlowGPUResult synth_status;
#if defined(MPVFLOW_DEBUG_CAPTURE)
        if (!soft_blend) {
            const bool ok = dispatch_capture_decisions(ctx, pair, source0, source1,
                (int) source0->params.w, (int) source0->params.h, capture_timestep);
            synth_status = ok ? MPVFLOW_GPU_OK : MPVFLOW_GPU_ERROR;
            variant_output = ok ? ctx->capture_hard_output : NULL;
        } else
#endif
        {
            synth_status = mpvflow_gpu_synthesize_with_policy(
                ctx, pair, source0, source1, capture_timestep,
                false, soft_blend, preserve_motion_blend_on_conflict, &variant_output);
        }
        if (synth_status != MPVFLOW_GPU_OK || !variant_output) {
            mpvflow_gpu_capture_fail(ctx, "paired_variant_synthesis_failed");
            break;
        }
        const enum MPVFlowGPUResult capture_status = mpvflow_gpu_capture_sample(
            ctx, pair, source0, source1, variant_output, capture_timestep,
            source_pts0, source_pts1, capture_pts,
            false, soft_blend, preserve_motion_blend_on_conflict);
        if (capture_status != MPVFLOW_GPU_OK)
            break;
    }

    /* Paired variants overwrite the pair-owned scratch output. Restore the
     * playback policy before returning it to the renderer. */
    return mpvflow_gpu_synthesize_with_policy(
        ctx, pair, source0, source1, timestep,
        production_visibility_resolver, production_soft_blend,
        production_preserve_motion_blend_on_conflict, production_output);
}
