/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 *
 * Native Media3 adapter for Vulkan-rendered MPV Flow with selectable compute.
 * Decoder frames remain in Android HardwareBuffers; GLES is used only by the
 * existing SurfaceTexture/FBO capture and final presentation stages. All
 * downscale and renderer handoff use libplacebo/Vulkan; Flow motion analysis
 * and synthesis dispatch through the selected OpenCL or Vulkan backend. No
 * pixel data is read back to the CPU.
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
#include "mpvflow/mpvflow_vulkan_optical.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <mutex>
#include <unordered_set>
#include <unistd.h>
#include <vector>

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
    bool lastHoldUsedSyncFd = false;
};

struct PendingHoldFence {
    uint64_t sequence = 0;
    VkSemaphore semaphore = VK_NULL_HANDLE;
    EGLSyncKHR eglSync = EGL_NO_SYNC_KHR;
};

struct PendingReleaseSemaphore {
    uint64_t retireAfterSequence = 0;
    VkSemaphore semaphore = VK_NULL_HANDLE;
};

struct FlowContext {
    pl_log placeboLog = nullptr;
    pl_vulkan vulkan = nullptr;
    MPVFlowGPUContext *flow = nullptr;
    uint32_t computeQueueFamily = UINT32_MAX;
    uint32_t computeTimestampValidBits = 0;
    bool computeTimestampsSupported = false;
    bool timestampComputeAndGraphics = false;
    double timestampPeriodNs = 0.0;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getBufferProperties = nullptr;
    PFN_vkImportSemaphoreFdKHR importSemaphoreFd = nullptr;
    PFN_vkGetSemaphoreFdKHR getSemaphoreFd = nullptr;
    PFNEGLCREATESYNCKHRPROC createEglSync = nullptr;
    PFNEGLDESTROYSYNCKHRPROC destroyEglSync = nullptr;
    PFNEGLCLIENTWAITSYNCKHRPROC clientWaitEglSync = nullptr;
    PFNEGLWAITSYNCKHRPROC waitEglSync = nullptr;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC dupNativeFenceFd = nullptr;
    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    std::unordered_set<SharedImage *> images;
    std::unordered_set<MPVFlowGPUPair *> pairs;
    std::vector<PendingHoldFence> pendingHoldFences;
    std::vector<PendingReleaseSemaphore> pendingReleaseSemaphores;
    std::mutex mutex;
    uint64_t diagnosticMask = 0;
    uint64_t holdSequence = 0;
    uint64_t completedSequence = 0;
    uint64_t asyncHoldCount = 0;
    uint64_t asyncReleaseCount = 0;
    uint64_t fallbackHoldCount = 0;
    uint64_t fallbackReleaseCount = 0;
    uint64_t cpuSyncWaitCount = 0;
    uint64_t cpuSyncWaitTotalNs = 0;
    uint64_t cpuSyncWaitMaxNs = 0;
    uint32_t syncOperationsSinceSummary = 0;
    bool syncFdInteropReady = false;
    std::string captureSessionId;
    bool captureFailureLogged = false;
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
    DIAG_SYNC_CAPABILITY = 1ull << 16,
    DIAG_SYNC_FAILURE = 1ull << 17,
    DIAG_SYNC_BACKPRESSURE = 1ull << 18,
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

void logQueueFamilyCapability(pl_vulkan vulkan) {
    if (!vulkan || !vulkan->phys_device)
        return;
    uint32_t familyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(vulkan->phys_device, &familyCount, nullptr);
    std::vector<VkQueueFamilyProperties> families(familyCount);
    if (familyCount)
        vkGetPhysicalDeviceQueueFamilyProperties(vulkan->phys_device, &familyCount, families.data());
    uint32_t dedicatedComputeFamilies = 0;
    int firstDedicatedComputeFamily = -1;
    for (uint32_t i = 0; i < familyCount; ++i) {
        const VkQueueFlags flags = families[i].queueFlags;
        if (families[i].queueCount && (flags & VK_QUEUE_COMPUTE_BIT) && !(flags & VK_QUEUE_GRAPHICS_BIT)) {
            if (firstDedicatedComputeFamily < 0)
                firstDedicatedComputeFamily = static_cast<int>(i);
            ++dedicatedComputeFamilies;
        }
    }
    logFlow(ANDROID_LOG_INFO,
            "MPVFLOW_DIAGNOSTIC component=native-media3 event=queue_capability "
            "state=probed graphics_queue_family=%u selected_compute_queue_family=%u "
            "dedicated_compute_families=%u first_dedicated_compute_family=%d "
            "async_compute_enabled=0 async_transfer_enabled=0 queue_count=1 "
            "ahb_image_sharing=exclusive multi_family_wrapping_safe=0 "
            "async_queue_disabled_reason=exclusive_ahb_images_require_verified_queue_ownership",
            vulkan->queue_graphics.index, vulkan->queue_compute.index,
            dedicatedComputeFamilies, firstDedicatedComputeFamily);
}

bool hasEglExtension(const char *extensions, const char *name) {
    if (!extensions || !name || !*name)
        return false;
    const size_t length = std::strlen(name);
    for (const char *item = extensions; (item = std::strstr(item, name)); item += length) {
        const bool beginsToken = item == extensions || item[-1] == ' ';
        const bool endsToken = item[length] == '\0' || item[length] == ' ';
        if (beginsToken && endsToken)
            return true;
    }
    return false;
}

void initializeExternalSync(FlowContext *context) {
    if (!context || !context->vulkan)
        return;
    context->eglDisplay = eglGetCurrentDisplay();
    const char *eglExtensions = context->eglDisplay != EGL_NO_DISPLAY
        ? eglQueryString(context->eglDisplay, EGL_EXTENSIONS) : nullptr;
    const bool hasNativeFence = hasEglExtension(eglExtensions, "EGL_ANDROID_native_fence_sync");
    const bool hasServerWait = hasEglExtension(eglExtensions, "EGL_ANDROID_wait_sync") ||
                               hasEglExtension(eglExtensions, "EGL_KHR_wait_sync");
    context->createEglSync = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
        eglGetProcAddress("eglCreateSyncKHR"));
    context->destroyEglSync = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
        eglGetProcAddress("eglDestroySyncKHR"));
    context->clientWaitEglSync = reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(
        eglGetProcAddress("eglClientWaitSyncKHR"));
    context->waitEglSync = reinterpret_cast<PFNEGLWAITSYNCKHRPROC>(
        eglGetProcAddress("eglWaitSyncKHR"));
    context->dupNativeFenceFd = reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
        eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    context->importSemaphoreFd = reinterpret_cast<PFN_vkImportSemaphoreFdKHR>(
        vkGetDeviceProcAddr(context->vulkan->device, "vkImportSemaphoreFdKHR"));
    context->getSemaphoreFd = reinterpret_cast<PFN_vkGetSemaphoreFdKHR>(
        vkGetDeviceProcAddr(context->vulkan->device, "vkGetSemaphoreFdKHR"));

    auto getSemaphoreProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceExternalSemaphoreProperties>(
        context->vulkan->get_proc_addr(context->vulkan->instance,
                                       "vkGetPhysicalDeviceExternalSemaphoreProperties"));
    if (!getSemaphoreProperties) {
        getSemaphoreProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceExternalSemaphoreProperties>(
            context->vulkan->get_proc_addr(context->vulkan->instance,
                                           "vkGetPhysicalDeviceExternalSemaphorePropertiesKHR"));
    }
    VkExternalSemaphoreProperties semaphoreProperties{};
    semaphoreProperties.sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES;
    if (getSemaphoreProperties) {
        VkPhysicalDeviceExternalSemaphoreInfo semaphoreInfo{};
        semaphoreInfo.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO;
        semaphoreInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
        getSemaphoreProperties(context->vulkan->phys_device, &semaphoreInfo,
                               &semaphoreProperties);
    }
    const VkExternalSemaphoreFeatureFlags requiredFeatures =
        VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT |
        VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT;
    const bool vkSyncFdEnabled = hasEnabledDeviceExtension(
        context->vulkan, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
    const bool vkSyncFdSupported = getSemaphoreProperties &&
        (semaphoreProperties.compatibleHandleTypes &
         VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT) &&
        (semaphoreProperties.externalSemaphoreFeatures & requiredFeatures) == requiredFeatures;
    const bool eglFunctionsReady = context->createEglSync && context->destroyEglSync &&
        context->clientWaitEglSync && context->waitEglSync && context->dupNativeFenceFd;
    const bool oneQueue = context->vulkan->queue_graphics.index ==
                          context->vulkan->queue_compute.index;
    context->syncFdInteropReady = context->eglDisplay != EGL_NO_DISPLAY &&
        hasNativeFence && hasServerWait && eglFunctionsReady && vkSyncFdEnabled &&
        vkSyncFdSupported && context->importSemaphoreFd && context->getSemaphoreFd && oneQueue;
    logFlow(ANDROID_LOG_INFO,
            "MPVFLOW_DIAGNOSTIC component=native-media3 event=sync_capability state=%s "
            "mode=%s egl_native_fence=%d egl_server_wait=%d egl_functions=%d "
            "vk_sync_fd_enabled=%d vk_import_export=%d graphics_queue_family=%u "
            "compute_queue_family=%u same_queue=%d",
            context->syncFdInteropReady ? "ready" : "fallback",
            context->syncFdInteropReady ? "sync_fd_gpu_handoff" : "glFinish_pl_gpu_finish",
            hasNativeFence, hasServerWait, eglFunctionsReady, vkSyncFdEnabled,
            vkSyncFdSupported, context->vulkan->queue_graphics.index,
            context->vulkan->queue_compute.index, oneQueue);
}

