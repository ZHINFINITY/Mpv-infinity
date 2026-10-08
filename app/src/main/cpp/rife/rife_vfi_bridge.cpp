/* SPDX-License-Identifier: AGPL-3.0-or-later */

#include "rife_vfi.h"
#include "rife_vfi_ahb_policy.h"
#include "rife_vfi_output_layout.h"
#include "rife_fp16_policy.h"

#include "gpu.h"
#include "allocator.h"
#include "command.h"
#include "pipeline.h"
#include "rife_vfi_pack_rgb.comp.hex.h"
#include "rife_vfi_rgba_output.comp.hex.h"
#include "rife_vfi_rgba_output_fp32.comp.hex.h"
#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cstring>
#include <cstdio>
#include <mutex>
#include <memory>
#include <new>
#include <string>
#include <unordered_map>
#include <vector>

#include "rife.h"

#if defined(__ANDROID__) && __ANDROID_API__ >= 26
#include <android/hardware_buffer.h>

struct RifeVfiAhbImportCacheEntry {
    AHardwareBuffer *hardware_buffer = nullptr;
    uint32_t width = 0;
    uint32_t height = 0;
    int output_width = 0;
    int output_height = 0;
    uint64_t last_used = 0;
    std::unique_ptr<ncnn::VkAndroidHardwareBufferImageAllocator> allocator;
    ncnn::VkImageMat source;
    std::unique_ptr<ncnn::ImportAndroidHardwareBufferPipeline> pipeline;

    ~RifeVfiAhbImportCacheEntry()
    {
        pipeline.reset();
        source = ncnn::VkImageMat();
        allocator.reset();
        if (hardware_buffer)
            AHardwareBuffer_release(hardware_buffer);
    }
};
#endif

struct RifeVfiEngine {
    RIFE *network = nullptr;
    ncnn::VulkanDevice *vkdev = nullptr;
    ncnn::Pipeline *pack_rgb_pipeline = nullptr;
    ncnn::Pipeline *output_rgba_pipeline = nullptr;
    ncnn::Pipeline *output_rgba_fp32_pipeline = nullptr;
    bool fp16_arithmetic = false;
    std::atomic<bool> ahb_input_import_succeeded{false};
    std::atomic<bool> ahb_output_slot_created{false};
    std::atomic<unsigned int> references{1};
    std::mutex process_mutex;
#if defined(__ANDROID__) && __ANDROID_API__ >= 26
    // mpv's AImageReader is created with maxImages=3; mirror that bound so
    // cached AHB refs cannot grow without limit across seeks/resolution changes.
    std::vector<std::unique_ptr<RifeVfiAhbImportCacheEntry>> ahb_import_cache;
    uint64_t ahb_import_cache_clock = 0;
#endif
};

struct RifeVfiGpuFrame {
    ncnn::VkMat image;
    RifeVfiEngine *engine = nullptr;
    ncnn::VulkanDevice *vkdev = nullptr;
    ncnn::VkAllocator *blob_allocator = nullptr;

    ~RifeVfiGpuFrame()
    {
        image = ncnn::VkMat();
        if (blob_allocator && vkdev)
            vkdev->reclaim_blob_allocator(blob_allocator);
    }
};

struct RifeVfiOutput {
#if defined(__ANDROID__) && __ANDROID_API__ >= 26
    RifeVfiEngine *engine = nullptr;
    AHardwareBuffer *hardware_buffer = nullptr;
    ncnn::VkAllocator *wrapper_allocator = nullptr;
    ncnn::VkImageMemory image_memory{};
    ncnn::VkImageMat image;
    VkCommandPool transition_pool = VK_NULL_HANDLE;
    VkCommandBuffer transition_command = VK_NULL_HANDLE;
    int width = 0;
    int height = 0;
    bool initialized = false;
    bool foreign_owned = false;
    bool initial_acquire_done = false;
#endif
};

#if defined(__ANDROID__) && __ANDROID_API__ >= 26
namespace {
struct AHardwareBufferRef {
    explicit AHardwareBufferRef(AHardwareBuffer *buffer_) : buffer(buffer_)
    {
        if (buffer)
            AHardwareBuffer_acquire(buffer);
    }
    ~AHardwareBufferRef()
    {
        if (buffer)
            AHardwareBuffer_release(buffer);
    }
    AHardwareBuffer *buffer;
};

struct RifeVfiStagingAllocatorGuard {
    explicit RifeVfiStagingAllocatorGuard(ncnn::VulkanDevice *device_)
        : device(device_), allocator(device ? device->acquire_staging_allocator() : nullptr)
    {
    }
    ~RifeVfiStagingAllocatorGuard()
    {
        if (allocator && device)
            device->reclaim_staging_allocator(allocator);
    }
    ncnn::VulkanDevice *device;
    ncnn::VkAllocator *allocator;
};
}
#endif

