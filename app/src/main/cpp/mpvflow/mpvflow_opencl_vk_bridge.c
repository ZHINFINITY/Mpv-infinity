/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 *
 * Vulkan <-> OpenCL BLOB-AHardwareBuffer bridge. The VkBuffer and OpenCL
 * wrapper import the same AHardwareBuffer allocation. VkImage objects are
 * handled only as images and are never imported as linear OpenCL byte buffers.
 */
#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR 1
#endif
#include "mpvflow_opencl_vk_bridge.h"

#include <android/hardware_buffer.h>
#include <libplacebo/vulkan.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#ifndef VK_QUEUE_FAMILY_FOREIGN_EXT
#define VK_QUEUE_FAMILY_FOREIGN_EXT 0xfffffffdU
#endif

#define MPVFLOW_VK_MAX_DIMENSION 4096u
#define MPVFLOW_VK_MAX_BYTES (256u * 1024u * 1024u)
#define MPVFLOW_VK_ROW_ALIGNMENT 64u
#define MPVFLOW_VK_FOREVER UINT64_MAX
#define MPVFLOW_IMPORT_DIAGNOSTIC_DIMENSION 32u

enum BufferState {
    /* Fresh buffer is foreign-owned. Caller must explicitly announce that no
     * OpenCL work is pending (or that clFinish completed) before Vulkan use. */
    BUFFER_FOREIGN_INITIAL = 0,
    BUFFER_FOREIGN_CL_PENDING,
    BUFFER_FOREIGN_CL_DONE,
    BUFFER_GPU_OWNED,
};

struct MPVFlowOpenCLVkBuffer {
    MPVFlowOpenCLVkBridge *bridge;
    struct MPVFlowOpenCLVkBuffer *next;
    AHardwareBuffer *ahb;
    VkBuffer vk_buffer;
    VkDeviceMemory memory;
    VkDeviceSize vk_allocation_size;
    size_t blob_capacity;
    uint32_t width;
    uint32_t height;
    uint32_t row_stride;
    enum MPVFlowOpenCLAccess access;
    MPVFlowOpenCLBuffer *opencl_buffer;
    enum BufferState state;
};

struct MPVFlowOpenCLVkBridge {
    pl_gpu gpu;
    pl_vulkan vk;
    MPVFlowOpenCLContext *opencl; /* borrowed */
    VkQueue queue;
    VkCommandPool command_pool;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID get_ahb_properties;
    VkExternalMemoryFeatureFlags external_memory_features;
    VkExternalMemoryHandleTypeFlags external_memory_compatible_handle_types;
    VkExternalMemoryHandleTypeFlags external_memory_export_from_imported_handle_types;
    VkExternalMemoryHandleTypeFlags external_buffer_handle_type;
    pthread_mutex_t mutex;
    struct MPVFlowOpenCLVkBuffer *buffers;
    char error[384];
};

static void set_error(MPVFlowOpenCLVkBridge *b, const char *fmt, ...)
{
    va_list ap;
    if (!b) return;
    va_start(ap, fmt);
    (void)vsnprintf(b->error, sizeof(b->error), fmt, ap);
    va_end(ap);
}

static void copy_error(char *dst, size_t cap, const char *message)
{
    if (!dst || !cap) return;
    (void)snprintf(dst, cap, "%s", message ? message : "unknown error");
}

static const char *vk_result_name(VkResult result)
{
    switch (result) {
    case VK_SUCCESS: return "VK_SUCCESS";
    case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
    case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
    case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
    case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
    case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
    case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
    case VK_ERROR_INVALID_EXTERNAL_HANDLE: return "VK_ERROR_INVALID_EXTERNAL_HANDLE";
    default: return "VkResult (see numeric diagnostic)";
    }
}

static bool device_extension_enabled(pl_vulkan vk, const char *wanted)
{
    for (int i = 0; i < vk->num_extensions; i++) {
        if (vk->extensions[i] && strcmp(vk->extensions[i], wanted) == 0)
            return true;
    }
    return false;
}

static PFN_vkGetPhysicalDeviceExternalBufferProperties
load_external_buffer_properties(pl_vulkan vk)
{
    if (!vk || !vk->get_proc_addr)
        return NULL;
    PFN_vkVoidFunction function = vk->get_proc_addr(
        vk->instance, "vkGetPhysicalDeviceExternalBufferProperties");
    if (!function) {
        function = vk->get_proc_addr(
            vk->instance, "vkGetPhysicalDeviceExternalBufferPropertiesKHR");
    }
    return (PFN_vkGetPhysicalDeviceExternalBufferProperties) function;
}

static enum MPVFlowOpenCLVkBridgeStatus fail_vk(
    MPVFlowOpenCLVkBridge *b, const char *operation, VkResult result)
{
    set_error(b, "%s failed: %s (%d)", operation, vk_result_name(result), (int)result);
    return MPVFLOW_OPENCL_VK_BRIDGE_VULKAN_ERROR;
}

static void init_import_stage_diagnostics(
    struct MPVFlowOpenCLVkImportStageDiagnostics *diagnostics,
    size_t qcom_padding_bytes, enum MPVFlowOpenCLAccess access)
{
    if (!diagnostics)
        return;
    *diagnostics = (struct MPVFlowOpenCLVkImportStageDiagnostics) {
        .bridge_status = MPVFLOW_OPENCL_VK_BRIDGE_INVALID,
        .ahb_allocate_result = INT32_MIN,
        .vk_ahb_properties_result = INT32_MIN,
        .vk_create_buffer_result = INT32_MIN,
        .vk_allocate_memory_result = INT32_MIN,
        .vk_bind_buffer_memory_result = INT32_MIN,
        .opencl_wrapper_status = INT32_MIN,
        .cl_create_buffer_error = MPVFLOW_OPENCL_CL_ERROR_NOT_RETURNED,
        .cl_release_mem_object_error = MPVFLOW_OPENCL_CL_ERROR_NOT_RETURNED,
        .vk_selected_memory_type = UINT32_MAX,
        .qcom_size_arg = MPVFLOW_OPENCL_QCOM_IMPORT_SIZE_ARG,
        .qcom_flags = MPVFLOW_OPENCL_QCOM_IMPORT_FLAGS,
        .qcom_allocation_type = MPVFLOW_OPENCL_QCOM_AHB_ALLOCATION_TYPE,
        .qcom_cache_policy = MPVFLOW_OPENCL_QCOM_HOST_WRITEBACK_POLICY,
        .wrapper_access = (int32_t)access,
        .qcom_padding_bytes = qcom_padding_bytes,
    };
}

static void record_ahb_descriptor(
    struct MPVFlowOpenCLVkImportStageDiagnostics *diagnostics,
    const AHardwareBuffer_Desc *desc)
{
    if (!diagnostics || !desc)
        return;
    diagnostics->actual_format = desc->format;
    diagnostics->actual_width = desc->width;
    diagnostics->actual_height = desc->height;
    diagnostics->actual_stride = desc->stride;
    diagnostics->actual_layers = desc->layers;
    diagnostics->actual_usage = desc->usage;
}

static void record_import_call(
    struct MPVFlowOpenCLVkImportStageDiagnostics *diagnostics,
    enum MPVFlowOpenCLStatus status, int32_t cl_error)
{
    if (!diagnostics)
        return;
    diagnostics->opencl_wrapper_status = (int32_t)status;
    diagnostics->cl_create_buffer_error = cl_error;
    diagnostics->cl_create_buffer_attempted =
        cl_error != MPVFLOW_OPENCL_CL_ERROR_NOT_RETURNED;
}

