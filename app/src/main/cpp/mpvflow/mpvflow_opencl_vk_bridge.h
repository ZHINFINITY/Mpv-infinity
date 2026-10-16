/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 *
 * Vulkan <-> OpenCL bridge for GPU_DATA_BUFFER AHardwareBuffers. These APIs
 * create one BLOB AHardwareBuffer allocation, import that allocation as a
 * VkBuffer and as an mpvflow OpenCL buffer, and copy to/from pl_tex using only
 * Vulkan transfer commands. No image/AHB is ever interpreted as linear bytes.
 */
#ifndef MPV_INFINITY_MPVFLOW_OPENCL_VK_BRIDGE_H
#define MPV_INFINITY_MPVFLOW_OPENCL_VK_BRIDGE_H
#include "mpvflow_visibility.h"

#include <stddef.h>
#include <stdint.h>
#include <libplacebo/gpu.h>
#include "mpvflow_opencl.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MPVFlowOpenCLVkBridge MPVFlowOpenCLVkBridge;
typedef struct MPVFlowOpenCLVkBuffer MPVFlowOpenCLVkBuffer;

enum MPVFlowOpenCLVkBridgeStatus {
    MPVFLOW_OPENCL_VK_BRIDGE_OK = 0,
    MPVFLOW_OPENCL_VK_BRIDGE_INVALID = -1,
    MPVFLOW_OPENCL_VK_BRIDGE_UNSUPPORTED = -2,
    MPVFLOW_OPENCL_VK_BRIDGE_VULKAN_ERROR = -3,
    MPVFLOW_OPENCL_VK_BRIDGE_OPENCL_ERROR = -4,
    MPVFLOW_OPENCL_VK_BRIDGE_BUSY = -5,
};

/* Results from diagnostic-only imports on disposable 32x32 BLOB buffers. */
struct MPVFlowOpenCLVkImportStageDiagnostics {
    int32_t bridge_status;
    int32_t ahb_allocate_result;
    uint32_t requested_width_bytes;
    uint32_t actual_format;
    uint32_t actual_width;
    uint32_t actual_height;
    uint32_t actual_stride;
    uint32_t actual_layers;
    uint64_t actual_usage;
    int vk_ahb_properties_attempted;
    int32_t vk_ahb_properties_result;
    uint64_t vk_ahb_allocation_size;
    uint32_t vk_ahb_memory_type_bits;
    uint32_t vk_external_memory_features;
    uint32_t vk_external_memory_compatible_handle_types;
    uint32_t vk_external_memory_export_from_imported_handle_types;
    int vk_create_buffer_attempted;
    int32_t vk_create_buffer_result;
    uint64_t vk_create_buffer_size;
    uint32_t vk_create_buffer_usage;
    uint32_t vk_external_handle_type;
    uint64_t vk_buffer_requirement_size;
    uint64_t vk_buffer_requirement_alignment;
    uint32_t vk_buffer_requirement_memory_type_bits;
    uint32_t vk_selected_memory_type;
    uint64_t vk_allocate_memory_size;
    int vk_allocate_memory_attempted;
    int32_t vk_allocate_memory_result;
    uint64_t vk_bind_offset;
    int vk_bind_buffer_memory_attempted;
    int32_t vk_bind_buffer_memory_result;
    int32_t opencl_wrapper_status;
    int32_t cl_create_buffer_error;
    int32_t cl_release_mem_object_error;
    size_t qcom_size_arg;
    uint64_t qcom_flags;
    uint32_t qcom_allocation_type;
    uint32_t qcom_cache_policy;
    int32_t wrapper_access;
    size_t qcom_padding_bytes;
    int cl_create_buffer_attempted;
    int cl_object_released;
    int ahb_released;
    int vk_buffer_destroyed;
    int vk_memory_freed;
};

struct MPVFlowOpenCLVkImportDiagnostics {
    struct MPVFlowOpenCLVkImportStageDiagnostics before_vk_bind;
    struct MPVFlowOpenCLVkImportStageDiagnostics after_vk_bind;
};

/*
 * Create a bridge over an existing Vulkan-backed pl_gpu and OpenCL context.
 * Both objects are borrowed and must outlive the bridge and all its buffers.
 * On failure, error_out (when supplied) receives an explicit diagnostic.
 */
MPVFLOW_INTERNAL MPVFlowOpenCLVkBridge *mpvflow_opencl_vk_bridge_create(
    pl_gpu gpu, MPVFlowOpenCLContext *opencl,
    char *error_out, size_t error_capacity);

/* Fails with BUSY unless every child buffer has first been destroyed. */
MPVFLOW_INTERNAL enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_bridge_destroy(
    MPVFlowOpenCLVkBridge *bridge);

/* Last diagnostic for an operation on this context (process-owned storage). */
MPVFLOW_INTERNAL const char *mpvflow_opencl_vk_bridge_last_error(
    const MPVFlowOpenCLVkBridge *bridge);