constexpr size_t kMaxPendingInteropFences = 64;

VkSemaphore createInteropSemaphore(FlowContext *context, bool exportable) {
    VkExportSemaphoreCreateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    VkSemaphoreCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    createInfo.pNext = exportable ? &exportInfo : nullptr;
    VkSemaphore semaphore = VK_NULL_HANDLE;
    if (vkCreateSemaphore(context->vulkan->device, &createInfo, nullptr, &semaphore) != VK_SUCCESS)
        return VK_NULL_HANDLE;
    return semaphore;
}

int createEglNativeFenceFd(FlowContext *context) {
    if (!context || context->eglDisplay == EGL_NO_DISPLAY ||
        eglGetCurrentDisplay() != context->eglDisplay ||
        eglGetCurrentContext() == EGL_NO_CONTEXT || !context->createEglSync ||
        !context->destroyEglSync || !context->dupNativeFenceFd)
        return -1;
    const EGLint attributes[] = {
        EGL_SYNC_NATIVE_FENCE_FD_ANDROID, EGL_NO_NATIVE_FENCE_FD_ANDROID, EGL_NONE,
    };
    EGLSyncKHR fence = context->createEglSync(
        context->eglDisplay, EGL_SYNC_NATIVE_FENCE_ANDROID, attributes);
    if (fence == EGL_NO_SYNC_KHR)
        return -1;
    glFlush();
    const int fd = context->dupNativeFenceFd(context->eglDisplay, fence);
    context->destroyEglSync(context->eglDisplay, fence);
    return fd;
}

void logSyncSummaryIfReady(FlowContext *context) {
    if (!context || context->syncOperationsSinceSummary < 120)
        return;
    context->syncOperationsSinceSummary = 0;
    logFlow(ANDROID_LOG_INFO,
            "MPVFLOW_DIAGNOSTIC component=native-media3 event=sync_handoff_summary "
            "mode=%s async_release=%llu async_hold=%llu fallback_release=%llu "
            "fallback_hold=%llu cpu_sync_wait_count=%llu cpu_sync_wait_total_ms=%.3f "
            "cpu_sync_wait_max_ms=%.3f pending_release_semaphores=%zu pending_hold_fences=%zu",
            context->syncFdInteropReady ? "sync_fd_gpu_handoff" : "glFinish_pl_gpu_finish",
            static_cast<unsigned long long>(context->asyncReleaseCount),
            static_cast<unsigned long long>(context->asyncHoldCount),
            static_cast<unsigned long long>(context->fallbackReleaseCount),
            static_cast<unsigned long long>(context->fallbackHoldCount),
            static_cast<unsigned long long>(context->cpuSyncWaitCount),
            context->cpuSyncWaitTotalNs / 1000000.0,
            context->cpuSyncWaitMaxNs / 1000000.0,
            context->pendingReleaseSemaphores.size(), context->pendingHoldFences.size());
}

void recordCpuSyncWait(FlowContext *context,
                       std::chrono::steady_clock::time_point started) {
    if (!context)
        return;
    const uint64_t elapsed = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - started).count());
    ++context->cpuSyncWaitCount;
    context->cpuSyncWaitTotalNs += elapsed;
    context->cpuSyncWaitMaxNs = std::max(context->cpuSyncWaitMaxNs, elapsed);
}

void reapCompletedInterop(FlowContext *context) {
    if (!context || !context->syncFdInteropReady)
        return;
    for (auto it = context->pendingHoldFences.begin(); it != context->pendingHoldFences.end();) {
        const EGLint status = context->clientWaitEglSync(
            context->eglDisplay, it->eglSync, 0, 0);
        if (status == EGL_CONDITION_SATISFIED_KHR) {
            context->completedSequence = std::max(context->completedSequence, it->sequence);
            context->destroyEglSync(context->eglDisplay, it->eglSync);
            vkDestroySemaphore(context->vulkan->device, it->semaphore, nullptr);
            it = context->pendingHoldFences.erase(it);
        } else {
            if (status != EGL_TIMEOUT_EXPIRED_KHR)
                logFlowOnce(context, DIAG_SYNC_FAILURE, ANDROID_LOG_WARN,
                            "MPVFLOW_DIAGNOSTIC component=native-media3 event=sync_handoff state=fallback "
                            "stage=egl_fence_reap reason=client_wait_error egl_status=0x%x",
                            status);
            ++it;
        }
    }
    for (auto it = context->pendingReleaseSemaphores.begin();
         it != context->pendingReleaseSemaphores.end();) {
        if (it->retireAfterSequence <= context->completedSequence) {
            vkDestroySemaphore(context->vulkan->device, it->semaphore, nullptr);
            it = context->pendingReleaseSemaphores.erase(it);
        } else {
            ++it;
        }
    }
}

