/* SPDX-License-Identifier: AGPL-3.0-or-later */

#include "rife_vfi.h"
#include "rife_vfi_ahb_policy.h"
#include "rife_fp16_policy.h"

#include "gpu.h"
#include <atomic>
#include <cstring>
#include <cstdio>
#include <mutex>
#include <memory>
#include <new>
#include <string>

#include "rife.h"

#if defined(__ANDROID__) && __ANDROID_API__ >= 26
#include <android/hardware_buffer.h>
#endif

struct RifeVfiEngine {
    RIFE *network = nullptr;
    ncnn::VulkanDevice *vkdev = nullptr;
    bool fp16_arithmetic = false;
    std::atomic<unsigned int> references{1};
    std::mutex process_mutex;
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

namespace {
std::mutex gpu_mutex;
int gpu_users = 0;

void set_error(char *buffer, size_t size, const char *message)
{
    if (buffer && size > 0)
        std::snprintf(buffer, size, "%s", message ? message : "unknown RIFE error");
}

void release_gpu_instance()
{
    std::lock_guard<std::mutex> lock(gpu_mutex);
    if (gpu_users > 0 && --gpu_users == 0)
        ncnn::destroy_gpu_instance();
}

void release_engine_reference(RifeVfiEngine *engine)
{
    if (engine && engine->references.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        delete engine->network;
        delete engine;
        release_gpu_instance();
    }
}
}

extern "C" RifeVfiEngine *rife_vfi_create(const char *model_dir,
                                            char *error,
                                            size_t error_size)
{
    if (!model_dir || !model_dir[0]) {
        set_error(error, error_size, "model directory is empty");
        return nullptr;
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

    std::string directory(model_dir);
    if (directory.back() != '/')
        directory.push_back('/');
    if (engine->network->load(directory) != 0) {
        delete engine->network;
        delete engine;
        release_gpu_instance();
        set_error(error, error_size, "RIFE-v4.6 model loading failed");
        return nullptr;
    }

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
    capabilities->ahb_input_probe_available =
        rife_vfi_ahb_probe_runtime_supported(
            __ANDROID_API__, capabilities->vulkan_device_ready,
            capabilities->android_ahb_import_extension,
            capabilities->foreign_queue_family_extension, 1);
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

extern "C" int rife_vfi_interpolate_vulkan(RifeVfiEngine *engine,
                                             const void *ncnn_vkmat0,
                                             const void *ncnn_vkmat1,
                                             float timestep,
                                             RifeVfiGpuFrame **output)
{
    if (output)
        *output = nullptr;
    if (!engine || !engine->network || !ncnn_vkmat0 || !ncnn_vkmat1 || !output ||
        timestep <= 0.f || timestep >= 1.f)
        return -1;

    const ncnn::VkMat *input0 =
        static_cast<const ncnn::VkMat *>(ncnn_vkmat0);
    const ncnn::VkMat *input1 =
        static_cast<const ncnn::VkMat *>(ncnn_vkmat1);
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
    *output = synthesized;
    return 0;
}

extern "C" int rife_vfi_import_ahb_rgb32f_for_probe(
    RifeVfiEngine *engine, void *android_hardware_buffer,
    int output_width, int output_height, RifeVfiGpuFrame **output)
{
    if (output)
        *output = nullptr;
#if defined(__ANDROID__) && __ANDROID_API__ >= 26
    if (!engine || !engine->vkdev || !android_hardware_buffer || !output ||
        output_width <= 0 || output_height <= 0 ||
        output_width > 8192 || output_height > 8192)
        return -1;

    auto *hardware_buffer = static_cast<AHardwareBuffer *>(android_hardware_buffer);
    AHardwareBuffer_Desc desc = {};
    AHardwareBuffer_describe(hardware_buffer, &desc);
    if (!rife_vfi_ahb_probe_runtime_supported(
            __ANDROID_API__, engine->vkdev != nullptr,
            engine->vkdev->info.support_VK_ANDROID_external_memory_android_hardware_buffer() > 0,
            engine->vkdev->info.support_VK_EXT_queue_family_foreign() > 0,
            !!(desc.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE)) ||
        !desc.width || !desc.height)
        return -3;

    std::lock_guard<std::mutex> lock(engine->process_mutex);
    std::unique_ptr<RifeVfiGpuFrame> imported(new (std::nothrow) RifeVfiGpuFrame());
    if (!imported)
        return -4;
    imported->vkdev = engine->vkdev;
    imported->blob_allocator = engine->vkdev->acquire_blob_allocator();
    ncnn::VkAllocator *staging_allocator = engine->vkdev->acquire_staging_allocator();
    if (!imported->blob_allocator || !staging_allocator) {
        if (staging_allocator)
            engine->vkdev->reclaim_staging_allocator(staging_allocator);
        return -5;
    }

    ncnn::Option opt;
    opt.use_vulkan_compute = true;
    opt.use_fp16_packed = false;
    opt.use_fp16_storage = false;
    opt.use_int8_storage = false;
    opt.blob_vkallocator = imported->blob_allocator;
    opt.workspace_vkallocator = imported->blob_allocator;
    opt.staging_vkallocator = staging_allocator;

    int result = -6;
    {
        // This first prototype intentionally creates the import pipeline per
        // probe. Playback must cache it per AHB allocation before enablement.
        ncnn::VkAndroidHardwareBufferImageAllocator ahb_allocator(
            engine->vkdev, hardware_buffer);
        ncnn::VkImageMat source =
            ncnn::VkImageMat::from_android_hardware_buffer(&ahb_allocator);
        if (!source.empty()) {
            ncnn::ImportAndroidHardwareBufferPipeline import_pipeline(engine->vkdev);
            int pipeline_result = import_pipeline.create(
                &ahb_allocator, 1, 1, output_width, output_height, opt);
            if (pipeline_result == 0) {
                ncnn::VkMat rgb;
                rgb.create(output_width, output_height, 3, 4u, 1,
                           imported->blob_allocator);
                if (!rgb.empty()) {
                    ncnn::VkCompute cmd(engine->vkdev);
                    cmd.record_import_android_hardware_buffer(
                        &import_pipeline, source, rgb);
                    cmd.submit_and_wait();
                    imported->image = rgb;
                    result = 0;
                }
                import_pipeline.destroy_pipeline(opt);
            }
        }
    }
    engine->vkdev->reclaim_staging_allocator(staging_allocator);
    if (result != 0)
        return result;

    engine->references.fetch_add(1, std::memory_order_relaxed);
    imported->engine = engine;
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

extern "C" int rife_vfi_gpu_frame_get_dimensions(
    const RifeVfiGpuFrame *frame, int *width, int *height, int *channels)
{
    if (!frame || frame->image.empty())
        return -1;
    if (width)
        *width = frame->image.w;
    if (height)
        *height = frame->image.h;
    if (channels)
        *channels = frame->image.c;
    return 0;
}

extern "C" int rife_vfi_gpu_frame_copy_rgb32f_for_probe(
    const RifeVfiGpuFrame *frame, float *output, size_t output_float_capacity)
{
    if (!frame || !frame->engine || !frame->vkdev || !output ||
        frame->image.empty() || frame->image.dims != 3 || frame->image.c != 3 ||
        frame->image.elempack != 1 || frame->image.elemsize != 4u)
        return -1;

    const size_t width = (size_t)frame->image.w;
    const size_t height = (size_t)frame->image.h;
    if (!width || !height || width > SIZE_MAX / height ||
        width * height > SIZE_MAX / 3)
        return -2;
    const size_t required = width * height * 3;
    if (output_float_capacity < required)
        return -3;

    std::lock_guard<std::mutex> lock(frame->engine->process_mutex);
    ncnn::VkAllocator *blob_allocator = frame->vkdev->acquire_blob_allocator();
    ncnn::VkAllocator *staging_allocator = frame->vkdev->acquire_staging_allocator();
    if (!blob_allocator || !staging_allocator) {
        if (blob_allocator)
            frame->vkdev->reclaim_blob_allocator(blob_allocator);
        if (staging_allocator)
            frame->vkdev->reclaim_staging_allocator(staging_allocator);
        return -4;
    }

    ncnn::Option opt;
    opt.blob_vkallocator = blob_allocator;
    opt.staging_vkallocator = staging_allocator;
    ncnn::Mat cpu_rgb;
    {
        ncnn::VkCompute cmd(frame->vkdev);
        cmd.record_clone(frame->image, cpu_rgb, opt);
        cmd.submit_and_wait();
    }
    int result = -5;
    if (!cpu_rgb.empty() && cpu_rgb.elemsize == 4u &&
        cpu_rgb.total() >= required) {
        std::memcpy(output, cpu_rgb.data, required * sizeof(float));
        result = 0;
    }
    frame->vkdev->reclaim_blob_allocator(blob_allocator);
    frame->vkdev->reclaim_staging_allocator(staging_allocator);
    return result;
}

extern "C" const void *rife_vfi_gpu_frame_get_ncnn_vkmat(
    const RifeVfiGpuFrame *frame)
{
    return frame ? &frame->image : nullptr;
}

extern "C" void rife_vfi_gpu_frame_release(RifeVfiGpuFrame *frame)
{
    if (!frame)
        return;
    RifeVfiEngine *engine = frame->engine;
    delete frame;
    release_engine_reference(engine);
}
