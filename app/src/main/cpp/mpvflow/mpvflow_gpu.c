/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * GPU-resident, clean-room block-motion interpolation prototype. All pixel
 * processing is expressed as libplacebo compute passes; no CPU texture
 * transfers, pixel reads, or CPU-side synthesis occur here.
 */
#include "mpvflow_gpu.h"
#include "mpvflow_opencl.h"
#include "mpvflow_opencl_vk_bridge.h"
#include "mpvflow_vulkan_optical.h"

#include <libplacebo/vulkan.h>

#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define FLOW_LEVELS 3
#define FLOW_BLOCK MPVFLOW_GPU_MOTION_BLOCK_SIZE
#define FLOW_FINE_BLOCK MPVFLOW_GPU_MOTION_FINE_BLOCK_SIZE
#define FLOW_STEP MPVFLOW_GPU_MOTION_GRID_STEP
#define FLOW_REDUCTIONS 8
#define FLOW_DEFAULT_MAX_DIM 640
#define FLOW_DEFAULT_RADIUS 8

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
    pl_pass pass_synthesize;
    MPVFlowVulkanOptical *optical;
    struct FlowTimerStats timers[FLOW_TIMER_COUNT];
    uint64_t timing_window_start_ns;
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
    pl_tex optical_seed[2];     /* optional, pair-scoped coarse motion priors */
    bool optical_seed_ready;
    int w, h;
    int output_w, output_h;
    int levels;
    struct FlowLevel level[FLOW_LEVELS];
    int reductions;
    pl_tex scene[FLOW_REDUCTIONS];
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
            .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_SAMPLE_BLOCK_MATCH_BIT_QCOM,
        };
        image_usage_result = get_image_properties2(vk->phys_device, &image_info,
                                                    &image_properties);
    }

    const bool block_match = features_queried && base_features.textureBlockMatch == VK_TRUE;
    const bool window_match = features_queried && window_extension &&
        window_features.textureBlockMatch2 == VK_TRUE;
    const char *state = !base_extension ? "not_supported" :
        !features_queried ? "query_unavailable" :
        block_match && format_queried && r8_block_match_format &&
            image_usage_result == VK_SUCCESS ? "base_r8_capability_present_shader_unintegrated" : "partial";
    flow_log(vk->gpu, PL_LOG_INFO,
             "MPVFLOW_DIAGNOSTIC event=qcom_block_match_capability state=%s "
             "base_extension_supported=%d window_extension_supported=%d "
             "base_extension_enabled=%d window_extension_enabled=%d features_queried=%d "
             "texture_block_match_supported=%d texture_block_match2_supported=%d properties_queried=%d "
             "max_block_match_region=%ux%u max_block_match_window=%ux%u "
             "r8_format_queried=%d r8_block_match_format=%d image_usage_result=%d "
             "shader_execution=not_integrated_in_flow_core",
             state, base_extension, window_extension, base_enabled, window_enabled, features_queried,
             block_match, window_match, properties_queried,
             base_properties.maxBlockMatchRegion.width,
             base_properties.maxBlockMatchRegion.height,
             window_properties.maxBlockMatchWindow.width,
             window_properties.maxBlockMatchWindow.height,
             format_queried, r8_block_match_format, image_usage_result);
}
#else
static void flow_log_qcom_block_match_capability(pl_vulkan vk)
{
    if (vk && vk->gpu)
        flow_log(vk->gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC event=qcom_block_match_capability state=not_compiled "
                 "shader_execution=not_integrated_in_flow_core");
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

static const char shader_flow[] =
    "#version 450\n"
    "layout(set=0,binding=0,rgba16f) readonly uniform image2D srcTex;\n"
    "layout(set=0,binding=1,rgba16f) readonly uniform image2D dstTex;\n"
    "layout(set=0,binding=2,rgba16f) readonly uniform image2D parentTex;\n"
    "layout(set=0,binding=3,rgba16f) writeonly uniform image2D flowTex;\n"
    "layout(set=0,binding=4,rgba16f) readonly uniform image2D sceneTex;\n"
    "layout(push_constant) uniform FlowPush { ivec4 cfg; float timestep; } pc;\n"
    "layout(local_size_x=8, local_size_y=8) in;\n"
    "float flowGridAxis(float p,float extent,int blockSize,int gridCount){if(gridCount<=1)return 0.0;float lastAnchor=max(extent-float(blockSize),0.0),previousAnchor=min(float(gridCount-2)*4.0,lastAnchor),lastIndex=float(gridCount-1);if(p<=previousAnchor)return clamp(p/4.0,0.0,lastIndex);if(lastAnchor<=previousAnchor)return lastIndex;return clamp(lastIndex-1.0+(p-previousAnchor)/(lastAnchor-previousAnchor),0.0,lastIndex);}\n"
    "int sadAt(ivec2 p,ivec2 d,ivec2 sz,int cutoff,int blockSize){ int cost=0; for(int y=0;y<blockSize;y++) for(int x=0;x<blockSize;x++){ float a=imageLoad(srcTex,p+ivec2(x,y)).r; float b=imageLoad(dstTex,p+ivec2(x,y)+d).r; cost+=int(abs(a-b)*255.0+0.5); if(cost>cutoff) return cost; } return cost; }\n"
    "float subpixelOffset(float minusCost,float centerCost,float plusCost){ float curvature=minusCost-2.0*centerCost+plusCost; if(centerCost>minusCost||centerCost>plusCost||curvature<=0.0001) return 0.0; return clamp(0.5*(minusCost-plusCost)/curvature,-0.5,0.5); }\n"
    "float refineAxis(ivec2 p,ivec2 v,ivec2 axis,ivec2 sz,int blockSize,int centerCost){ ivec2 m=v-axis,q=v+axis,block=ivec2(blockSize); if(any(lessThan(p+m,ivec2(0)))||any(lessThan(p+q,ivec2(0)))||any(greaterThan(p+m,sz-block))||any(greaterThan(p+q,sz-block))) return 0.0; int cm=sadAt(p,m,sz,2147483647,blockSize),cp=sadAt(p,q,sz,2147483647,blockSize); return subpixelOffset(float(cm),float(centerCost),float(cp)); }\n"
    "void main(){ ivec2 g=ivec2(gl_GlobalInvocationID.xy); ivec2 grid=imageSize(flowTex); if(any(greaterThanEqual(g,grid))) return; if(imageLoad(sceneTex,ivec2(0)).r>0.1882353){ imageStore(flowTex,g,vec4(0.0,0.0,1.0,0.0)); return; } ivec2 sz=pc.cfg.xy; int blockSize=(pc.cfg.w>>8); if(blockSize!=4) blockSize=8; ivec2 block=ivec2(blockSize),p=min(g*4,sz-block); bool opticalSeed=(pc.cfg.w&2)!=0,hasParent=(pc.cfg.w&1)!=0||opticalSeed;ivec2 center=ivec2(0); float parentResidual=0.0,parentUniqueness=1.0; if(hasParent){ vec4 parentFlow; if(opticalSeed){ parentFlow=imageLoad(parentTex,g); } else { ivec2 pg=imageSize(parentTex),parentSize=(sz+ivec2(1))/2; vec2 parentPos=vec2(flowGridAxis(float(p.x)*0.5,float(parentSize.x),8,pg.x),flowGridAxis(float(p.y)*0.5,float(parentSize.y),8,pg.y)); ivec2 a=clamp(ivec2(floor(parentPos)),ivec2(0),pg-1); ivec2 b=min(a+1,pg-1); vec2 f=fract(parentPos); vec4 top=mix(imageLoad(parentTex,a),imageLoad(parentTex,ivec2(b.x,a.y)),f.x); vec4 bot=mix(imageLoad(parentTex,ivec2(a.x,b.y)),imageLoad(parentTex,b),f.x); parentFlow=mix(top,bot,f.y); } center=ivec2(round(parentFlow.rg*(opticalSeed?1.0:2.0))); parentResidual=parentFlow.b; parentUniqueness=parentFlow.a; } center=clamp(center,-p,sz-block-p); int radius=pc.cfg.z; if(hasParent&&parentResidual>0.12&&parentUniqueness>0.0005)radius=min(radius*2,16); ivec2 lo=max(-p,center-ivec2(radius)); ivec2 hi=min(sz-block-p,center+ivec2(radius)); ivec2 bestVector=center; int best=sadAt(p,bestVector,sz,2147483647,blockSize),second=2147483647; bool stopOnCenterTie=false;\n"
    "for(int ring=1;ring<=radius&&!stopOnCenterTie;ring++) for(int dy=-ring;dy<=ring&&!stopOnCenterTie;dy++) for(int dx=-ring;dx<=ring&&!stopOnCenterTie;dx++){ if(max(abs(dx),abs(dy))!=ring) continue; ivec2 d=center+ivec2(dx,dy); if(any(lessThan(d,lo))||any(greaterThan(d,hi))) continue; if(all(equal(d,bestVector))) continue; if(best==0&&second==0){ if(all(equal(bestVector,center))){stopOnCenterTie=true;continue;} if(d.y>bestVector.y||(d.y==bestVector.y&&d.x>=bestVector.x)) continue; } int c=sadAt(p,d,sz,best==0?0:second,blockSize); if(best==0){if(c==0){second=0; if(!all(equal(bestVector,center))&&(d.y<bestVector.y||(d.y==bestVector.y&&d.x<bestVector.x))) bestVector=d;} else if(second!=0) second=1;} else if(c<best){second=best;best=c;bestVector=d;} else if(c==best){if(second>c) second=c; if(!all(equal(bestVector,center))&&(d.y<bestVector.y||(d.y==bestVector.y&&d.x<bestVector.x))) bestVector=d;} else if(c<second) second=c; }\n"
    "vec2 refined=vec2(bestVector); refined.x+=refineAxis(p,bestVector,ivec2(1,0),sz,blockSize,best); refined.y+=refineAxis(p,bestVector,ivec2(0,1),sz,blockSize,best); float uniqueness=second<2147483647?clamp(float(second-best)/max(float(second),1.0),0.0,1.0):0.0; imageStore(flowTex,g,vec4(refined,float(best)/(float(blockSize*blockSize)*255.0),uniqueness)); }\n"
;

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
    "vec4 flowImageAt(ivec2 p,int direction){ return direction==0?imageLoad(fwdTex,p):imageLoad(backTex,p); }\n"
    "float lumaAt(vec2 p,int direction){ ivec2 sz=direction==0?imageSize(luma0Tex):imageSize(luma1Tex); ivec2 q=clamp(ivec2(round(p)),ivec2(0),sz-1); return direction==0?imageLoad(luma0Tex,q).r:imageLoad(luma1Tex,q).r; }\n"
    "float flowGridAxis(float p,float extent,int blockSize,int gridCount){if(gridCount<=1)return 0.0;float lastAnchor=max(extent-float(blockSize),0.0),previousAnchor=min(float(gridCount-2)*4.0,lastAnchor),lastIndex=float(gridCount-1);if(p<=previousAnchor)return clamp(p/4.0,0.0,lastIndex);if(lastAnchor<=previousAnchor)return lastIndex;return clamp(lastIndex-1.0+(p-previousAnchor)/(lastAnchor-previousAnchor),0.0,lastIndex); }\n"
    "vec2 flowGridCoord(vec2 p,int direction){ivec2 extent=direction==0?imageSize(luma0Tex):imageSize(luma1Tex),grid=imageSize(fwdTex);return vec2(flowGridAxis(p.x,float(extent.x),4,grid.x),flowGridAxis(p.y,float(extent.y),4,grid.y));}\n"
    "vec4 flowAtEdgeAware(vec2 p,int direction){ ivec2 sz=imageSize(fwdTex); vec2 g=flowGridCoord(p,direction); ivec2 a=ivec2(floor(g)); ivec2 b=min(a+1,sz-1); vec2 t=fract(g); vec4 v00=flowImageAt(a,direction),v10=flowImageAt(ivec2(b.x,a.y),direction); vec4 v01=flowImageAt(ivec2(a.x,b.y),direction),v11=flowImageAt(b,direction); float s00=(1.0-t.x)*(1.0-t.y),s10=t.x*(1.0-t.y),s01=(1.0-t.x)*t.y,s11=t.x*t.y; ivec2 lumaSize=direction==0?imageSize(luma0Tex):imageSize(luma1Tex); ivec2 c00=min(a*4,lumaSize-4)+2,c10=min(ivec2(b.x,a.y)*4,lumaSize-4)+2; ivec2 c01=min(ivec2(a.x,b.y)*4,lumaSize-4)+2,c11=min(b*4,lumaSize-4)+2; float guide=lumaAt(p,direction),d00=lumaAt(vec2(c00),direction)-guide; float d10=lumaAt(vec2(c10),direction)-guide,d01=lumaAt(vec2(c01),direction)-guide; float d11=lumaAt(vec2(c11),direction)-guide; float variance=s00*d00*d00+s10*d10*d10+s01*d01*d01+s11*d11*d11; float safeVariance=max(variance,0.000001); float w00=s00*exp(-(d00*d00)/safeVariance),w10=s10*exp(-(d10*d10)/safeVariance); float w01=s01*exp(-(d01*d01)/safeVariance),w11=s11*exp(-(d11*d11)/safeVariance); float total=w00+w10+w01+w11; return total>0.000001?(v00*w00+v10*w10+v01*w01+v11*w11)/total:flowImageAt(a,direction); }\n"
    "vec4 flowAtBilinear(vec2 p,int direction){ ivec2 sz=imageSize(fwdTex); vec2 g=flowGridCoord(p,direction); ivec2 a=ivec2(floor(g)); ivec2 b=min(a+1,sz-1); vec2 t=fract(g); vec4 v00=flowImageAt(a,direction),v10=flowImageAt(ivec2(b.x,a.y),direction); vec4 v01=flowImageAt(ivec2(a.x,b.y),direction),v11=flowImageAt(b,direction); return mix(mix(v00,v10,t.x),mix(v01,v11,t.x),t.y); }\n"
    "vec4 colorAt(sampler2D s,vec2 p){ ivec2 sz=textureSize(s,0); p=clamp(p,vec2(0.0),vec2(sz-1)); return textureLod(s,(p+vec2(0.5))/vec2(sz),0.0); }\n"
    "float pointInBounds(vec2 p,ivec2 sz){ return step(0.0,p.x)*step(p.x,float(sz.x-1))*step(0.0,p.y)*step(p.y,float(sz.y-1)); }\n"
    "float reliability(vec4 flow,float cycle,float valid){ float cycleQuality=1.0-smoothstep(0.75,4.0,cycle); float matchQuality=1.0-smoothstep(0.025,0.22,flow.z); float uniquenessQuality=smoothstep(0.08,0.30,flow.w); return cycleQuality*matchQuality*uniquenessQuality*valid; }\n"
    "float neighborhoodQuality(vec2 p,int direction,vec2 center){ vec2 d=vec2(2.0); vec2 n0=flowAtBilinear(p-d,direction).xy,n1=flowAtBilinear(p+vec2(d.x,-d.y),direction).xy,n2=flowAtBilinear(p+vec2(-d.x,d.y),direction).xy,n3=flowAtBilinear(p+d,direction).xy; float spread=(length(n0-center)+length(n1-center)+length(n2-center)+length(n3-center))*0.25; return 1.0-smoothstep(0.65,2.8,spread); }\n"
    "void main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy); ivec2 outSize=imageSize(outTex),motionSize=pc.cfg.zw; if(any(greaterThanEqual(p,outSize))) return; vec2 srcP=vec2(p),uv=(srcP+vec2(0.5))/vec2(outSize),flowP=uv*vec2(motionSize); float cut=imageLoad(sceneTex,ivec2(0)).r; if(cut>0.1882353){ vec4 c=pc.timestep<=0.5?texelFetch(frame0,p,0):texelFetch(frame1,p,0); imageStore(outTex,p,c); return; } { float staticLumaDelta=abs(lumaAt(flowP,0)-lumaAt(flowP,1)); if(staticLumaDelta<=0.0039216){ vec4 fastForward=flowAtBilinear(flowP,0),fastBackward=flowAtBilinear(flowP,1); if(dot(fastForward.xy,fastForward.xy)<=0.0025&&dot(fastBackward.xy,fastBackward.xy)<=0.0025){ vec4 static0=texelFetch(frame0,p,0),static1=texelFetch(frame1,p,0); float colorDelta=max(max(abs(static0.r-static1.r),abs(static0.g-static1.g)),abs(static0.b-static1.b)); bool staticPixel=colorDelta<=0.0039216; if(staticPixel){ vec4 outc=mix(static0,static1,pc.timestep); outc.a=1.0; imageStore(outTex,p,outc); return; } } } } /* Vulkan fast path: bilinear initial/cycle flow; retain final edge refinement. */ vec4 f=flowAtBilinear(flowP,0),b=flowAtBilinear(flowP,1); vec2 a=flowP-pc.timestep*f.xy,z=flowP-(1.0-pc.timestep)*b.xy; for(int i=0;i<2;i++){ f=flowAtBilinear(a,0); a=flowP-pc.timestep*f.xy; b=flowAtBilinear(z,1); z=flowP-(1.0-pc.timestep)*b.xy; } f=flowAtEdgeAware(a,0); b=flowAtEdgeAware(z,1); vec2 cycle0=a+f.xy,cycle1=z+b.xy; vec4 backAt0=flowAtEdgeAware(cycle0,1); vec4 forwardAt1=flowAtEdgeAware(cycle1,0); float cycleError0=length(f.xy+backAt0.xy); float cycleError1=length(b.xy+forwardAt1.xy); vec2 scale=vec2(outSize)/vec2(motionSize),srcA=srcP+(a-flowP)*scale,srcZ=srcP+(z-flowP)*scale; vec4 ca=colorAt(frame0,srcA); vec4 cb=colorAt(frame1,srcZ); float valid0=pointInBounds(a,motionSize)*pointInBounds(cycle0,motionSize); float valid1=pointInBounds(z,motionSize)*pointInBounds(cycle1,motionSize); float confidence0=reliability(f,cycleError0,valid0); float confidence1=reliability(b,cycleError1,valid1); float confidence=max(confidence0,confidence1); vec4 outc; if(confidence<0.28){ outc=pc.timestep<0.5?texelFetch(frame0,p,0):texelFetch(frame1,p,0); } else { float visibility0=smoothstep(0.15,0.45,confidence0),visibility1=smoothstep(0.15,0.45,confidence1); float weight0=(1.0-pc.timestep)*visibility0,weight1=pc.timestep*visibility1,total=weight0+weight1; float lumaDelta=abs(dot(ca.rgb,vec3(0.299,0.587,0.114))-dot(cb.rgb,vec3(0.299,0.587,0.114))); float colorDelta=max(max(abs(ca.r-cb.r),abs(ca.g-cb.g)),abs(ca.b-cb.b)); float chromaDelta=max(abs((ca.r-ca.g)-(cb.r-cb.g)),abs((ca.b-ca.g)-(cb.b-cb.g))); float neighborhood0=neighborhoodQuality(a,0,f.xy),neighborhood1=neighborhoodQuality(z,1,b.xy); float flowSpread=length(f.xy-b.xy); bool neighborhoodConflict=(min(neighborhood0,neighborhood1)<0.30&&(lumaDelta>0.06||colorDelta>0.08))||(flowSpread>3.0&&min(neighborhood0,neighborhood1)<0.45); bool chromaBoundary=colorDelta>0.12&&chromaDelta>0.06; bool boundaryConflict=neighborhoodConflict||lumaDelta>0.08||chromaBoundary||(min(f.w,b.w)<0.08&&colorDelta>0.10); float edgeConfidence0=confidence0*(0.5+0.5*smoothstep(0.02,0.35,f.w)),edgeConfidence1=confidence1*(0.5+0.5*smoothstep(0.02,0.35,b.w)); if(boundaryConflict&&edgeConfidence0>edgeConfidence1+0.10) outc=ca; else if(boundaryConflict&&edgeConfidence1>edgeConfidence0+0.10) outc=cb; else if(boundaryConflict) outc=pc.timestep<0.5?texelFetch(frame0,p,0):texelFetch(frame1,p,0); else outc=total>0.0001?mix(ca,cb,weight1/total):(pc.timestep<0.5?texelFetch(frame0,p,0):texelFetch(frame1,p,0)); } outc.a=1.0; imageStore(outTex,p,outc); }\n"
;

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

    char optical_reason[160] = {0};
    ctx->optical = mpvflow_vulkan_optical_create(gpu, optical_reason,
                                                 sizeof(optical_reason));
    if (diagnostics) {
        diagnostics->optical_flow_attempted = true;
        diagnostics->optical_flow_ready = ctx->optical != NULL;
        snprintf(diagnostics->optical_flow_reason,
                 sizeof(diagnostics->optical_flow_reason), "%s",
                 ctx->optical ? "ready" :
                     (optical_reason[0] ? optical_reason : "unavailable"));
    }
    if (!ctx->optical)
        flow_log(gpu, PL_LOG_INFO,
                 "MPVFLOW_DIAGNOSTIC event=optical_flow_backend state=disabled "
                 "reason=%s fallback=validated_vulkan_block_matching",
                 optical_reason[0] ? optical_reason : "unavailable");

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
    ctx->pass_flow = make_pass(gpu, shader_flow, flow_desc, 5);
    ctx->pass_synthesize = make_pass(gpu, shader_synthesize, synth_desc, 8);
    if (ctx->optical) {
        ctx->pass_optical_luma = make_pass(gpu, shader_optical_luma,
                                           optical_luma_desc, 2);
        ctx->pass_optical_seed = make_pass(gpu, shader_optical_seed,
                                           optical_seed_desc, 3);
        if (!ctx->pass_optical_luma || !ctx->pass_optical_seed) {
            flow_log(gpu, PL_LOG_WARN,
                     "MPVFLOW_DIAGNOSTIC event=optical_flow_backend state=disabled "
                     "reason=optional_seed_shader_compile_failed fallback=validated_vulkan_block_matching "
                     "luma_pass=%d seed_pass=%d",
                     !!ctx->pass_optical_luma, !!ctx->pass_optical_seed);
            mpvflow_vulkan_optical_destroy(&ctx->optical);
            if (ctx->pass_optical_luma)
                pl_pass_destroy(gpu, &ctx->pass_optical_luma);
            if (ctx->pass_optical_seed)
                pl_pass_destroy(gpu, &ctx->pass_optical_seed);
        }
    }
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
             "pass_mask=0x7f max_dimension=%d search_radius=%d",
             ctx->max_dimension, ctx->search_radius);
    return ctx;
}