bool waitForOldestInteropFence(FlowContext *context) {
    if (!context || !context->syncFdInteropReady || context->pendingHoldFences.empty())
        return false;
    const auto started = std::chrono::steady_clock::now();
    const EGLint status = context->clientWaitEglSync(
        context->eglDisplay, context->pendingHoldFences.front().eglSync,
        0, EGL_FOREVER_KHR);
    recordCpuSyncWait(context, started);
    if (status != EGL_CONDITION_SATISFIED_KHR) {
        logFlowOnce(context, DIAG_SYNC_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=sync_handoff state=fallback "
                    "stage=interop_backpressure reason=egl_client_wait_failed egl_status=0x%x",
                    status);
        return false;
    }
    logFlowOnce(context, DIAG_SYNC_BACKPRESSURE, ANDROID_LOG_WARN,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=sync_handoff state=backpressure_wait "
                "stage=oldest_gpu_fence reason=interop_resource_cap");
    reapCompletedInterop(context);
    return true;
}

void drainPendingInterop(FlowContext *context) {
    if (!context || !context->vulkan)
        return;
    const auto started = std::chrono::steady_clock::now();
    pl_gpu_finish(context->vulkan->gpu);
    if (context->eglDisplay != EGL_NO_DISPLAY &&
        eglGetCurrentDisplay() == context->eglDisplay &&
        eglGetCurrentContext() != EGL_NO_CONTEXT)
        glFinish();
    recordCpuSyncWait(context, started);
    for (const PendingHoldFence &pending : context->pendingHoldFences) {
        if (pending.eglSync != EGL_NO_SYNC_KHR)
            context->destroyEglSync(context->eglDisplay, pending.eglSync);
        if (pending.semaphore != VK_NULL_HANDLE)
            vkDestroySemaphore(context->vulkan->device, pending.semaphore, nullptr);
    }
    context->pendingHoldFences.clear();
    for (const PendingReleaseSemaphore &pending : context->pendingReleaseSemaphores) {
        if (pending.semaphore != VK_NULL_HANDLE)
            vkDestroySemaphore(context->vulkan->device, pending.semaphore, nullptr);
    }
    context->pendingReleaseSemaphores.clear();
    context->completedSequence = context->holdSequence;
}

bool holdImageForExternal(FlowContext *context, SharedImage *image,
                          bool forceSynchronous = false) {
    if (!context || !image || !image->texture)
        return false;
    if (image->heldByExternal)
        return true;

    if (context->syncFdInteropReady) {
        reapCompletedInterop(context);
        if (context->pendingHoldFences.size() >= kMaxPendingInteropFences ||
            context->pendingReleaseSemaphores.size() >= kMaxPendingInteropFences)
            if (!waitForOldestInteropFence(context))
                drainPendingInterop(context);
    }
    const bool useSyncFd = context->syncFdInteropReady && !forceSynchronous;
    VkSemaphore semaphore = createInteropSemaphore(context, useSyncFd);
    if (semaphore == VK_NULL_HANDLE) {
        logFlowOnce(context, DIAG_OWNERSHIP_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ownership state=failed "
                    "stage=external_release_semaphore image_role=%s",
                    image->output ? "output" : "input");
        return false;
    }

    struct pl_vulkan_hold_params holdParams{};
    holdParams.tex = image->texture;
    holdParams.layout = VK_IMAGE_LAYOUT_GENERAL;
    holdParams.qf = VK_QUEUE_FAMILY_FOREIGN_EXT;
    holdParams.semaphore.sem = semaphore;
    holdParams.semaphore.value = 0;
    const bool held = pl_vulkan_hold_ex(context->vulkan->gpu, &holdParams);
    image->heldByExternal = held;
    image->lastHoldUsedSyncFd = false;
    if (!held) {
        drainPendingInterop(context);
        vkDestroySemaphore(context->vulkan->device, semaphore, nullptr);
        logFlowOnce(context, DIAG_OWNERSHIP_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ownership state=failed "
                    "stage=libplacebo_hold_to_external image_role=%s layout=general queue_family=%u",
                    image->output ? "output" : "input", (unsigned int) VK_QUEUE_FAMILY_FOREIGN_EXT);
        return false;
    }

    const uint64_t sequence = ++context->holdSequence;
    if (useSyncFd) {
        VkSemaphoreGetFdInfoKHR fdInfo{};
        fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
        fdInfo.semaphore = semaphore;
        fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
        int fd = -1;
        const VkResult exportResult = context->getSemaphoreFd(
            context->vulkan->device, &fdInfo, &fd);
        if (exportResult == VK_SUCCESS && fd == -1) {
            // VK_SYNC_FD exports may use -1 to represent an already-signaled
            // binary semaphore; the Vulkan ownership barrier has completed.
            context->completedSequence = std::max(context->completedSequence, sequence);
            vkDestroySemaphore(context->vulkan->device, semaphore, nullptr);
            image->lastHoldUsedSyncFd = true;
            ++context->asyncHoldCount;
            ++context->syncOperationsSinceSummary;
            logFlowOnce(context, DIAG_SYNC_CAPABILITY, ANDROID_LOG_INFO,
                        "MPVFLOW_DIAGNOSTIC component=native-media3 event=sync_handoff state=async "
                        "mode=vulkan_sync_fd_already_signaled queue_family=%u",
                        context->vulkan->queue_graphics.index);
            reapCompletedInterop(context);
            logSyncSummaryIfReady(context);
            return true;
        }
        if (exportResult == VK_SUCCESS && fd >= 0) {
            const EGLint attributes[] = {
                EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fd, EGL_NONE,
            };
            EGLSyncKHR eglSync = context->createEglSync(
                context->eglDisplay, EGL_SYNC_NATIVE_FENCE_ANDROID, attributes);
            if (eglSync != EGL_NO_SYNC_KHR) {
                const EGLint waitResult = context->waitEglSync(context->eglDisplay, eglSync, 0);
                if (waitResult == EGL_TRUE) {
                    context->pendingHoldFences.push_back({ sequence, semaphore, eglSync });
                    image->lastHoldUsedSyncFd = true;
                    ++context->asyncHoldCount;
                    ++context->syncOperationsSinceSummary;
                    logFlowOnce(context, DIAG_SYNC_CAPABILITY, ANDROID_LOG_INFO,
                                "MPVFLOW_DIAGNOSTIC component=native-media3 event=sync_handoff state=async "
                                "mode=vulkan_sync_fd_to_egl_server_wait queue_family=%u",
                                context->vulkan->queue_graphics.index);
                    reapCompletedInterop(context);
                    logSyncSummaryIfReady(context);
                    return true;
                }
                context->destroyEglSync(context->eglDisplay, eglSync);
            } else {
                close(fd);
            }
        } else if (fd >= 0) {
            close(fd);
        }
        const auto started = std::chrono::steady_clock::now();
        pl_gpu_finish(context->vulkan->gpu);
        recordCpuSyncWait(context, started);
        context->completedSequence = std::max(context->completedSequence, sequence);
        vkDestroySemaphore(context->vulkan->device, semaphore, nullptr);
        ++context->fallbackHoldCount;
        ++context->syncOperationsSinceSummary;
        logFlowOnce(context, DIAG_SYNC_FAILURE, ANDROID_LOG_WARN,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=sync_handoff state=fallback "
                    "stage=vulkan_signal_export_or_egl_wait reason=interop_call_failed "
                    "fallback=pl_gpu_finish");
        reapCompletedInterop(context);
        logSyncSummaryIfReady(context);
        return true;
    }

    const auto started = std::chrono::steady_clock::now();
    pl_gpu_finish(context->vulkan->gpu);
    recordCpuSyncWait(context, started);
    context->completedSequence = std::max(context->completedSequence, sequence);
    vkDestroySemaphore(context->vulkan->device, semaphore, nullptr);
    ++context->fallbackHoldCount;
    ++context->syncOperationsSinceSummary;
    if (!forceSynchronous)
        logFlowOnce(context, DIAG_SYNC_FAILURE, ANDROID_LOG_WARN,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=sync_handoff state=fallback "
                    "stage=vulkan_to_egl reason=sync_fd_capability_unavailable "
                    "fallback=pl_gpu_finish");
    reapCompletedInterop(context);
    logSyncSummaryIfReady(context);
    return image->heldByExternal;
}

