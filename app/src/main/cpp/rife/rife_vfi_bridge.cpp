/* SPDX-License-Identifier: AGPL-3.0-or-later */

#include "rife_vfi.h"

#include "gpu.h"
#include <cstdio>
#include <mutex>
#include <new>
#include <string>

#include "rife.h"

struct RifeVfiEngine {
    RIFE *network = nullptr;
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
    delete engine->network;
    delete engine;
    release_gpu_instance();
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

    // RIFE writes RGB24 into synthesized's caller-owned buffer, avoiding an
    // intermediate output allocation and a full-frame memcpy after inference.
    if (engine->network->process(input0, input1, timestep, synthesized) != 0)
        return -1;

    return 0;
}