static VkResult begin_command(MPVFlowOpenCLVkBridge *b, VkCommandBuffer *out)
{
    VkCommandBufferAllocateInfo alloc = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = b->command_pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    VkResult vr = vkAllocateCommandBuffers(b->vk->device, &alloc, out);
    if (vr != VK_SUCCESS) return vr;
    VkCommandBufferBeginInfo begin = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    vr = vkBeginCommandBuffer(*out, &begin);
    if (vr != VK_SUCCESS) {
        vkFreeCommandBuffers(b->vk->device, b->command_pool, 1, out);
        *out = VK_NULL_HANDLE;
    }
    return vr;
}

/* Submit on libplacebo's compute queue while holding its external queue lock,
 * then synchronously wait the fence. No command/staging/readback path exists. */
static VkResult finish_command(MPVFlowOpenCLVkBridge *b, VkCommandBuffer cmd,
                               VkSemaphore wait_semaphore)
{
    VkResult vr;
    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fence_info = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) goto done;
    vr = vkCreateFence(b->vk->device, &fence_info, NULL, &fence);
    if (vr != VK_SUCCESS) goto done;

    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = wait_semaphore != VK_NULL_HANDLE ? 1u : 0u,
        .pWaitSemaphores = wait_semaphore != VK_NULL_HANDLE ? &wait_semaphore : NULL,
        .pWaitDstStageMask = wait_semaphore != VK_NULL_HANDLE ? &wait_stage : NULL,
        .commandBufferCount = 1,
        .pCommandBuffers = &cmd,
    };
    b->vk->lock_queue(b->vk, b->vk->queue_compute.index, 0);
    vr = vkQueueSubmit(b->queue, 1, &submit, fence);
    b->vk->unlock_queue(b->vk, b->vk->queue_compute.index, 0);
    if (vr != VK_SUCCESS) goto done;
    vr = vkWaitForFences(b->vk->device, 1, &fence, VK_TRUE, MPVFLOW_VK_FOREVER);

done:
    if (fence != VK_NULL_HANDLE) vkDestroyFence(b->vk->device, fence, NULL);
    if (cmd != VK_NULL_HANDLE)
        vkFreeCommandBuffers(b->vk->device, b->command_pool, 1, &cmd);
    return vr;
}

static void record_buffer_acquire(MPVFlowOpenCLVkBridge *b, VkCommandBuffer cmd,
                                  VkBuffer buffer)
{
    VkBufferMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        .srcAccessMask = 0,
        .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
        .dstQueueFamilyIndex = b->vk->queue_compute.index,
        .buffer = buffer,
        .offset = 0,
        .size = VK_WHOLE_SIZE,
    };
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                         0, NULL, 1, &barrier, 0, NULL);
}

static void record_buffer_release(MPVFlowOpenCLVkBridge *b, VkCommandBuffer cmd,
                                  VkBuffer buffer, VkAccessFlags src_access)
{
    VkBufferMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        .srcAccessMask = src_access,
        .dstAccessMask = 0,
        .srcQueueFamilyIndex = b->vk->queue_compute.index,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
        .buffer = buffer,
        .offset = 0,
        .size = VK_WHOLE_SIZE,
    };
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
                         0, NULL, 1, &barrier, 0, NULL);
}

static VkResult create_binary_semaphore(MPVFlowOpenCLVkBridge *b, VkSemaphore *out)
{
    VkSemaphoreCreateInfo info = { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
    return vkCreateSemaphore(b->vk->device, &info, NULL, out);
}

static void release_held_texture(MPVFlowOpenCLVkBridge *b, pl_tex tex,
                                 VkImageLayout layout)
{
    struct pl_vulkan_release_params release = {
        .tex = tex,
        .layout = layout,
        .qf = b->vk->queue_compute.index,
    };
    pl_vulkan_release_ex(b->gpu, &release);
}

MPVFlowOpenCLVkBridge *mpvflow_opencl_vk_bridge_create(
    pl_gpu gpu, MPVFlowOpenCLContext *opencl, char *error_out, size_t error_capacity)
{
    MPVFlowOpenCLVkBridge *b = NULL;
    VkPhysicalDeviceExternalBufferInfo ext_info = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_BUFFER_INFO,
        .flags = 0,
        .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
    };
    VkExternalBufferProperties ext_props = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_BUFFER_PROPERTIES,
    };
    PFN_vkGetPhysicalDeviceExternalBufferProperties get_external_buffer_properties;
    char message[384] = "invalid bridge arguments";

    if (!gpu || !opencl) {
        copy_error(error_out, error_capacity, message);
        return NULL;
    }
    pl_vulkan vk = pl_vulkan_get(gpu);
    if (!vk || vk->gpu != gpu) {
        copy_error(error_out, error_capacity,
                   "pl_gpu is not backed by the expected libplacebo Vulkan device");
        return NULL;
    }
    if (!device_extension_enabled(vk, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) ||
        !device_extension_enabled(vk, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
        copy_error(error_out, error_capacity,
                   "Vulkan device must enable VK_ANDROID_external_memory_android_hardware_buffer and VK_EXT_queue_family_foreign");
        return NULL;
    }
    if (!vk->queue_compute.count || !vk->lock_queue || !vk->unlock_queue) {
        copy_error(error_out, error_capacity,
                   "libplacebo Vulkan compute queue or queue-lock callbacks are unavailable");
        return NULL;
    }
    get_external_buffer_properties = load_external_buffer_properties(vk);
    if (!get_external_buffer_properties) {
        copy_error(error_out, error_capacity,
                   "Vulkan external-buffer-properties entry point is unavailable");
        return NULL;
    }
    get_external_buffer_properties(vk->phys_device, &ext_info, &ext_props);
    if (!(ext_props.externalMemoryProperties.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) ||
        !(ext_props.externalMemoryProperties.compatibleHandleTypes & ext_info.handleType)) {
        copy_error(error_out, error_capacity,
                   "VkBuffer transfer-src/dst usage is not importable from Android HardwareBuffer on this device");
        return NULL;
    }

    b = calloc(1, sizeof(*b));
    if (!b) {
        copy_error(error_out, error_capacity, "out of host memory creating bridge context");
        return NULL;
    }
    b->gpu = gpu;
    b->vk = vk;
    b->opencl = opencl;
    b->external_memory_features =
        ext_props.externalMemoryProperties.externalMemoryFeatures;
    b->external_memory_compatible_handle_types =
        ext_props.externalMemoryProperties.compatibleHandleTypes;
    b->external_memory_export_from_imported_handle_types =
        ext_props.externalMemoryProperties.exportFromImportedHandleTypes;
    b->external_buffer_handle_type = ext_info.handleType;
    if (pthread_mutex_init(&b->mutex, NULL) != 0) {
        copy_error(error_out, error_capacity, "failed to initialize bridge mutex");
        free(b);
        return NULL;
    }
    b->get_ahb_properties = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)
        vkGetDeviceProcAddr(vk->device, "vkGetAndroidHardwareBufferPropertiesANDROID");
    if (!b->get_ahb_properties) {
        (void)snprintf(message, sizeof(message),
                       "vkGetAndroidHardwareBufferPropertiesANDROID was not loaded");
        goto fail;
    }
    vkGetDeviceQueue(vk->device, vk->queue_compute.index, 0, &b->queue);
    if (b->queue == VK_NULL_HANDLE) {
        (void)snprintf(message, sizeof(message), "libplacebo compute queue handle is null");
        goto fail;
    }
    VkCommandPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = vk->queue_compute.index,
    };
    VkResult vr = vkCreateCommandPool(vk->device, &pool_info, NULL, &b->command_pool);
    if (vr != VK_SUCCESS) {
        (void)snprintf(message, sizeof(message), "vkCreateCommandPool failed: %s (%d)",
                       vk_result_name(vr), (int)vr);
        goto fail;
    }
    b->error[0] = '\0';
    copy_error(error_out, error_capacity, "");
    return b;

