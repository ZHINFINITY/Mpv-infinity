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
#include "mpvflow/mpvflow_gpu.h"

#include <algorithm>
#include <cstdint>
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
    pl_vulkan vulkan = nullptr;
    MPVFlowGPUContext *flow = nullptr;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getBufferProperties = nullptr;
    std::unordered_set<SharedImage *> images;
    std::unordered_set<MPVFlowGPUPair *> pairs;
    std::mutex mutex;
};

void logError(const char *message, int code = 0) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s (code=%d)", message, code);
}

bool holdImageForExternal(FlowContext *context, SharedImage *image) {
    if (!context || !image || !image->texture)
        return false;
    if (image->heldByExternal)
        return true;

    VkSemaphoreCreateInfo semaphoreInfo{};
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkSemaphore semaphore = VK_NULL_HANDLE;
    if (vkCreateSemaphore(context->vulkan->device, &semaphoreInfo, nullptr, &semaphore) != VK_SUCCESS)
        return false;

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

    const VkResult result = vkGetPhysicalDeviceImageFormatProperties2(
        context->vulkan->phys_device, &imageInfo, &imageProperties);
    if (result != VK_SUCCESS)
        return false;
    const auto &memory = externalProperties.externalMemoryProperties;
    if (!(memory.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT))
        return false;
    return (bufferDesc.usage & ahbUsage.androidHardwareBufferUsage) ==
           ahbUsage.androidHardwareBufferUsage;
}