void mpvflow_gpu_destroy(MPVFlowGPUContext *ctx)
{
    if (!ctx)
        return;
    mpvflow_vulkan_optical_destroy(&ctx->optical);
    if (ctx->pass_preprocess) pl_pass_destroy(ctx->gpu, &ctx->pass_preprocess);
    if (ctx->pass_luma) pl_pass_destroy(ctx->gpu, &ctx->pass_luma);
    if (ctx->pass_downsample) pl_pass_destroy(ctx->gpu, &ctx->pass_downsample);
    if (ctx->pass_reduce_first) pl_pass_destroy(ctx->gpu, &ctx->pass_reduce_first);
    if (ctx->pass_reduce) pl_pass_destroy(ctx->gpu, &ctx->pass_reduce);
    if (ctx->pass_flow) pl_pass_destroy(ctx->gpu, &ctx->pass_flow);
    if (ctx->pass_optical_luma) pl_pass_destroy(ctx->gpu, &ctx->pass_optical_luma);
    if (ctx->pass_optical_seed) pl_pass_destroy(ctx->gpu, &ctx->pass_optical_seed);
    if (ctx->pass_synthesize) pl_pass_destroy(ctx->gpu, &ctx->pass_synthesize);
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
            pl_tex src = lv->luma[dir ? 1 : 0];
            pl_tex dst = lv->luma[dir ? 0 : 1];
            pl_tex flow = dir ? lv->backward : lv->forward;
            pl_tex parent_flow = optical_parent ? pair->optical_seed[dir] : parent
                ? (dir ? parent->backward : parent->forward)
                : (dir ? lv->forward : lv->backward);
            const bool has_parent = parent || optical_parent;
            int radius = parent ? 2 : ctx->search_radius;
            const int block_size = li == 0 ? FLOW_FINE_BLOCK : FLOW_BLOCK;
            int fc[4] = { lv->w, lv->h, radius,
                          (has_parent ? 1 : 0) | (optical_parent ? 2 : 0) |
                          (block_size << 8) };
            struct pl_desc_binding fb[5] = {
                { .object = src }, { .object = dst }, { .object = parent_flow },
                { .object = flow }, { .object = scene_delta },
            };
            const char *stage = dir
                ? (li == 0 ? "motion_search_backward_level_0" :
                   li == 1 ? "motion_search_backward_level_1" : "motion_search_backward_level_2")
                : (li == 0 ? "motion_search_forward_level_0" :
                   li == 1 ? "motion_search_forward_level_1" : "motion_search_forward_level_2");
            if (!run_pass(ctx, ctx->pass_flow, fb, 5, fc, 0.0f,
                          flow->params.w, flow->params.h,
                          stage))
                goto failure;
        }
    }
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