fail:
    if (b->command_pool != VK_NULL_HANDLE)
        vkDestroyCommandPool(vk->device, b->command_pool, NULL);
    pthread_mutex_destroy(&b->mutex);
    free(b);
    copy_error(error_out, error_capacity, message);
    return NULL;
}

enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_bridge_destroy(
    MPVFlowOpenCLVkBridge *b)
{
    if (!b) return MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
    pthread_mutex_lock(&b->mutex);
    if (b->buffers) {
        set_error(b, "cannot destroy bridge while shared buffers remain; destroy every buffer first");
        pthread_mutex_unlock(&b->mutex);
        return MPVFLOW_OPENCL_VK_BRIDGE_BUSY;
    }
    pthread_mutex_unlock(&b->mutex);
    vkDestroyCommandPool(b->vk->device, b->command_pool, NULL);
    pthread_mutex_destroy(&b->mutex);
    free(b);
    return MPVFLOW_OPENCL_VK_BRIDGE_OK;
}

const char *mpvflow_opencl_vk_bridge_last_error(const MPVFlowOpenCLVkBridge *b)
{
    return b ? b->error : "null bridge context";
}

static void probe_import_before_vk_bind(
    MPVFlowOpenCLVkBridge *b,
    struct MPVFlowOpenCLVkImportStageDiagnostics *diagnostics)
{
    const uint32_t frame_width = MPVFLOW_IMPORT_DIAGNOSTIC_DIMENSION;
    const uint32_t frame_height = MPVFLOW_IMPORT_DIAGNOSTIC_DIMENSION;
    const uint32_t row_stride = frame_width * 4u;
    const size_t logical_bytes = (size_t)row_stride * frame_height;
    const size_t qcom_padding = mpvflow_opencl_get_qcom_ext_mem_padding_bytes(b->opencl);
    AHardwareBuffer_Desc requested = {
        .width = 0,
        .height = 1,
        .layers = 1,
        .format = AHARDWAREBUFFER_FORMAT_BLOB,
        .usage = AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER,
    };
    AHardwareBuffer_Desc reported = {0};
    VkAndroidHardwareBufferPropertiesANDROID ahb_props = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID,
    };
    AHardwareBuffer *ahb = NULL;
    MPVFlowOpenCLBuffer *opencl_buffer = NULL;
    struct MPVFlowOpenCLAHBDesc cl_desc = {0};
    int32_t cl_error = MPVFLOW_OPENCL_CL_ERROR_NOT_RETURNED;
    enum MPVFlowOpenCLVkBridgeStatus bridge_status =
        MPVFLOW_OPENCL_VK_BRIDGE_VULKAN_ERROR;

    init_import_stage_diagnostics(diagnostics, qcom_padding,
                                  MPVFLOW_OPENCL_READ_WRITE);
    diagnostics->requested_width_bytes =
        logical_bytes <= UINT32_MAX &&
        qcom_padding <= UINT32_MAX - logical_bytes
            ? (uint32_t)(logical_bytes + qcom_padding) : 0;
    if (!diagnostics->requested_width_bytes) {
        diagnostics->bridge_status = MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
        return;
    }
    requested.width = diagnostics->requested_width_bytes;

    pthread_mutex_lock(&b->mutex);
    diagnostics->ahb_allocate_result =
        AHardwareBuffer_allocate(&requested, &ahb);
    if (diagnostics->ahb_allocate_result != 0 || !ahb) {
        pthread_mutex_unlock(&b->mutex);
        diagnostics->bridge_status = MPVFLOW_OPENCL_VK_BRIDGE_VULKAN_ERROR;
        return;
    }

    AHardwareBuffer_describe(ahb, &reported);
    record_ahb_descriptor(diagnostics, &reported);
    diagnostics->vk_ahb_properties_attempted = 1;
    diagnostics->vk_ahb_properties_result = (int32_t)b->get_ahb_properties(
        b->vk->device, ahb, &ahb_props);
    if (diagnostics->vk_ahb_properties_result == VK_SUCCESS) {
        diagnostics->vk_ahb_allocation_size = ahb_props.allocationSize;
        diagnostics->vk_ahb_memory_type_bits = ahb_props.memoryTypeBits;
        diagnostics->vk_external_memory_features = b->external_memory_features;
        diagnostics->vk_external_memory_compatible_handle_types =
            b->external_memory_compatible_handle_types;
        diagnostics->vk_external_memory_export_from_imported_handle_types =
            b->external_memory_export_from_imported_handle_types;
    }

    if (reported.format == AHARDWAREBUFFER_FORMAT_BLOB &&
        reported.height == 1 && reported.layers == 1 &&
        reported.stride == reported.width &&
        reported.width >= logical_bytes &&
        (reported.usage & AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER) != 0 &&
        (reported.usage & AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT) == 0) {
        cl_desc = (struct MPVFlowOpenCLAHBDesc) {
            .ahb_width = reported.width,
            .ahb_height = reported.height,
            .ahb_stride = reported.stride,
            .layers = reported.layers,
            .format = MPVFLOW_AHB_FORMAT_BLOB,
            .usage = reported.usage,
            .allocation_bytes = reported.width,
            .frame_width = frame_width,
            .frame_height = frame_height,
            .row_stride_bytes = row_stride,
            .layout = MPVFLOW_OPENCL_LAYOUT_BLOB_RGBA8_ROWS,
        };
        enum MPVFlowOpenCLStatus cl_status =
            mpvflow_opencl_import_ahardwarebuffer(
                b->opencl, ahb, &cl_desc, MPVFLOW_OPENCL_READ_WRITE,
                &opencl_buffer, &cl_error);
        record_import_call(diagnostics, cl_status, cl_error);
        bridge_status = cl_status == MPVFLOW_OPENCL_OK
            ? MPVFLOW_OPENCL_VK_BRIDGE_OK
            : MPVFLOW_OPENCL_VK_BRIDGE_OPENCL_ERROR;
        if (opencl_buffer) {
            diagnostics->cl_release_mem_object_error =
                mpvflow_opencl_buffer_release(b->opencl, opencl_buffer);
            diagnostics->cl_object_released =
                diagnostics->cl_release_mem_object_error == 0;
            if (!diagnostics->cl_object_released)
                bridge_status = MPVFLOW_OPENCL_VK_BRIDGE_OPENCL_ERROR;
        }
    } else {
        diagnostics->opencl_wrapper_status =
            MPVFLOW_OPENCL_UNSUPPORTED_FORMAT_OR_USAGE;
        bridge_status = MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
    }

    AHardwareBuffer_release(ahb);
    diagnostics->ahb_released = 1;
    pthread_mutex_unlock(&b->mutex);
    diagnostics->bridge_status = bridge_status;
}