/* Run short, diagnostic-only QCOM imports on separate fresh 32x32 BLOB AHBs:
 * one before any Vulkan buffer bind and one after a successful Vulkan bind.
 * Neither result enables OpenCL; the caller must separately pass its complete
 * Vulkan/OpenCL kernel round-trip before selecting that backend. */
MPVFLOW_INTERNAL void mpvflow_opencl_vk_bridge_probe_import_stages(
    MPVFlowOpenCLVkBridge *bridge,
    struct MPVFlowOpenCLVkImportDiagnostics *diagnostics);

/*
 * Allocate and import a single-layer BLOB AHardwareBuffer. Logical RGBA8 frame
 * dimensions and row stride are stored separately from the BLOB descriptor.
 * The buffer size is bounded to 256 MiB and each dimension to 4096. The access
 * mode is passed unchanged to mpvflow_opencl_import_ahardwarebuffer. Imported
 * buffers start foreign-owned; before the first Vulkan copy, the caller marks
 * the unused OpenCL side complete. Later Vulkan copies are fence-waited before
 * returning, so the caller may then start OpenCL work directly.
 */
MPVFLOW_INTERNAL enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_buffer_create(
    MPVFlowOpenCLVkBridge *bridge, uint32_t frame_width,
    uint32_t frame_height, enum MPVFlowOpenCLAccess access,
    MPVFlowOpenCLVkBuffer **buffer_out);

/*
 * Destroy only after OpenCL work has completed and
 * mpvflow_opencl_vk_buffer_opencl_finished() has been called for the last
 * OpenCL access. Vulkan/AHB/OpenCL objects are released in dependency order.
 */
MPVFLOW_INTERNAL enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_buffer_destroy(
    MPVFlowOpenCLVkBuffer *buffer);

/* Borrowed OpenCL wrapper, owned/released by the bridge buffer. */
MPVFLOW_INTERNAL MPVFlowOpenCLBuffer *mpvflow_opencl_vk_buffer_get_opencl(
    MPVFlowOpenCLVkBuffer *buffer);
MPVFLOW_INTERNAL uint32_t mpvflow_opencl_vk_buffer_width(const MPVFlowOpenCLVkBuffer *buffer);
MPVFLOW_INTERNAL uint32_t mpvflow_opencl_vk_buffer_height(const MPVFlowOpenCLVkBuffer *buffer);
MPVFLOW_INTERNAL uint32_t mpvflow_opencl_vk_buffer_row_stride(const MPVFlowOpenCLVkBuffer *buffer);

/*
 * Call before queuing OpenCL work that will touch this buffer. This records that
 * completion must be explicitly confirmed before the next Vulkan use/destruction.
 */
MPVFLOW_INTERNAL enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_buffer_opencl_started(
    MPVFlowOpenCLVkBuffer *buffer);

/* Call only after the OpenCL queue has completed all work touching this buffer
 * (the current mpvflow OpenCL pair/synthesis APIs are blocking and call
 * clFinish before returning). This is the explicit host-side OpenCL -> Vulkan
 * synchronization point; it does not call or emulate clFinish. The next
 * Vulkan copy performs the foreign-family acquire barrier and waits for its
 * fence before returning.
 */
MPVFLOW_INTERNAL enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_buffer_opencl_finished(
    MPVFlowOpenCLVkBuffer *buffer);

/* GPU-only Vulkan transfer fill for deterministic bridge self-tests. The buffer
 * must be foreign-owned and idle; ownership is returned to the foreign family
 * and the queue fence is waited before this function returns. */
MPVFLOW_INTERNAL enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_fill_buffer(
    MPVFlowOpenCLVkBuffer *buffer, uint32_t value);

/*
 * GPU-only VkImageToBuffer copy. Source must be a 2D, single-sample,
 * VK_FORMAT_R8G8B8A8_UNORM pl_tex with TRANSFER_SRC usage and exact dimensions.
 * On return the Vulkan fence has signaled and the buffer is released to the
 * foreign queue family for OpenCL use. The fence is waited before return.
 */
MPVFLOW_INTERNAL enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_copy_texture_to_buffer(
    MPVFlowOpenCLVkBridge *bridge, pl_tex source,
    MPVFlowOpenCLVkBuffer *destination);

/*
 * GPU-only VkBufferToImage copy. Destination must be a 2D, single-sample,
 * VK_FORMAT_R8G8B8A8_UNORM pl_tex with TRANSFER_DST usage and exact dimensions.
 * It may be uninitialized (UNDEFINED layout); in that case it is transitioned
 * directly to TRANSFER_DST_OPTIMAL and then GENERAL before release.
 * The buffer must have been marked complete after OpenCL clFinish.
 */
MPVFLOW_INTERNAL enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_copy_buffer_to_texture(
    MPVFlowOpenCLVkBridge *bridge, MPVFlowOpenCLVkBuffer *source,
    pl_tex destination);

#ifdef __cplusplus
}
#endif
#endif /* MPV_INFINITY_MPVFLOW_OPENCL_VK_BRIDGE_H */
