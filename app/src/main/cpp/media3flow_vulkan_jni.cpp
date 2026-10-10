/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Native Media3 adapter for the shared MPV Flow Vulkan compute pipeline.
 * Decoder frames remain in Android HardwareBuffers; GLES is used only by the
 * existing SurfaceTexture/FBO capture and final presentation stages. All
 * downscale, motion analysis, and frame synthesis execute as Vulkan SPIR-V
 * passes through the same libplacebo API and mpvflow_gpu implementation as the
 * MPV route. No pixel data is read back to the CPU.
 */
#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <libplacebo/vulkan.h>
#include <libplacebo/log.h>
#include "mpvflow/mpvflow_gpu.h"

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <unordered_set>

#ifndef VK_QUEUE_FAMILY_FOREIGN_EXT
#define VK_QUEUE_FAMILY_FOREIGN_EXT 0xfffffffd
#endif

namespace {
constexpr const char *kTag = "MpvInfinityFlowVk";
constexpr VkFormat kSharedFormat = VK_FORMAT_R8G8B8A8_UNORM;

struct SharedImage {
    AHardwareBuffer *buffer = nullptr;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    pl_tex texture = nullptr;
    pl_tex prepared = nullptr;
    int width = 0;
    int height = 0;
    bool output = false;
    bool heldByExternal = true;
};

struct FlowContext {
    pl_log placeboLog = nullptr;
    pl_vulkan vulkan = nullptr;
    MPVFlowGPUContext *flow = nullptr;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getBufferProperties = nullptr;
    std::unordered_set<SharedImage *> images;
    std::unordered_set<MPVFlowGPUPair *> pairs;
    std::mutex mutex;
    uint64_t diagnosticMask = 0;
};

enum FlowDiagnosticBit : uint64_t {
    DIAG_AHB_PROBE_FAILURE = 1ull << 0,
    DIAG_INPUT_IMPORT_FAILURE = 1ull << 1,
    DIAG_OUTPUT_IMPORT_FAILURE = 1ull << 2,
    DIAG_INPUT_IMPORT_READY = 1ull << 3,
    DIAG_OUTPUT_IMPORT_READY = 1ull << 4,
    DIAG_PREPARE_FAILURE = 1ull << 5,
    DIAG_PREPARE_READY = 1ull << 6,
    DIAG_ANALYZE_FAILURE = 1ull << 7,
    DIAG_ANALYZE_READY = 1ull << 8,
    DIAG_SYNTH_FAILURE = 1ull << 9,
    DIAG_SYNTH_READY = 1ull << 10,
    DIAG_OWNERSHIP_FAILURE = 1ull << 11,
    DIAG_INPUT_FORMAT_READY = 1ull << 12,
    DIAG_OUTPUT_FORMAT_READY = 1ull << 13,
    DIAG_INPUT_CREATE_RETURN_FAILURE = 1ull << 14,
    DIAG_OUTPUT_CREATE_RETURN_FAILURE = 1ull << 15,
};

enum EglDiagnosticBit : uint32_t {
    EGL_DIAG_ARGUMENTS = 1u << 0,
    EGL_DIAG_PROC_ADDRESSES = 1u << 1,
    EGL_DIAG_HARDWAREBUFFER = 1u << 2,
    EGL_DIAG_CLIENT_BUFFER = 1u << 3,
    EGL_DIAG_IMAGE_CREATE = 1u << 4,
    EGL_DIAG_TEXTURE_CREATE = 1u << 5,
    EGL_DIAG_IMAGE_TARGET = 1u << 6,
    EGL_DIAG_IMAGE_DESTROY = 1u << 7,
};

std::atomic<uint32_t> gEglDiagnosticMask{0};

void logFlow(int priority, const char *format, ...) {
    char message[1536];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    __android_log_write(priority, kTag, message);
}

void logFlowOnce(FlowContext *context, uint64_t bit, int priority,
                 const char *format, ...) {
    if (!context || (context->diagnosticMask & bit))
        return;
    context->diagnosticMask |= bit;
    char message[1536];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    __android_log_write(priority, kTag, message);
}

void logEglOnce(uint32_t bit, int priority, const char *format, ...) {
    const uint32_t previous = gEglDiagnosticMask.fetch_or(bit, std::memory_order_relaxed);
    if (previous & bit)
        return;
    char message[1536];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    __android_log_write(priority, kTag, message);
}

const char *placeboLogLevelName(enum pl_log_level level) {
    switch (level) {
    case PL_LOG_FATAL: return "fatal";
    case PL_LOG_ERR: return "error";
    case PL_LOG_WARN: return "warning";
    case PL_LOG_INFO: return "info";
    case PL_LOG_DEBUG: return "debug";
    case PL_LOG_TRACE: return "trace";
    default: return "none";
    }
}

void placeboLogCallback(void *, enum pl_log_level level, const char *message) {
    const int priority = level <= PL_LOG_ERR ? ANDROID_LOG_ERROR
        : level == PL_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    logFlow(priority,
            "MPVFLOW_DIAGNOSTIC component=libplacebo level=%s message=%s",
            placeboLogLevelName(level), message ? message : "(empty)");
}

bool hasEnabledDeviceExtension(pl_vulkan vulkan, const char *name) {
    if (!vulkan || !name)
        return false;
    for (int i = 0; i < vulkan->num_extensions; ++i) {
        if (vulkan->extensions[i] && std::strcmp(vulkan->extensions[i], name) == 0)
            return true;
    }
    return false;
}

bool holdImageForExternal(FlowContext *context, SharedImage *image) {
    if (!context || !image || !image->texture)
        return false;
    if (image->heldByExternal)
        return true;

    VkSemaphoreCreateInfo semaphoreInfo{};
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkSemaphore semaphore = VK_NULL_HANDLE;
    const VkResult semaphoreResult = vkCreateSemaphore(
        context->vulkan->device, &semaphoreInfo, nullptr, &semaphore);
    if (semaphoreResult != VK_SUCCESS) {
        logFlowOnce(context, DIAG_OWNERSHIP_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ownership state=failed "
                    "stage=external_release_semaphore vk_result=%d image_role=%s",
                    semaphoreResult, image->output ? "output" : "input");
        return false;
    }

    struct pl_vulkan_hold_params holdParams{};
    holdParams.tex = image->texture;
    holdParams.layout = VK_IMAGE_LAYOUT_GENERAL;
    holdParams.qf = VK_QUEUE_FAMILY_FOREIGN_EXT;
    holdParams.semaphore.sem = semaphore;
    holdParams.semaphore.value = 0;
    const bool held = pl_vulkan_hold_ex(context->vulkan->gpu, &holdParams);
    // pl_gpu_finish waits for the release barrier and its signal semaphore.
    pl_gpu_finish(context->vulkan->gpu);
    vkDestroySemaphore(context->vulkan->device, semaphore, nullptr);
    image->heldByExternal = held;
    if (!held)
        logFlowOnce(context, DIAG_OWNERSHIP_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ownership state=failed "
                    "stage=libplacebo_hold_to_external image_role=%s layout=general queue_family=%u",
                    image->output ? "output" : "input", (unsigned int) VK_QUEUE_FAMILY_FOREIGN_EXT);
    return image->heldByExternal;
}

bool releaseImageToVulkan(FlowContext *context, SharedImage *image) {
    if (!context || !image || !image->texture)
        return false;
    if (!image->heldByExternal)
        return true;
    struct pl_vulkan_release_params releaseParams{};
    releaseParams.tex = image->texture;
    releaseParams.layout = VK_IMAGE_LAYOUT_GENERAL;
    releaseParams.qf = VK_QUEUE_FAMILY_FOREIGN_EXT;
    pl_vulkan_release_ex(context->vulkan->gpu, &releaseParams);
    image->heldByExternal = false;
    return true;
}

void destroyImage(FlowContext *context, SharedImage *image) {
    if (!context || !image)
        return;
    if (image->prepared)
        pl_tex_destroy(context->vulkan->gpu, &image->prepared);
    if (image->texture) {
        if (!image->heldByExternal)
            (void) holdImageForExternal(context, image);
        pl_tex_destroy(context->vulkan->gpu, &image->texture);
    }
    if (image->image)
        vkDestroyImage(context->vulkan->device, image->image, nullptr);
    if (image->memory)
        vkFreeMemory(context->vulkan->device, image->memory, nullptr);
    if (image->buffer)
        AHardwareBuffer_release(image->buffer);
    delete image;
}

bool queryAhbImageSupport(FlowContext *context, VkImageUsageFlags usage,
                          const AHardwareBuffer_Desc &bufferDesc) {
    VkAndroidHardwareBufferUsageANDROID ahbUsage{};
    ahbUsage.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_USAGE_ANDROID;
    VkExternalImageFormatProperties externalProperties{};
    externalProperties.sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES;
    externalProperties.pNext = &ahbUsage;
    VkImageFormatProperties2 imageProperties{};
    imageProperties.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2;
    imageProperties.pNext = &externalProperties;

    VkPhysicalDeviceExternalImageFormatInfo externalInfo{};
    externalInfo.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO;
    externalInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkPhysicalDeviceImageFormatInfo2 imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2;
    imageInfo.pNext = &externalInfo;
    imageInfo.format = kSharedFormat;
    imageInfo.type = VK_IMAGE_TYPE_2D;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = usage;
    imageInfo.flags = 0;

    if (!context->vulkan->get_proc_addr) {
        logFlowOnce(context, DIAG_AHB_PROBE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_probe state=failed "
                    "stage=vulkan_get_proc_addr width=%u height=%u format=%u buffer_usage=0x%llx requested_vk_usage=0x%x",
                    bufferDesc.width, bufferDesc.height, bufferDesc.format,
                    static_cast<unsigned long long>(bufferDesc.usage), usage);
        return false;
    }
    auto getImageFormatProperties2 =
        reinterpret_cast<PFN_vkGetPhysicalDeviceImageFormatProperties2>(
            context->vulkan->get_proc_addr(
                context->vulkan->instance, "vkGetPhysicalDeviceImageFormatProperties2"));
    if (!getImageFormatProperties2) {
        getImageFormatProperties2 =
            reinterpret_cast<PFN_vkGetPhysicalDeviceImageFormatProperties2>(
                context->vulkan->get_proc_addr(
                    context->vulkan->instance, "vkGetPhysicalDeviceImageFormatProperties2KHR"));
    }
    if (!getImageFormatProperties2) {
        logFlowOnce(context, DIAG_AHB_PROBE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_probe state=failed "
                    "stage=vkGetPhysicalDeviceImageFormatProperties2_proc width=%u height=%u format=%u buffer_usage=0x%llx requested_vk_usage=0x%x",
                    bufferDesc.width, bufferDesc.height, bufferDesc.format,
                    static_cast<unsigned long long>(bufferDesc.usage), usage);
        return false;
    }
    const VkResult result = getImageFormatProperties2(
        context->vulkan->phys_device, &imageInfo, &imageProperties);
    if (result != VK_SUCCESS) {
        logFlowOnce(context, DIAG_AHB_PROBE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_probe state=failed "
                    "stage=vkGetPhysicalDeviceImageFormatProperties2 vk_result=%d width=%u height=%u "
                    "format=%u buffer_usage=0x%llx requested_vk_usage=0x%x",
                    result, bufferDesc.width, bufferDesc.height, bufferDesc.format,
                    static_cast<unsigned long long>(bufferDesc.usage), usage);
        return false;
    }
    const auto &memory = externalProperties.externalMemoryProperties;
    if (!(memory.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT)) {
        logFlowOnce(context, DIAG_AHB_PROBE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_probe state=failed "
                    "stage=external_memory_not_importable external_features=0x%x compatible_handles=0x%x "
                    "width=%u height=%u format=%u requested_vk_usage=0x%x",
                    memory.externalMemoryFeatures, memory.compatibleHandleTypes,
                    bufferDesc.width, bufferDesc.height, bufferDesc.format, usage);
        return false;
    }
    const bool usageSupported =
        (bufferDesc.usage & ahbUsage.androidHardwareBufferUsage) ==
        ahbUsage.androidHardwareBufferUsage;
    if (!usageSupported)
        logFlowOnce(context, DIAG_AHB_PROBE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_probe state=failed "
                    "stage=hardwarebuffer_usage_mismatch buffer_usage=0x%llx required_ahb_usage=0x%llx "
                    "external_features=0x%x compatible_handles=0x%x",
                    static_cast<unsigned long long>(bufferDesc.usage),
                    static_cast<unsigned long long>(ahbUsage.androidHardwareBufferUsage),
                    memory.externalMemoryFeatures, memory.compatibleHandleTypes);
    return usageSupported;
}

SharedImage *createSharedImage(FlowContext *context, JNIEnv *env, jobject javaBuffer,
                               int width, int height, bool output) {
    const uint64_t importFailureBit = output ? DIAG_OUTPUT_IMPORT_FAILURE : DIAG_INPUT_IMPORT_FAILURE;
    const uint64_t importReadyBit = output ? DIAG_OUTPUT_IMPORT_READY : DIAG_INPUT_IMPORT_READY;
    const char *role = output ? "output" : "input";
    if (!context || !env || !javaBuffer || width <= 0 || height <= 0) {
        logFlowOnce(context, importFailureBit, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed "
                    "stage=arguments image_role=%s width=%d height=%d context=%d env=%d buffer=%d",
                    role, width, height, context != nullptr, env != nullptr, javaBuffer != nullptr);
        return nullptr;
    }
    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, javaBuffer);
    if (!buffer) {
        logFlowOnce(context, importFailureBit, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed "
                    "stage=AHardwareBuffer_fromHardwareBuffer image_role=%s requested_width=%d requested_height=%d",
                    role, width, height);
        return nullptr;
    }
    AHardwareBuffer_acquire(buffer);