static enum MPVFlowOpenCLVkBridgeStatus buffer_create_internal(
    MPVFlowOpenCLVkBridge *b, uint32_t width, uint32_t height,
    enum MPVFlowOpenCLAccess access, MPVFlowOpenCLVkBuffer **out,
    struct MPVFlowOpenCLVkImportStageDiagnostics *diagnostics)
{
    MPVFlowOpenCLVkBuffer *buf = NULL;
    AHardwareBuffer_Desc ahb_desc;
    VkAndroidHardwareBufferPropertiesANDROID ahb_props = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID,
    };
    VkBufferCreateInfo buffer_info;
    VkMemoryRequirements requirements;
    VkMemoryAllocateInfo alloc_info;
    VkMemoryDedicatedAllocateInfo dedicated_info;
    VkImportAndroidHardwareBufferInfoANDROID import_info;
    VkPhysicalDeviceMemoryProperties memory_props;
    VkDeviceSize row, row_stride, logical_bytes, ahb_blob_bytes;
    size_t qcom_padding_bytes;
    uint32_t memory_type = UINT32_MAX;
    VkResult vr;
    enum MPVFlowOpenCLStatus cl_status;
    int32_t cl_create_buffer_error = MPVFLOW_OPENCL_CL_ERROR_NOT_RETURNED;
    struct MPVFlowOpenCLAHBDesc cl_desc;
    enum MPVFlowOpenCLVkBridgeStatus status = MPVFLOW_OPENCL_VK_BRIDGE_VULKAN_ERROR;

    if (out) *out = NULL;
    if (!b || !out || !width || !height || width > MPVFLOW_VK_MAX_DIMENSION ||
        height > MPVFLOW_VK_MAX_DIMENSION ||
        (access != MPVFLOW_OPENCL_READ_ONLY && access != MPVFLOW_OPENCL_WRITE_ONLY &&
         access != MPVFLOW_OPENCL_READ_WRITE))
        return MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
    row = (VkDeviceSize)width * 4u;
    row_stride = (row + MPVFLOW_VK_ROW_ALIGNMENT - 1u) &
                 ~((VkDeviceSize)MPVFLOW_VK_ROW_ALIGNMENT - 1u);
    if (row_stride > UINT32_MAX || height > UINT64_MAX / row_stride) {
        set_error(b, "RGBA8 row-stride or frame-size arithmetic overflow");
        return MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
    }
    logical_bytes = row_stride * height;
    qcom_padding_bytes = mpvflow_opencl_get_qcom_ext_mem_padding_bytes(b->opencl);
    init_import_stage_diagnostics(diagnostics, qcom_padding_bytes, access);
    if ((uint64_t)qcom_padding_bytes > UINT64_MAX - logical_bytes) {
        set_error(b, "BLOB size plus QCOM external-memory padding overflows");
        return MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
    }
    ahb_blob_bytes = logical_bytes + (VkDeviceSize)qcom_padding_bytes;
    if (!logical_bytes || ahb_blob_bytes > MPVFLOW_VK_MAX_BYTES || ahb_blob_bytes > UINT32_MAX) {
        set_error(b, "padded BLOB allocation size %llu (frame bytes=%llu, QCOM padding=%zu) exceeds bridge limit (%u bytes)",
                  (unsigned long long)ahb_blob_bytes,
                  (unsigned long long)logical_bytes, qcom_padding_bytes,
                  MPVFLOW_VK_MAX_BYTES);
        return MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
    }

    /* Command pools require external synchronization; serialize allocation and
     * the initial foreign-family release with all other bridge operations. */
    pthread_mutex_lock(&b->mutex);

    buf = calloc(1, sizeof(*buf));
    if (!buf) {
        set_error(b, "out of host memory creating shared-buffer wrapper");
        pthread_mutex_unlock(&b->mutex);
        return MPVFLOW_OPENCL_VK_BRIDGE_VULKAN_ERROR;
    }
    buf->bridge = b;
    buf->width = width;
    buf->height = height;
    buf->row_stride = (uint32_t)row_stride;
    buf->blob_capacity = (size_t)logical_bytes;
    buf->access = access;

    memset(&ahb_desc, 0, sizeof(ahb_desc));
    ahb_desc.width = (uint32_t)ahb_blob_bytes;
    ahb_desc.height = 1;
    ahb_desc.layers = 1;
    ahb_desc.format = AHARDWAREBUFFER_FORMAT_BLOB;
    ahb_desc.usage = AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER;
    if (diagnostics)
        diagnostics->requested_width_bytes = ahb_desc.width;
    int32_t ahb_result = AHardwareBuffer_allocate(&ahb_desc, &buf->ahb);
    if (diagnostics)
        diagnostics->ahb_allocate_result = ahb_result;
    if (ahb_result != 0 || !buf->ahb) {
        set_error(b, "AHardwareBuffer_allocate(BLOB, GPU_DATA_BUFFER) failed (%d)", (int)ahb_result);
        status = MPVFLOW_OPENCL_VK_BRIDGE_VULKAN_ERROR;
        goto fail;
    }
    AHardwareBuffer_Desc reported;
    memset(&reported, 0, sizeof(reported));
    AHardwareBuffer_describe(buf->ahb, &reported);
    record_ahb_descriptor(diagnostics, &reported);
    if (reported.format != AHARDWAREBUFFER_FORMAT_BLOB || reported.layers != 1 ||
        reported.height != 1 || reported.width != ahb_desc.width ||
        reported.stride != reported.width ||
        (reported.usage & AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER) == 0 ||
        (reported.usage & AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT) != 0) {
        set_error(b, "allocated AHardwareBuffer descriptor is unsupported (format=%u, %ux%u, stride=%u, layers=%u, usage=0x%llx)",
                  reported.format, reported.width, reported.height, reported.stride,
                  reported.layers, (unsigned long long)reported.usage);
        status = MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
        goto fail;
    }

    vr = b->get_ahb_properties(b->vk->device, buf->ahb, &ahb_props);
    if (diagnostics) {
        diagnostics->vk_ahb_properties_attempted = 1;
        diagnostics->vk_ahb_properties_result = (int32_t)vr;
        if (vr == VK_SUCCESS) {
            diagnostics->vk_ahb_allocation_size = ahb_props.allocationSize;
            diagnostics->vk_ahb_memory_type_bits = ahb_props.memoryTypeBits;
            diagnostics->vk_external_memory_features = b->external_memory_features;
            diagnostics->vk_external_memory_compatible_handle_types =
                b->external_memory_compatible_handle_types;
            diagnostics->vk_external_memory_export_from_imported_handle_types =
                b->external_memory_export_from_imported_handle_types;
        }
    }
    if (vr != VK_SUCCESS) {
        status = fail_vk(b, "vkGetAndroidHardwareBufferPropertiesANDROID", vr);
        goto fail;
    }
    if (!ahb_props.allocationSize || !ahb_props.memoryTypeBits) {
        set_error(b, "Android HardwareBuffer Vulkan properties reported zero allocation size or memory type bits");
        status = MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
        goto fail;
    }
    if (ahb_props.allocationSize < ahb_blob_bytes ||
        ahb_props.allocationSize > MPVFLOW_VK_MAX_BYTES) {
        set_error(b, "actual Vulkan AHardwareBuffer allocationSize (%llu) does not cover padded BLOB size (%llu) or exceeds the %u-byte limit",
                  (unsigned long long)ahb_props.allocationSize,
                  (unsigned long long)ahb_blob_bytes,
                  MPVFLOW_VK_MAX_BYTES);
        status = MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
        goto fail;
    }
    buf->vk_allocation_size = ahb_props.allocationSize;

    memset(&buffer_info, 0, sizeof(buffer_info));
    buffer_info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    buffer_info.pNext = &(VkExternalMemoryBufferCreateInfo) {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO,
        .handleTypes = b->external_buffer_handle_type,
    };
    buffer_info.size = buf->vk_allocation_size;
    buffer_info.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    buffer_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (diagnostics) {
        diagnostics->vk_create_buffer_size = buffer_info.size;
        diagnostics->vk_create_buffer_usage = buffer_info.usage;
        diagnostics->vk_external_handle_type = b->external_buffer_handle_type;
        diagnostics->vk_create_buffer_attempted = 1;
    }
    vr = vkCreateBuffer(b->vk->device, &buffer_info, NULL, &buf->vk_buffer);
    if (diagnostics)
        diagnostics->vk_create_buffer_result = (int32_t)vr;
    if (vr != VK_SUCCESS) {
        status = fail_vk(b, "vkCreateBuffer for imported AHardwareBuffer", vr);
        goto fail;
    }
    vkGetBufferMemoryRequirements(b->vk->device, buf->vk_buffer, &requirements);
    if (diagnostics) {
        diagnostics->vk_buffer_requirement_size = requirements.size;
        diagnostics->vk_buffer_requirement_alignment = requirements.alignment;
        diagnostics->vk_buffer_requirement_memory_type_bits = requirements.memoryTypeBits;
    }
    vkGetPhysicalDeviceMemoryProperties(b->vk->phys_device, &memory_props);
    uint32_t compatible = requirements.memoryTypeBits & ahb_props.memoryTypeBits;
    for (uint32_t i = 0; i < memory_props.memoryTypeCount; i++) {
        if (compatible & (1u << i)) {
            memory_type = i;
            break;
        }
    }
    if (diagnostics)
        diagnostics->vk_selected_memory_type = memory_type;
    if (memory_type == UINT32_MAX) {
        set_error(b, "no Vulkan memory type is compatible with both VkBuffer requirements and AHardwareBuffer memoryTypeBits (0x%x & 0x%x)",
                  requirements.memoryTypeBits, ahb_props.memoryTypeBits);
        status = MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
        goto fail;
    }
    if (requirements.size > ahb_props.allocationSize) {
        set_error(b, "VkBuffer memory requirement (%llu) exceeds actual AHardwareBuffer allocationSize (%llu)",
                  (unsigned long long)requirements.size,
                  (unsigned long long)ahb_props.allocationSize);
        status = MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
        goto fail;
    }

    dedicated_info = (VkMemoryDedicatedAllocateInfo) {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .buffer = buf->vk_buffer,
    };
    import_info = (VkImportAndroidHardwareBufferInfoANDROID) {
        .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
        .pNext = &dedicated_info,
        .buffer = buf->ahb,
    };
    alloc_info = (VkMemoryAllocateInfo) {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = &import_info,
        /* Must be actual Vulkan allocationSize, not BLOB descriptor width. */
        .allocationSize = ahb_props.allocationSize,
        .memoryTypeIndex = memory_type,
    };
    if (diagnostics) {
        diagnostics->vk_allocate_memory_size = alloc_info.allocationSize;
        diagnostics->vk_allocate_memory_attempted = 1;
    }
    vr = vkAllocateMemory(b->vk->device, &alloc_info, NULL, &buf->memory);
    if (diagnostics)
        diagnostics->vk_allocate_memory_result = (int32_t)vr;
    if (vr != VK_SUCCESS) {
        status = fail_vk(b, "vkAllocateMemory importing Android HardwareBuffer", vr);
        goto fail;
    }
    vr = vkBindBufferMemory(b->vk->device, buf->vk_buffer, buf->memory, 0);
    if (diagnostics) {
        diagnostics->vk_bind_offset = 0;
        diagnostics->vk_bind_buffer_memory_attempted = 1;
        diagnostics->vk_bind_buffer_memory_result = (int32_t)vr;
    }
    if (vr != VK_SUCCESS) {
        status = fail_vk(b, "vkBindBufferMemory for imported Android HardwareBuffer", vr);
        goto fail;
    }
    /* OpenCL's descriptor reflects the AHB BLOB descriptor capacity. Vulkan's
     * potentially larger allocationSize is intentionally kept separate above. */
    memset(&cl_desc, 0, sizeof(cl_desc));
    cl_desc.ahb_width = reported.width;
    cl_desc.ahb_height = reported.height;
    cl_desc.ahb_stride = reported.stride;
    cl_desc.layers = reported.layers;
    cl_desc.format = MPVFLOW_AHB_FORMAT_BLOB;
    cl_desc.usage = reported.usage;
    cl_desc.allocation_bytes = (size_t)reported.width;
    cl_desc.frame_width = width;
    cl_desc.frame_height = height;
    cl_desc.row_stride_bytes = buf->row_stride;
    cl_desc.layout = MPVFLOW_OPENCL_LAYOUT_BLOB_RGBA8_ROWS;
    /* The private QCOM AHardwareBuffer ABI uses size=0 for its primary
     * clCreateBuffer call; do not report the descriptor allocation as the
     * ordinary size argument. */
    if (diagnostics)
        diagnostics->qcom_size_arg = MPVFLOW_OPENCL_QCOM_IMPORT_SIZE_ARG;
    cl_status = mpvflow_opencl_import_ahardwarebuffer(
        b->opencl, buf->ahb, &cl_desc, access, &buf->opencl_buffer,
        &cl_create_buffer_error);
    record_import_call(diagnostics, cl_status, cl_create_buffer_error);
    if (cl_status != MPVFLOW_OPENCL_OK) {
        set_error(b,
                  "OpenCL AHardwareBuffer import failed (wrapper_status=%d cl_error_available=%d "
                  "cl_error=%d qcom_primary_size_arg=%zu qcom_access_flags=default "
                  "qcom_required_padding_bytes=%zu "
                  "qcom_flags=USE_HOST_PTR|EXT_HOST_PTR_QCOM wrapper_access=%d "
                  "ahb_allocation_bytes=%zu desc={format=%u width=%u height=%u stride=%u layers=%u "
                  "usage=0x%llx frame=%ux%u row_stride=%u})",
                  (int)cl_status,
                  cl_create_buffer_error != MPVFLOW_OPENCL_CL_ERROR_NOT_RETURNED,
                  (int)cl_create_buffer_error, MPVFLOW_OPENCL_QCOM_IMPORT_SIZE_ARG,
                  qcom_padding_bytes, (int)access, cl_desc.allocation_bytes,
                  cl_desc.format, cl_desc.ahb_width, cl_desc.ahb_height,
                  cl_desc.ahb_stride, cl_desc.layers,
                  (unsigned long long)cl_desc.usage, cl_desc.frame_width,
                  cl_desc.frame_height, cl_desc.row_stride_bytes);
        status = MPVFLOW_OPENCL_VK_BRIDGE_OPENCL_ERROR;
        goto fail;
    }
    buf->state = BUFFER_FOREIGN_INITIAL;
    buf->next = b->buffers;
    b->buffers = buf;
    b->error[0] = '\0';
    pthread_mutex_unlock(&b->mutex);
    *out = buf;
    if (diagnostics)
        diagnostics->bridge_status = MPVFLOW_OPENCL_VK_BRIDGE_OK;
    return MPVFLOW_OPENCL_VK_BRIDGE_OK;

