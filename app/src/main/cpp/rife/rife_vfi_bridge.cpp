/* SPDX-License-Identifier: AGPL-3.0-or-later */

#include "rife_vfi.h"
#include "rife_fp16_policy.h"

#include "gpu.h"
#include <atomic>
#include <cstdio>
#include <mutex>
#include <new>
#include <string>

#include "rife.h"

struct RifeVfiEngine {
    RIFE *network = nullptr;
    bool fp16_arithmetic = false;
    std::atomic<unsigned int> references{1};
    std::mutex process_mutex;
};

struct RifeVfiGpuFrame {
    ncnn::VkMat image;
    RifeVfiEngine *engine = nullptr;
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