SharedImage *createSharedImage(FlowContext *context, JNIEnv *env, jobject javaBuffer,
                               int width, int height, bool output) {
    if (!context || !env || !javaBuffer || width <= 0 || height <= 0)
        return nullptr;
    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, javaBuffer);
    if (!buffer)
        return nullptr;
    AHardwareBuffer_acquire(buffer);

    AHardwareBuffer_Desc bufferDesc{};
    AHardwareBuffer_describe(buffer, &bufferDesc);
    if (bufferDesc.width != static_cast<uint32_t>(width) ||
        bufferDesc.height != static_cast<uint32_t>(height) ||
        bufferDesc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
        AHardwareBuffer_release(buffer);
        return nullptr;
    }

    const VkImageUsageFlags usage = output
        ? (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
        : VK_IMAGE_USAGE_SAMPLED_BIT;
    if (!queryAhbImageSupport(context, usage, bufferDesc)) {
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
        logError("vkCreateImage for AHardwareBuffer failed", result);
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
        logError("AHardwareBuffer Vulkan format query failed", result);
        destroyImage(context, shared);
        return nullptr;
    }

    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(context->vulkan->device, shared->image, &requirements);
    const uint32_t compatibleBits = requirements.memoryTypeBits & bufferProperties.memoryTypeBits;
    if (!compatibleBits || bufferProperties.allocationSize < requirements.size) {
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
    if (result == VK_SUCCESS)
        result = vkBindImageMemory(context->vulkan->device, shared->image, shared->memory, 0);
    if (result != VK_SUCCESS) {
        logError("AHardwareBuffer Vulkan memory import failed", result);
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
        logError("libplacebo could not wrap the shared AHardwareBuffer image");
        destroyImage(context, shared);
        return nullptr;
    }

    context->images.insert(shared);
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
    pl_vulkan vulkan = pl_vulkan_create(nullptr, &params);
    if (!vulkan) {
        logError("libplacebo Vulkan device creation failed");
        return 0;
    }
    auto *context = new FlowContext();
    context->vulkan = vulkan;
    context->getBufferProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(vulkan->device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (!context->getBufferProperties) {
        logError("Vulkan AHardwareBuffer import extension is unavailable");
        pl_vulkan_destroy(&context->vulkan);
        delete context;
        return 0;
    }
    MPVFlowGPUConfig config{};
    config.max_dimension = 640;
    config.search_radius = 8;
    MPVFlowGPUResult status = MPVFLOW_GPU_INVALID;
    context->flow = mpvflow_gpu_create(vulkan->gpu, &config, &status);
    if (!context->flow) {
        logError("shared MPV Flow Vulkan passes could not be created", status);
        pl_vulkan_destroy(&context->vulkan);
        delete context;
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "backend=vulkan compute=libplacebo flows=shared");
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeCreateFrameImage(
    JNIEnv *env, jobject, jlong contextHandle, jobject hardwareBuffer,
    jint width, jint height, jboolean output) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    if (!context)
        return 0;
    std::lock_guard<std::mutex> guard(context->mutex);
    SharedImage *image = createSharedImage(context, env, hardwareBuffer,
                                           width, height, output == JNI_TRUE);
    return reinterpret_cast<jlong>(image);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeCreateEglTexture(
    JNIEnv *env, jobject, jobject hardwareBuffer) {
    if (!env || !hardwareBuffer || eglGetCurrentDisplay() == EGL_NO_DISPLAY ||
        eglGetCurrentContext() == EGL_NO_CONTEXT)
        return 0;
    auto getClientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
        eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    auto createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
        eglGetProcAddress("eglCreateImageKHR"));
    auto destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
        eglGetProcAddress("eglDestroyImageKHR"));
    auto imageTarget = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
        eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!getClientBuffer || !createImage || !destroyImage || !imageTarget)
        return 0;
    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!buffer)
        return 0;
    EGLClientBuffer clientBuffer = getClientBuffer(buffer);
    const EGLint attributes[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR image = createImage(eglGetCurrentDisplay(), EGL_NO_CONTEXT,
                                    EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attributes);
    if (image == EGL_NO_IMAGE_KHR)
        return 0;
    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    imageTarget(GL_TEXTURE_2D, reinterpret_cast<GLeglImageOES>(image));
    const EGLBoolean destroyed = destroyImage(eglGetCurrentDisplay(), image);
    glBindTexture(GL_TEXTURE_2D, 0);
    if (!destroyed || glGetError() != GL_NO_ERROR) {
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
    if (!context || !image || !image->texture)
        return JNI_FALSE;
    std::lock_guard<std::mutex> guard(context->mutex);
    if (image->prepared)
        pl_tex_destroy(context->vulkan->gpu, &image->prepared);
    if (!releaseImageToVulkan(context, image))
        return JNI_FALSE;
    const MPVFlowGPUResult status = mpvflow_gpu_prepare_input(
        context->flow, image->texture, &image->prepared);
    if (status != MPVFLOW_GPU_OK) {
        pl_gpu_finish(context->vulkan->gpu);
        (void) holdImageForExternal(context, image);
        logError("Vulkan frame preparation failed", status);
        return JNI_FALSE;
    }
    if (!holdImageForExternal(context, image)) {
        pl_tex_destroy(context->vulkan->gpu, &image->prepared);
        logError("Could not return staged frame to the shared-image owner");
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
    if (!context || !frame0 || !frame1 || !frame0->prepared || !frame1->prepared)
        return 0;
    std::lock_guard<std::mutex> guard(context->mutex);
    MPVFlowGPUPair *pair = nullptr;
    const MPVFlowGPUResult result = mpvflow_gpu_analyze_pair(
        context->flow, frame0->prepared, frame1->prepared, &pair);
    if (result != MPVFLOW_GPU_OK || !pair) {
        logError("Vulkan motion-pair analysis failed", result);
        return 0;
    }
    context->pairs.insert(pair);
    return reinterpret_cast<jlong>(pair);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeSynthesize(
    JNIEnv *, jobject, jlong contextHandle, jlong pairHandle,
    jfloat timestep, jlong outputHandle) {
    auto *context = reinterpret_cast<FlowContext *>(contextHandle);
    auto *pair = reinterpret_cast<MPVFlowGPUPair *>(pairHandle);
    auto *output = reinterpret_cast<SharedImage *>(outputHandle);
    if (!context || !pair || !output || !output->texture || timestep <= 0.f || timestep >= 1.f)
        return JNI_FALSE;
    std::lock_guard<std::mutex> guard(context->mutex);
    if (context->pairs.find(pair) == context->pairs.end() ||
        !releaseImageToVulkan(context, output))
        return JNI_FALSE;
    pl_tex synthesized = nullptr;
    const MPVFlowGPUResult result = mpvflow_gpu_synthesize(
        context->flow, pair, timestep, &synthesized);
    if (result != MPVFLOW_GPU_OK || !synthesized) {
        (void) holdImageForExternal(context, output);
        logError("Vulkan frame synthesis failed", result);
        return JNI_FALSE;
    }
    struct pl_tex_blit_params blitParams{};
    blitParams.src = synthesized;
    blitParams.dst = output->texture;
    blitParams.sample_mode = PL_TEX_SAMPLE_LINEAR;
    pl_tex_blit(context->vulkan->gpu, &blitParams);
    if (!holdImageForExternal(context, output)) {
        logError("Vulkan output did not return to the display owner");
        return JNI_FALSE;
    }
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
    }
    delete context;
}