fail:
    if (buf) {
        if (buf->opencl_buffer) {
            if (diagnostics) {
                diagnostics->cl_release_mem_object_error =
                    mpvflow_opencl_buffer_release(b->opencl, buf->opencl_buffer);
                diagnostics->cl_object_released =
                    diagnostics->cl_release_mem_object_error == 0;
            } else {
                (void)mpvflow_opencl_buffer_release(b->opencl, buf->opencl_buffer);
            }
        }
        if (buf->vk_buffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(b->vk->device, buf->vk_buffer, NULL);
            if (diagnostics)
                diagnostics->vk_buffer_destroyed = 1;
        }
        if (buf->memory != VK_NULL_HANDLE) {
            vkFreeMemory(b->vk->device, buf->memory, NULL);
            if (diagnostics)
                diagnostics->vk_memory_freed = 1;
        }
        if (buf->ahb) {
            AHardwareBuffer_release(buf->ahb);
            if (diagnostics)
                diagnostics->ahb_released = 1;
        }
        free(buf);
    }
    pthread_mutex_unlock(&b->mutex);
    if (diagnostics)
        diagnostics->bridge_status = status;
    return status;
}

enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_buffer_create(
    MPVFlowOpenCLVkBridge *b, uint32_t width, uint32_t height,
    enum MPVFlowOpenCLAccess access, MPVFlowOpenCLVkBuffer **out)
{
    return buffer_create_internal(b, width, height, access, out, NULL);
}