namespace {
std::mutex gpu_mutex;
std::mutex engine_registry_mutex;
std::unordered_map<std::string, RifeVfiEngine *> engine_registry;
int gpu_users = 0;

void set_error(char *buffer, size_t size, const char *message)
{
    if (buffer && size > 0)
        std::snprintf(buffer, size, "%s", message ? message : "unknown RIFE error");
}

void set_errorf(char *buffer, size_t size, const char *format, ...)
{
    if (!buffer || size == 0)
        return;
    va_list args;
    va_start(args, format);
    std::vsnprintf(buffer, size, format, args);
    va_end(args);
}

bool create_gpu_pipeline(RifeVfiEngine *engine, const char *shader_data,
                         int shader_size, ncnn::Pipeline **output,
                         bool use_int8_storage = true)
{
    if (!engine || !engine->vkdev || !shader_data || shader_size <= 0 || !output)
        return false;

    ncnn::Option opt;
    opt.use_vulkan_compute = true;
    opt.use_fp16_packed = false;
    opt.use_fp16_storage = false;
    opt.use_int8_storage = use_int8_storage;

    std::vector<uint32_t> spirv;
    if (ncnn::compile_spirv_module(shader_data, shader_size, opt, spirv) != 0 ||
        spirv.empty())
        return false;

    std::unique_ptr<ncnn::Pipeline> pipeline(
        new (std::nothrow) ncnn::Pipeline(engine->vkdev));
    if (!pipeline)
        return false;
    pipeline->set_optimal_local_size_xyz(8, 8, 1);
    const std::vector<ncnn::vk_specialization_type> specializations;
    if (pipeline->create(spirv.data(), spirv.size() * sizeof(uint32_t),
                         specializations) != 0)
        return false;

    *output = pipeline.release();
    return true;
}

void ensure_gpu_conversion_pipelines(RifeVfiEngine *engine)
{
    if (!engine || !engine->vkdev)
        return;
    if (!engine->pack_rgb_pipeline)
        create_gpu_pipeline(engine, rife_vfi_pack_rgb_comp_data,
                            sizeof(rife_vfi_pack_rgb_comp_data),
                            &engine->pack_rgb_pipeline);
    if (!engine->output_rgba_pipeline)
        create_gpu_pipeline(engine, rife_vfi_rgba_output_comp_data,
                            sizeof(rife_vfi_rgba_output_comp_data),
                            &engine->output_rgba_pipeline);
    if (!engine->output_rgba_fp32_pipeline)
        create_gpu_pipeline(engine, rife_vfi_rgba_output_fp32_comp_data,
                            sizeof(rife_vfi_rgba_output_fp32_comp_data),
                            &engine->output_rgba_fp32_pipeline, false);
}

void release_gpu_instance()
{
    std::lock_guard<std::mutex> lock(gpu_mutex);
    if (gpu_users > 0 && --gpu_users == 0)
        ncnn::destroy_gpu_instance();
}

void release_engine_reference(RifeVfiEngine *engine)
{
    if (!engine)
        return;
    bool destroy = false;
    {
        std::lock_guard<std::mutex> lock(engine_registry_mutex);
        if (engine->references.fetch_sub(1, std::memory_order_acq_rel) == 1) {
            for (auto it = engine_registry.begin(); it != engine_registry.end(); ++it) {
                if (it->second == engine) {
                    engine_registry.erase(it);
                    break;
                }
            }
            destroy = true;
        }
    }
    if (destroy) {
        delete engine->pack_rgb_pipeline;
        delete engine->output_rgba_pipeline;
        delete engine->output_rgba_fp32_pipeline;
        delete engine->network;
        delete engine;
        release_gpu_instance();
    }
}

#if defined(__ANDROID__) && __ANDROID_API__ >= 26 && \
    defined(VK_ANDROID_external_memory_android_hardware_buffer) && \
    defined(VK_EXT_queue_family_foreign)
bool record_output_transition(RifeVfiOutput *output,
                              uint32_t src_family, uint32_t dst_family,
                              VkAccessFlags src_access, VkAccessFlags dst_access,
                              VkImageLayout old_layout, VkImageLayout new_layout,
                              VkPipelineStageFlags src_stage,
                              VkPipelineStageFlags dst_stage)
{
    if (!output || !output->engine || !output->engine->vkdev ||
        !output->transition_command || !output->image_memory.image)
        return false;

    VkResult result = vkResetCommandBuffer(output->transition_command, 0);
    if (result != VK_SUCCESS)
        return false;

    VkCommandBufferBeginInfo begin_info{};
    begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(output->transition_command, &begin_info) != VK_SUCCESS)
        return false;

    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = src_access;
    barrier.dstAccessMask = dst_access;
    barrier.oldLayout = old_layout;
    barrier.newLayout = new_layout;
    barrier.srcQueueFamilyIndex = src_family;
    barrier.dstQueueFamilyIndex = dst_family;
    barrier.image = output->image_memory.image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(output->transition_command, src_stage, dst_stage, 0,
                         0, nullptr, 0, nullptr, 1, &barrier);
    if (vkEndCommandBuffer(output->transition_command) != VK_SUCCESS)
        return false;

    VkSubmitInfo submit_info{};
    submit_info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit_info.commandBufferCount = 1;
    submit_info.pCommandBuffers = &output->transition_command;
    ncnn::VulkanDevice *vkdev = output->engine->vkdev;
    const uint32_t family = vkdev->info.compute_queue_family_index();
    // NCNN exposes a finite pool of queues. Never pin one per output slot:
    // RIFE inference must be able to acquire a queue from the same pool.
    VkQueue queue = vkdev->acquire_queue(family);
    if (!queue)
        return false;
    if (vkQueueSubmit(queue, 1, &submit_info, VK_NULL_HANDLE) != VK_SUCCESS) {
        vkdev->reclaim_queue(family, queue);
        return false;
    }
    // Do not hand the image to EGL until the ownership barrier completes.
    VkResult wait_result = vkQueueWaitIdle(queue);
    vkdev->reclaim_queue(family, queue);
    return wait_result == VK_SUCCESS;
}

void destroy_output_resources(RifeVfiOutput *output)
{
    if (!output || !output->engine || !output->engine->vkdev)
        return;
    ncnn::VulkanDevice *vkdev = output->engine->vkdev;
    output->image = ncnn::VkImageMat();
    if (output->transition_pool)
        vkDestroyCommandPool(vkdev->vkdevice(), output->transition_pool, nullptr);
    output->transition_pool = VK_NULL_HANDLE;
    output->transition_command = VK_NULL_HANDLE;
    if (output->image_memory.imageview)
        vkDestroyImageView(vkdev->vkdevice(), output->image_memory.imageview, nullptr);
    if (output->image_memory.image)
        vkDestroyImage(vkdev->vkdevice(), output->image_memory.image, nullptr);
    if (output->image_memory.memory)
        vkFreeMemory(vkdev->vkdevice(), output->image_memory.memory, nullptr);
    output->image_memory = {};
    if (output->wrapper_allocator)
        vkdev->reclaim_blob_allocator(output->wrapper_allocator);
    output->wrapper_allocator = nullptr;
    if (output->hardware_buffer)
        AHardwareBuffer_release(output->hardware_buffer);
    output->hardware_buffer = nullptr;
}