enum MPVFlowGPUResult mpvflow_gpu_synthesize(MPVFlowGPUContext *ctx,
                                             MPVFlowGPUPair *pair,
                                             pl_tex source0,
                                             pl_tex source1,
                                             float t, pl_tex *output)
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

    const int output_w = (int) p0->w;
    const int output_h = (int) p0->h;
    if (!pair->output || pair->output_w != output_w || pair->output_h != output_h) {
        destroy_tex(ctx, &pair->output);
        pair->output = create_texture_ex(ctx, output_w, output_h, ctx->fmt_rgba8,
                                         true, true,
                                         ctx->compute_api == MPVFLOW_GPU_COMPUTE_OPENCL,
                                         "full_resolution_pair_output");
        pair->output_w = pair->output ? output_w : 0;
        pair->output_h = pair->output ? output_h : 0;
        if (!pair->output) {
            flow_log_once(ctx, FLOW_DIAG_SYNTHESIZE, PL_LOG_WARN,
                          "MPVFLOW_DIAGNOSTIC event=synthesize state=failed stage=full_resolution_output_allocation "
                          "width=%d height=%d format=rgba8 motion_width=%d motion_height=%d",
                          output_w, output_h, pair->w, pair->h);
            return MPVFLOW_GPU_UNSUPPORTED_FORMAT;
        }
    }
    if (pair->opencl_pair) {
        enum MPVFlowGPUResult result =
            flow_opencl_synthesize(ctx, pair, source0, source1, t);
        if (result == MPVFLOW_GPU_OK) {
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
        return mpvflow_gpu_synthesize(ctx, pair, source0, source1, t, output);
    }
    pl_tex out = pair->output;
    struct FlowLevel *full = (struct FlowLevel *) &pair->level[0];
    int cfg[4] = { output_w, output_h, pair->w, pair->h };
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
    if (!run_pass(ctx, ctx->pass_synthesize, sb, 8, cfg, t, output_w, output_h,
                  "synthesize_output")) {
        flow_log_once(ctx, FLOW_DIAG_SYNTHESIZE, PL_LOG_WARN,
                      "MPVFLOW_DIAGNOSTIC event=synthesize state=failed stage=synthesis_dispatch "
                      "output=%dx%d motion=%dx%d timestep=%.9g status=%d; see preceding compute_dispatch stage",
                      output_w, output_h, pair->w, pair->h, t, MPVFLOW_GPU_ERROR);
        return MPVFLOW_GPU_ERROR;
    }
    *output = out;
    return MPVFLOW_GPU_OK;
}