void mpvflow_opencl_vk_bridge_probe_import_stages(
    MPVFlowOpenCLVkBridge *b,
    struct MPVFlowOpenCLVkImportDiagnostics *diagnostics)
{
    if (!diagnostics)
        return;
    memset(diagnostics, 0, sizeof(*diagnostics));
    size_t padding = b
        ? mpvflow_opencl_get_qcom_ext_mem_padding_bytes(b->opencl) : 0;
    init_import_stage_diagnostics(&diagnostics->before_vk_bind, padding,
                                  MPVFLOW_OPENCL_READ_WRITE);
    init_import_stage_diagnostics(&diagnostics->after_vk_bind, padding,
                                  MPVFLOW_OPENCL_READ_WRITE);
    if (!b) {
        diagnostics->before_vk_bind.bridge_status =
            MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
        diagnostics->after_vk_bind.bridge_status =
            MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
        return;
    }

    probe_import_before_vk_bind(b, &diagnostics->before_vk_bind);

    MPVFlowOpenCLVkBuffer *buffer = NULL;
    enum MPVFlowOpenCLVkBridgeStatus create_status = buffer_create_internal(
        b, MPVFLOW_IMPORT_DIAGNOSTIC_DIMENSION,
        MPVFLOW_IMPORT_DIAGNOSTIC_DIMENSION, MPVFLOW_OPENCL_READ_WRITE,
        &buffer, &diagnostics->after_vk_bind);
    if (create_status != MPVFLOW_OPENCL_VK_BRIDGE_OK || !buffer)
        return;

    enum MPVFlowOpenCLVkBridgeStatus state_status =
        mpvflow_opencl_vk_buffer_opencl_finished(buffer);
    if (state_status != MPVFLOW_OPENCL_VK_BRIDGE_OK) {
        diagnostics->after_vk_bind.bridge_status = state_status;
        return;
    }
    if (buffer->opencl_buffer) {
        diagnostics->after_vk_bind.cl_release_mem_object_error =
            mpvflow_opencl_buffer_release(b->opencl, buffer->opencl_buffer);
        diagnostics->after_vk_bind.cl_object_released =
            diagnostics->after_vk_bind.cl_release_mem_object_error == 0;
        if (!diagnostics->after_vk_bind.cl_object_released)
            diagnostics->after_vk_bind.bridge_status =
                MPVFLOW_OPENCL_VK_BRIDGE_OPENCL_ERROR;
        buffer->opencl_buffer = NULL;
    }
    enum MPVFlowOpenCLVkBridgeStatus destroy_status =
        mpvflow_opencl_vk_buffer_destroy(buffer);
    if (destroy_status == MPVFLOW_OPENCL_VK_BRIDGE_OK) {
        diagnostics->after_vk_bind.vk_buffer_destroyed = 1;
        diagnostics->after_vk_bind.vk_memory_freed = 1;
        diagnostics->after_vk_bind.ahb_released = 1;
    } else {
        diagnostics->after_vk_bind.bridge_status = destroy_status;
    }
}

enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_buffer_destroy(
    MPVFlowOpenCLVkBuffer *buf)
{
    MPVFlowOpenCLVkBridge *b;
    MPVFlowOpenCLVkBuffer **cursor;
    if (!buf || !buf->bridge) return MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
    b = buf->bridge;
    pthread_mutex_lock(&b->mutex);
    if (buf->state != BUFFER_FOREIGN_CL_DONE) {
        set_error(b, "shared buffer cannot be destroyed until OpenCL completion has been explicitly confirmed");
        pthread_mutex_unlock(&b->mutex);
        return MPVFLOW_OPENCL_VK_BRIDGE_BUSY;
    }
    cursor = &b->buffers;
    while (*cursor && *cursor != buf) cursor = &(*cursor)->next;
    if (!*cursor) {
        set_error(b, "shared buffer is not registered with its bridge context");
        pthread_mutex_unlock(&b->mutex);
        return MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
    }
    *cursor = buf->next;
    pthread_mutex_unlock(&b->mutex);

    /* The OpenCL wrapper and its AHB reference are released before the bridge's
     * AHB reference; the imported VkBuffer is destroyed before VkDeviceMemory. */
    if (buf->opencl_buffer)
        mpvflow_opencl_buffer_release(b->opencl, buf->opencl_buffer);
    if (buf->vk_buffer != VK_NULL_HANDLE)
        vkDestroyBuffer(b->vk->device, buf->vk_buffer, NULL);
    if (buf->memory != VK_NULL_HANDLE)
        vkFreeMemory(b->vk->device, buf->memory, NULL);
    if (buf->ahb) AHardwareBuffer_release(buf->ahb);
    free(buf);
    return MPVFLOW_OPENCL_VK_BRIDGE_OK;
}

MPVFlowOpenCLBuffer *mpvflow_opencl_vk_buffer_get_opencl(MPVFlowOpenCLVkBuffer *buf)
{
    return buf ? buf->opencl_buffer : NULL;
}
uint32_t mpvflow_opencl_vk_buffer_width(const MPVFlowOpenCLVkBuffer *buf)
{
    return buf ? buf->width : 0;
}
uint32_t mpvflow_opencl_vk_buffer_height(const MPVFlowOpenCLVkBuffer *buf)
{
    return buf ? buf->height : 0;
}
uint32_t mpvflow_opencl_vk_buffer_row_stride(const MPVFlowOpenCLVkBuffer *buf)
{
    return buf ? buf->row_stride : 0;
}

enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_buffer_opencl_started(
    MPVFlowOpenCLVkBuffer *buf)
{
    MPVFlowOpenCLVkBridge *b;
    if (!buf || !buf->bridge) return MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
    b = buf->bridge;
    pthread_mutex_lock(&b->mutex);
    if (buf->state != BUFFER_FOREIGN_CL_DONE) {
        set_error(b, "OpenCL cannot begin until the prior API access is complete and the shared buffer is foreign-owned");
        pthread_mutex_unlock(&b->mutex);
        return MPVFLOW_OPENCL_VK_BRIDGE_BUSY;
    }
    buf->state = BUFFER_FOREIGN_CL_PENDING;
    b->error[0] = '\0';
    pthread_mutex_unlock(&b->mutex);
    return MPVFLOW_OPENCL_VK_BRIDGE_OK;
}

enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_buffer_opencl_finished(
    MPVFlowOpenCLVkBuffer *buf)
{
    MPVFlowOpenCLVkBridge *b;
    if (!buf || !buf->bridge) return MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
    b = buf->bridge;
    pthread_mutex_lock(&b->mutex);
    if (buf->state == BUFFER_FOREIGN_CL_DONE) {
        pthread_mutex_unlock(&b->mutex);
        return MPVFLOW_OPENCL_VK_BRIDGE_OK;
    }
    if (buf->state != BUFFER_FOREIGN_INITIAL && buf->state != BUFFER_FOREIGN_CL_PENDING) {
        set_error(b, "OpenCL completion notification is invalid in the buffer's current ownership state");
        pthread_mutex_unlock(&b->mutex);
        return MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
    }
    /* This is a caller assertion that clFinish (or an equivalent completion
     * wait) has already returned. It does not invoke or fake OpenCL sync. */
    buf->state = BUFFER_FOREIGN_CL_DONE;
    b->error[0] = '\0';
    pthread_mutex_unlock(&b->mutex);
    return MPVFLOW_OPENCL_VK_BRIDGE_OK;
}

enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_fill_buffer(
    MPVFlowOpenCLVkBuffer *buf, uint32_t value)
{
    if (!buf || !buf->bridge)
        return MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
    MPVFlowOpenCLVkBridge *b = buf->bridge;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkResult vr;
    enum MPVFlowOpenCLVkBridgeStatus status;
    pthread_mutex_lock(&b->mutex);
    if (buf->state != BUFFER_FOREIGN_CL_DONE) {
        set_error(b, "Vulkan fill requires an idle, foreign-owned buffer");
        status = MPVFLOW_OPENCL_VK_BRIDGE_BUSY;
        goto done;
    }
    if (!buf->blob_capacity || (buf->blob_capacity & 3u)) {
        set_error(b, "Vulkan fill requires a non-empty 4-byte-aligned BLOB capacity");
        status = MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
        goto done;
    }
    vr = begin_command(b, &cmd);
    if (vr != VK_SUCCESS) {
        status = fail_vk(b, "allocate Vulkan buffer-fill command", vr);
        goto done;
    }
    record_buffer_acquire(b, cmd, buf->vk_buffer);
    vkCmdFillBuffer(cmd, buf->vk_buffer, 0,
                    (VkDeviceSize)buf->blob_capacity, value);
    record_buffer_release(b, cmd, buf->vk_buffer, VK_ACCESS_TRANSFER_WRITE_BIT);
    vr = finish_command(b, cmd, VK_NULL_HANDLE);
    cmd = VK_NULL_HANDLE;
    if (vr != VK_SUCCESS) {
        status = fail_vk(b, "submit/wait Vulkan shared-buffer fill", vr);
        goto done;
    }
    buf->state = BUFFER_FOREIGN_CL_DONE;
    b->error[0] = '\0';
    status = MPVFLOW_OPENCL_VK_BRIDGE_OK;

done:
    if (cmd != VK_NULL_HANDLE)
        vkFreeCommandBuffers(b->vk->device, b->command_pool, 1, &cmd);
    pthread_mutex_unlock(&b->mutex);
    return status;
}

static enum MPVFlowOpenCLVkBridgeStatus validate_texture(
    MPVFlowOpenCLVkBridge *b, pl_tex tex, VkImageUsageFlags wanted_usage,
    uint32_t width, uint32_t height, VkImage *image, VkImageUsageFlags *actual_usage)
{
    VkFormat format = VK_FORMAT_UNDEFINED;
    if (!tex) {
        set_error(b, "null pl_tex");
        return MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
    }
    if (tex->params.w != (int)width || tex->params.h != (int)height ||
        tex->params.d != 0) {
        set_error(b, "pl_tex dimensions/type mismatch: expected %ux%u 2D, got %dx%dx%d",
                  width, height, tex->params.w, tex->params.h, tex->params.d);
        return MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
    }
    *image = pl_vulkan_unwrap(b->gpu, tex, &format, actual_usage);
    if (*image == VK_NULL_HANDLE) {
        set_error(b, "pl_vulkan_unwrap failed for texture");
        return MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
    }
    if (format != VK_FORMAT_R8G8B8A8_UNORM) {
        set_error(b, "pl_tex VkFormat must be VK_FORMAT_R8G8B8A8_UNORM (got %d)", (int)format);
        return MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
    }
    if (!(*actual_usage & wanted_usage)) {
        set_error(b, "pl_tex lacks required Vulkan transfer usage flag 0x%x", (unsigned)wanted_usage);
        return MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
    }
    return MPVFLOW_OPENCL_VK_BRIDGE_OK;
}

static VkImageMemoryBarrier texture_barrier(VkImage image, VkImageLayout old_layout,
                                           VkImageLayout new_layout,
                                           VkAccessFlags src_access,
                                           VkAccessFlags dst_access)
{
    VkImageMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = src_access,
        .dstAccessMask = dst_access,
        .oldLayout = old_layout,
        .newLayout = new_layout,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    return barrier;
}

enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_copy_texture_to_buffer(
    MPVFlowOpenCLVkBridge *b, pl_tex source,
    MPVFlowOpenCLVkBuffer *dst)
{
    VkImage image = VK_NULL_HANDLE;
    VkImageUsageFlags image_usage = 0;
    VkImageLayout old_layout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkSemaphore semaphore = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkResult vr;
    enum MPVFlowOpenCLVkBridgeStatus status;
    if (!b || !dst || dst->bridge != b) return MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
    pthread_mutex_lock(&b->mutex);
    if (dst->state != BUFFER_FOREIGN_CL_DONE) {
        set_error(b, "texture-to-buffer requires mpvflow_opencl_vk_buffer_opencl_finished() after clFinish (or before first use)");
        status = MPVFLOW_OPENCL_VK_BRIDGE_BUSY;
        goto done;
    }
    status = validate_texture(b, source, VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                              dst->width, dst->height, &image, &image_usage);
    if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK) goto done;
    struct pl_vulkan_hold_params hold = {
        .tex = source,
        .out_layout = &old_layout,
        /* Place the held image on the queue family that records the copy. */
        .qf = b->vk->queue_compute.index,
    };
    vr = create_binary_semaphore(b, &semaphore);
    if (vr != VK_SUCCESS) {
        status = fail_vk(b, "vkCreateSemaphore for texture hold", vr);
        goto done;
    }
    hold.semaphore.sem = semaphore;
    hold.semaphore.value = 0;
    if (!pl_vulkan_hold_ex(b->gpu, &hold)) {
        set_error(b, "pl_vulkan_hold_ex failed for source texture");
        status = MPVFLOW_OPENCL_VK_BRIDGE_VULKAN_ERROR;
        goto done;
    }
    if (old_layout == VK_IMAGE_LAYOUT_UNDEFINED) {
        set_error(b, "source texture has UNDEFINED layout and therefore no valid frame contents");
        status = MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED;
        goto release_texture;
    }
    vr = begin_command(b, &cmd);
    if (vr != VK_SUCCESS) {
        status = fail_vk(b, "allocate texture-to-buffer command", vr);
        goto release_texture;
    }
    record_buffer_acquire(b, cmd, dst->vk_buffer);
    VkImageMemoryBarrier to_transfer = texture_barrier(
        image, old_layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
        VK_ACCESS_TRANSFER_READ_BIT);
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                         0, NULL, 0, NULL, 1, &to_transfer);
    VkBufferImageCopy region = {
        .bufferOffset = 0,
        .bufferRowLength = dst->row_stride / 4u,
        .bufferImageHeight = dst->height,
        .imageSubresource = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .mipLevel = 0,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
        .imageOffset = { 0, 0, 0 },
        .imageExtent = { dst->width, dst->height, 1 },
    };
    vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           dst->vk_buffer, 1, &region);
    VkImageMemoryBarrier restore = texture_barrier(
        image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, old_layout,
        VK_ACCESS_TRANSFER_READ_BIT,
        VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
                         0, NULL, 0, NULL, 1, &restore);
    record_buffer_release(b, cmd, dst->vk_buffer, VK_ACCESS_TRANSFER_WRITE_BIT);
    vr = finish_command(b, cmd, semaphore);
    cmd = VK_NULL_HANDLE;
    if (vr != VK_SUCCESS) {
        status = fail_vk(b, "submit/wait texture-to-buffer copy", vr);
        goto release_texture;
    }
    /* Fence completion plus the FOREIGN release makes the buffer available to
     * OpenCL immediately; no OpenCL work is pending yet. */
    dst->state = BUFFER_FOREIGN_CL_DONE;
    release_held_texture(b, source, old_layout);
    vkDestroySemaphore(b->vk->device, semaphore, NULL);
    semaphore = VK_NULL_HANDLE;
    b->error[0] = '\0';
    status = MPVFLOW_OPENCL_VK_BRIDGE_OK;
    goto done;

release_texture:
    /* Ensure the hold semaphore's signal cannot outlive this stack-local handle. */
    (void)vkDeviceWaitIdle(b->vk->device);
    release_held_texture(b, source, old_layout == VK_IMAGE_LAYOUT_UNDEFINED ?
                         VK_IMAGE_LAYOUT_GENERAL : old_layout);
done:
    if (cmd != VK_NULL_HANDLE)
        vkFreeCommandBuffers(b->vk->device, b->command_pool, 1, &cmd);
    if (semaphore != VK_NULL_HANDLE)
        vkDestroySemaphore(b->vk->device, semaphore, NULL);
    pthread_mutex_unlock(&b->mutex);
    return status;
}

enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_copy_buffer_to_texture(
    MPVFlowOpenCLVkBridge *b, MPVFlowOpenCLVkBuffer *src,
    pl_tex destination)
{
    VkImage image = VK_NULL_HANDLE;
    VkImageUsageFlags image_usage = 0;
    VkImageLayout old_layout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkSemaphore semaphore = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkResult vr;
    enum MPVFlowOpenCLVkBridgeStatus status;
    if (!b || !src || src->bridge != b) return MPVFLOW_OPENCL_VK_BRIDGE_INVALID;
    pthread_mutex_lock(&b->mutex);
    if (src->state != BUFFER_FOREIGN_CL_DONE) {
        set_error(b, "buffer-to-texture requires mpvflow_opencl_vk_buffer_opencl_finished() after clFinish");
        status = MPVFLOW_OPENCL_VK_BRIDGE_BUSY;
        goto done;
    }
    status = validate_texture(b, destination, VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                              src->width, src->height, &image, &image_usage);
    if (status != MPVFLOW_OPENCL_VK_BRIDGE_OK) goto done;
    struct pl_vulkan_hold_params hold = {
        .tex = destination,
        .out_layout = &old_layout,
        .qf = b->vk->queue_compute.index,
    };
    vr = create_binary_semaphore(b, &semaphore);
    if (vr != VK_SUCCESS) {
        status = fail_vk(b, "vkCreateSemaphore for destination texture hold", vr);
        goto done;
    }
    hold.semaphore.sem = semaphore;
    hold.semaphore.value = 0;
    if (!pl_vulkan_hold_ex(b->gpu, &hold)) {
        set_error(b, "pl_vulkan_hold_ex failed for destination texture");
        status = MPVFLOW_OPENCL_VK_BRIDGE_VULKAN_ERROR;
        goto done;
    }
    vr = begin_command(b, &cmd);
    if (vr != VK_SUCCESS) {
        status = fail_vk(b, "allocate buffer-to-texture command", vr);
        goto release_texture;
    }
    record_buffer_acquire(b, cmd, src->vk_buffer);
    VkImageMemoryBarrier to_transfer = texture_barrier(
        image, old_layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        old_layout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 :
            (VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT),
        VK_ACCESS_TRANSFER_WRITE_BIT);
    vkCmdPipelineBarrier(cmd,
                         old_layout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                         0, NULL, 0, NULL, 1, &to_transfer);
    VkBufferImageCopy region = {
        .bufferOffset = 0,
        .bufferRowLength = src->row_stride / 4u,
        .bufferImageHeight = src->height,
        .imageSubresource = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .mipLevel = 0,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
        .imageOffset = { 0, 0, 0 },
        .imageExtent = { src->width, src->height, 1 },
    };
    vkCmdCopyBufferToImage(cmd, src->vk_buffer, image,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    VkImageMemoryBarrier to_general = texture_barrier(
        image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
        VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0,
                         0, NULL, 0, NULL, 1, &to_general);
    record_buffer_release(b, cmd, src->vk_buffer, VK_ACCESS_TRANSFER_READ_BIT);
    vr = finish_command(b, cmd, semaphore);
    cmd = VK_NULL_HANDLE;
    if (vr != VK_SUCCESS) {
        status = fail_vk(b, "submit/wait buffer-to-texture copy", vr);
        goto release_texture;
    }
    /* Vulkan has synchronously consumed the output and returned ownership to
     * FOREIGN; the buffer is idle until the next explicit OpenCL start. */
    src->state = BUFFER_FOREIGN_CL_DONE;
    release_held_texture(b, destination, VK_IMAGE_LAYOUT_GENERAL);
    vkDestroySemaphore(b->vk->device, semaphore, NULL);
    semaphore = VK_NULL_HANDLE;
    b->error[0] = '\0';
    status = MPVFLOW_OPENCL_VK_BRIDGE_OK;
    goto done;

release_texture:
    (void)vkDeviceWaitIdle(b->vk->device);
    release_held_texture(b, destination,
                         old_layout == VK_IMAGE_LAYOUT_UNDEFINED ?
                         VK_IMAGE_LAYOUT_UNDEFINED : old_layout);
done:
    if (cmd != VK_NULL_HANDLE)
        vkFreeCommandBuffers(b->vk->device, b->command_pool, 1, &cmd);
    if (semaphore != VK_NULL_HANDLE)
        vkDestroySemaphore(b->vk->device, semaphore, NULL);
    pthread_mutex_unlock(&b->mutex);
    return status;
}