bool initialize_output_slot(RifeVfiOutput *output, int width, int height,
                            char *error, size_t error_size)
{
    if (!output || !output->engine || !output->engine->vkdev || width <= 0 ||
        height <= 0 || width > 8192 || height > 8192 ||
        !output->engine->output_rgba_pipeline) {
        set_errorf(error, error_size,
                   "stage=output_precondition_failed width=%d height=%d engine=%d vk_device=%d output_shader=%d",
                   width, height, output && output->engine,
                   output && output->engine && output->engine->vkdev,
                   output && output->engine && output->engine->output_rgba_pipeline);
        return false;
    }

    RifeVfiEngine *engine = output->engine;
    ncnn::VulkanDevice *vkdev = engine->vkdev;
    if (vkdev->info.support_VK_ANDROID_external_memory_android_hardware_buffer() <= 0 ||
        vkdev->info.support_VK_EXT_queue_family_foreign() <= 0 ||
        ncnn::support_VK_KHR_get_physical_device_properties2 <= 0 ||
        !vkdev->vkGetAndroidHardwareBufferPropertiesANDROID ||
        !ncnn::vkGetPhysicalDeviceImageFormatProperties2KHR ||
        !vkdev->vkBindImageMemory2KHR) {
        set_errorf(error, error_size,
                   "stage=vulkan_output_extensions_unavailable ahb=%d foreign=%d properties2=%d query_proc=%d bind_proc=%d",
                   vkdev->info.support_VK_ANDROID_external_memory_android_hardware_buffer() > 0,
                   vkdev->info.support_VK_EXT_queue_family_foreign() > 0,
                   ncnn::support_VK_KHR_get_physical_device_properties2 > 0,
                   ncnn::vkGetPhysicalDeviceImageFormatProperties2KHR != nullptr,
                   vkdev->vkBindImageMemory2KHR != nullptr);
        return false;
    }

    constexpr VkImageUsageFlags image_usage =
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    VkAndroidHardwareBufferUsageANDROID ahb_usage{};
    ahb_usage.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_USAGE_ANDROID;
    VkExternalImageFormatProperties external_properties{};
    external_properties.sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES;
    external_properties.pNext = &ahb_usage;
    VkImageFormatProperties2 image_properties{};
    image_properties.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2;
    image_properties.pNext = &external_properties;
    VkPhysicalDeviceExternalImageFormatInfo external_info{};
    external_info.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO;
    external_info.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkPhysicalDeviceImageFormatInfo2 image_info{};
    image_info.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2;
    image_info.pNext = &external_info;
    image_info.format = VK_FORMAT_R8G8B8A8_UNORM;
    image_info.type = VK_IMAGE_TYPE_2D;
    image_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    image_info.usage = image_usage;
    image_info.flags = 0;
    // The API-26 Vulkan stub does not link the Vulkan 1.1 core symbol. NCNN
    // resolves the KHR alias through vkGetInstanceProcAddr when supported.
    VkResult result = ncnn::vkGetPhysicalDeviceImageFormatProperties2KHR(
        vkdev->info.physical_device(), &image_info, &image_properties);
    if (result != VK_SUCCESS) {
        set_errorf(error, error_size,
                   "stage=external_image_format_query_failed vk_result=%d format=%d image_usage=0x%08x",
                   (int)result, (int)image_info.format, (unsigned int)image_usage);
        return false;
    }
    if (!(external_properties.externalMemoryProperties.externalMemoryFeatures &
          VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT)) {
        set_errorf(error, error_size,
                   "stage=external_image_not_importable external_features=0x%08x format=%d image_usage=0x%08x",
                   (unsigned int)external_properties.externalMemoryProperties.externalMemoryFeatures,
                   (int)image_info.format, (unsigned int)image_usage);
        return false;
    }
    if (!ahb_usage.androidHardwareBufferUsage) {
        set_errorf(error, error_size,
                   "stage=android_usage_flags_missing format=%d image_usage=0x%08x",
                   (int)image_info.format, (unsigned int)image_usage);
        return false;
    }
    if ((uint32_t)width > image_properties.imageFormatProperties.maxExtent.width ||
        (uint32_t)height > image_properties.imageFormatProperties.maxExtent.height) {
        set_errorf(error, error_size,
                   "stage=output_extent_exceeded requested=%dx%d max=%ux%u",
                   width, height,
                   image_properties.imageFormatProperties.maxExtent.width,
                   image_properties.imageFormatProperties.maxExtent.height);
        return false;
    }

    AHardwareBuffer_Desc desc{};
    desc.width = (uint32_t)width;
    desc.height = (uint32_t)height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = ahb_usage.androidHardwareBufferUsage |
                 AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    int ahb_result = AHardwareBuffer_allocate(&desc, &output->hardware_buffer);
    if (ahb_result != 0 || !output->hardware_buffer) {
        set_errorf(error, error_size,
                   "stage=ahb_allocate_failed ahb_result=%d dimensions=%ux%u format=%d usage=0x%016llx queried_usage=0x%016llx",
                   ahb_result, desc.width, desc.height, desc.format,
                   (unsigned long long)desc.usage,
                   (unsigned long long)ahb_usage.androidHardwareBufferUsage);
        return false;
    }

    AHardwareBuffer_Desc allocated_desc{};
    AHardwareBuffer_describe(output->hardware_buffer, &allocated_desc);
    if (allocated_desc.width != desc.width || allocated_desc.height != desc.height ||
        allocated_desc.format != desc.format ||
        (allocated_desc.usage & desc.usage) != desc.usage) {
        set_errorf(error, error_size,
                   "stage=ahb_descriptor_mismatch requested=%ux%ux%u format=%d usage=0x%016llx actual=%ux%ux%u format=%d usage=0x%016llx",
                   desc.width, desc.height, desc.layers, desc.format,
                   (unsigned long long)desc.usage,
                   allocated_desc.width, allocated_desc.height, allocated_desc.layers,
                   allocated_desc.format, (unsigned long long)allocated_desc.usage);
        return false;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID format_properties{};
    format_properties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID buffer_properties{};
    buffer_properties.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    buffer_properties.pNext = &format_properties;
    result = vkdev->vkGetAndroidHardwareBufferPropertiesANDROID(
        vkdev->vkdevice(), output->hardware_buffer, &buffer_properties);
    // Vulkan requires externalFormat to be a nonzero opaque token even when
    // format has a VkFormat equivalent. This RGBA8 path uses the concrete
    // VkFormat for ordinary format queries and image creation.
    if (result != VK_SUCCESS ||
        format_properties.format != VK_FORMAT_R8G8B8A8_UNORM ||
        !buffer_properties.allocationSize || !buffer_properties.memoryTypeBits) {
        set_errorf(error, error_size,
                   "stage=ahb_properties_import_failed vk_result=%d format=%d external_format=0x%016llx allocation_size=%llu memory_type_bits=0x%08x",
                   (int)result, (int)format_properties.format,
                   (unsigned long long)format_properties.externalFormat,
                   (unsigned long long)buffer_properties.allocationSize,
                   (unsigned int)buffer_properties.memoryTypeBits);
        return false;
    }

    VkExternalMemoryImageCreateInfo external_image{};
    external_image.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    external_image.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo image_create{};
    image_create.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    image_create.pNext = &external_image;
    image_create.imageType = VK_IMAGE_TYPE_2D;
    image_create.format = format_properties.format;
    image_create.extent = {desc.width, desc.height, 1};
    image_create.mipLevels = 1;
    image_create.arrayLayers = 1;
    image_create.samples = VK_SAMPLE_COUNT_1_BIT;
    image_create.tiling = VK_IMAGE_TILING_OPTIMAL;
    image_create.usage = image_usage;
    image_create.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    image_create.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    result = vkCreateImage(vkdev->vkdevice(), &image_create, nullptr,
                           &output->image_memory.image);
    if (result != VK_SUCCESS) {
        set_errorf(error, error_size,
                   "stage=vk_create_image_failed vk_result=%d format=%d dimensions=%ux%u image_usage=0x%08x",
                   (int)result, (int)image_create.format,
                   image_create.extent.width, image_create.extent.height,
                   (unsigned int)image_create.usage);
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID import_info{};
    import_info.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import_info.buffer = output->hardware_buffer;
    VkMemoryDedicatedAllocateInfo dedicated_info{};
    dedicated_info.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated_info.pNext = &import_info;
    dedicated_info.image = output->image_memory.image;
    VkMemoryAllocateInfo memory_info{};
    memory_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    memory_info.pNext = &dedicated_info;
    memory_info.allocationSize = buffer_properties.allocationSize;
    memory_info.memoryTypeIndex = vkdev->find_memory_index(
        buffer_properties.memoryTypeBits, 0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
    if (memory_info.memoryTypeIndex == (uint32_t)-1) {
        set_errorf(error, error_size,
                   "stage=memory_type_unavailable memory_type_bits=0x%08x allocation_size=%llu",
                   (unsigned int)buffer_properties.memoryTypeBits,
                   (unsigned long long)buffer_properties.allocationSize);
        return false;
    }
    result = vkAllocateMemory(vkdev->vkdevice(), &memory_info, nullptr,
                              &output->image_memory.memory);
    if (result != VK_SUCCESS) {
        set_errorf(error, error_size,
                   "stage=vk_allocate_imported_memory_failed vk_result=%d memory_type_index=%u memory_type_bits=0x%08x allocation_size=%llu",
                   (int)result, memory_info.memoryTypeIndex,
                   (unsigned int)buffer_properties.memoryTypeBits,
                   (unsigned long long)buffer_properties.allocationSize);
        return false;
    }
    VkBindImageMemoryInfo bind_info{};
    bind_info.sType = VK_STRUCTURE_TYPE_BIND_IMAGE_MEMORY_INFO;
    bind_info.image = output->image_memory.image;
    bind_info.memory = output->image_memory.memory;
    result = vkdev->vkBindImageMemory2KHR(vkdev->vkdevice(), 1, &bind_info);
    if (result != VK_SUCCESS) {
        set_errorf(error, error_size,
                   "stage=vk_bind_imported_memory_failed vk_result=%d memory_type_index=%u",
                   (int)result, memory_info.memoryTypeIndex);
        return false;
    }

    VkImageViewCreateInfo view_info{};
    view_info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    view_info.image = output->image_memory.image;
    view_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
    view_info.format = format_properties.format;
    view_info.components = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                            VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};
    view_info.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    result = vkCreateImageView(vkdev->vkdevice(), &view_info, nullptr,
                               &output->image_memory.imageview);
    if (result != VK_SUCCESS) {
        set_errorf(error, error_size,
                   "stage=vk_create_image_view_failed vk_result=%d format=%d",
                   (int)result, (int)view_info.format);
        return false;
    }

    output->image_memory.width = width;
    output->image_memory.height = height;
    output->image_memory.depth = 1;
    output->image_memory.format = format_properties.format;
    output->image_memory.mapped_ptr = nullptr;
    output->image_memory.bind_offset = 0;
    output->image_memory.bind_capacity = (size_t)buffer_properties.allocationSize;
    output->image_memory.access_flags = 0;
    output->image_memory.image_layout = VK_IMAGE_LAYOUT_UNDEFINED;
    output->image_memory.stage_flags = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    output->image_memory.command_refcount = 0;
    // VkImageMat's external-data constructor does not hold a refcounted wrapper;
    // NCNN VkCompute deletes VkImageMemory when refcount and the last command
    // reference are both zero. Keep this embedded slot owned until our cleanup.
    output->image_memory.refcount = 1;
    output->wrapper_allocator = vkdev->acquire_blob_allocator();
    if (!output->wrapper_allocator) {
        set_error(error, error_size, "stage=wrapper_allocator_unavailable");
        return false;
    }
    output->image = ncnn::VkImageMat(width, height, &output->image_memory, 4u,
                                     output->wrapper_allocator);
    if (output->image.empty()) {
        set_error(error, error_size, "stage=output_image_wrapper_empty");
        return false;
    }

    const uint32_t family = vkdev->info.compute_queue_family_index();
    if (!vkdev->info.compute_queue_count()) {
        set_errorf(error, error_size,
                   "stage=compute_queue_unavailable queue_family=%u", family);
        return false;
    }
    VkCommandPoolCreateInfo pool_info{};
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queueFamilyIndex = family;
    result = vkCreateCommandPool(vkdev->vkdevice(), &pool_info, nullptr,
                                 &output->transition_pool);
    if (result != VK_SUCCESS) {
        set_errorf(error, error_size,
                   "stage=vk_create_transition_pool_failed vk_result=%d queue_family=%u",
                   (int)result, family);
        return false;
    }
    VkCommandBufferAllocateInfo command_info{};
    command_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    command_info.commandPool = output->transition_pool;
    command_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    command_info.commandBufferCount = 1;
    result = vkAllocateCommandBuffers(vkdev->vkdevice(), &command_info,
                                      &output->transition_command);
    if (result != VK_SUCCESS) {
        set_errorf(error, error_size,
                   "stage=vk_allocate_transition_command_failed vk_result=%d",
                   (int)result);
        return false;
    }
    output->width = width;
    output->height = height;
    output->initialized = false;
    output->foreign_owned = false;
    output->initial_acquire_done = false;
    set_error(error, error_size, "");
    return true;
}
#endif
}

extern "C" RifeVfiEngine *rife_vfi_create(const char *model_dir,
                                            char *error,
                                            size_t error_size)
{
    if (!model_dir || !model_dir[0]) {
        set_error(error, error_size, "model directory is empty");
        return nullptr;
    }

    std::string model_key(model_dir);
    while (model_key.size() > 1 && model_key.back() == '/')
        model_key.pop_back();
    std::unique_lock<std::mutex> registry_lock(engine_registry_mutex);
    auto cached = engine_registry.find(model_key);
    if (cached != engine_registry.end()) {
        cached->second->references.fetch_add(1, std::memory_order_relaxed);
        set_error(error, error_size, "");
        return cached->second;
    }

    int gpu_id = -1;
    {
        std::lock_guard<std::mutex> lock(gpu_mutex);
        if (gpu_users == 0)
            ncnn::create_gpu_instance();
        if (ncnn::get_gpu_count() <= 0) {
            if (gpu_users == 0)
                ncnn::destroy_gpu_instance();
            set_error(error, error_size, "ncnn did not find a Vulkan device");
            return nullptr;
        }
        gpu_id = ncnn::get_default_gpu_index();
        if (gpu_id < 0) {
            if (gpu_users == 0)
                ncnn::destroy_gpu_instance();
            set_error(error, error_size, "ncnn could not select a Vulkan device");
            return nullptr;
        }
        ++gpu_users;
    }

    RifeVfiEngine *engine = new (std::nothrow) RifeVfiEngine();
    if (!engine) {
        release_gpu_instance();
        set_error(error, error_size, "unable to allocate RIFE engine");
        return nullptr;
    }

    const ncnn::GpuInfo &gpu_info = ncnn::get_gpu_info(gpu_id);
    engine->vkdev = ncnn::get_gpu_device(gpu_id);
    if (!engine->vkdev) {
        delete engine;
        release_gpu_instance();
        set_error(error, error_size, "ncnn could not create its Vulkan device");
        return nullptr;
    }
    engine->fp16_arithmetic = rife_fp16_arithmetic_is_usable(
        true, gpu_info.support_fp16_arithmetic(),
        gpu_info.bug_implicit_fp16_arithmetic());
    engine->network = new (std::nothrow) RIFE(gpu_id, false, false, true, 1, false, true);
    if (!engine->network) {
        delete engine;
        release_gpu_instance();
        set_error(error, error_size, "unable to allocate RIFE network");
        return nullptr;
    }

    std::string directory(model_key);
    if (directory.back() != '/')
        directory.push_back('/');
    if (engine->network->load(directory) != 0) {
        delete engine->network;
        delete engine;
        release_gpu_instance();
        set_error(error, error_size, "RIFE-v4.6 model loading failed");
        return nullptr;
    }

#if defined(__ANDROID__) && __ANDROID_API__ >= 26
    // Optional resident-path kernels are initialized once. Failure leaves the
    // existing CPU-readable vf_rife path usable and keeps the GPU path closed.
    ensure_gpu_conversion_pipelines(engine);
#endif

    engine_registry.emplace(model_key, engine);
    set_error(error, error_size, "");
    return engine;
}

extern "C" void rife_vfi_destroy(RifeVfiEngine *engine)
{
    if (!engine)
        return;
    release_engine_reference(engine);
}

extern "C" int rife_vfi_uses_fp16_arithmetic(const RifeVfiEngine *engine)
{
    return engine && engine->fp16_arithmetic;
}

extern "C" int rife_vfi_get_gpu_capabilities(
    const RifeVfiEngine *engine, RifeVfiGpuCapabilities *capabilities)
{
    if (!capabilities)
        return -1;
    *capabilities = {};
    if (!engine || !engine->vkdev)
        return -1;

    capabilities->vulkan_device_ready = 1;
#if defined(__ANDROID__) && __ANDROID_API__ >= 26
    capabilities->android_ahb_import_extension =
        engine->vkdev->info.support_VK_ANDROID_external_memory_android_hardware_buffer() > 0;
    capabilities->foreign_queue_family_extension =
        engine->vkdev->info.support_VK_EXT_queue_family_foreign() > 0;
    capabilities->ahb_input_available =
        engine->ahb_input_import_succeeded.load(std::memory_order_acquire);
    // Shader compilation and extensions are necessary, not sufficient: the
    // output is reported available only after a real queried AHB slot is made.
    capabilities->ahb_rgba_output_slot_created =
        engine->ahb_output_slot_created.load(std::memory_order_acquire);
#endif
    // These integration/acceptance boundaries are intentionally not satisfied.
    capabilities->gpu_resident_playback_ready =
        rife_vfi_gpu_resident_playback_supported(0, 0, 0, 0, 0, 0, 0, 0);
    return 0;
}

extern "C" int rife_vfi_interpolate_rgb24(RifeVfiEngine *engine,
                                            const uint8_t *frame0,
                                            const uint8_t *frame1,
                                            int width,
                                            int height,
                                            float timestep,
                                            uint8_t *output)
{
    if (!engine || !engine->network || !frame0 || !frame1 || !output ||
        width <= 0 || height <= 0 || timestep <= 0.f || timestep >= 1.f)
        return -1;

    // The pinned RIFE process_v4 path expects packed RGB bytes and wraps them as
    // external Mats itself; from_pixels() would expand to float planes first.
    ncnn::Mat input0(width, height, const_cast<uint8_t *>(frame0), (size_t)3, 3);
    ncnn::Mat input1(width, height, const_cast<uint8_t *>(frame1), (size_t)3, 3);
    ncnn::Mat synthesized(width, height, output, (size_t)3, 3);

    std::lock_guard<std::mutex> lock(engine->process_mutex);
    // RIFE writes RGB24 into synthesized's caller-owned buffer, avoiding an
    // intermediate output allocation and a full-frame memcpy after inference.
    if (engine->network->process(input0, input1, timestep, synthesized) != 0)
        return -1;

    return 0;
}

static int rife_vfi_interpolate_vulkan_internal(
    RifeVfiEngine *engine, const ncnn::VkMat *input0,
    const ncnn::VkMat *input1, float timestep, RifeVfiGpuFrame **output)
{
    if (output)
        *output = nullptr;
    if (!engine || !engine->network || !input0 || !input1 || !output ||
        timestep <= 0.f || timestep >= 1.f)
        return -1;
    if (input0->empty() || input1->empty())
        return -1;

    RifeVfiGpuFrame *synthesized = new (std::nothrow) RifeVfiGpuFrame();
    if (!synthesized)
        return -1;

    int result;
    {
        std::lock_guard<std::mutex> lock(engine->process_mutex);
        result = engine->network->process_v4_gpu(
            *input0, *input1, timestep, synthesized->image);
    }
    if (result != 0) {
        delete synthesized;
        return result;
    }

    engine->references.fetch_add(1, std::memory_order_relaxed);
    synthesized->engine = engine;
    synthesized->vkdev = engine->vkdev;
    *output = synthesized;
    return 0;
}

extern "C" int rife_vfi_import_ahb_rgb8(
    RifeVfiEngine *engine, void *android_hardware_buffer,
    int output_width, int output_height, RifeVfiGpuFrame **output)
{
    if (output)
        *output = nullptr;
#if defined(__ANDROID__) && __ANDROID_API__ >= 26
    if (!engine || !engine->vkdev || !android_hardware_buffer || !output ||
        output_width <= 0 || output_height <= 0 ||
        output_width > 8192 || output_height > 8192 ||
        !engine->pack_rgb_pipeline)
        return -1;

    auto *hardware_buffer = static_cast<AHardwareBuffer *>(android_hardware_buffer);
    AHardwareBufferRef buffer_ref(hardware_buffer);
    AHardwareBuffer_Desc desc = {};
    AHardwareBuffer_describe(hardware_buffer, &desc);
    if (!rife_vfi_ahb_probe_runtime_supported(
            __ANDROID_API__, engine->vkdev != nullptr,
            engine->vkdev->info.support_VK_ANDROID_external_memory_android_hardware_buffer() > 0,
            engine->vkdev->info.support_VK_EXT_queue_family_foreign() > 0,
            !!(desc.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE)) ||
        !desc.width || !desc.height || desc.width > 8192 || desc.height > 8192)
        return -3;

    std::lock_guard<std::mutex> lock(engine->process_mutex);
    std::unique_ptr<RifeVfiGpuFrame> imported(new (std::nothrow) RifeVfiGpuFrame());
    if (!imported)
        return -4;
    imported->vkdev = engine->vkdev;
    imported->blob_allocator = engine->vkdev->acquire_blob_allocator();
    RifeVfiStagingAllocatorGuard staging_guard(engine->vkdev);
    ncnn::VkAllocator *staging_allocator = staging_guard.allocator;
    if (!imported->blob_allocator || !staging_allocator) {
        return -5;
    }

    ncnn::Option import_opt;
    import_opt.use_vulkan_compute = true;
    import_opt.use_fp16_packed = false;
    import_opt.use_fp16_storage = false;
    import_opt.use_int8_storage = false;
    import_opt.blob_vkallocator = imported->blob_allocator;
    import_opt.workspace_vkallocator = imported->blob_allocator;
    import_opt.staging_vkallocator = staging_allocator;

    int result = -6;
    RifeVfiAhbImportCacheEntry *cache_entry = nullptr;
    auto cache_it = std::find_if(
        engine->ahb_import_cache.begin(), engine->ahb_import_cache.end(),
        [hardware_buffer](const std::unique_ptr<RifeVfiAhbImportCacheEntry> &entry) {
            return entry->hardware_buffer == hardware_buffer;
        });
    if (cache_it != engine->ahb_import_cache.end()) {
        if ((*cache_it)->width == desc.width && (*cache_it)->height == desc.height &&
            (*cache_it)->output_width == output_width &&
            (*cache_it)->output_height == output_height) {
            cache_entry = cache_it->get();
        } else {
            // A reused pointer with changed dimensions/specialization is not a
            // valid pipeline cache hit; release the old entry before rebuilding.
            engine->ahb_import_cache.erase(cache_it);
        }
    }

    if (!cache_entry) {
        std::unique_ptr<RifeVfiAhbImportCacheEntry> entry(
            new (std::nothrow) RifeVfiAhbImportCacheEntry());
        if (!entry)
            return -6;

        entry->hardware_buffer = hardware_buffer;
        AHardwareBuffer_acquire(hardware_buffer);
        entry->width = desc.width;
        entry->height = desc.height;
        entry->output_width = output_width;
        entry->output_height = output_height;
        entry->allocator.reset(new (std::nothrow)
            ncnn::VkAndroidHardwareBufferImageAllocator(engine->vkdev, hardware_buffer));
        entry->pipeline.reset(new (std::nothrow)
            ncnn::ImportAndroidHardwareBufferPipeline(engine->vkdev));
        if (!entry->allocator || !entry->pipeline)
            return -7;
        entry->source = ncnn::VkImageMat::from_android_hardware_buffer(
            entry->allocator.get());
        if (entry->source.empty())
            return -8;
        if (entry->pipeline->create(entry->allocator.get(), 1, 1,
                                    output_width, output_height, import_opt) != 0)
            return -9;

        // AImageReader's documented maxImages is three in mpv's mapper.
        constexpr size_t kMaxAhbImportCacheEntries = 3;
        if (engine->ahb_import_cache.size() >= kMaxAhbImportCacheEntries) {
            auto oldest = std::min_element(
                engine->ahb_import_cache.begin(), engine->ahb_import_cache.end(),
                [](const std::unique_ptr<RifeVfiAhbImportCacheEntry> &a,
                   const std::unique_ptr<RifeVfiAhbImportCacheEntry> &b) {
                    return a->last_used < b->last_used;
                });
            if (oldest != engine->ahb_import_cache.end())
                engine->ahb_import_cache.erase(oldest);
        }
        entry->last_used = ++engine->ahb_import_cache_clock;
        engine->ahb_import_cache.push_back(std::move(entry));
        cache_entry = engine->ahb_import_cache.back().get();
    } else {
        cache_entry->last_used = ++engine->ahb_import_cache_clock;
    }

    // NCNN's sampled AHB importer yields planar FP32. Pack to the exact
    // interleaved RGB8 VkMat expected by the pinned int8 RIFE preprocessor.
    ncnn::VkMat planar_rgb;
    ncnn::VkMat packed_rgb;
    planar_rgb.create(output_width, output_height, 3, 4u, 1,
                      imported->blob_allocator);
    packed_rgb.create(output_width, output_height, (size_t)3, 1,
                      imported->blob_allocator);
    if (!planar_rgb.empty() && !packed_rgb.empty()) {
        ncnn::VkCompute cmd(engine->vkdev);
        cmd.record_import_android_hardware_buffer(
            cache_entry->pipeline.get(), cache_entry->source, planar_rgb);
        std::vector<ncnn::vk_constant_type> constants(3);
        constants[0].i = output_width;
        constants[1].i = output_height;
        constants[2].i = (int)planar_rgb.cstep;
        cmd.record_pipeline(engine->pack_rgb_pipeline,
                            {planar_rgb, packed_rgb}, constants, packed_rgb);
        if (cmd.submit_and_wait() == 0) {
            imported->image = packed_rgb;
            result = 0;
        }
    }
    if (result != 0)
        return result;

    engine->references.fetch_add(1, std::memory_order_relaxed);
    imported->engine = engine;
    engine->ahb_input_import_succeeded.store(true, std::memory_order_release);
    *output = imported.release();
    return 0;
#else
    (void)engine;
    (void)android_hardware_buffer;
    (void)output_width;
    (void)output_height;
    (void)output;
    return -10;
#endif
}

extern "C" int rife_vfi_interpolate_gpu_frames(
    RifeVfiEngine *engine, const RifeVfiGpuFrame *frame0,
    const RifeVfiGpuFrame *frame1, float timestep,
    RifeVfiGpuFrame **output)
{
    if (!engine || !frame0 || !frame1 || frame0->engine != engine ||
        frame1->engine != engine || frame0->vkdev != engine->vkdev ||
        frame1->vkdev != engine->vkdev)
        return -1;
    return rife_vfi_interpolate_vulkan_internal(
        engine, &frame0->image, &frame1->image, timestep, output);
}

extern "C" RifeVfiOutput *rife_vfi_output_create(
    RifeVfiEngine *engine, int width, int height, char *error, size_t error_size)
{
#if defined(__ANDROID__) && __ANDROID_API__ >= 26 && \
    defined(VK_ANDROID_external_memory_android_hardware_buffer) && \
    defined(VK_EXT_queue_family_foreign)
    if (!engine || !engine->vkdev) {
        set_error(error, error_size, "RIFE Vulkan engine is unavailable");
        return nullptr;
    }
    std::unique_ptr<RifeVfiOutput> output(new (std::nothrow) RifeVfiOutput());
    if (!output) {
        set_error(error, error_size, "Unable to allocate a RIFE output slot");
        return nullptr;
    }
    output->engine = engine;
    engine->references.fetch_add(1, std::memory_order_relaxed);
    bool initialized = false;
    {
        std::lock_guard<std::mutex> lock(engine->process_mutex);
        initialized = initialize_output_slot(output.get(), width, height,
                                             error, error_size);
        if (!initialized)
            destroy_output_resources(output.get());
    }
    if (!initialized) {
        output->engine = nullptr;
        release_engine_reference(engine);
        return nullptr;
    }
    engine->ahb_output_slot_created.store(true, std::memory_order_release);
    return output.release();
#else
    (void)engine;
    (void)width;
    (void)height;
    set_error(error, error_size, "Writable Vulkan AHardwareBuffer images are unavailable");
    return nullptr;
#endif
}

extern "C" void *rife_vfi_output_get_ahb(const RifeVfiOutput *output)
{
#if defined(__ANDROID__) && __ANDROID_API__ >= 26
    return output ? output->hardware_buffer : nullptr;
#else
    (void)output;
    return nullptr;
#endif
}

extern "C" int rife_vfi_write_output_rgba(
    RifeVfiEngine *engine, const RifeVfiGpuFrame *rgb_frame,
    RifeVfiOutput *output, char *error, size_t error_size)
{
#if defined(__ANDROID__) && __ANDROID_API__ >= 26 && \
    defined(VK_ANDROID_external_memory_android_hardware_buffer) && \
    defined(VK_EXT_queue_family_foreign)
    const ncnn::VkMat *image = rgb_frame ? &rgb_frame->image : nullptr;
    const RifeVfiOutputLayout tensor_layout = image
        ? rife_vfi_output_layout_classify(image->dims, image->d, image->c,
                                          image->elempack, image->elemsize)
        : RifeVfiOutputLayout::unsupported;
    const char *layout_name =
        tensor_layout == RifeVfiOutputLayout::packed_rgb8 ? "packed_rgb8" :
        tensor_layout == RifeVfiOutputLayout::planar_rgb32f ? "planar_rgb32f" :
        "unsupported";
    ncnn::Pipeline *conversion_pipeline = nullptr;
    if (engine && tensor_layout == RifeVfiOutputLayout::packed_rgb8)
        conversion_pipeline = engine->output_rgba_pipeline;
    else if (engine && tensor_layout == RifeVfiOutputLayout::planar_rgb32f)
        conversion_pipeline = engine->output_rgba_fp32_pipeline;

    const bool input_size_matches = image && output &&
        image->w == output->width && image->h == output->height;
    if (!engine || !engine->vkdev || !rgb_frame || !output ||
        output->engine != engine || rgb_frame->engine != engine ||
        rgb_frame->vkdev != engine->vkdev || !image || image->empty() ||
        tensor_layout == RifeVfiOutputLayout::unsupported ||
        !conversion_pipeline || !input_size_matches || output->image.empty()) {
        set_errorf(error, error_size,
                   "stage=output_contract_failed layout=%s engine=%d vk_device=%d frame_engine_match=%d frame_device_match=%d output_engine_match=%d input_empty=%d input_dims=%d input_w=%d input_h=%d input_d=%d input_c=%d input_elempack=%d input_elemsize=%zu input_cstep=%zu expected_w=%d expected_h=%d output_image_empty=%d packed_shader=%d fp32_shader=%d",
                   layout_name, engine != nullptr,
                   engine && engine->vkdev != nullptr,
                   engine && rgb_frame && rgb_frame->engine == engine,
                   engine && rgb_frame && rgb_frame->vkdev == engine->vkdev,
                   engine && output && output->engine == engine,
                   image ? image->empty() : -1,
                   image ? image->dims : -1, image ? image->w : -1,
                   image ? image->h : -1, image ? image->d : -1,
                   image ? image->c : -1, image ? image->elempack : -1,
                   image ? image->elemsize : 0u,
                   image ? image->cstep : 0u,
                   output ? output->width : -1, output ? output->height : -1,
                   output ? output->image.empty() : -1,
                   engine && engine->output_rgba_pipeline,
                   engine && engine->output_rgba_fp32_pipeline);
        return -1;
    }

    std::lock_guard<std::mutex> lock(engine->process_mutex);
    const uint32_t family = engine->vkdev->info.compute_queue_family_index();
    if (!output->initial_acquire_done || output->foreign_owned) {
        const VkImageLayout old_layout = output->initialized
            ? output->image_memory.image_layout : VK_IMAGE_LAYOUT_UNDEFINED;
        if (!record_output_transition(
                output, VK_QUEUE_FAMILY_FOREIGN_EXT, family, 0,
                VK_ACCESS_SHADER_WRITE_BIT, old_layout, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)) {
            set_errorf(error, error_size,
                       "stage=output_acquire_transition_failed layout=%s old_layout=%d queue_family=%u",
                       layout_name, (int)old_layout, family);
            return -2;
        }
        output->image_memory.image_layout = VK_IMAGE_LAYOUT_GENERAL;
        output->image_memory.access_flags = VK_ACCESS_SHADER_WRITE_BIT;
        output->image_memory.stage_flags = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        output->foreign_owned = false;
        output->initial_acquire_done = true;
    }

    ncnn::VkCompute cmd(engine->vkdev);
    if (tensor_layout == RifeVfiOutputLayout::planar_rgb32f) {
        std::vector<ncnn::vk_constant_type> constants(3);
        constants[0].i = output->width;
        constants[1].i = output->height;
        constants[2].i = (int)image->cstep;
        cmd.record_pipeline(conversion_pipeline, {*image}, {output->image},
                            constants, output->image);
    } else {
        std::vector<ncnn::vk_constant_type> constants(2);
        constants[0].i = output->width;
        constants[1].i = output->height;
        cmd.record_pipeline(conversion_pipeline, {*image}, {output->image},
                            constants, output->image);
    }
    const int submit_result = cmd.submit_and_wait();
    if (submit_result != 0) {
        set_errorf(error, error_size,
                   "stage=output_conversion_submit_failed layout=%s result=%d input_dims=%d input_c=%d input_elemsize=%zu input_cstep=%zu",
                   layout_name, submit_result, image->dims, image->c,
                   image->elemsize, image->cstep);
        return -3;
    }
    // submit_and_wait already waits for this compute submission's fence; a
    // device-wide idle here needlessly stalls unrelated renderer work.

    const VkImageLayout layout = output->image_memory.image_layout;
    const VkAccessFlags access = output->image_memory.access_flags;
    const VkPipelineStageFlags stage = output->image_memory.stage_flags;
    if (layout != VK_IMAGE_LAYOUT_GENERAL ||
        !record_output_transition(
            output, family, VK_QUEUE_FAMILY_FOREIGN_EXT,
            access ? access : VK_ACCESS_SHADER_WRITE_BIT, 0,
            layout, VK_IMAGE_LAYOUT_GENERAL,
            stage ? stage : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT)) {
        set_errorf(error, error_size,
                   "stage=output_release_transition_failed layout=%s image_layout=%d access=0x%08x stage_mask=0x%08x queue_family=%u",
                   layout_name, (int)layout, (unsigned int)access,
                   (unsigned int)stage, family);
        return -5;
    }

    output->initialized = true;
    output->foreign_owned = true;
    output->image_memory.image_layout = VK_IMAGE_LAYOUT_GENERAL;
    output->image_memory.access_flags = 0;
    output->image_memory.stage_flags = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    set_error(error, error_size, "");
    return 0;
#else
    (void)engine;
    (void)rgb_frame;
    (void)output;
    set_error(error, error_size,
              "stage=android_vulkan_output_unavailable api_or_extension_guard");
    return -10;
#endif
}

extern "C" void rife_vfi_output_destroy(RifeVfiOutput *output)
{
    if (!output)
        return;
#if defined(__ANDROID__) && __ANDROID_API__ >= 26 && \
    defined(VK_ANDROID_external_memory_android_hardware_buffer) && \
    defined(VK_EXT_queue_family_foreign)
    RifeVfiEngine *engine = output->engine;
    if (engine) {
        {
            std::lock_guard<std::mutex> lock(engine->process_mutex);
            destroy_output_resources(output);
        }
        delete output;
        release_engine_reference(engine);
        return;
    }
#endif
    delete output;
}

extern "C" void rife_vfi_gpu_frame_release(RifeVfiGpuFrame *frame)
{
    if (!frame)
        return;
    RifeVfiEngine *engine = frame->engine;
    delete frame;
    release_engine_reference(engine);
}