bool releaseImageToVulkan(FlowContext *context, SharedImage *image) {
    if (!context || !image || !image->texture)
        return false;
    if (!image->heldByExternal)
        return true;
    if (eglGetCurrentContext() == EGL_NO_CONTEXT ||
        eglGetCurrentDisplay() != context->eglDisplay)
        return false;

    if (context->syncFdInteropReady) {
        reapCompletedInterop(context);
        if (context->pendingReleaseSemaphores.size() >= kMaxPendingInteropFences ||
            context->pendingHoldFences.size() >= kMaxPendingInteropFences)
            if (!waitForOldestInteropFence(context))
                drainPendingInterop(context);
        const int fd = createEglNativeFenceFd(context);
        VkSemaphore semaphore = fd >= 0 ? createInteropSemaphore(context, true) : VK_NULL_HANDLE;
        if (semaphore != VK_NULL_HANDLE) {
            VkImportSemaphoreFdInfoKHR importInfo{};
            importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR;
            importInfo.semaphore = semaphore;
            importInfo.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
            importInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
            importInfo.fd = fd;
            const VkResult importResult = context->importSemaphoreFd(
                context->vulkan->device, &importInfo);
            if (importResult == VK_SUCCESS) {
                struct pl_vulkan_release_params releaseParams{};
                releaseParams.tex = image->texture;
                releaseParams.layout = VK_IMAGE_LAYOUT_GENERAL;
                releaseParams.qf = VK_QUEUE_FAMILY_FOREIGN_EXT;
                releaseParams.semaphore.sem = semaphore;
                releaseParams.semaphore.value = 0;
                pl_vulkan_release_ex(context->vulkan->gpu, &releaseParams);
                image->heldByExternal = false;
                context->pendingReleaseSemaphores.push_back({
                    context->holdSequence + 1, semaphore,
                });
                ++context->asyncReleaseCount;
                ++context->syncOperationsSinceSummary;
                reapCompletedInterop(context);
                logSyncSummaryIfReady(context);
                return true;
            }
            close(fd); // Ownership transfers only when vkImportSemaphoreFdKHR succeeds.
            vkDestroySemaphore(context->vulkan->device, semaphore, nullptr);
        } else if (fd >= 0) {
            close(fd);
        }
        logFlowOnce(context, DIAG_SYNC_FAILURE, ANDROID_LOG_WARN,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=sync_handoff state=fallback "
                    "stage=egl_to_vulkan reason=native_fence_export_or_semaphore_import_failed "
                    "fallback=glFinish");
    }

    const auto started = std::chrono::steady_clock::now();
    glFinish();
    recordCpuSyncWait(context, started);
    struct pl_vulkan_release_params releaseParams{};
    releaseParams.tex = image->texture;
    releaseParams.layout = VK_IMAGE_LAYOUT_GENERAL;
    releaseParams.qf = VK_QUEUE_FAMILY_FOREIGN_EXT;
    pl_vulkan_release_ex(context->vulkan->gpu, &releaseParams);
    image->heldByExternal = false;
    ++context->fallbackReleaseCount;
    ++context->syncOperationsSinceSummary;
    logSyncSummaryIfReady(context);
    return true;
}