    AHardwareBuffer_Desc bufferDesc{};
    AHardwareBuffer_describe(buffer, &bufferDesc);
    if (bufferDesc.width != static_cast<uint32_t>(width) ||
        bufferDesc.height != static_cast<uint32_t>(height) ||
        bufferDesc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
        logFlowOnce(context, importFailureBit, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed "
                    "stage=hardwarebuffer_descriptor_validation image_role=%s requested=%dx%d "
                    "actual=%ux%u format=%u required_format=%u layers=%u stride=%u usage=0x%llx",
                    role, width, height, bufferDesc.width, bufferDesc.height,
                    bufferDesc.format, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
                    bufferDesc.layers, bufferDesc.stride,
                    static_cast<unsigned long long>(bufferDesc.usage));
        AHardwareBuffer_release(buffer);
        return nullptr;
    }

    const VkImageUsageFlags usage = output
        ? (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
        : VK_IMAGE_USAGE_SAMPLED_BIT;
    if (!queryAhbImageSupport(context, usage, bufferDesc)) {
        logFlowOnce(context, importFailureBit, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed "
                    "stage=external_image_format_support image_role=%s width=%d height=%d "
                    "buffer_format=%u buffer_usage=0x%llx requested_vk_usage=0x%x",
                    role, width, height, bufferDesc.format,
                    static_cast<unsigned long long>(bufferDesc.usage), usage);
        AHardwareBuffer_release(buffer);
        return nullptr;
    }

    auto *shared = new SharedImage();
    shared->buffer = buffer;
    shared->width = width;
    shared->height = height;
    shared->output = output;

    VkExternalMemoryImageCreateInfo externalInfo{};
    externalInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    externalInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &externalInfo;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = kSharedFormat;
    imageInfo.extent = { static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1 };
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = usage;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkResult result = vkCreateImage(context->vulkan->device, &imageInfo, nullptr, &shared->image);
    if (result != VK_SUCCESS) {
        logFlowOnce(context, importFailureBit, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed "
                    "stage=vkCreateImage image_role=%s vk_result=%d width=%d height=%d format=%u usage=0x%x "
                    "tiling=%u samples=%u external_handle_type=ANDROID_HARDWARE_BUFFER",
                    role, result, width, height, static_cast<unsigned int>(kSharedFormat), usage,
                    static_cast<unsigned int>(imageInfo.tiling),
                    static_cast<unsigned int>(imageInfo.samples));
        destroyImage(context, shared);
        return nullptr;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProperties{};
    formatProperties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID bufferProperties{};
    bufferProperties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    bufferProperties.pNext = &formatProperties;
    result = context->getBufferProperties(context->vulkan->device, buffer, &bufferProperties);
    if (result != VK_SUCCESS || formatProperties.format != kSharedFormat) {
        logFlowOnce(context, importFailureBit, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed "
                    "stage=vkGetAndroidHardwareBufferPropertiesANDROID image_role=%s vk_result=%d "
                    "vk_format=%u required_format=%u external_format=%llu format_features=0x%x "
                    "memory_type_bits=0x%x allocation_size=%llu",
                    role, result, static_cast<unsigned int>(formatProperties.format),
                    static_cast<unsigned int>(kSharedFormat),
                    static_cast<unsigned long long>(formatProperties.externalFormat),
                    formatProperties.formatFeatures, bufferProperties.memoryTypeBits,
                    static_cast<unsigned long long>(bufferProperties.allocationSize));
        destroyImage(context, shared);
        return nullptr;
    }
    logFlowOnce(context, output ? DIAG_OUTPUT_FORMAT_READY : DIAG_INPUT_FORMAT_READY,
                ANDROID_LOG_INFO,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_format state=ready "
                "image_role=%s width=%d height=%d vk_format=%u external_format=%llu "
                "format_features=0x%x memory_type_bits=0x%x allocation_size=%llu",
                role, width, height, formatProperties.format,
                static_cast<unsigned long long>(formatProperties.externalFormat),
                formatProperties.formatFeatures, bufferProperties.memoryTypeBits,
                static_cast<unsigned long long>(bufferProperties.allocationSize));

    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(context->vulkan->device, shared->image, &requirements);
    const uint32_t compatibleBits = requirements.memoryTypeBits & bufferProperties.memoryTypeBits;
    if (!compatibleBits || bufferProperties.allocationSize < requirements.size) {
        logFlowOnce(context, importFailureBit, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed "
                    "stage=memory_requirements image_role=%s image_memory_type_bits=0x%x "
                    "buffer_memory_type_bits=0x%x compatible_memory_type_bits=0x%x "
                    "required_size=%llu buffer_allocation_size=%llu alignment=%llu",
                    role, requirements.memoryTypeBits, bufferProperties.memoryTypeBits,
                    compatibleBits, static_cast<unsigned long long>(requirements.size),
                    static_cast<unsigned long long>(bufferProperties.allocationSize),
                    static_cast<unsigned long long>(requirements.alignment));
        destroyImage(context, shared);
        return nullptr;
    }
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(context->vulkan->phys_device, &memoryProperties);
    uint32_t memoryType = UINT32_MAX;
    for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
        if (compatibleBits & (1u << index)) {
            memoryType = index;
            break;
        }
    }
    if (memoryType == UINT32_MAX) {
        logFlowOnce(context, importFailureBit, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed "
                    "stage=compatible_memory_type_selection image_role=%s compatible_memory_type_bits=0x%x "
                    "physical_memory_type_count=%u",
                    role, compatibleBits, memoryProperties.memoryTypeCount);
        destroyImage(context, shared);
        return nullptr;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = buffer;
    VkMemoryDedicatedAllocateInfo dedicatedInfo{};
    dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicatedInfo.pNext = &importInfo;
    dedicatedInfo.image = shared->image;
    VkMemoryAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocateInfo.pNext = &dedicatedInfo;
    allocateInfo.allocationSize = bufferProperties.allocationSize;
    allocateInfo.memoryTypeIndex = memoryType;
    result = vkAllocateMemory(context->vulkan->device, &allocateInfo, nullptr, &shared->memory);
    if (result != VK_SUCCESS) {
        logFlowOnce(context, importFailureBit, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed "
                    "stage=vkAllocateMemory image_role=%s vk_result=%d allocation_size=%llu "
                    "memory_type_index=%u dedicated_image=1",
                    role, result, static_cast<unsigned long long>(allocateInfo.allocationSize), memoryType);
    } else {
        result = vkBindImageMemory(context->vulkan->device, shared->image, shared->memory, 0);
        if (result != VK_SUCCESS)
            logFlowOnce(context, importFailureBit, ANDROID_LOG_ERROR,
                        "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed "
                        "stage=vkBindImageMemory image_role=%s vk_result=%d memory_type_index=%u",
                        role, result, memoryType);
    }
    if (result != VK_SUCCESS) {
        destroyImage(context, shared);
        return nullptr;
    }

    struct pl_vulkan_wrap_params wrapParams{};
    wrapParams.image = shared->image;
    wrapParams.width = width;
    wrapParams.height = height;
    wrapParams.depth = 0;
    wrapParams.format = kSharedFormat;
    wrapParams.usage = usage;
    shared->texture = pl_vulkan_wrap(context->vulkan->gpu, &wrapParams);
    if (!shared->texture || !shared->texture->params.sampleable ||
        (output && !shared->texture->params.blit_dst)) {
        logFlowOnce(context, importFailureBit, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed "
                    "stage=pl_vulkan_wrap image_role=%s texture_present=%d sampleable=%d blit_dst=%d "
                    "width=%d height=%d format=%u usage=0x%x",
                    role, shared->texture != nullptr,
                    shared->texture ? shared->texture->params.sampleable : 0,
                    shared->texture ? shared->texture->params.blit_dst : 0,
                    width, height, static_cast<unsigned int>(kSharedFormat), usage);
        destroyImage(context, shared);
        return nullptr;
    }

    context->images.insert(shared);
    logFlowOnce(context, importReadyBit, ANDROID_LOG_INFO,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=ready "
                "image_role=%s width=%d height=%d format=rgba8 usage=0x%x sampleable=%d blit_dst=%d "
                "queue_family_foreign=%u",
                role, width, height, usage, shared->texture->params.sampleable,
                shared->texture->params.blit_dst, (unsigned int) VK_QUEUE_FAMILY_FOREIGN_EXT);
    return shared;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeCreateContext(
    JNIEnv *, jobject) {
    const char *extensions[] = {
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
        "VK_EXT_queue_family_foreign",
    };
    struct pl_vulkan_params params = pl_vulkan_default_params;
    params.async_compute = false;
    params.async_transfer = false;
    params.queue_count = 1;
    params.extensions = extensions;
    params.num_extensions = sizeof(extensions) / sizeof(extensions[0]);
    struct pl_log_params logParams{};
    logParams.log_cb = placeboLogCallback;
    logParams.log_level = PL_LOG_INFO;
    pl_log placeboLog = pl_log_create(PL_API_VER, &logParams);
    if (!placeboLog) {
        logFlow(ANDROID_LOG_ERROR,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=renderer_init state=unavailable "
                "stage=libplacebo_log_create reason=allocation_failed");
        return 0;
    }
    logFlow(ANDROID_LOG_INFO,
            "MPVFLOW_DIAGNOSTIC component=native-media3 event=vulkan_context state=requested "
            "api=vulkan async_compute=0 async_transfer=0 queue_count=1 "
            "required_extensions=VK_ANDROID_external_memory_android_hardware_buffer,VK_EXT_queue_family_foreign");
    pl_vulkan vulkan = pl_vulkan_create(placeboLog, &params);
    if (!vulkan) {
        logFlow(ANDROID_LOG_ERROR,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=vulkan_context state=unavailable "
                "stage=pl_vulkan_create reason=device_or_extension_initialization_failed "
                "required_extensions=VK_ANDROID_external_memory_android_hardware_buffer,VK_EXT_queue_family_foreign");
        pl_log_destroy(&placeboLog);
        return 0;
    }
    auto *context = new FlowContext();
    context->placeboLog = placeboLog;
    context->vulkan = vulkan;
    context->getBufferProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(vulkan->device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (!context->getBufferProperties) {
        logFlow(ANDROID_LOG_ERROR,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=vulkan_context state=unavailable "
                "stage=vkGetAndroidHardwareBufferPropertiesANDROID_proc "
                "required_extension=VK_ANDROID_external_memory_android_hardware_buffer "
                "ahb_extension_enabled=%d foreign_queue_extension_enabled=%d enabled_device_extension_count=%d",
                hasEnabledDeviceExtension(vulkan, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME),
                hasEnabledDeviceExtension(vulkan, "VK_EXT_queue_family_foreign"),
                vulkan->num_extensions);
        pl_vulkan_destroy(&context->vulkan);
        pl_log_destroy(&context->placeboLog);
        delete context;
        return 0;
    }
    MPVFlowGPUConfig config{};
    config.max_dimension = 640;
    config.search_radius = 8;
    MPVFlowGPUResult status = MPVFLOW_GPU_INVALID;
    MPVFlowGPUCreateDiagnostics createDiagnostics{};
    context->flow = mpvflow_gpu_create(vulkan->gpu, &config, &status, &createDiagnostics);
    if (!context->flow) {
        logFlow(ANDROID_LOG_ERROR,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=renderer_init state=unavailable "
                "stage=mpvflow_gpu_create create_stage=%s status=%d api_version=%u "
                "glsl_compute=%d glsl_vulkan=%d glsl_version=%d max_pushc_size=%zu "
                "max_tex_2d_dim=%u compute_queues=%u failed_pass_mask=0x%x "
                "rgba16f_found=%d rgba16f_caps=0x%x rgba8_found=%d rgba8_caps=0x%x",
                createDiagnostics.stage ? createDiagnostics.stage : "unknown", status,
                vulkan->api_version, vulkan->gpu->glsl.compute, vulkan->gpu->glsl.vulkan,
                vulkan->gpu->glsl.version, vulkan->gpu->limits.max_pushc_size,
                vulkan->gpu->limits.max_tex_2d_dim, vulkan->gpu->limits.compute_queues,
                createDiagnostics.failed_pass_mask, createDiagnostics.rgba16f_found,
                createDiagnostics.rgba16f_caps, createDiagnostics.rgba8_found,
                createDiagnostics.rgba8_caps);
        pl_vulkan_destroy(&context->vulkan);
        pl_log_destroy(&context->placeboLog);
        delete context;
        return 0;
    }
    logFlow(ANDROID_LOG_INFO,
            "MPVFLOW_DIAGNOSTIC component=native-media3 event=renderer_init state=ready "
            "backend=vulkan-spirv api_version=%u compute_queue_family=%u compute_queue_count=%u "
            "glsl_compute=%d glsl_vulkan=%d glsl_version=%d max_pushc_size=%zu "
            "max_texture_dimension=%d compute_queues=%u ahb_extension_enabled=%d "
            "foreign_queue_extension_enabled=%d "
            "hardware_decoder=media3 hardware_buffers=android cpu_readback=no",
            vulkan->api_version, vulkan->queue_compute.index, vulkan->queue_compute.count,
            vulkan->gpu->glsl.compute, vulkan->gpu->glsl.vulkan, vulkan->gpu->glsl.version,
            vulkan->gpu->limits.max_pushc_size, config.max_dimension,
            vulkan->gpu->limits.compute_queues,
            hasEnabledDeviceExtension(vulkan, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME),
            hasEnabledDeviceExtension(vulkan, "VK_EXT_queue_family_foreign"));
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeCreateFrameImage(
    JNIEnv *env, jobject, jlong contextHandle, jobject hardwareBuffer,
    jint width, jint height, jboolean output) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    const bool outputRole = output == JNI_TRUE;
    if (!context) {
        return 0;
    }
    std::lock_guard<std::mutex> guard(context->mutex);
    SharedImage *image = createSharedImage(context, env, hardwareBuffer,
                                           width, height, outputRole);
    if (!image)
        logFlowOnce(context,
                    outputRole ? DIAG_OUTPUT_CREATE_RETURN_FAILURE : DIAG_INPUT_CREATE_RETURN_FAILURE,
                    ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_import state=failed "
                    "stage=nativeCreateFrameImage image_role=%s width=%d height=%d reason=see_prior_stage_record",
                    outputRole ? "output" : "input", width, height);
    return reinterpret_cast<jlong>(image);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeCreateEglTexture(
    JNIEnv *env, jobject, jobject hardwareBuffer) {
    if (!env || !hardwareBuffer || eglGetCurrentDisplay() == EGL_NO_DISPLAY ||
        eglGetCurrentContext() == EGL_NO_CONTEXT) {
        logEglOnce(EGL_DIAG_ARGUMENTS, ANDROID_LOG_ERROR,
                   "MPVFLOW_DIAGNOSTIC component=native-media3 event=egl_import state=failed "
                   "stage=arguments env=%d hardware_buffer=%d display_ready=%d context_ready=%d egl_error=0x%x",
                   env != nullptr, hardwareBuffer != nullptr,
                   eglGetCurrentDisplay() != EGL_NO_DISPLAY,
                   eglGetCurrentContext() != EGL_NO_CONTEXT, eglGetError());
        return 0;
    }
    auto getClientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
        eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    auto createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
        eglGetProcAddress("eglCreateImageKHR"));
    auto destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
        eglGetProcAddress("eglDestroyImageKHR"));
    auto imageTarget = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
        eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!getClientBuffer || !createImage || !destroyImage || !imageTarget) {
        logEglOnce(EGL_DIAG_PROC_ADDRESSES, ANDROID_LOG_ERROR,
                   "MPVFLOW_DIAGNOSTIC component=native-media3 event=egl_import state=failed "
                   "stage=egl_extension_proc_lookup get_native_client_buffer=%d create_image=%d "
                   "destroy_image=%d image_target_texture=%d egl_error=0x%x",
                   getClientBuffer != nullptr, createImage != nullptr,
                   destroyImage != nullptr, imageTarget != nullptr, eglGetError());
        return 0;
    }
    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!buffer) {
        logEglOnce(EGL_DIAG_HARDWAREBUFFER, ANDROID_LOG_ERROR,
                   "MPVFLOW_DIAGNOSTIC component=native-media3 event=egl_import state=failed "
                   "stage=AHardwareBuffer_fromHardwareBuffer");
        return 0;
    }
    EGLClientBuffer clientBuffer = getClientBuffer(buffer);
    AHardwareBuffer_Desc bufferDesc{};
    AHardwareBuffer_describe(buffer, &bufferDesc);
    if (!clientBuffer) {
        logEglOnce(EGL_DIAG_CLIENT_BUFFER, ANDROID_LOG_ERROR,
                   "MPVFLOW_DIAGNOSTIC component=native-media3 event=egl_import state=failed "
                   "stage=eglGetNativeClientBufferANDROID width=%u height=%u format=%u usage=0x%llx egl_error=0x%x",
                   bufferDesc.width, bufferDesc.height, bufferDesc.format,
                   static_cast<unsigned long long>(bufferDesc.usage), eglGetError());
        return 0;
    }
    const EGLint attributes[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR image = createImage(eglGetCurrentDisplay(), EGL_NO_CONTEXT,
                                    EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attributes);
    if (image == EGL_NO_IMAGE_KHR) {
        logEglOnce(EGL_DIAG_IMAGE_CREATE, ANDROID_LOG_ERROR,
                   "MPVFLOW_DIAGNOSTIC component=native-media3 event=egl_import state=failed "
                   "stage=eglCreateImageKHR error=0x%x width=%u height=%u format=%u usage=0x%llx",
                   eglGetError(), bufferDesc.width, bufferDesc.height, bufferDesc.format,
                   static_cast<unsigned long long>(bufferDesc.usage));
        return 0;
    }
    GLuint texture = 0;
    glGenTextures(1, &texture);
    if (!texture) {
        logEglOnce(EGL_DIAG_TEXTURE_CREATE, ANDROID_LOG_ERROR,
                   "MPVFLOW_DIAGNOSTIC component=native-media3 event=egl_import state=failed "
                   "stage=glGenTextures gl_error=0x%x egl_error=0x%x",
                   glGetError(), eglGetError());
        (void) destroyImage(eglGetCurrentDisplay(), image);
        AHardwareBuffer_release(buffer);
        return 0;
    }
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    imageTarget(GL_TEXTURE_2D, reinterpret_cast<GLeglImageOES>(image));
    const GLenum imageTargetError = glGetError();
    const EGLBoolean destroyed = destroyImage(eglGetCurrentDisplay(), image);
    const EGLint destroyError = eglGetError();
    glBindTexture(GL_TEXTURE_2D, 0);
    const GLenum finalGlError = glGetError();
    if (imageTargetError != GL_NO_ERROR) {
        logEglOnce(EGL_DIAG_IMAGE_TARGET, ANDROID_LOG_ERROR,
                   "MPVFLOW_DIAGNOSTIC component=native-media3 event=egl_import state=failed "
                   "stage=glEGLImageTargetTexture2DOES gl_error=0x%x texture_id=%u",
                   imageTargetError, texture);
    }
    if (!destroyed || destroyError != EGL_SUCCESS) {
        logEglOnce(EGL_DIAG_IMAGE_DESTROY, ANDROID_LOG_ERROR,
                   "MPVFLOW_DIAGNOSTIC component=native-media3 event=egl_import state=failed "
                   "stage=eglDestroyImageKHR success=%d error=0x%x texture_id=%u",
                   destroyed == EGL_TRUE, destroyError, texture);
    }
    if (imageTargetError != GL_NO_ERROR || !destroyed ||
        destroyError != EGL_SUCCESS || finalGlError != GL_NO_ERROR) {
        if (texture)
            glDeleteTextures(1, &texture);
        return 0;
    }
    return static_cast<jint>(texture);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativePrepareFrame(
    JNIEnv *, jobject, jlong contextHandle, jlong imageHandle) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    auto *image = reinterpret_cast<SharedImage *>(imageHandle);
    if (!context)
        return JNI_FALSE;
    std::lock_guard<std::mutex> guard(context->mutex);
    if (!image || !image->texture) {
        logFlowOnce(context, DIAG_PREPARE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=prepare_frame state=failed "
                    "stage=shared_image_validation image_present=%d texture_present=%d",
                    image != nullptr, image && image->texture);
        return JNI_FALSE;
    }
    if (image->prepared)
        pl_tex_destroy(context->vulkan->gpu, &image->prepared);
    if (!releaseImageToVulkan(context, image)) {
        logFlowOnce(context, DIAG_PREPARE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=prepare_frame state=failed "
                    "stage=external_image_release image_role=%s width=%d height=%d",
                    image->output ? "output" : "input", image->width, image->height);
        return JNI_FALSE;
    }
    const MPVFlowGPUResult status = mpvflow_gpu_prepare_input(
        context->flow, image->texture, &image->prepared);
    if (status != MPVFLOW_GPU_OK) {
        pl_gpu_finish(context->vulkan->gpu);
        (void) holdImageForExternal(context, image);
        logFlowOnce(context, DIAG_PREPARE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=prepare_frame state=failed "
                    "stage=mpvflow_gpu_prepare_input status=%d image_role=%s width=%d height=%d "
                    "input_format=%s prepared_texture_present=%d",
                    status, image->output ? "output" : "input", image->width, image->height,
                    image->texture->params.format ? image->texture->params.format->name : "missing",
                    image->prepared != nullptr);
        return JNI_FALSE;
    }
    if (!holdImageForExternal(context, image)) {
        pl_tex_destroy(context->vulkan->gpu, &image->prepared);
        logFlowOnce(context, DIAG_PREPARE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=prepare_frame state=failed "
                    "stage=return_prepared_input_to_external image_role=%s width=%d height=%d",
                    image->output ? "output" : "input", image->width, image->height);
        return JNI_FALSE;
    }
    logFlowOnce(context, DIAG_PREPARE_READY, ANDROID_LOG_INFO,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=prepare_frame state=ready "
                "backend=vulkan-spirv image_role=%s source=%dx%d prepared=%ux%u "
                "format=%s sampleable=%d cpu_readback=no",
                image->output ? "output" : "input", image->width, image->height,
                image->prepared->params.w, image->prepared->params.h,
                image->prepared->params.format ? image->prepared->params.format->name : "missing",
                image->prepared->params.sampleable);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeAnalyzePair(
    JNIEnv *, jobject, jlong contextHandle, jlong frame0Handle, jlong frame1Handle) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    auto *frame0 = reinterpret_cast<SharedImage *>(frame0Handle);
    auto *frame1 = reinterpret_cast<SharedImage *>(frame1Handle);
    if (!context)
        return 0;
    std::lock_guard<std::mutex> guard(context->mutex);
    if (!frame0 || !frame1 || !frame0->prepared || !frame1->prepared) {
        logFlowOnce(context, DIAG_ANALYZE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=analyze_pair state=failed "
                    "stage=prepared_frame_validation frame0_present=%d frame0_prepared=%d "
                    "frame1_present=%d frame1_prepared=%d",
                    frame0 != nullptr, frame0 && frame0->prepared,
                    frame1 != nullptr, frame1 && frame1->prepared);
        return 0;
    }
    MPVFlowGPUPair *pair = nullptr;
    const MPVFlowGPUResult result = mpvflow_gpu_analyze_pair(
        context->flow, frame0->prepared, frame1->prepared, &pair);
    if (result != MPVFLOW_GPU_OK || !pair) {
        logFlowOnce(context, DIAG_ANALYZE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=analyze_pair state=failed "
                    "stage=mpvflow_gpu_analyze_pair status=%d frame0=%ux%u frame1=%ux%u "
                    "backend=vulkan-spirv",
                    result, frame0->prepared->params.w, frame0->prepared->params.h,
                    frame1->prepared->params.w, frame1->prepared->params.h);
        return 0;
    }
    context->pairs.insert(pair);
    logFlowOnce(context, DIAG_ANALYZE_READY, ANDROID_LOG_INFO,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=analyze_pair state=ready "
                "backend=vulkan-spirv dimensions=%ux%u frames=2 output=GPU-resident cpu_readback=no",
                frame0->prepared->params.w, frame0->prepared->params.h);
    return reinterpret_cast<jlong>(pair);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeSynthesize(
    JNIEnv *, jobject, jlong contextHandle, jlong pairHandle,
    jfloat timestep, jlong outputHandle) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    auto *pair = reinterpret_cast<MPVFlowGPUPair *>(pairHandle);
    auto *output = reinterpret_cast<SharedImage *>(outputHandle);
    if (!context)
        return JNI_FALSE;
    std::lock_guard<std::mutex> guard(context->mutex);
    if (!pair || !output || !output->texture || timestep <= 0.f || timestep >= 1.f) {
        logFlowOnce(context, DIAG_SYNTH_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=synthesize state=failed "
                    "stage=arguments pair_present=%d output_present=%d output_texture_present=%d "
                    "timestep=%.9g reason=invalid_handle_or_timestep",
                    pair != nullptr, output != nullptr, output && output->texture, timestep);
        return JNI_FALSE;
    }
    if (context->pairs.find(pair) == context->pairs.end()) {
        logFlowOnce(context, DIAG_SYNTH_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=synthesize state=failed "
                    "stage=pair_registration reason=pair_not_owned_by_context timestep=%.9g",
                    timestep);
        return JNI_FALSE;
    }
    if (!releaseImageToVulkan(context, output)) {
        logFlowOnce(context, DIAG_SYNTH_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=synthesize state=failed "
                    "stage=output_image_ownership_release output=%dx%d timestep=%.9g",
                    output->width, output->height, timestep);
        return JNI_FALSE;
    }
    pl_tex synthesized = nullptr;
    const MPVFlowGPUResult result = mpvflow_gpu_synthesize(
        context->flow, pair, timestep, &synthesized);
    if (result != MPVFLOW_GPU_OK || !synthesized) {
        (void) holdImageForExternal(context, output);
        logFlowOnce(context, DIAG_SYNTH_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=synthesize state=failed "
                    "stage=mpvflow_gpu_synthesize status=%d synthesized_texture_present=%d "
                    "timestep=%.9g output=%dx%d",
                    result, synthesized != nullptr, timestep, output->width, output->height);
        return JNI_FALSE;
    }
    struct pl_tex_blit_params blitParams{};
    blitParams.src = synthesized;
    blitParams.dst = output->texture;
    blitParams.sample_mode = PL_TEX_SAMPLE_LINEAR;
    pl_tex_blit(context->vulkan->gpu, &blitParams);
    if (!holdImageForExternal(context, output)) {
        logFlowOnce(context, DIAG_SYNTH_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=synthesize state=failed "
                    "stage=return_output_to_external output=%dx%d timestep=%.9g",
                    output->width, output->height, timestep);
        return JNI_FALSE;
    }
    logFlowOnce(context, DIAG_SYNTH_READY, ANDROID_LOG_INFO,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=synthesize state=returned_to_display "
                "backend=vulkan-spirv timestep=%.9g output=%dx%d sample_mode=linear "
                "command_completion=pl_gpu_finish cpu_readback=no",
                timestep, output->width, output->height);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeReleasePair(
    JNIEnv *, jobject, jlong contextHandle, jlong pairHandle) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    auto *pair = reinterpret_cast<MPVFlowGPUPair *>(pairHandle);
    if (!context || !pair)
        return;
    std::lock_guard<std::mutex> guard(context->mutex);
    if (context->pairs.erase(pair)) {
        pl_gpu_finish(context->vulkan->gpu);
        mpvflow_gpu_pair_release(context->flow, pair);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeDestroyImage(
    JNIEnv *, jobject, jlong contextHandle, jlong imageHandle) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    auto *image = reinterpret_cast<SharedImage *>(imageHandle);
    if (!context || !image)
        return;
    std::lock_guard<std::mutex> guard(context->mutex);
    pl_gpu_finish(context->vulkan->gpu);
    if (context->images.erase(image))
        destroyImage(context, image);
}

extern "C" JNIEXPORT void JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeDestroyContext(
    JNIEnv *, jobject, jlong contextHandle) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    if (!context)
        return;
    {
        std::lock_guard<std::mutex> guard(context->mutex);
        for (MPVFlowGPUPair *pair : context->pairs)
            mpvflow_gpu_pair_release(context->flow, pair);
        context->pairs.clear();
        pl_gpu_finish(context->vulkan->gpu);
        for (SharedImage *image : context->images)
            destroyImage(context, image);
        context->images.clear();
        mpvflow_gpu_destroy(context->flow);
        context->flow = nullptr;
        pl_vulkan_destroy(&context->vulkan);
        pl_log_destroy(&context->placeboLog);
    }
    delete context;
}