void destroyImage(FlowContext *context, SharedImage *image) {
    if (!context || !image)
        return;
    if (image->prepared)
        pl_tex_destroy(context->vulkan->gpu, &image->prepared);
    if (image->texture) {
        if (!image->heldByExternal)
            (void) holdImageForExternal(context, image, true);
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
                          const AHardwareBuffer_Desc *bufferDesc,
                          uint64_t *requiredAhbUsage, const char *imageRole) {
    const uint32_t width = bufferDesc ? bufferDesc->width : 0;
    const uint32_t height = bufferDesc ? bufferDesc->height : 0;
    const uint32_t format = bufferDesc ? bufferDesc->format : 0;
    const uint64_t bufferUsage = bufferDesc ? bufferDesc->usage : 0;
    const char *role = imageRole ? imageRole : "unknown";
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

    if (!context || !context->vulkan || !context->vulkan->get_proc_addr) {
        logFlowOnce(context, DIAG_AHB_PROBE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_probe state=failed "
                    "stage=vulkan_get_proc_addr image_role=%s width=%u height=%u format=%u "
                    "buffer_usage=0x%llx requested_vk_usage=0x%x",
                    role, width, height, format,
                    static_cast<unsigned long long>(bufferUsage), usage);
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
                    "stage=vkGetPhysicalDeviceImageFormatProperties2_proc image_role=%s "
                    "width=%u height=%u format=%u buffer_usage=0x%llx requested_vk_usage=0x%x",
                    role, width, height, format,
                    static_cast<unsigned long long>(bufferUsage), usage);
        return false;
    }
    const VkResult result = getImageFormatProperties2(
        context->vulkan->phys_device, &imageInfo, &imageProperties);
    if (result != VK_SUCCESS) {
        logFlowOnce(context, DIAG_AHB_PROBE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_probe state=failed "
                    "stage=vkGetPhysicalDeviceImageFormatProperties2 image_role=%s vk_result=%d "
                    "width=%u height=%u format=%u buffer_usage=0x%llx requested_vk_usage=0x%x",
                    role, result, width, height, format,
                    static_cast<unsigned long long>(bufferUsage), usage);
        return false;
    }
    const auto &memory = externalProperties.externalMemoryProperties;
    if (!(memory.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) ||
        !(memory.compatibleHandleTypes &
          VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID)) {
        logFlowOnce(context, DIAG_AHB_PROBE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_probe state=failed "
                    "stage=external_memory_not_importable image_role=%s external_features=0x%x "
                    "compatible_handles=0x%x width=%u height=%u format=%u requested_vk_usage=0x%x",
                    role,
                    memory.externalMemoryFeatures, memory.compatibleHandleTypes,
                    width, height, format, usage);
        return false;
    }
    const uint64_t gpuUsageFlags = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                                   AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    if (!(ahbUsage.androidHardwareBufferUsage & gpuUsageFlags)) {
        logFlowOnce(context, DIAG_AHB_PROBE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_probe state=failed "
                    "stage=required_usage_missing_gpu_flag image_role=%s required_ahb_usage=0x%llx "
                    "external_features=0x%x compatible_handles=0x%x",
                    role,
                    static_cast<unsigned long long>(ahbUsage.androidHardwareBufferUsage),
                    memory.externalMemoryFeatures, memory.compatibleHandleTypes);
        return false;
    }
    if (requiredAhbUsage)
        *requiredAhbUsage = ahbUsage.androidHardwareBufferUsage;
    if (!bufferDesc)
        return true;

    const bool usageSupported =
        (bufferDesc->usage & ahbUsage.androidHardwareBufferUsage) ==
        ahbUsage.androidHardwareBufferUsage;
    if (!usageSupported)
        logFlowOnce(context, DIAG_AHB_PROBE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_probe state=failed "
                    "stage=hardwarebuffer_usage_mismatch image_role=%s buffer_usage=0x%llx "
                    "required_ahb_usage=0x%llx external_features=0x%x compatible_handles=0x%x",
                    role,
                    static_cast<unsigned long long>(bufferDesc->usage),
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
    if (!queryAhbImageSupport(context, usage, &bufferDesc, nullptr, role)) {
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
    JNIEnv *, jobject, jint requestedMaxDimension, jint requestedComputeApi) {
    if (requestedComputeApi < MPVFLOW_GPU_COMPUTE_AUTO ||
        requestedComputeApi > MPVFLOW_GPU_COMPUTE_VULKAN) {
        logFlow(ANDROID_LOG_ERROR,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=renderer_init state=unavailable "
                "stage=compute_api_validation requested_compute_api=%d reason=invalid_compute_api",
                requestedComputeApi);
        return 0;
    }
    const char *extensions[] = {
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
        "VK_EXT_queue_family_foreign",
    };
    const char *optionalExtensions[] = {
        VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
#ifdef VK_NV_optical_flow
        VK_NV_OPTICAL_FLOW_EXTENSION_NAME,
#endif
#ifdef VK_QCOM_image_processing
        VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME,
#ifdef VK_QCOM_image_processing2
        VK_QCOM_IMAGE_PROCESSING_2_EXTENSION_NAME,
#endif
#endif
    };
#if defined(VK_NV_optical_flow) || defined(VK_QCOM_image_processing) || defined(VK_QCOM_image_processing2)
    VkPhysicalDeviceFeatures2 optionalFeatures{};
    optionalFeatures.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    void *optionalFeatureChain = nullptr;
#endif
#ifdef VK_QCOM_image_processing2
    VkPhysicalDeviceImageProcessing2FeaturesQCOM imageProcessing2Feature{};
    imageProcessing2Feature.sType =
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_2_FEATURES_QCOM;
    imageProcessing2Feature.textureBlockMatch2 = VK_TRUE;
    imageProcessing2Feature.pNext = optionalFeatureChain;
    optionalFeatureChain = &imageProcessing2Feature;
#endif
#ifdef VK_QCOM_image_processing
    VkPhysicalDeviceImageProcessingFeaturesQCOM imageProcessingFeature{};
    imageProcessingFeature.sType =
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_FEATURES_QCOM;
    imageProcessingFeature.textureBlockMatch = VK_TRUE;
    imageProcessingFeature.pNext = optionalFeatureChain;
    optionalFeatureChain = &imageProcessingFeature;
#endif
#ifdef VK_NV_optical_flow
    VkPhysicalDeviceOpticalFlowFeaturesNV opticalFlowFeature{};
    opticalFlowFeature.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV;
    opticalFlowFeature.opticalFlow = VK_TRUE;
    opticalFlowFeature.pNext = optionalFeatureChain;
    optionalFeatureChain = &opticalFlowFeature;
#endif
#if defined(VK_NV_optical_flow) || defined(VK_QCOM_image_processing) || defined(VK_QCOM_image_processing2)
    optionalFeatures.pNext = optionalFeatureChain;
#endif
    struct pl_vulkan_params params = pl_vulkan_default_params;
    params.async_compute = false;
    params.async_transfer = false;
    params.queue_count = 1;
    params.extensions = extensions;
    params.num_extensions = sizeof(extensions) / sizeof(extensions[0]);
    params.opt_extensions = optionalExtensions;
    params.num_opt_extensions = sizeof(optionalExtensions) / sizeof(optionalExtensions[0]);
#ifdef VK_NV_optical_flow
    params.extra_queues = VK_QUEUE_OPTICAL_FLOW_BIT_NV;
#endif
#if defined(VK_NV_optical_flow) || defined(VK_QCOM_image_processing) || defined(VK_QCOM_image_processing2)
    params.features = &optionalFeatures;
#endif
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
            "renderer_api=vulkan requested_compute_api=%d async_compute=0 async_transfer=0 queue_count=1 "
            "analysis_max_dimension=%d optical_flow_extension_requested=%d optical_flow_queue_requested=%d "
            "required_extensions=VK_ANDROID_external_memory_android_hardware_buffer,VK_EXT_queue_family_foreign",
            requestedComputeApi, requestedMaxDimension,
#ifdef VK_NV_optical_flow
            1, 1
#else
            0, 0
#endif
    );
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
    logQueueFamilyCapability(vulkan);
    initializeExternalSync(context);
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
    config.compute_api = static_cast<MPVFlowGPUComputeAPI>(requestedComputeApi);
    config.diagnostic_engine = "media3";
    config.max_dimension = std::max(160, std::min(640, static_cast<int>(requestedMaxDimension)));
    config.search_radius = 8;
    MPVFlowGPUResult status = MPVFLOW_GPU_INVALID;
    MPVFlowGPUCreateDiagnostics createDiagnostics{};
    context->flow = mpvflow_gpu_create(vulkan->gpu, &config, &status, &createDiagnostics);
    if (!context->flow) {
        logFlow(ANDROID_LOG_ERROR,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=renderer_init state=unavailable "
                "renderer_api=vulkan requested_compute_api=%d stage=mpvflow_gpu_create create_stage=%s status=%d api_version=%u "
                "glsl_compute=%d glsl_vulkan=%d glsl_version=%d max_pushc_size=%zu "
                "max_tex_2d_dim=%u compute_queues=%u failed_pass_mask=0x%x "
                "rgba16f_found=%d rgba16f_caps=0x%x rgba8_found=%d rgba8_caps=0x%x",
                requestedComputeApi, createDiagnostics.stage ? createDiagnostics.stage : "unknown", status,
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
    MPVFlowGPUTimingStats timingStats{};
    mpvflow_gpu_get_timing_stats(context->flow, &timingStats);
    context->computeQueueFamily = timingStats.compute_queue_family;
    context->computeTimestampValidBits = timingStats.compute_timestamp_valid_bits;
    context->computeTimestampsSupported = timingStats.compute_timestamps_supported;
    context->timestampComputeAndGraphics = timingStats.timestamp_compute_and_graphics;
    context->timestampPeriodNs = timingStats.timestamp_period_ns;
    uint32_t timerObjectCount = 0;
    for (uint32_t i = 0; i < timingStats.stage_count; i++)
        timerObjectCount += timingStats.stages[i].timer_created;
    const bool vulkanTimestampSupported = context->computeTimestampsSupported &&
                                          timerObjectCount == timingStats.stage_count;
    logFlow(vulkanTimestampSupported ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
            "MPVFLOW_DIAGNOSTIC component=native-media3 event=vulkan_timing_capability state=%s "
            "api=Vulkan source=libplacebo_timestamp_query timing_scope=libplacebo_vulkan_passes "
            "flow_compute_api=%s opencl_queue_timing=included_by_host_wait_only compute_queue_family=%u "
            "timestamp_period_ns=%.6f timestamp_compute_and_graphics=%d "
            "compute_queue_timestamp_valid_bits=%u device_timestamp_supported=%d "
            "timer_objects=%u timer_stages=%u nonblocking=1 "
            "fallback=CPU_submission_and_successful_EGL_swap_cadence reason=%s",
            vulkanTimestampSupported ? "supported" : "unsupported",
            mpvflow_gpu_get_compute_api_name(context->flow),
            context->computeQueueFamily, context->timestampPeriodNs,
            context->timestampComputeAndGraphics, context->computeTimestampValidBits,
            context->computeTimestampsSupported, timerObjectCount, timingStats.stage_count,
            context->timestampPeriodNs <= 0.0 ? "device_timestamp_period_zero" :
            !context->timestampComputeAndGraphics && context->computeTimestampValidBits == 0
                ? "compute_queue_timestampValidBits_zero" :
            timerObjectCount != timingStats.stage_count ? "libplacebo_timer_object_creation_failed" : "none");
    logFlow(ANDROID_LOG_INFO,
            "MPVFLOW_DIAGNOSTIC component=native-media3 event=renderer_init state=ready "
                "renderer_api=vulkan flow_compute_api=%s api_version=%u compute_queue_family=%u compute_queue_count=%u "
                "glsl_compute=%d glsl_vulkan=%d glsl_version=%d max_pushc_size=%zu "
                "analysis_max_dimension=%d hardware_max_texture_dimension=%u compute_queues=%u ahb_extension_enabled=%d "
            "optical_flow_extension_enabled=%d "
                "foreign_queue_extension_enabled=%d compute_queue_timestamp_valid_bits=%u "
                "timestamp_period_ns=%.6f timestamp_compute_and_graphics=%d vulkan_timing_supported=%d "
                "qcom2_adapter_ready=%d qcom2_luma_pass_ready=%d qcom2_seed_reason=%s "
                "hardware_decoder=media3 hardware_buffers=android cpu_readback=no",
            mpvflow_gpu_get_compute_api_name(context->flow),
            vulkan->api_version, vulkan->queue_compute.index, vulkan->queue_compute.count,
            vulkan->gpu->glsl.compute, vulkan->gpu->glsl.vulkan, vulkan->gpu->glsl.version,
                vulkan->gpu->limits.max_pushc_size, config.max_dimension,
            vulkan->gpu->limits.max_tex_2d_dim, vulkan->gpu->limits.compute_queues,
            hasEnabledDeviceExtension(vulkan, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME),
            hasEnabledDeviceExtension(vulkan, "VK_NV_optical_flow"),
            hasEnabledDeviceExtension(vulkan, "VK_EXT_queue_family_foreign"),
            context->computeTimestampValidBits, context->timestampPeriodNs,
            context->timestampComputeAndGraphics, vulkanTimestampSupported,
            createDiagnostics.qcom_seed_adapter_ready,
            createDiagnostics.qcom_seed_luma_pass_ready,
            createDiagnostics.qcom_seed_reason[0]
                ? createDiagnostics.qcom_seed_reason : "unknown");
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeStartDiagnosticCapture(
    JNIEnv *env, jobject, jlong contextHandle, jstring sessionId, jstring baseDirectory,
    jstring settingsJson, jboolean pairedOutput) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    if (!context || !sessionId || !baseDirectory || !settingsJson) return nullptr;
    const char *sid = env->GetStringUTFChars(sessionId, nullptr);
    const char *base = env->GetStringUTFChars(baseDirectory, nullptr);
    const char *settings = env->GetStringUTFChars(settingsJson, nullptr);
    if (!sid || !base || !settings) {
        if (sid) env->ReleaseStringUTFChars(sessionId, sid);
        if (base) env->ReleaseStringUTFChars(baseDirectory, base);
        if (settings) env->ReleaseStringUTFChars(settingsJson, settings);
        return env->NewStringUTF("Unable to read capture arguments");
    }
    std::string error;
    {
        std::lock_guard<std::mutex> guard(context->mutex);
        const std::string id(sid);
        if (id.empty() || id.size() > 80 || id.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-") != std::string::npos)
            error = "Invalid capture session ID";
        else if (!context->flow || !context->vulkan || !context->vulkan->gpu)
            error = "Flow/Vulkan resources unavailable";
        else if (!context->captureSessionId.empty() || mpvflow_gpu_capture_active(context->flow))
            error = "A diagnostic capture is already active";
        else {
            const auto result = mpvflow_gpu_capture_start(
                context->flow, base, id.c_str(), "native-media3", settings,
                pairedOutput == JNI_TRUE);
            if (result == MPVFLOW_GPU_OK) { context->captureSessionId = id; context->captureFailureLogged = false; }
            else error = "Native diagnostic capture start failed";
        }
    }
    env->ReleaseStringUTFChars(sessionId, sid); env->ReleaseStringUTFChars(baseDirectory, base); env->ReleaseStringUTFChars(settingsJson, settings);
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeStopDiagnosticCapture(
    JNIEnv *env, jobject, jlong contextHandle, jstring sessionId) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    if (!context || !sessionId) return env->NewStringUTF("Native Flow context unavailable");
    const char *sid = env->GetStringUTFChars(sessionId, nullptr);
    if (!sid) return env->NewStringUTF("Unable to read capture session ID");
    std::string error;
    {
        std::lock_guard<std::mutex> guard(context->mutex);
        if (context->captureSessionId != sid) error = "Capture session ID mismatch";
        else { mpvflow_gpu_capture_stop(context->flow); context->captureSessionId.clear(); }
    }
    env->ReleaseStringUTFChars(sessionId, sid);
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeGetComputeApiName(
    JNIEnv *env, jobject, jlong contextHandle) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    if (!context)
        return env->NewStringUTF("unavailable");
    std::lock_guard<std::mutex> guard(context->mutex);
    const char *name = context->flow
        ? mpvflow_gpu_get_compute_api_name(context->flow) : "unavailable";
    return env->NewStringUTF(name ? name : "unknown");
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeGetRequiredHardwareBufferUsage(
    JNIEnv *, jobject, jlong contextHandle, jboolean output) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    if (!context)
        return 0;
    std::lock_guard<std::mutex> guard(context->mutex);
    const bool outputRole = output == JNI_TRUE;
    const VkImageUsageFlags usage = outputRole
        ? (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
        : VK_IMAGE_USAGE_SAMPLED_BIT;
    uint64_t requiredAhbUsage = 0;
    const char *role = outputRole ? "output" : "input";
    if (!queryAhbImageSupport(context, usage, nullptr, &requiredAhbUsage, role))
        return 0;
    logFlow(ANDROID_LOG_INFO,
            "MPVFLOW_DIAGNOSTIC component=native-media3 event=ahb_usage state=required "
            "image_role=%s requested_vk_usage=0x%x required_ahb_usage=0x%llx",
            role, usage, static_cast<unsigned long long>(requiredAhbUsage));
    return static_cast<jlong>(requiredAhbUsage);
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
    const bool imageOwned = image && context->images.find(image) != context->images.end();
    if (!imageOwned || !image->texture) {
        logFlowOnce(context, DIAG_PREPARE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=prepare_frame state=failed "
                    "stage=shared_image_validation image_present=%d image_owned=%d texture_present=%d",
                    image != nullptr, imageOwned, imageOwned && image->texture);
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
        (void) holdImageForExternal(context, image, true);
        logFlowOnce(context, DIAG_PREPARE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=prepare_frame state=failed "
                    "stage=mpvflow_gpu_prepare_input status=%d image_role=%s width=%d height=%d "
                    "input_format=%s prepared_texture_present=%d",
                    status, image->output ? "output" : "input", image->width, image->height,
                    image->texture->params.format ? image->texture->params.format->name : "missing",
                    image->prepared != nullptr);
        return JNI_FALSE;
    }
    logFlowOnce(context, DIAG_PREPARE_READY, ANDROID_LOG_INFO,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=prepare_frame state=ready "
                "renderer_api=vulkan flow_compute_api=%s preprocess_api=vulkan-libplacebo "
                "image_role=%s source=%dx%d prepared=%ux%u "
                "format=%s sampleable=%d cpu_readback=no "
                "source_image_ownership=vulkan_until_external_read_or_reuse",
                mpvflow_gpu_get_compute_api_name(context->flow),
                image->output ? "output" : "input", image->width, image->height,
                image->prepared->params.w, image->prepared->params.h,
                image->prepared->params.format ? image->prepared->params.format->name : "missing",
                image->prepared->params.sampleable);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeHoldImageForExternal(
    JNIEnv *, jobject, jlong contextHandle, jlong imageHandle) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    auto *image = reinterpret_cast<SharedImage *>(imageHandle);
    if (!context || !image)
        return JNI_FALSE;
    std::lock_guard<std::mutex> guard(context->mutex);
    if (context->images.find(image) == context->images.end())
        return JNI_FALSE;
    if (!holdImageForExternal(context, image)) {
        logFlowOnce(context, DIAG_OWNERSHIP_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=ownership state=failed "
                    "stage=return_image_to_external image_role=%s width=%d height=%d",
                    image->output ? "output" : "input", image->width, image->height);
        return JNI_FALSE;
    }
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
    const bool frame0Owned = frame0 && context->images.find(frame0) != context->images.end();
    const bool frame1Owned = frame1 && context->images.find(frame1) != context->images.end();
    if (!frame0Owned || !frame1Owned) {
        logFlowOnce(context, DIAG_ANALYZE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=analyze_pair state=failed "
                    "stage=frame_registration frame0_present=%d frame0_owned=%d "
                    "frame1_present=%d frame1_owned=%d",
                    frame0 != nullptr, frame0Owned, frame1 != nullptr, frame1Owned);
        return 0;
    }
    if (frame0 == frame1 || frame0->output || frame1->output ||
        !frame0->prepared || !frame1->prepared) {
        logFlowOnce(context, DIAG_ANALYZE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=analyze_pair state=failed "
                    "stage=prepared_frame_validation frame0_prepared=%d frame0_role=%s "
                    "frame1_prepared=%d frame1_role=%s distinct_images=%d",
                    frame0->prepared != nullptr, frame0->output ? "output" : "input",
                    frame1->prepared != nullptr, frame1->output ? "output" : "input",
                    frame0 != frame1);
        return 0;
    }
    MPVFlowGPUPair *pair = nullptr;
    const MPVFlowGPUResult result = mpvflow_gpu_analyze_pair(
        context->flow, frame0->prepared, frame1->prepared, &pair);
    if (result != MPVFLOW_GPU_OK || !pair) {
        logFlowOnce(context, DIAG_ANALYZE_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=analyze_pair state=failed "
                    "stage=mpvflow_gpu_analyze_pair renderer_api=vulkan flow_compute_api=%s "
                    "status=%d frame0=%ux%u frame1=%ux%u",
                    mpvflow_gpu_get_compute_api_name(context->flow), result,
                    frame0->prepared->params.w, frame0->prepared->params.h,
                    frame1->prepared->params.w, frame1->prepared->params.h);
        return 0;
    }
    context->pairs.insert(pair);
    logFlowOnce(context, DIAG_ANALYZE_READY, ANDROID_LOG_INFO,
                "MPVFLOW_DIAGNOSTIC component=native-media3 event=analyze_pair state=ready "
                "renderer_api=vulkan flow_compute_api=%s dimensions=%ux%u frames=2 "
                "output=GPU-resident cpu_readback=no",
                mpvflow_gpu_get_compute_api_name(context->flow),
                frame0->prepared->params.w, frame0->prepared->params.h);
    return reinterpret_cast<jlong>(pair);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeSynthesize(
    JNIEnv *, jobject, jlong contextHandle, jlong pairHandle,
    jlong source0Handle, jlong source1Handle, jlong sourcePts0Us,
    jlong sourcePts1Us, jlong outputPtsUs, jfloat timestep,
    jboolean visibilityResolverEnabled, jboolean softBlendEnabled,
    jlong outputHandle) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    auto *pair = reinterpret_cast<MPVFlowGPUPair *>(pairHandle);
    auto *source0 = reinterpret_cast<SharedImage *>(source0Handle);
    auto *source1 = reinterpret_cast<SharedImage *>(source1Handle);
    auto *output = reinterpret_cast<SharedImage *>(outputHandle);
    if (!context)
        return JNI_FALSE;
    std::lock_guard<std::mutex> guard(context->mutex);
    if (!pair || !source0 || !source1 || source0 == source1 ||
        !output || !output->texture || !std::isfinite(timestep) ||
        timestep <= 0.f || timestep >= 1.f) {
        logFlowOnce(context, DIAG_SYNTH_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=synthesize state=failed "
                    "stage=arguments pair_present=%d source0_present=%d source1_present=%d "
                    "output_present=%d output_texture_present=%d "
                    "timestep=%.9g reason=invalid_handle_or_timestep",
                    pair != nullptr, source0 != nullptr, source1 != nullptr,
                    output != nullptr, output && output->texture, timestep);
        return JNI_FALSE;
    }
    if (context->pairs.find(pair) == context->pairs.end()) {
        logFlowOnce(context, DIAG_SYNTH_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=synthesize state=failed "
                    "stage=pair_registration reason=pair_not_owned_by_context timestep=%.9g",
                    timestep);
        return JNI_FALSE;
    }
    const bool source0Owned = context->images.find(source0) != context->images.end();
    const bool source1Owned = context->images.find(source1) != context->images.end();
    const bool outputOwned = context->images.find(output) != context->images.end();
    if (!source0Owned || !source1Owned || !outputOwned ||
        (source0Owned && (source0->output || !source0->texture || !source0->prepared)) ||
        (source1Owned && (source1->output || !source1->texture || !source1->prepared)) ||
        (outputOwned && !output->output)) {
        logFlowOnce(context, DIAG_SYNTH_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=synthesize state=failed "
                    "stage=image_registration source0_owned=%d source1_owned=%d output_owned=%d "
                    "source0_role=%s source1_role=%s output_role=%s",
                    source0Owned, source1Owned, outputOwned,
                    source0Owned ? (source0->output ? "output" : "input") : "unregistered",
                    source1Owned ? (source1->output ? "output" : "input") : "unregistered",
                    outputOwned ? (output->output ? "output" : "input") : "unregistered");
        return JNI_FALSE;
    }
    if (!releaseImageToVulkan(context, source0) ||
        !releaseImageToVulkan(context, source1)) {
        logFlowOnce(context, DIAG_SYNTH_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=synthesize state=failed "
                    "stage=source_image_ownership_release source0_external=%d source1_external=%d "
                    "timestep=%.9g",
                    source0->heldByExternal, source1->heldByExternal, timestep);
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
    MPVFlowGPUResult result;
    if (mpvflow_gpu_capture_is_paired(context->flow)) {
        result = mpvflow_gpu_capture_paired_sample(
            context->flow, pair, source0->texture, source1->texture, timestep,
            static_cast<double>(sourcePts0Us) / 1000000.0,
            static_cast<double>(sourcePts1Us) / 1000000.0,
            static_cast<double>(outputPtsUs) / 1000000.0,
            softBlendEnabled == JNI_TRUE,
            visibilityResolverEnabled == JNI_TRUE,
            softBlendEnabled == JNI_TRUE, softBlendEnabled == JNI_TRUE,
            &synthesized);
    } else {
        result = mpvflow_gpu_synthesize_with_policy(
            context->flow, pair, source0->texture, source1->texture, timestep,
            visibilityResolverEnabled == JNI_TRUE, softBlendEnabled == JNI_TRUE,
            softBlendEnabled == JNI_TRUE, &synthesized);
        if (result == MPVFLOW_GPU_OK && synthesized &&
            mpvflow_gpu_capture_active(context->flow)) {
            const MPVFlowGPUResult captureResult = mpvflow_gpu_capture_sample(
                context->flow, pair, source0->texture, source1->texture, synthesized,
                timestep, static_cast<double>(sourcePts0Us) / 1000000.0,
                static_cast<double>(sourcePts1Us) / 1000000.0,
                static_cast<double>(outputPtsUs) / 1000000.0,
                visibilityResolverEnabled == JNI_TRUE, softBlendEnabled == JNI_TRUE,
                softBlendEnabled == JNI_TRUE);
            if (captureResult != MPVFLOW_GPU_OK) {
                if (!context->captureFailureLogged) {
                    context->captureFailureLogged = true;
                    logFlow(ANDROID_LOG_ERROR, "MPVFLOW_DIAGNOSTIC component=capture event=sample_failed status=%d playback_preserved=1", captureResult);
                }
                mpvflow_gpu_capture_fail(context->flow, "capture_sample_failed");
            }
        }
    }
    if (result != MPVFLOW_GPU_OK || !synthesized) {
        (void) holdImageForExternal(context, output, true);
        logFlowOnce(context, DIAG_SYNTH_FAILURE, ANDROID_LOG_ERROR,
                    "MPVFLOW_DIAGNOSTIC component=native-media3 event=synthesize state=failed "
                    "stage=mpvflow_gpu_synthesize_with_policy status=%d synthesized_texture_present=%d "
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
                "renderer_api=vulkan flow_compute_api=%s timestep=%.9g output=%dx%d sample_mode=linear "
                "command_completion=%s cpu_readback=no",
                mpvflow_gpu_get_compute_api_name(context->flow),
                timestep, output->width, output->height,
                output->lastHoldUsedSyncFd ? "sync_fd_handoff" : "pl_gpu_finish_fallback");
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
        // libplacebo retains command-referenced Vulkan textures until the queue
        // completion callbacks drop their references; a device-wide finish here
        // unnecessarily serialized every retired motion pair with the CPU.
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

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeGetVulkanTimingStats(
    JNIEnv *env, jobject, jlong contextHandle) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    if (!env || !context || !context->flow)
        return nullptr;
    std::lock_guard<std::mutex> guard(context->mutex);
    MPVFlowGPUTimingStats stats{};
    mpvflow_gpu_get_timing_stats(context->flow, &stats);
    constexpr jsize headerFields = 7;
    constexpr jsize stageFields = 5;
    constexpr jsize valueCount = headerFields + MPVFLOW_GPU_STAGE_COUNT * stageFields;
    jlong values[valueCount]{};
    values[0] = 3; // JNI snapshot schema version
    values[1] = stats.compute_timestamps_supported ? 1 : 0;
    values[2] = static_cast<jlong>(stats.timestamp_period_ns * 1000.0 + 0.5); // picoseconds per tick
    values[3] = stats.timestamp_compute_and_graphics ? 1 : 0;
    values[4] = stats.compute_timestamp_valid_bits;
    values[5] = stats.compute_queue_family == UINT32_MAX
        ? -1 : static_cast<jlong>(stats.compute_queue_family);
    values[6] = stats.stage_count;
    for (uint32_t i = 0; i < stats.stage_count && i < MPVFLOW_GPU_STAGE_COUNT; i++) {
        const auto &stage = stats.stages[i];
        const jsize offset = headerFields + static_cast<jsize>(i) * stageFields;
        values[offset + 0] = stage.timer_created ? 1 : 0;
        values[offset + 1] = static_cast<jlong>(stage.dispatches);
        values[offset + 2] = static_cast<jlong>(stage.completed_samples);
        values[offset + 3] = static_cast<jlong>(stage.total_ns);
        values[offset + 4] = static_cast<jlong>(stage.maximum_ns);
    }
    jlongArray result = env->NewLongArray(valueCount);
    if (!result)
        return nullptr;
    env->SetLongArrayRegion(result, 0, valueCount, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeDestroyContext(
    JNIEnv *, jobject, jlong contextHandle) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    if (!context)
        return;
    {
        std::lock_guard<std::mutex> guard(context->mutex);
        drainPendingInterop(context);
        if (context->flow && mpvflow_gpu_capture_active(context->flow)) { mpvflow_gpu_capture_stop(context->flow); context->captureSessionId.clear(); }
        for (MPVFlowGPUPair *pair : context->pairs)
            mpvflow_gpu_pair_release(context->flow, pair);
        context->pairs.clear();
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
