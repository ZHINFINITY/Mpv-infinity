/*
 * vf_anvil.c - ANVIL Video Frame Interpolation filter for mpv
 *
 * Copyright (c) 2026 NihilDigit
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 *
 * ---
 * Three-accelerator pipeline (CPU + Vulkan GPU + QNN HTP INT8) for
 * real-time 1080p frame doubling via codec MV prealignment.
 * Usage: --vf=anvil
 */

// ====================================================================
// Section 1: Includes
// ====================================================================

#include <math.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <dlfcn.h>
#include <time.h>
#include <pthread.h>

#ifdef __ARM_NEON
#include <arm_neon.h>
#endif

#include <libavutil/motion_vector.h>
#include <libavutil/frame.h>

#include "common/common.h"
#include "filters/filter.h"
#include "filters/filter_internal.h"
#include "filters/user_filters.h"
#include "video/mp_image.h"
#include "video/img_format.h"

// QNN headers (compile-time types only; runtime via dlopen)
#include "QNN/QnnInterface.h"
#include "QNN/System/QnnSystemInterface.h"
#include "QNN/HTP/QnnHtpPerfInfrastructure.h"
#include "QNN/HTP/QnnHtpDevice.h"

// Vulkan compute (GPU prealign + warp)
#include <vulkan/vulkan.h>

// Pre-compiled SPIR-V shaders
#include "median5_spv.h"
#include "gauss_sep_spv.h"
#include "warp_pack_spv.h"
#include "warp_pack_quant_spv.h"
#include "residual_yuv_spv.h"

// ====================================================================
// Section 2: Constants and thread configuration
// ====================================================================

#define PA_DS        4
#define PA_MED_K     5
#define PA_GAUSS_S   2.0f
#define N_THREADS    4

// BT.709 RGB[0,1] -> YUV limited range coefficients
// Y = YR*r + YG*g + YB*b + 16    (r,g,b in [0,1], coefficients pre-scaled by 255)
// U = UR*r + UG*g + UB*b + 128   (applied to r*255/4 averaged values in Phase 4)
// V = VR*r + VG*g + VB*b + 128
#define BT709_YR  (0.183f * 255.0f)
#define BT709_YG  (0.614f * 255.0f)
#define BT709_YB  (0.062f * 255.0f)

// ====================================================================
// Section 2b: Vulkan compute infrastructure
// ====================================================================

#define VK_CHECK(expr, label) do { \
    VkResult _r = (expr); \
    if (_r != VK_SUCCESS) { \
        MP_WARN(f, "Vulkan: %s failed (%d)\n", #expr, (int)_r); \
        goto label; \
    } \
} while(0)

// Push-constant structs matching shader layouts
struct pc_median  { int32_t width, height; };
struct pc_gauss   { int32_t width, height, dir; };
struct pc_warp    { int32_t W, H, sW, sH, y_stride, uv_stride, u_off, v_off; };
struct pc_warp_quant {
    int32_t W, H, sW, sH, y_stride, uv_stride, u_off, v_off;
    float inv_scale;
    int32_t offset;
};

struct pc_residual_yuv {
    int32_t W, H;
    int32_t y_stride, uv_stride;
    float in_scale;
    int32_t in_offset;
    float out_scale;
    int32_t out_offset;
    int32_t qnn_ok;
};

struct vk_compute {
    VkInstance instance;
    VkPhysicalDevice physDev;
    VkDevice device;
    VkQueue queue;
    uint32_t queueFamily;
    VkCommandPool cmdPool;
    VkCommandBuffer cmd;

    // 3 pipelines
    VkPipeline median_pipe, gauss_pipe, warp_pipe;
    VkPipelineLayout median_layout, gauss_layout, warp_layout;
    VkDescriptorSetLayout median_dsl, gauss_dsl, warp_dsl;
    VkDescriptorPool dpool;

    // Descriptor sets: 2 median + 4 gauss + 1 warp = 7 total
    VkDescriptorSet median_ds[2];   // [0]=fx: a->b, [1]=fy: a->b
    VkDescriptorSet gauss_ds[4];    // [0]=fx_h: b->a, [1]=fx_v: a->b, [2]=fy_h: b->a, [3]=fy_v: a->b
    VkDescriptorSet warp_ds;

    // 1/4 res flow ping-pong SSBOs (separate for fx and fy)
    VkBuffer sm_fx_a, sm_fx_b, sm_fy_a, sm_fy_b;
    VkDeviceMemory sm_fx_a_mem, sm_fx_b_mem, sm_fy_a_mem, sm_fy_b_mem;
    float *sm_fx_a_ptr, *sm_fx_b_ptr, *sm_fy_a_ptr, *sm_fy_b_ptr;

    // Full res: packed YUV frame buffers (prev, curr)
    VkBuffer prev_yuv_buf, curr_yuv_buf;
    VkDeviceMemory prev_yuv_mem, curr_yuv_mem;
    uint8_t *prev_yuv_ptr, *curr_yuv_ptr;
    size_t yuv_buf_size;

    // Full res outputs
    VkBuffer nhwc_buf;           // float NHWC 6ch
    VkDeviceMemory nhwc_mem;
    float *nhwc_ptr;

    VkBuffer blend_r_buf, blend_g_buf, blend_b_buf;
    VkDeviceMemory blend_r_mem, blend_g_mem, blend_b_mem;
    float *blend_r_ptr, *blend_g_ptr, *blend_b_ptr;

    // Quantized warp pipeline (warp_pack_quant shader)
    VkPipeline warp_quant_pipe;
    VkPipelineLayout warp_quant_layout;
    VkDescriptorSetLayout warp_quant_dsl;
    VkDescriptorSet warp_quant_ds;

    // uint8 NHWC output SSBO (packed into uint32, 3 words per 2 pixels)
    VkBuffer nhwc_u8_buf;
    VkDeviceMemory nhwc_u8_mem;
    uint8_t *nhwc_u8_ptr;   // mapped pointer, cast to uint8_t for memcpy
    VkDeviceSize nhwc_u8_size;

    // Residual+YUV pipeline (Phase 4 on GPU)
    VkPipeline residual_yuv_pipe;
    VkPipelineLayout residual_yuv_layout;
    VkDescriptorSetLayout residual_yuv_dsl;
    VkDescriptorSet residual_yuv_ds;

    // QNN output upload SSBO (uint8 NHWC 3ch, packed uint32)
    VkBuffer qnn_out_buf;
    VkDeviceMemory qnn_out_mem;
    uint8_t *qnn_out_ptr;
    VkDeviceSize qnn_out_size;

    // YUV output SSBOs (Y, U, V planes)
    VkBuffer y_buf, u_buf, v_buf;
    VkDeviceMemory y_mem, u_mem, v_mem;
    uint8_t *y_ptr, *u_ptr, *v_ptr;
    VkDeviceSize y_size, u_size, v_size;

    int ready;
    int W, H, sW, sH;
};

// Find a memory type index matching requirements
static uint32_t vk_find_memory_type(VkPhysicalDevice pd, uint32_t typeBits,
                                     VkMemoryPropertyFlags props)
{
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(pd, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((typeBits & (1 << i)) &&
            (memProps.memoryTypes[i].propertyFlags & props) == props)
            return i;
    }
    return UINT32_MAX;
}

// Create a HOST_VISIBLE | HOST_COHERENT SSBO and map it
static int vk_create_ssbo(VkDevice dev, VkPhysicalDevice pd,
                           VkBuffer *buf, VkDeviceMemory *mem, void **mapped,
                           VkDeviceSize size)
{
    VkBufferCreateInfo bi = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = size,
        .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
    };
    if (vkCreateBuffer(dev, &bi, NULL, buf) != VK_SUCCESS) return -1;

    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(dev, *buf, &req);

    uint32_t mti = vk_find_memory_type(pd, req.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (mti == UINT32_MAX) return -1;

    VkMemoryAllocateInfo ai = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = req.size,
        .memoryTypeIndex = mti,
    };
    if (vkAllocateMemory(dev, &ai, NULL, mem) != VK_SUCCESS) return -1;
    if (vkBindBufferMemory(dev, *buf, *mem, 0) != VK_SUCCESS) return -1;
    if (mapped) {
        if (vkMapMemory(dev, *mem, 0, size, 0, mapped) != VK_SUCCESS) return -1;
    }
    return 0;
}

static void vk_free_ssbo(VkDevice dev, VkBuffer buf, VkDeviceMemory mem)
{
    if (buf) vkDestroyBuffer(dev, buf, NULL);
    if (mem) {
        vkUnmapMemory(dev, mem);
        vkFreeMemory(dev, mem, NULL);
    }
}

// Create a compute pipeline from SPIR-V, with given layout
static VkPipeline vk_create_pipeline(VkDevice dev, VkPipelineLayout layout,
                                      const unsigned char *spv, uint32_t spv_len)
{
    VkShaderModuleCreateInfo smi = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = spv_len,
        .pCode = (const uint32_t *)spv,
    };
    VkShaderModule mod;
    if (vkCreateShaderModule(dev, &smi, NULL, &mod) != VK_SUCCESS) return VK_NULL_HANDLE;

    VkComputePipelineCreateInfo ci = {
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_COMPUTE_BIT,
            .module = mod,
            .pName = "main",
        },
        .layout = layout,
    };
    VkPipeline pipe = VK_NULL_HANDLE;
    vkCreateComputePipelines(dev, VK_NULL_HANDLE, 1, &ci, NULL, &pipe);
    vkDestroyShaderModule(dev, mod, NULL);
    return pipe;
}

// Write one SSBO descriptor
static void vk_write_ds_ssbo(VkDevice dev, VkDescriptorSet ds, uint32_t binding,
                              VkBuffer buf, VkDeviceSize size)
{
    VkDescriptorBufferInfo bi = { .buffer = buf, .offset = 0, .range = size };
    VkWriteDescriptorSet w = {
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .dstSet = ds,
        .dstBinding = binding,
        .descriptorCount = 1,
        .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
        .pBufferInfo = &bi,
    };
    vkUpdateDescriptorSets(dev, 1, &w, 0, NULL);
}

static int vk_init(struct vk_compute *vk, struct mp_filter *f, int W, int H)
{
    memset(vk, 0, sizeof(*vk));
    vk->W = W; vk->H = H;
    vk->sW = W / PA_DS; vk->sH = H / PA_DS;

    // --- Instance ---
    VkApplicationInfo appInfo = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "anvil-vfi",
        .apiVersion = VK_API_VERSION_1_0,
    };
    VkInstanceCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &appInfo,
    };
    VK_CHECK(vkCreateInstance(&ici, NULL, &vk->instance), fail);

    // --- Physical device (first GPU) ---
    uint32_t devCount = 1;
    VK_CHECK(vkEnumeratePhysicalDevices(vk->instance, &devCount, &vk->physDev), fail);
    if (devCount == 0) {
        MP_WARN(f, "Vulkan: no physical devices\n");
        goto fail;
    }

    // --- Find compute queue family ---
    uint32_t qfCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(vk->physDev, &qfCount, NULL);
    VkQueueFamilyProperties qfProps[16];
    if (qfCount > 16) qfCount = 16;
    vkGetPhysicalDeviceQueueFamilyProperties(vk->physDev, &qfCount, qfProps);
    vk->queueFamily = UINT32_MAX;
    for (uint32_t i = 0; i < qfCount; i++) {
        if (qfProps[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            vk->queueFamily = i;
            break;
        }
    }
    if (vk->queueFamily == UINT32_MAX) {
        MP_WARN(f, "Vulkan: no compute queue family\n");
        goto fail;
    }

    // --- Logical device + queue ---
    float prio = 1.0f;
    VkDeviceQueueCreateInfo dqci = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = vk->queueFamily,
        .queueCount = 1,
        .pQueuePriorities = &prio,
    };
    VkDeviceCreateInfo dci = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &dqci,
    };
    VK_CHECK(vkCreateDevice(vk->physDev, &dci, NULL, &vk->device), fail);
    vkGetDeviceQueue(vk->device, vk->queueFamily, 0, &vk->queue);

    // --- Command pool + buffer ---
    VkCommandPoolCreateInfo cpci = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = vk->queueFamily,
    };
    VK_CHECK(vkCreateCommandPool(vk->device, &cpci, NULL, &vk->cmdPool), fail);

    VkCommandBufferAllocateInfo cbai = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = vk->cmdPool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    VK_CHECK(vkAllocateCommandBuffers(vk->device, &cbai, &vk->cmd), fail);

    // --- Descriptor set layouts ---
    // Median/Gauss: 2 SSBOs (in, out)
    VkDescriptorSetLayoutBinding med_bindings[2] = {
        { .binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
          .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT },
        { .binding = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
          .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT },
    };
    VkDescriptorSetLayoutCreateInfo dslci_med = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 2, .pBindings = med_bindings,
    };
    VK_CHECK(vkCreateDescriptorSetLayout(vk->device, &dslci_med, NULL, &vk->median_dsl), fail);
    VK_CHECK(vkCreateDescriptorSetLayout(vk->device, &dslci_med, NULL, &vk->gauss_dsl), fail);

    // Warp: 8 SSBOs
    VkDescriptorSetLayoutBinding warp_bindings[8];
    for (int i = 0; i < 8; i++) {
        warp_bindings[i] = (VkDescriptorSetLayoutBinding){
            .binding = i,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        };
    }
    VkDescriptorSetLayoutCreateInfo dslci_warp = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 8, .pBindings = warp_bindings,
    };
    VK_CHECK(vkCreateDescriptorSetLayout(vk->device, &dslci_warp, NULL, &vk->warp_dsl), fail);

    // Warp quant: 5 SSBOs (flow_x, flow_y, prev_yuv, curr_yuv, nhwc_u8)
    VkDescriptorSetLayoutBinding warp_quant_bindings[5];
    for (int i = 0; i < 5; i++) {
        warp_quant_bindings[i] = (VkDescriptorSetLayoutBinding){
            .binding = i,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        };
    }
    VkDescriptorSetLayoutCreateInfo dslci_warp_quant = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 5, .pBindings = warp_quant_bindings,
    };
    VK_CHECK(vkCreateDescriptorSetLayout(vk->device, &dslci_warp_quant, NULL, &vk->warp_quant_dsl), fail);

    // Residual+YUV: 5 SSBOs (nhwc_in, qnn_out, y_out, u_out, v_out)
    VkDescriptorSetLayoutBinding residual_yuv_bindings[5];
    for (int i = 0; i < 5; i++) {
        residual_yuv_bindings[i] = (VkDescriptorSetLayoutBinding){
            .binding = i,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .descriptorCount = 1,
            .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        };
    }
    VkDescriptorSetLayoutCreateInfo dslci_residual_yuv = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 5, .pBindings = residual_yuv_bindings,
    };
    VK_CHECK(vkCreateDescriptorSetLayout(vk->device, &dslci_residual_yuv, NULL, &vk->residual_yuv_dsl), fail);

    // --- Pipeline layouts (with push constants) ---
    VkPushConstantRange pc_med = {
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0, .size = sizeof(struct pc_median),
    };
    VkPipelineLayoutCreateInfo plci_med = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1, .pSetLayouts = &vk->median_dsl,
        .pushConstantRangeCount = 1, .pPushConstantRanges = &pc_med,
    };
    VK_CHECK(vkCreatePipelineLayout(vk->device, &plci_med, NULL, &vk->median_layout), fail);

    VkPushConstantRange pc_gauss = {
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0, .size = sizeof(struct pc_gauss),
    };
    VkPipelineLayoutCreateInfo plci_gauss = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1, .pSetLayouts = &vk->gauss_dsl,
        .pushConstantRangeCount = 1, .pPushConstantRanges = &pc_gauss,
    };
    VK_CHECK(vkCreatePipelineLayout(vk->device, &plci_gauss, NULL, &vk->gauss_layout), fail);

    VkPushConstantRange pc_warp = {
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0, .size = sizeof(struct pc_warp),
    };
    VkPipelineLayoutCreateInfo plci_warp = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1, .pSetLayouts = &vk->warp_dsl,
        .pushConstantRangeCount = 1, .pPushConstantRanges = &pc_warp,
    };
    VK_CHECK(vkCreatePipelineLayout(vk->device, &plci_warp, NULL, &vk->warp_layout), fail);

    VkPushConstantRange pc_warp_quant = {
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0, .size = sizeof(struct pc_warp_quant),
    };
    VkPipelineLayoutCreateInfo plci_warp_quant = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1, .pSetLayouts = &vk->warp_quant_dsl,
        .pushConstantRangeCount = 1, .pPushConstantRanges = &pc_warp_quant,
    };
    VK_CHECK(vkCreatePipelineLayout(vk->device, &plci_warp_quant, NULL, &vk->warp_quant_layout), fail);

    VkPushConstantRange pc_residual_yuv = {
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0, .size = sizeof(struct pc_residual_yuv),
    };
    VkPipelineLayoutCreateInfo plci_residual_yuv = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1, .pSetLayouts = &vk->residual_yuv_dsl,
        .pushConstantRangeCount = 1, .pPushConstantRanges = &pc_residual_yuv,
    };
    VK_CHECK(vkCreatePipelineLayout(vk->device, &plci_residual_yuv, NULL, &vk->residual_yuv_layout), fail);

    // --- Compute pipelines ---
    vk->median_pipe = vk_create_pipeline(vk->device, vk->median_layout,
                                          median5_spv, median5_spv_len);
    vk->gauss_pipe  = vk_create_pipeline(vk->device, vk->gauss_layout,
                                          gauss_sep_spv, gauss_sep_spv_len);
    vk->warp_pipe   = vk_create_pipeline(vk->device, vk->warp_layout,
                                          warp_pack_spv, warp_pack_spv_len);
    vk->warp_quant_pipe = vk_create_pipeline(vk->device, vk->warp_quant_layout,
                                              warp_pack_quant_spv, warp_pack_quant_spv_len);
    vk->residual_yuv_pipe = vk_create_pipeline(vk->device, vk->residual_yuv_layout,
                                                residual_yuv_spv, residual_yuv_spv_len);
    if (!vk->median_pipe || !vk->gauss_pipe || !vk->warp_pipe || !vk->warp_quant_pipe
        || !vk->residual_yuv_pipe) {
        MP_WARN(f, "Vulkan: pipeline creation failed\n");
        goto fail;
    }

    // --- SSBOs ---
    VkDeviceSize sm_size = (VkDeviceSize)vk->sW * vk->sH * sizeof(float);
    VkDeviceSize px_size = (VkDeviceSize)W * H * sizeof(float);
    VkDeviceSize nhwc_size = (VkDeviceSize)W * H * 6 * sizeof(float);

    // Estimate packed YUV420P frame size: Y + U + V
    // Use conservative stride = W (may differ at runtime, re-packed each frame)
    VkDeviceSize yuv_size = (VkDeviceSize)W * H * 3 / 2;
    // Round up to 4 bytes for uint32 access in shader
    yuv_size = (yuv_size + 3) & ~(VkDeviceSize)3;
    vk->yuv_buf_size = (size_t)yuv_size;

    if (vk_create_ssbo(vk->device, vk->physDev, &vk->sm_fx_a, &vk->sm_fx_a_mem,
                        (void **)&vk->sm_fx_a_ptr, sm_size) < 0 ||
        vk_create_ssbo(vk->device, vk->physDev, &vk->sm_fx_b, &vk->sm_fx_b_mem,
                        (void **)&vk->sm_fx_b_ptr, sm_size) < 0 ||
        vk_create_ssbo(vk->device, vk->physDev, &vk->sm_fy_a, &vk->sm_fy_a_mem,
                        (void **)&vk->sm_fy_a_ptr, sm_size) < 0 ||
        vk_create_ssbo(vk->device, vk->physDev, &vk->sm_fy_b, &vk->sm_fy_b_mem,
                        (void **)&vk->sm_fy_b_ptr, sm_size) < 0 ||
        vk_create_ssbo(vk->device, vk->physDev, &vk->prev_yuv_buf, &vk->prev_yuv_mem,
                        (void **)&vk->prev_yuv_ptr, yuv_size) < 0 ||
        vk_create_ssbo(vk->device, vk->physDev, &vk->curr_yuv_buf, &vk->curr_yuv_mem,
                        (void **)&vk->curr_yuv_ptr, yuv_size) < 0 ||
        vk_create_ssbo(vk->device, vk->physDev, &vk->nhwc_buf, &vk->nhwc_mem,
                        (void **)&vk->nhwc_ptr, nhwc_size) < 0 ||
        vk_create_ssbo(vk->device, vk->physDev, &vk->blend_r_buf, &vk->blend_r_mem,
                        (void **)&vk->blend_r_ptr, px_size) < 0 ||
        vk_create_ssbo(vk->device, vk->physDev, &vk->blend_g_buf, &vk->blend_g_mem,
                        (void **)&vk->blend_g_ptr, px_size) < 0 ||
        vk_create_ssbo(vk->device, vk->physDev, &vk->blend_b_buf, &vk->blend_b_mem,
                        (void **)&vk->blend_b_ptr, px_size) < 0)
    {
        MP_WARN(f, "Vulkan: SSBO allocation failed\n");
        goto fail;
    }

    // uint8 NHWC 6ch SSBO for quantized warp output (packed into uint32: 3 words per 2 pixels)
    vk->nhwc_u8_size = (VkDeviceSize)(((W * H * 6) + 3) / 4) * 4;  // round up to uint32
    if (vk_create_ssbo(vk->device, vk->physDev, &vk->nhwc_u8_buf, &vk->nhwc_u8_mem,
                        (void **)&vk->nhwc_u8_ptr, vk->nhwc_u8_size) < 0)
    {
        MP_WARN(f, "Vulkan: nhwc_u8 SSBO allocation failed\n");
        goto fail;
    }

    // QNN output upload SSBO (uint8 NHWC 3ch)
    vk->qnn_out_size = (VkDeviceSize)(((W * H * 3) + 3) / 4) * 4;
    // YUV output SSBOs
    vk->y_size = (VkDeviceSize)(((W * H) + 3) / 4) * 4;
    vk->u_size = (VkDeviceSize)((((W / 2) * (H / 2)) + 3) / 4) * 4;
    vk->v_size = vk->u_size;
    if (vk_create_ssbo(vk->device, vk->physDev, &vk->qnn_out_buf, &vk->qnn_out_mem,
                        (void **)&vk->qnn_out_ptr, vk->qnn_out_size) < 0 ||
        vk_create_ssbo(vk->device, vk->physDev, &vk->y_buf, &vk->y_mem,
                        (void **)&vk->y_ptr, vk->y_size) < 0 ||
        vk_create_ssbo(vk->device, vk->physDev, &vk->u_buf, &vk->u_mem,
                        (void **)&vk->u_ptr, vk->u_size) < 0 ||
        vk_create_ssbo(vk->device, vk->physDev, &vk->v_buf, &vk->v_mem,
                        (void **)&vk->v_ptr, vk->v_size) < 0)
    {
        MP_WARN(f, "Vulkan: residual_yuv SSBO allocation failed\n");
        goto fail;
    }

    // --- Descriptor pool ---
    // 2 median (2 SSBO each) + 4 gauss (2 SSBO each) + 1 warp (8 SSBO) + 1 warp_quant (5 SSBO)
    // + 1 residual_yuv (5 SSBO) = 30 descriptors, 9 sets
    VkDescriptorPoolSize poolSize = {
        .type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
        .descriptorCount = 30,
    };
    VkDescriptorPoolCreateInfo dpci = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = 9,
        .poolSizeCount = 1, .pPoolSizes = &poolSize,
    };
    VK_CHECK(vkCreateDescriptorPool(vk->device, &dpci, NULL, &vk->dpool), fail);

    // --- Allocate descriptor sets ---
    // 2 median sets
    {
        VkDescriptorSetLayout layouts[2] = { vk->median_dsl, vk->median_dsl };
        VkDescriptorSetAllocateInfo ai = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = vk->dpool,
            .descriptorSetCount = 2, .pSetLayouts = layouts,
        };
        VK_CHECK(vkAllocateDescriptorSets(vk->device, &ai, vk->median_ds), fail);
    }
    // 4 gauss sets
    {
        VkDescriptorSetLayout layouts[4] = { vk->gauss_dsl, vk->gauss_dsl,
                                              vk->gauss_dsl, vk->gauss_dsl };
        VkDescriptorSetAllocateInfo ai = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = vk->dpool,
            .descriptorSetCount = 4, .pSetLayouts = layouts,
        };
        VK_CHECK(vkAllocateDescriptorSets(vk->device, &ai, vk->gauss_ds), fail);
    }
    // 1 warp set
    {
        VkDescriptorSetAllocateInfo ai = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = vk->dpool,
            .descriptorSetCount = 1, .pSetLayouts = &vk->warp_dsl,
        };
        VK_CHECK(vkAllocateDescriptorSets(vk->device, &ai, &vk->warp_ds), fail);
    }
    // 1 warp_quant set
    {
        VkDescriptorSetAllocateInfo ai = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = vk->dpool,
            .descriptorSetCount = 1, .pSetLayouts = &vk->warp_quant_dsl,
        };
        VK_CHECK(vkAllocateDescriptorSets(vk->device, &ai, &vk->warp_quant_ds), fail);
    }
    // 1 residual_yuv set
    {
        VkDescriptorSetAllocateInfo ai = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = vk->dpool,
            .descriptorSetCount = 1, .pSetLayouts = &vk->residual_yuv_dsl,
        };
        VK_CHECK(vkAllocateDescriptorSets(vk->device, &ai, &vk->residual_yuv_ds), fail);
    }

    // --- Bind SSBOs to descriptor sets ---
    // median_ds[0]: fx: sm_fx_a(in) -> sm_fx_b(out)
    vk_write_ds_ssbo(vk->device, vk->median_ds[0], 0, vk->sm_fx_a, sm_size);
    vk_write_ds_ssbo(vk->device, vk->median_ds[0], 1, vk->sm_fx_b, sm_size);
    // median_ds[1]: fy: sm_fy_a(in) -> sm_fy_b(out)
    vk_write_ds_ssbo(vk->device, vk->median_ds[1], 0, vk->sm_fy_a, sm_size);
    vk_write_ds_ssbo(vk->device, vk->median_ds[1], 1, vk->sm_fy_b, sm_size);

    // gauss_ds[0]: fx_h: sm_fx_b(in) -> sm_fx_a(out)  (ping back)
    vk_write_ds_ssbo(vk->device, vk->gauss_ds[0], 0, vk->sm_fx_b, sm_size);
    vk_write_ds_ssbo(vk->device, vk->gauss_ds[0], 1, vk->sm_fx_a, sm_size);
    // gauss_ds[1]: fx_v: sm_fx_a(in) -> sm_fx_b(out)
    vk_write_ds_ssbo(vk->device, vk->gauss_ds[1], 0, vk->sm_fx_a, sm_size);
    vk_write_ds_ssbo(vk->device, vk->gauss_ds[1], 1, vk->sm_fx_b, sm_size);
    // gauss_ds[2]: fy_h: sm_fy_b(in) -> sm_fy_a(out)  (ping back)
    vk_write_ds_ssbo(vk->device, vk->gauss_ds[2], 0, vk->sm_fy_b, sm_size);
    vk_write_ds_ssbo(vk->device, vk->gauss_ds[2], 1, vk->sm_fy_a, sm_size);
    // gauss_ds[3]: fy_v: sm_fy_a(in) -> sm_fy_b(out)
    vk_write_ds_ssbo(vk->device, vk->gauss_ds[3], 0, vk->sm_fy_a, sm_size);
    vk_write_ds_ssbo(vk->device, vk->gauss_ds[3], 1, vk->sm_fy_b, sm_size);

    // warp_ds: reads sm_fx_b + sm_fy_b (final smoothed flow), prev/curr YUV, writes nhwc+blend
    vk_write_ds_ssbo(vk->device, vk->warp_ds, 0, vk->sm_fx_b, sm_size);  // flow_x
    vk_write_ds_ssbo(vk->device, vk->warp_ds, 1, vk->sm_fy_b, sm_size);  // flow_y
    vk_write_ds_ssbo(vk->device, vk->warp_ds, 2, vk->prev_yuv_buf, yuv_size);
    vk_write_ds_ssbo(vk->device, vk->warp_ds, 3, vk->curr_yuv_buf, yuv_size);
    vk_write_ds_ssbo(vk->device, vk->warp_ds, 4, vk->nhwc_buf, nhwc_size);
    vk_write_ds_ssbo(vk->device, vk->warp_ds, 5, vk->blend_r_buf, px_size);
    vk_write_ds_ssbo(vk->device, vk->warp_ds, 6, vk->blend_g_buf, px_size);
    vk_write_ds_ssbo(vk->device, vk->warp_ds, 7, vk->blend_b_buf, px_size);

    // warp_quant_ds: reads same flow/yuv, writes nhwc_u8
    vk_write_ds_ssbo(vk->device, vk->warp_quant_ds, 0, vk->sm_fx_b, sm_size);  // flow_x
    vk_write_ds_ssbo(vk->device, vk->warp_quant_ds, 1, vk->sm_fy_b, sm_size);  // flow_y
    vk_write_ds_ssbo(vk->device, vk->warp_quant_ds, 2, vk->prev_yuv_buf, yuv_size);
    vk_write_ds_ssbo(vk->device, vk->warp_quant_ds, 3, vk->curr_yuv_buf, yuv_size);
    vk_write_ds_ssbo(vk->device, vk->warp_quant_ds, 4, vk->nhwc_u8_buf, vk->nhwc_u8_size);

    // residual_yuv_ds: reads nhwc_u8 (blend input) + qnn_out, writes Y/U/V
    vk_write_ds_ssbo(vk->device, vk->residual_yuv_ds, 0, vk->nhwc_u8_buf, vk->nhwc_u8_size);
    vk_write_ds_ssbo(vk->device, vk->residual_yuv_ds, 1, vk->qnn_out_buf, vk->qnn_out_size);
    vk_write_ds_ssbo(vk->device, vk->residual_yuv_ds, 2, vk->y_buf, vk->y_size);
    vk_write_ds_ssbo(vk->device, vk->residual_yuv_ds, 3, vk->u_buf, vk->u_size);
    vk_write_ds_ssbo(vk->device, vk->residual_yuv_ds, 4, vk->v_buf, vk->v_size);

    vk->ready = 1;

    VkPhysicalDeviceProperties devProps;
    vkGetPhysicalDeviceProperties(vk->physDev, &devProps);
    MP_INFO(f, "Vulkan: GPU compute ready — %s, sm=%dx%d\n",
            devProps.deviceName, vk->sW, vk->sH);
    return 0;

fail:
    // Partial cleanup handled by vk_cleanup later
    return -1;
}

// Pack YUV420P planes into a contiguous byte buffer for SSBO
// Returns packed byte offsets: u_off, v_off, effective y_stride, uv_stride
static void vk_pack_yuv(uint8_t *dst, const uint8_t *Y, int ys,
                          const uint8_t *U, int us, const uint8_t *V, int vs,
                          int W, int H,
                          int *out_y_stride, int *out_uv_stride,
                          int *out_u_off, int *out_v_off)
{
    // Pack Y plane contiguously (stride = W)
    *out_y_stride = W;
    for (int y = 0; y < H; y++)
        memcpy(dst + y * W, Y + y * ys, W);

    int cW = W >> 1, cH = H >> 1;
    *out_uv_stride = cW;
    *out_u_off = W * H;
    *out_v_off = W * H + cW * cH;

    for (int y = 0; y < cH; y++)
        memcpy(dst + *out_u_off + y * cW, U + y * us, cW);
    for (int y = 0; y < cH; y++)
        memcpy(dst + *out_v_off + y * cW, V + y * vs, cW);
}

// Record and submit the GPU command buffer for Phase 1b + Phase 2
// use_quant: 0 = float warp (warp_pack), 1 = uint8 quantized warp (warp_pack_quant)
static void vk_dispatch(struct vk_compute *vk, int y_stride, int uv_stride,
                         int u_off, int v_off,
                         int use_quant, float inv_scale, int offset)
{
    VkCommandBufferBeginInfo beginInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    vkBeginCommandBuffer(vk->cmd, &beginInfo);

    VkMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_HOST_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
    };
    vkCmdPipelineBarrier(vk->cmd,
        VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &barrier, 0, NULL, 0, NULL);

    struct pc_median pcm = { .width = vk->sW, .height = vk->sH };

    // Workgroup sizes: median/warp use 16x16, gauss uses 256x1
    uint32_t gx_sm = (vk->sW + 15) / 16;
    uint32_t gy_sm = (vk->sH + 15) / 16;
    uint32_t gx_gauss = (vk->sW + 255) / 256;
    uint32_t gy_gauss = vk->sH;

    VkMemoryBarrier compute_barrier = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
    };

    // --- Median on flow_x: sm_fx_a -> sm_fx_b ---
    vkCmdBindPipeline(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vk->median_pipe);
    vkCmdBindDescriptorSets(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        vk->median_layout, 0, 1, &vk->median_ds[0], 0, NULL);
    vkCmdPushConstants(vk->cmd, vk->median_layout,
        VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcm), &pcm);
    vkCmdDispatch(vk->cmd, gx_sm, gy_sm, 1);

    vkCmdPipelineBarrier(vk->cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &compute_barrier, 0, NULL, 0, NULL);

    // --- Median on flow_y: sm_fy_a -> sm_fy_b ---
    vkCmdBindDescriptorSets(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        vk->median_layout, 0, 1, &vk->median_ds[1], 0, NULL);
    vkCmdPushConstants(vk->cmd, vk->median_layout,
        VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcm), &pcm);
    vkCmdDispatch(vk->cmd, gx_sm, gy_sm, 1);

    vkCmdPipelineBarrier(vk->cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &compute_barrier, 0, NULL, 0, NULL);

    // --- Gaussian H on flow_x: sm_fx_b -> sm_fx_a ---
    struct pc_gauss pcg = { .width = vk->sW, .height = vk->sH, .dir = 0 };
    vkCmdBindPipeline(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vk->gauss_pipe);
    vkCmdBindDescriptorSets(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        vk->gauss_layout, 0, 1, &vk->gauss_ds[0], 0, NULL);
    vkCmdPushConstants(vk->cmd, vk->gauss_layout,
        VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcg), &pcg);
    vkCmdDispatch(vk->cmd, gx_gauss, gy_gauss, 1);

    vkCmdPipelineBarrier(vk->cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &compute_barrier, 0, NULL, 0, NULL);

    // --- Gaussian V on flow_x: sm_fx_a -> sm_fx_b ---
    pcg.dir = 1;
    vkCmdBindDescriptorSets(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        vk->gauss_layout, 0, 1, &vk->gauss_ds[1], 0, NULL);
    vkCmdPushConstants(vk->cmd, vk->gauss_layout,
        VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcg), &pcg);
    vkCmdDispatch(vk->cmd, gx_gauss, gy_gauss, 1);

    vkCmdPipelineBarrier(vk->cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &compute_barrier, 0, NULL, 0, NULL);

    // --- Gaussian H on flow_y: sm_fy_b -> sm_fy_a ---
    pcg.dir = 0;
    vkCmdBindDescriptorSets(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        vk->gauss_layout, 0, 1, &vk->gauss_ds[2], 0, NULL);
    vkCmdPushConstants(vk->cmd, vk->gauss_layout,
        VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcg), &pcg);
    vkCmdDispatch(vk->cmd, gx_gauss, gy_gauss, 1);

    vkCmdPipelineBarrier(vk->cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &compute_barrier, 0, NULL, 0, NULL);

    // --- Gaussian V on flow_y: sm_fy_a -> sm_fy_b ---
    pcg.dir = 1;
    vkCmdBindDescriptorSets(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        vk->gauss_layout, 0, 1, &vk->gauss_ds[3], 0, NULL);
    vkCmdPushConstants(vk->cmd, vk->gauss_layout,
        VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcg), &pcg);
    vkCmdDispatch(vk->cmd, gx_gauss, gy_gauss, 1);

    vkCmdPipelineBarrier(vk->cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &compute_barrier, 0, NULL, 0, NULL);

    // --- Warp+pack: full resolution ---
    if (use_quant) {
        // Quantized path: warp_pack_quant shader outputs uint8 NHWC
        struct pc_warp_quant pcwq = {
            .W = vk->W, .H = vk->H, .sW = vk->sW, .sH = vk->sH,
            .y_stride = y_stride, .uv_stride = uv_stride,
            .u_off = u_off, .v_off = v_off,
            .inv_scale = inv_scale, .offset = offset,
        };
        // Each invocation handles 2 pixels; workgroup is (8,16,1)
        uint32_t gx_wq = ((vk->W / 2) + 7) / 8;
        uint32_t gy_wq = (vk->H + 15) / 16;

        vkCmdBindPipeline(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vk->warp_quant_pipe);
        vkCmdBindDescriptorSets(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
            vk->warp_quant_layout, 0, 1, &vk->warp_quant_ds, 0, NULL);
        vkCmdPushConstants(vk->cmd, vk->warp_quant_layout,
            VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcwq), &pcwq);
        vkCmdDispatch(vk->cmd, gx_wq, gy_wq, 1);
    } else {
        // Float path: warp_pack shader outputs float NHWC + blend
        struct pc_warp pcw = {
            .W = vk->W, .H = vk->H, .sW = vk->sW, .sH = vk->sH,
            .y_stride = y_stride, .uv_stride = uv_stride,
            .u_off = u_off, .v_off = v_off,
        };
        uint32_t gx_warp = (vk->W + 15) / 16;
        uint32_t gy_warp = (vk->H + 15) / 16;

        vkCmdBindPipeline(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vk->warp_pipe);
        vkCmdBindDescriptorSets(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
            vk->warp_layout, 0, 1, &vk->warp_ds, 0, NULL);
        vkCmdPushConstants(vk->cmd, vk->warp_layout,
            VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcw), &pcw);
        vkCmdDispatch(vk->cmd, gx_warp, gy_warp, 1);
    }

    // Final barrier: GPU writes visible to host
    VkMemoryBarrier host_barrier = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
    };
    vkCmdPipelineBarrier(vk->cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
        0, 1, &host_barrier, 0, NULL, 0, NULL);

    vkEndCommandBuffer(vk->cmd);

    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1, .pCommandBuffers = &vk->cmd,
    };
    vkQueueSubmit(vk->queue, 1, &submit, VK_NULL_HANDLE);
    vkQueueWaitIdle(vk->queue);
}

// Record and submit the GPU command buffer for Phase 4 residual+YUV conversion
static void vk_dispatch_residual_yuv(struct vk_compute *vk, struct pc_residual_yuv *pc)
{
    vkResetCommandBuffer(vk->cmd, 0);

    VkCommandBufferBeginInfo beginInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    vkBeginCommandBuffer(vk->cmd, &beginInfo);

    // Host->shader barrier (input data was written via memcpy)
    VkMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_HOST_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
    };
    vkCmdPipelineBarrier(vk->cmd,
        VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &barrier, 0, NULL, 0, NULL);

    vkCmdBindPipeline(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vk->residual_yuv_pipe);
    vkCmdBindDescriptorSets(vk->cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        vk->residual_yuv_layout, 0, 1, &vk->residual_yuv_ds, 0, NULL);
    vkCmdPushConstants(vk->cmd, vk->residual_yuv_layout,
        VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(*pc), pc);

    // local_size_x=16, local_size_y=8; each invocation handles one 2x2 chroma block
    uint32_t gx = ((pc->W / 2) + 15) / 16;
    uint32_t gy = ((pc->H / 2) + 7) / 8;
    vkCmdDispatch(vk->cmd, gx, gy, 1);

    // Shader->host barrier
    VkMemoryBarrier host_barrier = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
    };
    vkCmdPipelineBarrier(vk->cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
        0, 1, &host_barrier, 0, NULL, 0, NULL);

    vkEndCommandBuffer(vk->cmd);

    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1, .pCommandBuffers = &vk->cmd,
    };
    vkQueueSubmit(vk->queue, 1, &submit, VK_NULL_HANDLE);
    vkQueueWaitIdle(vk->queue);
}

static void vk_cleanup(struct vk_compute *vk)
{
    if (!vk || !vk->device) return;
    vkDeviceWaitIdle(vk->device);

    if (vk->median_pipe)  vkDestroyPipeline(vk->device, vk->median_pipe, NULL);
    if (vk->gauss_pipe)   vkDestroyPipeline(vk->device, vk->gauss_pipe, NULL);
    if (vk->warp_pipe)    vkDestroyPipeline(vk->device, vk->warp_pipe, NULL);
    if (vk->warp_quant_pipe) vkDestroyPipeline(vk->device, vk->warp_quant_pipe, NULL);
    if (vk->residual_yuv_pipe) vkDestroyPipeline(vk->device, vk->residual_yuv_pipe, NULL);
    if (vk->median_layout) vkDestroyPipelineLayout(vk->device, vk->median_layout, NULL);
    if (vk->gauss_layout)  vkDestroyPipelineLayout(vk->device, vk->gauss_layout, NULL);
    if (vk->warp_layout)   vkDestroyPipelineLayout(vk->device, vk->warp_layout, NULL);
    if (vk->warp_quant_layout) vkDestroyPipelineLayout(vk->device, vk->warp_quant_layout, NULL);
    if (vk->residual_yuv_layout) vkDestroyPipelineLayout(vk->device, vk->residual_yuv_layout, NULL);
    if (vk->median_dsl)   vkDestroyDescriptorSetLayout(vk->device, vk->median_dsl, NULL);
    if (vk->gauss_dsl)    vkDestroyDescriptorSetLayout(vk->device, vk->gauss_dsl, NULL);
    if (vk->warp_dsl)     vkDestroyDescriptorSetLayout(vk->device, vk->warp_dsl, NULL);
    if (vk->warp_quant_dsl) vkDestroyDescriptorSetLayout(vk->device, vk->warp_quant_dsl, NULL);
    if (vk->residual_yuv_dsl) vkDestroyDescriptorSetLayout(vk->device, vk->residual_yuv_dsl, NULL);
    if (vk->dpool)        vkDestroyDescriptorPool(vk->device, vk->dpool, NULL);
    if (vk->cmdPool)      vkDestroyCommandPool(vk->device, vk->cmdPool, NULL);

    vk_free_ssbo(vk->device, vk->sm_fx_a, vk->sm_fx_a_mem);
    vk_free_ssbo(vk->device, vk->sm_fx_b, vk->sm_fx_b_mem);
    vk_free_ssbo(vk->device, vk->sm_fy_a, vk->sm_fy_a_mem);
    vk_free_ssbo(vk->device, vk->sm_fy_b, vk->sm_fy_b_mem);
    vk_free_ssbo(vk->device, vk->prev_yuv_buf, vk->prev_yuv_mem);
    vk_free_ssbo(vk->device, vk->curr_yuv_buf, vk->curr_yuv_mem);
    vk_free_ssbo(vk->device, vk->nhwc_buf, vk->nhwc_mem);
    vk_free_ssbo(vk->device, vk->blend_r_buf, vk->blend_r_mem);
    vk_free_ssbo(vk->device, vk->blend_g_buf, vk->blend_g_mem);
    vk_free_ssbo(vk->device, vk->blend_b_buf, vk->blend_b_mem);
    vk_free_ssbo(vk->device, vk->nhwc_u8_buf, vk->nhwc_u8_mem);
    vk_free_ssbo(vk->device, vk->qnn_out_buf, vk->qnn_out_mem);
    vk_free_ssbo(vk->device, vk->y_buf, vk->y_mem);
    vk_free_ssbo(vk->device, vk->u_buf, vk->u_mem);
    vk_free_ssbo(vk->device, vk->v_buf, vk->v_mem);

    vkDestroyDevice(vk->device, NULL);
    if (vk->instance) vkDestroyInstance(vk->instance, NULL);
    memset(vk, 0, sizeof(*vk));
}

// ====================================================================
// Section 3: Prealign v2 functions (frozen recipe, CPU fallback)
// ====================================================================

// Dense flow from AVMotionVector[]: ZOH fill
static void zoh_fill(const AVMotionVector *mvs, int n_mvs,
                     float *fx, float *fy, int H, int W)
{
    memset(fx, 0, H * W * sizeof(float));
    memset(fy, 0, H * W * sizeof(float));
    for (int i = 0; i < n_mvs; i++) {
        const AVMotionVector *m = &mvs[i];
        if (m->source != -1) continue;
        float scale = m->motion_scale > 0 ? (float)m->motion_scale : 1.0f;
        float mx = -(float)m->motion_x / scale;
        float my = -(float)m->motion_y / scale;
        int bx = m->dst_x - m->w / 2, by = m->dst_y - m->h / 2;
        int x0 = bx < 0 ? 0 : bx, y0 = by < 0 ? 0 : by;
        int x1 = bx + m->w > W ? W : bx + m->w;
        int y1 = by + m->h > H ? H : by + m->h;
        for (int y = y0; y < y1; y++) {
            float *fx_row = fx + y * W + x0;
            float *fy_row = fy + y * W + x0;
            int w = x1 - x0;
#ifdef __ARM_NEON
            float32x4_t vmx = vdupq_n_f32(mx);
            float32x4_t vmy = vdupq_n_f32(my);
            int j = 0;
            for (; j + 4 <= w; j += 4) {
                vst1q_f32(fx_row + j, vmx);
                vst1q_f32(fy_row + j, vmy);
            }
            for (; j < w; j++) {
                fx_row[j] = mx;
                fy_row[j] = my;
            }
#else
            for (int j = 0; j < w; j++) {
                fx_row[j] = mx;
                fy_row[j] = my;
            }
#endif
        }
    }
}

// Nearest-neighbor downsample (NEON-optimized for s=4)
static void downsample(const float *in, float *out, int H, int W, int s,
                       int *oH, int *oW)
{
    *oH = H / s; *oW = W / s;
#ifdef __ARM_NEON
    if (s == 4) {
        // Gather every 4th float: stride=16 bytes between source elements
        for (int y = 0; y < *oH; y++) {
            const float *row = in + y * s * W;
            float *dst = out + y * (*oW);
            int x = 0;
            for (; x + 4 <= *oW; x += 4) {
                // Load 4 source elements spaced 4 apart (indices 0,4,8,12)
                float32x4_t v = {row[x * 4], row[(x + 1) * 4],
                                 row[(x + 2) * 4], row[(x + 3) * 4]};
                vst1q_f32(dst + x, v);
            }
            for (; x < *oW; x++)
                dst[x] = row[x * 4];
        }
    } else
#endif
    {
        for (int y = 0; y < *oH; y++)
            for (int x = 0; x < *oW; x++)
                out[y * (*oW) + x] = in[y * s * W + x * s];
    }
}

// Median filter 2D — uses partial insertion sort (much faster than qsort for k=25)
static void median_2d(const float *in, float *out, int H, int W, int k) {
    int half = k / 2;
    int karea = k * k;
    int med_idx = karea / 2;
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) {
            float buf[PA_MED_K * PA_MED_K];
            int cnt = 0;
            for (int ky = -half; ky <= half; ky++) {
                int yy = y + ky; if (yy < 0) yy = 0; if (yy >= H) yy = H - 1;
                for (int kx = -half; kx <= half; kx++) {
                    int xx = x + kx; if (xx < 0) xx = 0; if (xx >= W) xx = W - 1;
                    buf[cnt++] = in[yy * W + xx];
                }
            }
            // Partial selection: find median via partial insertion sort
            // For 25 elements, this is faster than qsort (no function pointer overhead)
            for (int i = 0; i <= med_idx; i++) {
                int min_j = i;
                for (int j = i + 1; j < cnt; j++)
                    if (buf[j] < buf[min_j]) min_j = j;
                if (min_j != i) { float t = buf[i]; buf[i] = buf[min_j]; buf[min_j] = t; }
            }
            out[y * W + x] = buf[med_idx];
        }
}

// Gaussian blur separable
static void gauss_sep(float *data, float *tmp, int H, int W, float sigma) {
    int ks = (int)(sigma * 6.0f) | 1;
    if (ks < 3) ks = 3; if (ks > 49) ks = 49;
    int half = ks / 2;
    float kern[50], sum = 0;
    for (int i = 0; i < ks; i++) {
        float d = (float)(i - half);
        kern[i] = expf(-d * d / (2.0f * sigma * sigma));
        sum += kern[i];
    }
    for (int i = 0; i < ks; i++) kern[i] /= sum;
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) {
            float s = 0;
            for (int k = -half; k <= half; k++) {
                int xx = x + k; if (xx < 0) xx = 0; if (xx >= W) xx = W - 1;
                s += data[y * W + xx] * kern[k + half];
            }
            tmp[y * W + x] = s;
        }
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) {
            float s = 0;
            for (int k = -half; k <= half; k++) {
                int yy = y + k; if (yy < 0) yy = 0; if (yy >= H) yy = H - 1;
                s += tmp[yy * W + x] * kern[k + half];
            }
            data[y * W + x] = s;
        }
}

// Bilinear upsample
static void upsample(const float *in, float *out, int iH, int iW, int oH, int oW) {
    float sy = (float)iH / oH, sx = (float)iW / oW;
    for (int y = 0; y < oH; y++) {
        float fy = y * sy; int y0 = (int)fy, y1 = y0 + 1; float wy = fy - y0;
        if (y0 >= iH) y0 = iH - 1; if (y1 >= iH) y1 = iH - 1;
        for (int x = 0; x < oW; x++) {
            float fx = x * sx; int x0 = (int)fx, x1 = x0 + 1; float wx = fx - x0;
            if (x0 >= iW) x0 = iW - 1; if (x1 >= iW) x1 = iW - 1;
            out[y * oW + x] = (1-wx)*(1-wy)*in[y0*iW+x0] + wx*(1-wy)*in[y0*iW+x1]
                             + (1-wx)*wy*in[y1*iW+x0] + wx*wy*in[y1*iW+x1];
        }
    }
}

// ====================================================================
// Section 4: Inline bilinear YUV sampling helpers
// ====================================================================

// Bilinear sample a single uint8 plane with float coords.
// Clamps to image bounds. Used for Y, U, V planes independently.
static inline float sample_plane_bilinear(const uint8_t *plane, int stride,
                                           int W, int H, float sx, float sy)
{
    int x0 = (int)floorf(sx), y0 = (int)floorf(sy);
    int x1 = x0 + 1, y1 = y0 + 1;
    float wx = sx - x0, wy = sy - y0;
    if (x0 < 0) x0 = 0; if (x0 >= W) x0 = W - 1;
    if (x1 < 0) x1 = 0; if (x1 >= W) x1 = W - 1;
    if (y0 < 0) y0 = 0; if (y0 >= H) y0 = H - 1;
    if (y1 < 0) y1 = 0; if (y1 >= H) y1 = H - 1;
    float v00 = (float)plane[y0 * stride + x0];
    float v10 = (float)plane[y0 * stride + x1];
    float v01 = (float)plane[y1 * stride + x0];
    float v11 = (float)plane[y1 * stride + x1];
    return (1 - wx) * (1 - wy) * v00 + wx * (1 - wy) * v10
         + (1 - wx) * wy * v01       + wx * wy * v11;
}

// Sample a YUV420P pixel at fractional coords, convert to RGB [0,1].
// Handles Y at full res, U/V at half res.
static inline void sample_yuv_to_rgb(const uint8_t *Y, int y_stride,
                                      const uint8_t *U, int u_stride,
                                      const uint8_t *V, int v_stride,
                                      int W, int H,
                                      float sx, float sy,
                                      float *out_r, float *out_g, float *out_b)
{
    float yv = sample_plane_bilinear(Y, y_stride, W, H, sx, sy);
    // Chroma planes are half resolution
    int cW = W >> 1, cH = H >> 1;
    float csx = sx * 0.5f, csy = sy * 0.5f;
    float uv = sample_plane_bilinear(U, u_stride, cW, cH, csx, csy);
    float vv = sample_plane_bilinear(V, v_stride, cW, cH, csx, csy);

    float yf = 1.164f * (yv - 16.0f);
    float uf = uv - 128.0f;
    float vf = vv - 128.0f;
    *out_r = (yf + 1.793f * vf) / 255.0f;
    *out_g = (yf - 0.213f * uf - 0.533f * vf) / 255.0f;
    *out_b = (yf + 2.112f * uf) / 255.0f;
}

// ====================================================================
// Section 5: Multi-threaded fused Phase 2 — warp+yuv2rgb+blend+pack+quant
// ====================================================================

struct phase2_args {
    // Row range for this thread
    int y_start, y_end;
    int W, H;

    // Source YUV planes (prev and curr)
    const uint8_t *prev_Y, *prev_U, *prev_V;
    int prev_ys, prev_us, prev_vs;
    const uint8_t *curr_Y, *curr_U, *curr_V;
    int curr_ys, curr_us, curr_vs;

    // Flow field
    const float *flow_x, *flow_y;

    // Output: quantized NHWC uint8 for QNN (NULL if non-quantized)
    uint8_t *in_quant_buf;
    float in_inv_scale;
    int32_t in_offset;

    // Output: float NHWC for QNN (non-quantized path, NULL if quantized)
    float *input_buf;

    // Output: float blend RGB planar (for residual addition in Phase 4)
    float *blend_r, *blend_g, *blend_b;

    // Whether QNN uses quantized input
    int quantized;
};

static void *phase2_thread_fn(void *arg)
{
    struct phase2_args *a = (struct phase2_args *)arg;
    const int W = a->W, H = a->H;

    for (int y = a->y_start; y < a->y_end; y++) {
        for (int x = 0; x < W; x++) {
            int idx = y * W + x;
            float fxx = a->flow_x[idx] * 0.5f;
            float fyy = a->flow_y[idx] * 0.5f;

            // Warp prev by -flow/2 (backward)
            float sx0 = (float)x - fxx;
            float sy0 = (float)y - fyy;
            float r0, g0, b0;
            sample_yuv_to_rgb(a->prev_Y, a->prev_ys,
                              a->prev_U, a->prev_us,
                              a->prev_V, a->prev_vs,
                              W, H, sx0, sy0, &r0, &g0, &b0);

            // Warp curr by +flow/2 (forward)
            float sx1 = (float)x + fxx;
            float sy1 = (float)y + fyy;
            float r1, g1, b1;
            sample_yuv_to_rgb(a->curr_Y, a->curr_ys,
                              a->curr_U, a->curr_us,
                              a->curr_V, a->curr_vs,
                              W, H, sx1, sy1, &r1, &g1, &b1);

            // Blend: (warp0 + warp1) / 2  — stored for Phase 4
            float br = (r0 + r1) * 0.5f;
            float bg = (g0 + g1) * 0.5f;
            float bb = (b0 + b1) * 0.5f;
            a->blend_r[idx] = br;
            a->blend_g[idx] = bg;
            a->blend_b[idx] = bb;

            // Pack NHWC 6ch and optionally quantize
            if (a->quantized) {
                uint8_t *dst = a->in_quant_buf + idx * 6;
                float inv_s = a->in_inv_scale;
                int32_t off = a->in_offset;
                // Quantize: q = clamp(round(val / scale) - offset, 0, 255)
                #define QUANT_U8(val) do { \
                    float _v = roundf((val) * inv_s) - (float)off; \
                    int _iv = (int)_v; \
                    *dst++ = (uint8_t)(_iv < 0 ? 0 : (_iv > 255 ? 255 : _iv)); \
                } while(0)
                QUANT_U8(r0); QUANT_U8(g0); QUANT_U8(b0);
                QUANT_U8(r1); QUANT_U8(g1); QUANT_U8(b1);
                #undef QUANT_U8
            } else {
                float *dst = a->input_buf + idx * 6;
                dst[0] = r0; dst[1] = g0; dst[2] = b0;
                dst[3] = r1; dst[4] = g1; dst[5] = b1;
            }
        }
    }
    return NULL;
}

// ====================================================================
// Section 6: Multi-threaded fused Phase 4 — dequant+residual+clamp+rgb2yuv
// ====================================================================

struct phase4_args {
    int y_start, y_end;
    int W, H;

    // Blend from Phase 2 (NULL when GPU quantized path — derive from nhwc_quant)
    const float *blend_r, *blend_g, *blend_b;

    // GPU quantized path: derive blend inline from QNN uint8 input buffer
    const uint8_t *nhwc_quant;  // non-NULL = GPU quantized path
    float in_scale;
    int32_t in_offset;

    // QNN output (quantized or float NHWC 3ch)
    const uint8_t *out_quant_buf;
    const float *output_buf;
    float out_scale;
    int32_t out_offset;
    int output_quantized;
    int qnn_ok;  // whether QNN ran successfully

    // Output YUV planes
    uint8_t *out_Y, *out_U, *out_V;
    int out_ys, out_us, out_vs;
};

#ifdef __ARM_NEON
// NEON-accelerated RGB[0,1] -> Y row conversion, 4 pixels at a time
static inline void rgb_to_y_row_neon(const float *r_row, const float *g_row,
                                      const float *b_row, uint8_t *y_out,
                                      int W)
{
    const float32x4_t v_yr = vdupq_n_f32(BT709_YR);
    const float32x4_t v_yg = vdupq_n_f32(BT709_YG);
    const float32x4_t v_yb = vdupq_n_f32(BT709_YB);
    const float32x4_t v_16 = vdupq_n_f32(16.0f);
    const float32x4_t v_half = vdupq_n_f32(0.5f);
    const float32x4_t v_0 = vdupq_n_f32(0.0f);
    const float32x4_t v_255 = vdupq_n_f32(255.0f);

    int x = 0;
    for (; x + 3 < W; x += 4) {
        float32x4_t r = vld1q_f32(r_row + x);
        float32x4_t g = vld1q_f32(g_row + x);
        float32x4_t b = vld1q_f32(b_row + x);
        // Y = yr*r + yg*g + yb*b + 16
        float32x4_t yv = vmlaq_f32(v_16, r, v_yr);
        yv = vmlaq_f32(yv, g, v_yg);
        yv = vmlaq_f32(yv, b, v_yb);
        yv = vaddq_f32(yv, v_half);  // for rounding
        yv = vmaxq_f32(yv, v_0);
        yv = vminq_f32(yv, v_255);
        uint32x4_t yi = vcvtq_u32_f32(yv);
        uint16x4_t yi16 = vmovn_u32(yi);
        uint8x8_t yi8 = vmovn_u16(vcombine_u16(yi16, yi16));
        // Store only lower 4 bytes
        vst1_lane_u32((uint32_t *)(y_out + x), vreinterpret_u32_u8(yi8), 0);
    }
    // Scalar tail
    for (; x < W; x++) {
        float yv = BT709_YR * r_row[x] + BT709_YG * g_row[x] + BT709_YB * b_row[x] + 16.0f;
        int iv = (int)(yv + 0.5f);
        y_out[x] = (uint8_t)(iv < 0 ? 0 : (iv > 255 ? 255 : iv));
    }
}
#endif

static void *phase4_thread_fn(void *arg)
{
    struct phase4_args *a = (struct phase4_args *)arg;
    const int W = a->W, H = a->H;

    // We need temporary RGB rows for chroma subsampling (2x2 averaging).
    // Process rows and write Y immediately. For U/V, we accumulate over pairs.
    // Since each thread handles a contiguous row range, and chroma needs 2-row
    // pairs, we process Y for all rows and U/V for aligned pairs in range.

    for (int y = a->y_start; y < a->y_end; y++) {
        uint8_t *yrow = a->out_Y + y * a->out_ys;

        // Temporary storage for this row's final RGB (after residual + clamp)
        // Stored in planar layout for easy chroma processing
        float r_row[1920], g_row[1920], b_row[1920];  // max 1080p width
        // For wider frames, fall back to heap — but 1920 covers 1080p
        float *rr = W <= 1920 ? r_row : (float *)malloc(W * sizeof(float));
        float *gg = W <= 1920 ? g_row : (float *)malloc(W * sizeof(float));
        float *bb = W <= 1920 ? b_row : (float *)malloc(W * sizeof(float));

        for (int x = 0; x < W; x++) {
            int idx = y * W + x;
            float br, bg, bbb;

            if (a->nhwc_quant) {
                // GPU quantized path: derive blend from uint8 NHWC input buffer
                const uint8_t *qp = a->nhwc_quant + idx * 6;
                float r0 = a->in_scale * ((float)qp[0] + (float)a->in_offset);
                float g0 = a->in_scale * ((float)qp[1] + (float)a->in_offset);
                float b0 = a->in_scale * ((float)qp[2] + (float)a->in_offset);
                float r1 = a->in_scale * ((float)qp[3] + (float)a->in_offset);
                float g1 = a->in_scale * ((float)qp[4] + (float)a->in_offset);
                float b1 = a->in_scale * ((float)qp[5] + (float)a->in_offset);
                br  = (r0 + r1) * 0.5f;
                bg  = (g0 + g1) * 0.5f;
                bbb = (b0 + b1) * 0.5f;
            } else {
                br  = a->blend_r[idx];
                bg  = a->blend_g[idx];
                bbb = a->blend_b[idx];
            }

            // Add QNN residual if available
            if (a->qnn_ok) {
                float res_r, res_g, res_b;
                if (a->output_quantized) {
                    // Dequantize: val = scale * (quant + offset)
                    const uint8_t *qp = a->out_quant_buf + idx * 3;
                    res_r = a->out_scale * ((float)qp[0] + (float)a->out_offset);
                    res_g = a->out_scale * ((float)qp[1] + (float)a->out_offset);
                    res_b = a->out_scale * ((float)qp[2] + (float)a->out_offset);
                } else {
                    const float *fp = a->output_buf + idx * 3;
                    res_r = fp[0]; res_g = fp[1]; res_b = fp[2];
                }
                br += res_r;
                bg += res_g;
                bbb += res_b;
            }

            // Clamp to [0,1]
            if (br < 0.0f) br = 0.0f; if (br > 1.0f) br = 1.0f;
            if (bg < 0.0f) bg = 0.0f; if (bg > 1.0f) bg = 1.0f;
            if (bbb < 0.0f) bbb = 0.0f; if (bbb > 1.0f) bbb = 1.0f;

            rr[x] = br;
            gg[x] = bg;
            bb[x] = bbb;
        }

        // Convert to Y
#ifdef __ARM_NEON
        rgb_to_y_row_neon(rr, gg, bb, yrow, W);
#else
        for (int x = 0; x < W; x++) {
            float yv = BT709_YR * rr[x] + BT709_YG * gg[x] + BT709_YB * bb[x] + 16.0f;
            int iv = (int)(yv + 0.5f);
            yrow[x] = (uint8_t)(iv < 0 ? 0 : (iv > 255 ? 255 : iv));
        }
#endif

        // Convert to U/V on even rows (process 2x2 blocks)
        if ((y & 1) == 1 && y >= a->y_start + 1) {
            int cy = y >> 1;
            uint8_t *urow = a->out_U + cy * a->out_us;
            uint8_t *vrow = a->out_V + cy * a->out_vs;
            int cW = W >> 1;

            // We need the previous row's RGB too. Re-derive from blend+residual.
            // To avoid re-computing, we could store 2 rows, but that adds
            // complexity. For the prev row (y-1), we access blend directly.
            int prev_y = y - 1;

            for (int cx = 0; cx < cW; cx++) {
                float ru = 0, gu = 0, bu = 0;
                // Current row (y): rr[cx*2], rr[cx*2+1]
                ru += rr[cx * 2] + rr[cx * 2 + 1];
                gu += gg[cx * 2] + gg[cx * 2 + 1];
                bu += bb[cx * 2] + bb[cx * 2 + 1];

                // Previous row (y-1): re-derive from blend+residual
                for (int dx = 0; dx < 2; dx++) {
                    int pidx = prev_y * W + cx * 2 + dx;
                    float pr, pg, pb;
                    if (a->nhwc_quant) {
                        const uint8_t *iq = a->nhwc_quant + pidx * 6;
                        float r0 = a->in_scale * ((float)iq[0] + (float)a->in_offset);
                        float g0 = a->in_scale * ((float)iq[1] + (float)a->in_offset);
                        float b0 = a->in_scale * ((float)iq[2] + (float)a->in_offset);
                        float r1 = a->in_scale * ((float)iq[3] + (float)a->in_offset);
                        float g1 = a->in_scale * ((float)iq[4] + (float)a->in_offset);
                        float b1 = a->in_scale * ((float)iq[5] + (float)a->in_offset);
                        pr = (r0 + r1) * 0.5f;
                        pg = (g0 + g1) * 0.5f;
                        pb = (b0 + b1) * 0.5f;
                    } else {
                        pr = a->blend_r[pidx];
                        pg = a->blend_g[pidx];
                        pb = a->blend_b[pidx];
                    }
                    if (a->qnn_ok) {
                        if (a->output_quantized) {
                            const uint8_t *qp = a->out_quant_buf + pidx * 3;
                            pr += a->out_scale * ((float)qp[0] + (float)a->out_offset);
                            pg += a->out_scale * ((float)qp[1] + (float)a->out_offset);
                            pb += a->out_scale * ((float)qp[2] + (float)a->out_offset);
                        } else {
                            const float *fp = a->output_buf + pidx * 3;
                            pr += fp[0]; pg += fp[1]; pb += fp[2];
                        }
                    }
                    if (pr < 0.0f) pr = 0.0f; if (pr > 1.0f) pr = 1.0f;
                    if (pg < 0.0f) pg = 0.0f; if (pg > 1.0f) pg = 1.0f;
                    if (pb < 0.0f) pb = 0.0f; if (pb > 1.0f) pb = 1.0f;
                    ru += pr;
                    gu += pg;
                    bu += pb;
                }

                // Average 4 pixels, scale to [0,255]
                ru *= 255.0f / 4.0f;
                gu *= 255.0f / 4.0f;
                bu *= 255.0f / 4.0f;

                // ru/gu/bu are in [0,255] domain (averaged 2x2 * 255/4)
                float uv = -0.101f * ru - 0.339f * gu + 0.439f * bu + 128.0f;
                float vv =  0.439f * ru - 0.399f * gu - 0.040f * bu + 128.0f;

                int iu = (int)(uv + 0.5f);
                int iv = (int)(vv + 0.5f);
                urow[cx] = (uint8_t)(iu < 0 ? 0 : (iu > 255 ? 255 : iu));
                vrow[cx] = (uint8_t)(iv < 0 ? 0 : (iv > 255 ? 255 : iv));
            }
        }

        if (W > 1920) {
            free(rr); free(gg); free(bb);
        }
    }

    return NULL;
}

// ====================================================================
// Section 7: QNN loader (dlopen, from bench_e2e_pipeline.cpp pattern)
// ====================================================================

#define QNN_DEFAULT_DIR "/data/data/com.nihildigit.anvildemo/files/anvil"
#define QNN_CTX_BIN     "context.serialized.bin"
#define QNN_MAX_TENSORS 4

struct qnn_state {
    void *htp_lib;
    void *sys_lib;

    QNN_INTERFACE_VER_TYPE qnn;
    QNN_SYSTEM_INTERFACE_VER_TYPE sys;

    Qnn_BackendHandle_t backend;
    Qnn_ContextHandle_t context;
    Qnn_GraphHandle_t graph;
    QnnSystemContext_Handle_t sys_ctx;

    // I/O tensor arrays for graphExecute (aliases to current double-buffer slot)
    Qnn_Tensor_t in_tensors[QNN_MAX_TENSORS];
    Qnn_Tensor_t out_tensors[QNN_MAX_TENSORS];
    uint32_t n_in, n_out;

    // HTP perf profile
    uint32_t power_config_id;
    int power_config_set;  // 1 if we got a valid powerConfigId

    // Pre-allocated user-facing float32 I/O buffers (aliases to current slot)
    float *input_buf;    // H*W*6 float32 NHWC
    float *output_buf;   // H*W*3 float32 NHWC
    uint32_t in_elems, out_elems;   // total element count

    // Quantization support (INT8 context binaries)
    int input_is_quantized;   // 1 if input tensor is uint8 quantized
    int output_is_quantized;  // 1 if output tensor is uint8 quantized
    float in_scale, out_scale;
    int32_t in_offset, out_offset;
    uint8_t *in_quant_buf;    // uint8 buffer for graphExecute input (alias)
    uint8_t *out_quant_buf;   // uint8 buffer for graphExecute output (alias)

    // Double-buffer for pipeline parallelism (Phase B)
    int buf_idx;                          // current buffer index (0 or 1)
    uint8_t  *in_quant_buf_db[2];         // uint8 NHWC input per buffer
    uint8_t  *out_quant_buf_db[2];        // uint8 NHWC output per buffer
    float    *input_buf_db[2];            // float NHWC input per buffer
    float    *output_buf_db[2];           // float NHWC output per buffer
    Qnn_Tensor_t in_tensors_db[2][QNN_MAX_TENSORS];
    Qnn_Tensor_t out_tensors_db[2][QNN_MAX_TENSORS];

    int ready;
};

static const char *qnn_get_dir(void)
{
    const char *env = getenv("QNN_DIR");
    return (env && env[0]) ? env : QNN_DEFAULT_DIR;
}

static int qnn_init(struct qnn_state *q, struct mp_filter *f, int W, int H)
{
    memset(q, 0, sizeof(*q));

    const char *dir = qnn_get_dir();
    char path[512];

    // Set library search paths for HTP backend's internal dlopen calls
    setenv("LD_LIBRARY_PATH", dir, 1);
    setenv("ADSP_LIBRARY_PATH", dir, 1);

    // Pre-load HTP dependencies with absolute paths.
    // Android linker namespaces ignore LD_LIBRARY_PATH set via setenv after
    // process start, so we dlopen each dep with its full path.
    // Note: libQnnHtpV*Skel.so is 32-bit Hexagon code loaded by DSP RPC, NOT by us.
    // libcdsprpc.so is a system library in /vendor/lib64/.

    // QNN libs are bundled in the APK's jniLibs/arm64-v8a/ and loaded by name.
    // libcdsprpc.so is a vendor library declared via <uses-native-library> in
    // the manifest -- Android linker resolves it from /vendor/lib64/.
    // The Skel file + context binary are extracted from assets/ to files/anvil/.
    const char *preload[] = {
        "libcdsprpc.so",        // vendor DSP RPC (from /vendor/lib64/ via manifest)
        "libQnnHtpPrepare.so",  // from APK jniLibs
        "libQnnHtpV75Stub.so",  // from APK jniLibs
        NULL
    };
    for (int i = 0; preload[i]; i++) {
        void *h = dlopen(preload[i], RTLD_NOW | RTLD_GLOBAL);
        if (!h)
            MP_WARN(f, "QNN: preload %s: %s (non-fatal)\n", preload[i], dlerror());
    }

    // Load libQnnHtp.so (from APK jniLibs, by name)
    q->htp_lib = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_GLOBAL);
    if (!q->htp_lib) {
        MP_WARN(f, "QNN: dlopen libQnnHtp.so: %s\n", dlerror());
        return -1;
    }

    // Load libQnnSystem.so (from APK jniLibs, by name)
    q->sys_lib = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_GLOBAL);
    if (!q->sys_lib) {
        MP_WARN(f, "QNN: dlopen libQnnSystem.so: %s\n", dlerror());
        return -1;
    }

    // Resolve QNN interface
    typedef Qnn_ErrorHandle_t (*GetProvidersFn)(const QnnInterface_t***, uint32_t*);
    GetProvidersFn getProviders =
        (GetProvidersFn)dlsym(q->htp_lib, "QnnInterface_getProviders");
    if (!getProviders) {
        MP_WARN(f, "QNN: QnnInterface_getProviders not found\n");
        return -1;
    }
    const QnnInterface_t **providerList = NULL;
    uint32_t numProviders = 0;
    if (getProviders(&providerList, &numProviders) != QNN_SUCCESS || numProviders == 0) {
        MP_WARN(f, "QNN: getProviders failed\n");
        return -1;
    }
    q->qnn = providerList[0]->QNN_INTERFACE_VER_NAME;

    // Resolve System interface
    typedef Qnn_ErrorHandle_t (*SysGetProvidersFn)(const QnnSystemInterface_t***, uint32_t*);
    SysGetProvidersFn sysGetProviders =
        (SysGetProvidersFn)dlsym(q->sys_lib, "QnnSystemInterface_getProviders");
    if (!sysGetProviders) {
        MP_WARN(f, "QNN: QnnSystemInterface_getProviders not found\n");
        return -1;
    }
    const QnnSystemInterface_t **sysProviderList = NULL;
    uint32_t numSysProviders = 0;
    if (sysGetProviders(&sysProviderList, &numSysProviders) != QNN_SUCCESS ||
        numSysProviders == 0)
    {
        MP_WARN(f, "QNN: sysGetProviders failed\n");
        return -1;
    }
    q->sys = sysProviderList[0]->QNN_SYSTEM_INTERFACE_VER_NAME;

    // Create backend
    if (q->qnn.backendCreate(NULL, NULL, &q->backend) != QNN_SUCCESS) {
        MP_WARN(f, "QNN: backendCreate failed\n");
        return -1;
    }

    // Read context binary
    snprintf(path, sizeof(path), "%s/%s", dir, QNN_CTX_BIN);
    FILE *fp = fopen(path, "rb");
    if (!fp) {
        MP_WARN(f, "QNN: cannot open %s\n", path);
        return -1;
    }
    fseek(fp, 0, SEEK_END);
    long file_size = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    void *binary = malloc(file_size);
    if (!binary || fread(binary, 1, file_size, fp) != (size_t)file_size) {
        MP_WARN(f, "QNN: failed to read context binary\n");
        fclose(fp); free(binary);
        return -1;
    }
    fclose(fp);

    // Extract graph metadata via QnnSystem
    if (q->sys.systemContextCreate(&q->sys_ctx) != QNN_SUCCESS) {
        MP_WARN(f, "QNN: systemContextCreate failed\n");
        free(binary);
        return -1;
    }

    const QnnSystemContext_BinaryInfo_t *binaryInfo = NULL;
    Qnn_ContextBinarySize_t binaryInfoSize = 0;
    if (q->sys.systemContextGetBinaryInfo(q->sys_ctx, binary, (uint64_t)file_size,
                                           &binaryInfo, &binaryInfoSize) != QNN_SUCCESS)
    {
        MP_WARN(f, "QNN: systemContextGetBinaryInfo failed\n");
        free(binary);
        return -1;
    }

    // Get first graph's tensor info
    const QnnSystemContext_GraphInfo_t *graphs = NULL;
    uint32_t numGraphs = 0;
    const char *graph_name = NULL;

    if (binaryInfo->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) {
        graphs = binaryInfo->contextBinaryInfoV1.graphs;
        numGraphs = binaryInfo->contextBinaryInfoV1.numGraphs;
    } else if (binaryInfo->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) {
        graphs = binaryInfo->contextBinaryInfoV2.graphs;
        numGraphs = binaryInfo->contextBinaryInfoV2.numGraphs;
    } else if (binaryInfo->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) {
        graphs = binaryInfo->contextBinaryInfoV3.graphs;
        numGraphs = binaryInfo->contextBinaryInfoV3.numGraphs;
    }

    if (numGraphs == 0) {
        MP_WARN(f, "QNN: no graphs in context binary\n");
        free(binary);
        return -1;
    }

    const Qnn_Tensor_t *meta_inputs = NULL, *meta_outputs = NULL;

    if (graphs[0].version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
        graph_name  = graphs[0].graphInfoV1.graphName;
        q->n_in     = graphs[0].graphInfoV1.numGraphInputs;
        meta_inputs = graphs[0].graphInfoV1.graphInputs;
        q->n_out    = graphs[0].graphInfoV1.numGraphOutputs;
        meta_outputs= graphs[0].graphInfoV1.graphOutputs;
    } else if (graphs[0].version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
        graph_name  = graphs[0].graphInfoV2.graphName;
        q->n_in     = graphs[0].graphInfoV2.numGraphInputs;
        meta_inputs = graphs[0].graphInfoV2.graphInputs;
        q->n_out    = graphs[0].graphInfoV2.numGraphOutputs;
        meta_outputs= graphs[0].graphInfoV2.graphOutputs;
    } else if (graphs[0].version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3) {
        graph_name  = graphs[0].graphInfoV3.graphName;
        q->n_in     = graphs[0].graphInfoV3.numGraphInputs;
        meta_inputs = graphs[0].graphInfoV3.graphInputs;
        q->n_out    = graphs[0].graphInfoV3.numGraphOutputs;
        meta_outputs= graphs[0].graphInfoV3.graphOutputs;
    }

    if (!graph_name || q->n_in == 0 || q->n_out == 0) {
        MP_WARN(f, "QNN: invalid graph metadata\n");
        free(binary);
        return -1;
    }

    MP_INFO(f, "QNN: graph '%s', %u inputs, %u outputs\n",
            graph_name, q->n_in, q->n_out);

    // Calculate buffer sizes from tensor dims, detect quantization, log layout
    q->in_elems = 0;
    for (uint32_t i = 0; i < q->n_in && i < QNN_MAX_TENSORS; i++) {
        uint32_t elem = 1;
        char dims_str[128] = "";
        for (uint32_t d = 0; d < meta_inputs[i].v1.rank; d++) {
            elem *= meta_inputs[i].v1.dimensions[d];
            char tmp[16]; snprintf(tmp, sizeof(tmp), "%s%u", d?",":"", meta_inputs[i].v1.dimensions[d]);
            strncat(dims_str, tmp, sizeof(dims_str)-strlen(dims_str)-1);
        }
        MP_INFO(f, "QNN: input[%u] dims=[%s] dataType=0x%04x\n", i, dims_str,
                (unsigned)meta_inputs[i].v1.dataType);
        q->in_elems += elem;
    }
    q->out_elems = 0;
    for (uint32_t i = 0; i < q->n_out && i < QNN_MAX_TENSORS; i++) {
        uint32_t elem = 1;
        char dims_str[128] = "";
        for (uint32_t d = 0; d < meta_outputs[i].v1.rank; d++) {
            elem *= meta_outputs[i].v1.dimensions[d];
            char tmp[16]; snprintf(tmp, sizeof(tmp), "%s%u", d?",":"", meta_outputs[i].v1.dimensions[d]);
            strncat(dims_str, tmp, sizeof(dims_str)-strlen(dims_str)-1);
        }
        MP_INFO(f, "QNN: output[%u] dims=[%s] dataType=0x%04x\n", i, dims_str,
                (unsigned)meta_outputs[i].v1.dataType);
        q->out_elems += elem;
    }

    // Detect INT8 quantization from tensor dataType
    // QNN_DATATYPE_UFIXED_POINT_8 = 0x0408, QNN_DATATYPE_SFIXED_POINT_8 = 0x0308
    uint32_t in_dt = meta_inputs[0].v1.dataType;
    uint32_t out_dt = meta_outputs[0].v1.dataType;

    q->input_is_quantized  = (in_dt == 0x0408 || in_dt == 0x0308);
    q->output_is_quantized = (out_dt == 0x0408 || out_dt == 0x0308);

    if (q->input_is_quantized) {
        q->in_scale  = meta_inputs[0].v1.quantizeParams.scaleOffsetEncoding.scale;
        q->in_offset = meta_inputs[0].v1.quantizeParams.scaleOffsetEncoding.offset;
        MP_INFO(f, "QNN: input quantized (0x%04x): scale=%f, offset=%d\n",
                (unsigned)in_dt, q->in_scale, (int)q->in_offset);
    }
    if (q->output_is_quantized) {
        q->out_scale  = meta_outputs[0].v1.quantizeParams.scaleOffsetEncoding.scale;
        q->out_offset = meta_outputs[0].v1.quantizeParams.scaleOffsetEncoding.offset;
        MP_INFO(f, "QNN: output quantized (0x%04x): scale=%f, offset=%d\n",
                (unsigned)out_dt, q->out_scale, (int)q->out_offset);
    }

    // Allocate double-buffered I/O for pipeline parallelism (Phase B).
    // Each slot has its own input/output buffers and tensor arrays.
    for (int db = 0; db < 2; db++) {
        if (!q->input_is_quantized)
            q->input_buf_db[db] = (float *)calloc(q->in_elems, sizeof(float));
        if (!q->output_is_quantized)
            q->output_buf_db[db] = (float *)calloc(q->out_elems, sizeof(float));
        if (q->input_is_quantized)
            q->in_quant_buf_db[db] = (uint8_t *)calloc(q->in_elems, sizeof(uint8_t));
        if (q->output_is_quantized)
            q->out_quant_buf_db[db] = (uint8_t *)calloc(q->out_elems, sizeof(uint8_t));
    }
    // Set initial aliases to slot 0
    q->buf_idx = 0;
    q->input_buf     = q->input_buf_db[0];
    q->output_buf    = q->output_buf_db[0];
    q->in_quant_buf  = q->in_quant_buf_db[0];
    q->out_quant_buf = q->out_quant_buf_db[0];

    // Load context from binary
    Qnn_ErrorHandle_t ctx_err = q->qnn.contextCreateFromBinary(
        q->backend, NULL, NULL, binary,
        (Qnn_ContextBinarySize_t)file_size, &q->context, NULL);
    if (ctx_err != QNN_SUCCESS) {
        MP_WARN(f, "QNN: contextCreateFromBinary failed (err=0x%x). "
                "Check ADSP_LIBRARY_PATH=%s has libQnnHtpV*Skel.so\n",
                (unsigned)ctx_err, dir);
        free(binary);
        return -1;
    }

    // Retrieve graph handle
    if (q->qnn.graphRetrieve(q->context, graph_name, &q->graph) != QNN_SUCCESS) {
        MP_WARN(f, "QNN: graphRetrieve failed\n");
        free(binary);
        return -1;
    }

    // --- Set HTP performance profile ---
    // Default: POWER_SAVER_MODE for sustained real-time playback.
    // Override via env: ANVIL_HTP_PERF=burst|sustained|power_saver
    {
        QnnDevice_Infrastructure_t devInfra = NULL;
        if (q->qnn.deviceGetInfrastructure &&
            q->qnn.deviceGetInfrastructure(&devInfra) == QNN_SUCCESS && devInfra)
        {
            QnnHtpDevice_Infrastructure_t *htpInfra =
                (QnnHtpDevice_Infrastructure_t *)devInfra;
            if (htpInfra->perfInfra.createPowerConfigId &&
                htpInfra->perfInfra.setPowerConfig)
            {
                if (htpInfra->perfInfra.createPowerConfigId(
                        0, 0, &q->power_config_id) == QNN_SUCCESS)
                {
                    q->power_config_set = 1;

                    // Select power mode from env or default to performance (max clocks)
                    QnnHtpPerfInfrastructure_PowerMode_t mode =
                        QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
                    const char *perf_env = getenv("ANVIL_HTP_PERF");
                    if (perf_env) {
                        if (strcmp(perf_env, "burst") == 0)
                            mode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
                        else if (strcmp(perf_env, "sustained") == 0)
                            mode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_ADJUST_UP_DOWN;
                        else if (strcmp(perf_env, "power_saver") == 0)
                            mode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_POWER_SAVER_MODE;
                    }

                    QnnHtpPerfInfrastructure_PowerConfig_t dcvsConfig;
                    memset(&dcvsConfig, 0, sizeof(dcvsConfig));
                    dcvsConfig.option =
                        QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
                    dcvsConfig.dcvsV3Config.contextId = q->power_config_id;
                    dcvsConfig.dcvsV3Config.setDcvsEnable = 1;
                    dcvsConfig.dcvsV3Config.dcvsEnable = 1;
                    dcvsConfig.dcvsV3Config.powerMode = mode;
                    dcvsConfig.dcvsV3Config.setSleepLatency = 1;
                    dcvsConfig.dcvsV3Config.sleepLatency = 10;  // µs (reduced from 40)
                    dcvsConfig.dcvsV3Config.setSleepDisable = 1;
                    dcvsConfig.dcvsV3Config.sleepDisable = 1;   // keep DSP awake between inferences
                    dcvsConfig.dcvsV3Config.setBusParams = 1;
                    dcvsConfig.dcvsV3Config.busVoltageCornerMin =
                        DCVS_VOLTAGE_VCORNER_NOM;
                    dcvsConfig.dcvsV3Config.busVoltageCornerTarget =
                        DCVS_VOLTAGE_VCORNER_TURBO;
                    dcvsConfig.dcvsV3Config.busVoltageCornerMax =
                        DCVS_VOLTAGE_VCORNER_TURBO_PLUS;
                    dcvsConfig.dcvsV3Config.setCoreParams = 1;
                    dcvsConfig.dcvsV3Config.coreVoltageCornerMin =
                        DCVS_VOLTAGE_VCORNER_NOM;
                    dcvsConfig.dcvsV3Config.coreVoltageCornerTarget =
                        DCVS_VOLTAGE_VCORNER_TURBO;
                    dcvsConfig.dcvsV3Config.coreVoltageCornerMax =
                        DCVS_VOLTAGE_VCORNER_TURBO_PLUS;

                    // RPC control latency: reduce DSP wake latency
                    QnnHtpPerfInfrastructure_PowerConfig_t rpcLatConfig;
                    memset(&rpcLatConfig, 0, sizeof(rpcLatConfig));
                    rpcLatConfig.option =
                        QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY;
                    rpcLatConfig.rpcControlLatencyConfig = 100;  // 100µs

                    // RPC polling: keep DSP polling between inferences
                    QnnHtpPerfInfrastructure_PowerConfig_t rpcPollConfig;
                    memset(&rpcPollConfig, 0, sizeof(rpcPollConfig));
                    rpcPollConfig.option =
                        QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_POLLING_TIME;
                    rpcPollConfig.rpcPollingTimeConfig = 9999;  // µs

                    const QnnHtpPerfInfrastructure_PowerConfig_t *configs[] = {
                        &dcvsConfig, &rpcLatConfig, &rpcPollConfig, NULL
                    };
                    Qnn_ErrorHandle_t perfErr =
                        htpInfra->perfInfra.setPowerConfig(
                            q->power_config_id, configs);

                    const char *mode_name =
                        (mode == QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE)
                            ? "burst"
                        : (mode == QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_ADJUST_UP_DOWN)
                            ? "sustained"
                            : "power_saver";
                    MP_INFO(f, "QNN: HTP perf profile = %s (err=0x%x)\n",
                            mode_name, (unsigned)perfErr);
                }
            }
        }
    }

    // Set up tensor arrays for both double-buffer slots
    for (int db = 0; db < 2; db++) {
        uint32_t in_off_bytes = 0;
        for (uint32_t i = 0; i < q->n_in && i < QNN_MAX_TENSORS; i++) {
            q->in_tensors_db[db][i] = meta_inputs[i];
            q->in_tensors_db[db][i].v1.memType = QNN_TENSORMEMTYPE_RAW;
            uint32_t elem = 1;
            for (uint32_t d = 0; d < q->in_tensors_db[db][i].v1.rank; d++)
                elem *= q->in_tensors_db[db][i].v1.dimensions[d];
            if (q->input_is_quantized) {
                uint32_t sz = elem * sizeof(uint8_t);
                q->in_tensors_db[db][i].v1.clientBuf.data = q->in_quant_buf_db[db] + in_off_bytes;
                q->in_tensors_db[db][i].v1.clientBuf.dataSize = sz;
                in_off_bytes += sz;
            } else {
                uint32_t sz = elem * sizeof(float);
                q->in_tensors_db[db][i].v1.clientBuf.data = (uint8_t *)q->input_buf_db[db] + in_off_bytes;
                q->in_tensors_db[db][i].v1.clientBuf.dataSize = sz;
                in_off_bytes += sz;
            }
        }

        uint32_t out_off_bytes = 0;
        for (uint32_t i = 0; i < q->n_out && i < QNN_MAX_TENSORS; i++) {
            q->out_tensors_db[db][i] = meta_outputs[i];
            q->out_tensors_db[db][i].v1.memType = QNN_TENSORMEMTYPE_RAW;
            uint32_t elem = 1;
            for (uint32_t d = 0; d < q->out_tensors_db[db][i].v1.rank; d++)
                elem *= q->out_tensors_db[db][i].v1.dimensions[d];
            if (q->output_is_quantized) {
                uint32_t sz = elem * sizeof(uint8_t);
                q->out_tensors_db[db][i].v1.clientBuf.data = q->out_quant_buf_db[db] + out_off_bytes;
                q->out_tensors_db[db][i].v1.clientBuf.dataSize = sz;
                out_off_bytes += sz;
            } else {
                uint32_t sz = elem * sizeof(float);
                q->out_tensors_db[db][i].v1.clientBuf.data = (uint8_t *)q->output_buf_db[db] + out_off_bytes;
                q->out_tensors_db[db][i].v1.clientBuf.dataSize = sz;
                out_off_bytes += sz;
            }
        }
    }
    // Initialize active tensors from slot 0
    memcpy(q->in_tensors,  q->in_tensors_db[0],  sizeof(q->in_tensors));
    memcpy(q->out_tensors, q->out_tensors_db[0], sizeof(q->out_tensors));

    free(binary);
    q->ready = 1;
    MP_INFO(f, "QNN: initialized (in=%u elems, out=%u elems, in_quant=%d, out_quant=%d)\n",
            q->in_elems, q->out_elems, q->input_is_quantized, q->output_is_quantized);
    return 0;
}

// Switch active double-buffer slot: update aliases to point to slot `idx`
static void qnn_switch_buffer(struct qnn_state *q, int idx)
{
    q->buf_idx = idx;
    q->in_quant_buf  = q->in_quant_buf_db[idx];
    q->out_quant_buf = q->out_quant_buf_db[idx];
    q->input_buf     = q->input_buf_db[idx];
    q->output_buf    = q->output_buf_db[idx];
    memcpy(q->in_tensors,  q->in_tensors_db[idx],  sizeof(q->in_tensors));
    memcpy(q->out_tensors, q->out_tensors_db[idx], sizeof(q->out_tensors));
}

// Execute QNN graph on the CURRENT buffer slot's tensor arrays.
// For quantized models, Phase 2 already wrote in_quant_buf
// and Phase 4 will read out_quant_buf directly — no separate quant/dequant pass.
static Qnn_ErrorHandle_t qnn_execute(struct qnn_state *q)
{
    return q->qnn.graphExecute(q->graph,
                                q->in_tensors, q->n_in,
                                q->out_tensors, q->n_out,
                                NULL, NULL);
}

static void qnn_cleanup(struct qnn_state *q)
{
    if (!q) return;
    if (q->context) q->qnn.contextFree(q->context, NULL);
    if (q->backend) q->qnn.backendFree(q->backend);
    if (q->sys_ctx) q->sys.systemContextFree(q->sys_ctx);
    for (int db = 0; db < 2; db++) {
        free(q->input_buf_db[db]);
        free(q->output_buf_db[db]);
        free(q->in_quant_buf_db[db]);
        free(q->out_quant_buf_db[db]);
    }
    if (q->htp_lib) dlclose(q->htp_lib);
    if (q->sys_lib) dlclose(q->sys_lib);
    memset(q, 0, sizeof(*q));
}

// ====================================================================
// Section 8: Filter state and workspace
// ====================================================================

enum anvil_state {
    STATE_NEED_INPUT,
    STATE_HAVE_INTERP,   // interpolated frame ready (or in-flight), output it next
    STATE_HAVE_CURR,     // interpolated already output, output stored curr next
};

struct priv {
    enum anvil_state state;
    int frame_count;
    int log_interval;  // 30=default (1/s), change to 1 for full-frame paper bench

    // Previous frame for interpolation
    struct mp_image *prev;

    // Stored interpolated frame (output in HAVE_INTERP state)
    struct mp_image *interp;

    // Stored original curr frame (output in HAVE_CURR state)
    struct mp_frame stored_curr;

    // Prealign workspace (CPU fallback path)
    int alloc_w, alloc_h;
    float *flow_x, *flow_y;
    float *sm_a, *sm_b, *sm_tmp;
    int sH, sW;

    // Float RGB blend workspace (3 planes, for Phase 2 -> Phase 4)
    // When Vulkan active, Phase 4 reads from vk.blend_*_ptr instead
    float *blend_rgb[3];   // R, G, B blend = (warp0 + warp1) / 2

    // Vulkan compute state (GPU P1b + P2)
    struct vk_compute vk;
    int vk_checked;

    // Cached NHWC buffer for uncached SSBO -> cached copy
    float *nhwc_cached;
    size_t nhwc_cached_size;

    // QNN state
    struct qnn_state qnn;
    int qnn_checked;

    // ---- Pipeline parallelism (Phase B) ----
    // HTP async thread: runs QNN inference in background, overlapping with
    // downstream rendering of the original frame.
    pthread_t       htp_thread;
    pthread_mutex_t htp_mutex;
    pthread_cond_t  htp_ready_cond;   // signaled when HTP result is ready
    pthread_cond_t  htp_start_cond;   // signaled when new HTP work is submitted
    int htp_has_work;                 // 1 = HTP thread should run inference
    int htp_result_ready;             // 1 = HTP finished, result available
    int htp_ok;                       // result of last inference (1=success)
    int htp_shutdown;                 // 1 = thread should exit
    int htp_thread_created;           // 1 = pthread_create succeeded

    // Captured tensor arrays for the HTP thread to use (set at kick time,
    // before the double-buffer is flipped for the next frame).
    Qnn_Tensor_t htp_in_tensors[QNN_MAX_TENSORS];
    Qnn_Tensor_t htp_out_tensors[QNN_MAX_TENSORS];

    // Double-buffered pending state for HTP overlap
    struct {
        int active;                   // 1 = HTP in flight for this slot
        int use_quant_path;           // which warp path was used
        int buf_idx;                  // which QNN double-buffer slot has the data
        struct mp_image *out;         // pre-allocated output image for Phase 4
        double pts_prev;              // prev->pts for midpoint PTS
        double pts_curr;              // curr->pts for midpoint PTS
        double t_total;               // start time for total timing
        double t_p1a;                 // Phase 1a timing
        double t_gpu;                 // GPU timing
        double t_copy;                // copy timing
    } pend[2];
    int pend_cur;                     // slot that start_interpolation writes to (0 or 1)

    // For overlap: saved curr frame from previous pair
    struct mp_frame overlap_curr;
    int overlap_active;               // 1 = overlap path, output overlap_curr in HAVE_CURR
};

static void free_workspace(struct priv *p)
{
    free(p->flow_x);  free(p->flow_y);
    free(p->sm_a);    free(p->sm_b);    free(p->sm_tmp);
    p->flow_x = p->flow_y = p->sm_a = p->sm_b = p->sm_tmp = NULL;

    for (int c = 0; c < 3; c++) {
        free(p->blend_rgb[c]); p->blend_rgb[c] = NULL;
    }

    free(p->nhwc_cached); p->nhwc_cached = NULL;
    p->nhwc_cached_size = 0;

    p->alloc_w = p->alloc_h = 0;
}

static void alloc_workspace(struct priv *p, int w, int h)
{
    if (p->alloc_w == w && p->alloc_h == h) return;
    free_workspace(p);

    int px = w * h;
    p->sH = h / PA_DS; p->sW = w / PA_DS;
    int spx = p->sH * p->sW;

    p->flow_x  = calloc(px, sizeof(float));
    p->flow_y  = calloc(px, sizeof(float));
    p->sm_a    = malloc(spx * sizeof(float));
    p->sm_b    = malloc(spx * sizeof(float));
    p->sm_tmp  = malloc(spx * sizeof(float));

    for (int c = 0; c < 3; c++)
        p->blend_rgb[c] = malloc(px * sizeof(float));

    // Cached NHWC buffer for SSBO->cached copy (only used when Vulkan active)
    p->nhwc_cached_size = (size_t)px * 6 * sizeof(float);
    p->nhwc_cached = malloc(p->nhwc_cached_size);

    p->alloc_w = w; p->alloc_h = h;
}

// ====================================================================
// Section 9: Optimized interpolation pipeline
// ====================================================================

// Run prealign v2 on the flow field (modifies flow_x/flow_y in place)
static void run_prealign_v2(struct priv *p, int W, int H)
{
    int dH, dW;
    downsample(p->flow_x, p->sm_a, H, W, PA_DS, &dH, &dW);
    downsample(p->flow_y, p->sm_b, H, W, PA_DS, &dH, &dW);
    median_2d(p->sm_a, p->sm_tmp, dH, dW, PA_MED_K);
    median_2d(p->sm_b, p->sm_a,   dH, dW, PA_MED_K);
    // sm_tmp = filtered fx at 1/4, sm_a = filtered fy at 1/4
    gauss_sep(p->sm_tmp, p->sm_b, dH, dW, PA_GAUSS_S);
    gauss_sep(p->sm_a,   p->sm_b, dH, dW, PA_GAUSS_S);
    upsample(p->sm_tmp, p->flow_x, dH, dW, H, W);
    upsample(p->sm_a,   p->flow_y, dH, dW, H, W);
}

// Get monotonic time in milliseconds
static double get_time_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

// ====================================================================
// Section 9a: HTP async thread for pipeline parallelism (Phase B)
// ====================================================================

static void *htp_thread_fn(void *arg)
{
    struct priv *p = (struct priv *)arg;
    pthread_mutex_lock(&p->htp_mutex);
    while (!p->htp_shutdown) {
        // Wait for work
        while (!p->htp_has_work && !p->htp_shutdown)
            pthread_cond_wait(&p->htp_start_cond, &p->htp_mutex);
        if (p->htp_shutdown)
            break;
        p->htp_has_work = 0;
        pthread_mutex_unlock(&p->htp_mutex);

        // Run QNN inference using captured tensor arrays (blocking, ~13ms on HTP).
        // We use htp_in/out_tensors which were captured at kick time, NOT the
        // live qnn.in/out_tensors which may have been flipped to the next buffer.
        Qnn_ErrorHandle_t err = p->qnn.qnn.graphExecute(
            p->qnn.graph,
            p->htp_in_tensors, p->qnn.n_in,
            p->htp_out_tensors, p->qnn.n_out,
            NULL, NULL);

        pthread_mutex_lock(&p->htp_mutex);
        p->htp_ok = (err == QNN_SUCCESS) ? 1 : 0;
        p->htp_result_ready = 1;
        pthread_cond_signal(&p->htp_ready_cond);
    }
    pthread_mutex_unlock(&p->htp_mutex);
    return NULL;
}

// Kick HTP inference asynchronously. The current QNN buffer slot must already
// be filled with input data. Captures the tensor arrays before signaling so
// the main thread can safely flip the double-buffer for the next frame.
static void htp_kick_async(struct priv *p)
{
    // Capture current tensor arrays before they get flipped
    memcpy(p->htp_in_tensors,  p->qnn.in_tensors,  sizeof(p->htp_in_tensors));
    memcpy(p->htp_out_tensors, p->qnn.out_tensors, sizeof(p->htp_out_tensors));

    pthread_mutex_lock(&p->htp_mutex);
    p->htp_has_work = 1;
    p->htp_result_ready = 0;
    pthread_cond_signal(&p->htp_start_cond);
    pthread_mutex_unlock(&p->htp_mutex);
}

// Wait for the async HTP inference to complete. Returns the qnn_ok status.
static int htp_wait(struct priv *p)
{
    pthread_mutex_lock(&p->htp_mutex);
    while (!p->htp_result_ready)
        pthread_cond_wait(&p->htp_ready_cond, &p->htp_mutex);
    int ok = p->htp_ok;
    p->htp_result_ready = 0;
    pthread_mutex_unlock(&p->htp_mutex);
    return ok;
}

// ====================================================================
// Section 9b: start_interpolation / finish_interpolation (GPU/Q async path)
// ====================================================================

// Start interpolation: runs P1a + GPU + copy + kicks HTP async.
// Saves pending data for finish_interpolation. Only for GPU quantized path.
static void start_interpolation(struct priv *p, struct mp_filter *f,
                                 struct mp_image *prev, struct mp_image *curr,
                                 const AVMotionVector *mvs, int n_mvs)
{
    int W = curr->w, H = curr->h;
    double t_phase;

    // ---- Phase 1a: CPU ZOH + downsample -> SSBO mapped memory ----
    t_phase = get_time_ms();
    zoh_fill(mvs, n_mvs, p->flow_x, p->flow_y, H, W);
    int dH, dW;
    downsample(p->flow_x, p->vk.sm_fx_a_ptr, H, W, PA_DS, &dH, &dW);
    downsample(p->flow_y, p->vk.sm_fy_a_ptr, H, W, PA_DS, &dH, &dW);
    int y_stride, uv_stride, u_off, v_off;
    vk_pack_yuv(p->vk.prev_yuv_ptr,
                 prev->planes[0], prev->stride[0],
                 prev->planes[1], prev->stride[1],
                 prev->planes[2], prev->stride[2],
                 W, H, &y_stride, &uv_stride, &u_off, &v_off);
    vk_pack_yuv(p->vk.curr_yuv_ptr,
                 curr->planes[0], curr->stride[0],
                 curr->planes[1], curr->stride[1],
                 curr->planes[2], curr->stride[2],
                 W, H, &y_stride, &uv_stride, &u_off, &v_off);
    p->pend[p->pend_cur].t_p1a = get_time_ms() - t_phase;

    // ---- Phase 1b + Phase 2: GPU dispatches ----
    float inv_scale = 1.0f / p->qnn.in_scale;
    int quant_offset = p->qnn.in_offset;
    t_phase = get_time_ms();
    vk_dispatch(&p->vk, y_stride, uv_stride, u_off, v_off,
                 1 /* use_quant */, inv_scale, quant_offset);
    p->pend[p->pend_cur].t_gpu = get_time_ms() - t_phase;

    // ---- Copy from SSBO to QNN input buffer (current slot) ----
    t_phase = get_time_ms();
    {
        const size_t n = (size_t)W * H * 6;
#ifdef __ARM_NEON
        const uint8_t *src = p->vk.nhwc_u8_ptr;
        uint8_t *dst = p->qnn.in_quant_buf;
        size_t i = 0;
        for (; i + 64 <= n; i += 64) {
            __builtin_prefetch(src + i + 256, 0, 0);
            uint8x16_t a = vld1q_u8(src + i);
            uint8x16_t b = vld1q_u8(src + i + 16);
            uint8x16_t c = vld1q_u8(src + i + 32);
            uint8x16_t d = vld1q_u8(src + i + 48);
            vst1q_u8(dst + i, a);
            vst1q_u8(dst + i + 16, b);
            vst1q_u8(dst + i + 32, c);
            vst1q_u8(dst + i + 48, d);
        }
        if (i < n) memcpy(dst + i, src + i, n - i);
#else
        memcpy(p->qnn.in_quant_buf, p->vk.nhwc_u8_ptr, n);
#endif
    }
    p->pend[p->pend_cur].t_copy = get_time_ms() - t_phase;

    // ---- Pre-allocate output image ----
    p->pend[p->pend_cur].out = mp_image_new_copy(curr);
    p->pend[p->pend_cur].pts_prev = prev->pts;
    p->pend[p->pend_cur].pts_curr = curr->pts;
    p->pend[p->pend_cur].use_quant_path = 1;
    p->pend[p->pend_cur].buf_idx = p->qnn.buf_idx;

    // ---- Kick HTP async ----
    htp_kick_async(p);

    // ---- Flip to the other buffer for the next frame ----
    qnn_switch_buffer(&p->qnn, 1 - p->qnn.buf_idx);

    p->pend[p->pend_cur].active = 1;
}

// Finish interpolation: waits for HTP, runs Phase 4, returns completed image.
// Must be called after start_interpolation when pend[slot].active == 1.
// The slot parameter selects which pending state to drain.
static struct mp_image *finish_interpolation(struct priv *p, struct mp_filter *f,
                                              int slot)
{
    int W = p->pend[slot].out->w, H = p->pend[slot].out->h;
    double t_phase;

    // ---- Wait for HTP ----
    t_phase = get_time_ms();
    int qnn_ok = htp_wait(p);
    double t_p3 = get_time_ms() - t_phase;

    if (!qnn_ok)
        MP_WARN(f, "QNN: graphExecute failed (async), using blend only\n");

    // ---- Phase 4: dequant + residual + clamp + rgb2yuv ----
    // Use the buffer slot that HTP wrote to (pend[slot].buf_idx)
    int htp_buf = p->pend[slot].buf_idx;

    t_phase = get_time_ms();
    struct mp_image *out = p->pend[slot].out;
    p->pend[slot].out = NULL;

    int gpu_p4 = 0;
    if (p->vk.ready && p->vk.residual_yuv_pipe && p->pend[slot].use_quant_path) {
        // ---- GPU Phase 4 path ----
        gpu_p4 = 1;

        // Upload QNN output to SSBO
        memcpy(p->vk.qnn_out_ptr, p->qnn.out_quant_buf_db[htp_buf], (size_t)W * H * 3);

        // Zero the YUV output buffers (required for atomicOr)
        memset(p->vk.y_ptr, 0, p->vk.y_size);
        memset(p->vk.u_ptr, 0, p->vk.u_size);
        memset(p->vk.v_ptr, 0, p->vk.v_size);

        // GPU dispatch
        struct pc_residual_yuv pc = {
            .W = W, .H = H,
            .y_stride = W,
            .uv_stride = W / 2,
            .in_scale = p->qnn.in_scale,
            .in_offset = p->qnn.in_offset,
            .out_scale = p->qnn.out_scale,
            .out_offset = p->qnn.out_offset,
            .qnn_ok = qnn_ok,
        };
        vk_dispatch_residual_yuv(&p->vk, &pc);

        // Copy from SSBO to output image planes
        for (int y = 0; y < H; y++)
            memcpy(out->planes[0] + y * out->stride[0], p->vk.y_ptr + y * W, W);
        for (int y = 0; y < H / 2; y++) {
            memcpy(out->planes[1] + y * out->stride[1], p->vk.u_ptr + y * (W / 2), W / 2);
            memcpy(out->planes[2] + y * out->stride[2], p->vk.v_ptr + y * (W / 2), W / 2);
        }
    } else {
        // ---- CPU Phase 4 fallback (4 threads) ----
        pthread_t threads[N_THREADS - 1];
        struct phase4_args args[N_THREADS];
        int rows_per = (H / N_THREADS) & ~1;

        for (int t = 0; t < N_THREADS; t++) {
            args[t].y_start = t * rows_per;
            args[t].y_end = (t == N_THREADS - 1) ? H : (t + 1) * rows_per;
            args[t].W = W;
            args[t].H = H;
            args[t].blend_r = NULL;
            args[t].blend_g = NULL;
            args[t].blend_b = NULL;
            args[t].nhwc_quant = p->qnn.in_quant_buf_db[htp_buf];
            args[t].in_scale = p->qnn.in_scale;
            args[t].in_offset = p->qnn.in_offset;
            args[t].out_quant_buf = p->qnn.out_quant_buf_db[htp_buf];
            args[t].output_buf = p->qnn.output_buf_db[htp_buf];
            args[t].out_scale = p->qnn.out_scale;
            args[t].out_offset = p->qnn.out_offset;
            args[t].output_quantized = p->qnn.output_is_quantized;
            args[t].qnn_ok = qnn_ok;
            args[t].out_Y = out->planes[0]; args[t].out_ys = out->stride[0];
            args[t].out_U = out->planes[1]; args[t].out_us = out->stride[1];
            args[t].out_V = out->planes[2]; args[t].out_vs = out->stride[2];
        }

        for (int t = 0; t < N_THREADS - 1; t++)
            pthread_create(&threads[t], NULL, phase4_thread_fn, &args[t]);
        phase4_thread_fn(&args[N_THREADS - 1]);
        for (int t = 0; t < N_THREADS - 1; t++)
            pthread_join(threads[t], NULL);
    }

    out->pts = (p->pend[slot].pts_prev + p->pend[slot].pts_curr) / 2.0;
    double t_p4 = get_time_ms() - t_phase;
    double t_all = get_time_ms() - p->pend[slot].t_total;

    if (p->frame_count % p->log_interval == 0)
        MP_INFO(f, "ANVIL[GPU/Q/async]: total=%.1fms  P1a=%.1f  GPU=%.1f  copy=%.1f  P3=%.1f  P4%s=%.1f\n",
                t_all, p->pend[slot].t_p1a, p->pend[slot].t_gpu, p->pend[slot].t_copy, t_p3,
                gpu_p4 ? "(GPU)" : "", t_p4);

    p->pend[slot].active = 0;
    return out;
}

// ====================================================================
// Section 9c: Synchronous interpolation pipeline
// ====================================================================

// Compute prealigned blend + QNN residual -> full interpolated frame.
// Returns a new mp_image (YUV420P) with PTS set to midpoint.
//
// GPU pipeline (when Vulkan available):
//   Phase 1a (CPU): ZOH + downsample -> SSBO
//   Phase 1b (GPU): median5 x2 + gauss_sep x4 on 1/4 res flow
//   Phase 2  (GPU): warp_pack (upsample + warp YUV + yuv2rgb + blend + NHWC)
//   Phase 3  (HTP): graphExecute
//   Phase 4  (CPU, 4 threads): dequant + residual + clamp + rgb2yuv
//
// CPU fallback (when Vulkan unavailable):
//   Phase 1 (single thread): ZOH + prealign v2
//   Phase 2 (4 threads): fused warp + YUV->RGB + blend + pack NHWC + quantize
//   Phase 3 (HTP): graphExecute
//   Phase 4 (4 threads): dequant + residual + clamp + rgb2yuv
static struct mp_image *compute_interpolated(struct priv *p, struct mp_filter *f,
                                              struct mp_image *prev,
                                              struct mp_image *curr,
                                              const AVMotionVector *mvs, int n_mvs)
{
    int W = curr->w, H = curr->h;

    alloc_workspace(p, W, H);

    double t_total = get_time_ms();
    double t_phase;

    // Blend pointers: GPU path reads from SSBO, CPU path from blend_rgb[]
    const float *blend_r, *blend_g, *blend_b;

    int use_vk = p->vk.ready && p->vk.W == W && p->vk.H == H;

    if (use_vk) {
        // =============== GPU PATH ===============

        // ---- Phase 1a: CPU ZOH + downsample -> SSBO mapped memory ----
        t_phase = get_time_ms();
        zoh_fill(mvs, n_mvs, p->flow_x, p->flow_y, H, W);

        // Downsample to 1/4 res directly into SSBO mapped pointers
        int dH, dW;
        downsample(p->flow_x, p->vk.sm_fx_a_ptr, H, W, PA_DS, &dH, &dW);
        downsample(p->flow_y, p->vk.sm_fy_a_ptr, H, W, PA_DS, &dH, &dW);

        // Pack YUV planes into contiguous SSBO buffers
        int y_stride, uv_stride, u_off, v_off;
        vk_pack_yuv(p->vk.prev_yuv_ptr,
                     prev->planes[0], prev->stride[0],
                     prev->planes[1], prev->stride[1],
                     prev->planes[2], prev->stride[2],
                     W, H, &y_stride, &uv_stride, &u_off, &v_off);
        vk_pack_yuv(p->vk.curr_yuv_ptr,
                     curr->planes[0], curr->stride[0],
                     curr->planes[1], curr->stride[1],
                     curr->planes[2], curr->stride[2],
                     W, H, &y_stride, &uv_stride, &u_off, &v_off);

        double t_p1a = get_time_ms() - t_phase;

        // Decide if we use the GPU quantized warp path
        int use_quant_path = p->qnn.ready && p->qnn.input_is_quantized;
        float inv_scale = use_quant_path ? (1.0f / p->qnn.in_scale) : 0.0f;
        int quant_offset = use_quant_path ? p->qnn.in_offset : 0;

        // ---- Phase 1b + Phase 2: GPU dispatches ----
        t_phase = get_time_ms();
        vk_dispatch(&p->vk, y_stride, uv_stride, u_off, v_off,
                     use_quant_path, inv_scale, quant_offset);
        double t_gpu = get_time_ms() - t_phase;

        // ---- Copy from SSBO to cached/QNN buffers ----
        t_phase = get_time_ms();
        if (use_quant_path) {
            // GPU already produced uint8 NHWC in nhwc_u8 SSBO — NEON prefetch copy
            {
                const size_t n = (size_t)W * H * 6;
#ifdef __ARM_NEON
                const uint8_t *src = p->vk.nhwc_u8_ptr;
                uint8_t *dst = p->qnn.in_quant_buf;
                size_t ii = 0;
                for (; ii + 64 <= n; ii += 64) {
                    __builtin_prefetch(src + ii + 256, 0, 0);
                    uint8x16_t a = vld1q_u8(src + ii);
                    uint8x16_t b = vld1q_u8(src + ii + 16);
                    uint8x16_t c = vld1q_u8(src + ii + 32);
                    uint8x16_t d = vld1q_u8(src + ii + 48);
                    vst1q_u8(dst + ii, a);
                    vst1q_u8(dst + ii + 16, b);
                    vst1q_u8(dst + ii + 32, c);
                    vst1q_u8(dst + ii + 48, d);
                }
                if (ii < n) memcpy(dst + ii, src + ii, n - ii);
#else
                memcpy(p->qnn.in_quant_buf, p->vk.nhwc_u8_ptr, n);
#endif
            }
            // No blend copy needed — Phase 4 derives blend from in_quant_buf
            blend_r = NULL;
            blend_g = NULL;
            blend_b = NULL;
        } else {
            // Float path: copy NHWC from uncached SSBO, then quantize/copy for QNN
            size_t nhwc_elems = (size_t)W * H * 6;
            memcpy(p->nhwc_cached, p->vk.nhwc_ptr, nhwc_elems * sizeof(float));

            if (p->qnn.ready) {
                if (p->qnn.input_is_quantized) {
                    // Should not happen (use_quant_path would be true), but handle anyway
                    float inv_s = 1.0f / p->qnn.in_scale;
                    int32_t off = p->qnn.in_offset;
                    const float *src = p->nhwc_cached;
                    uint8_t *dst = p->qnn.in_quant_buf;
                    for (size_t i = 0; i < nhwc_elems; i++) {
                        float v = roundf(src[i] * inv_s) - (float)off;
                        int iv = (int)v;
                        dst[i] = (uint8_t)(iv < 0 ? 0 : (iv > 255 ? 255 : iv));
                    }
                } else {
                    memcpy(p->qnn.input_buf, p->nhwc_cached,
                           nhwc_elems * sizeof(float));
                }
            }

            // Copy blend from SSBO to cached for Phase 4
            size_t px = (size_t)W * H;
            memcpy(p->blend_rgb[0], p->vk.blend_r_ptr, px * sizeof(float));
            memcpy(p->blend_rgb[1], p->vk.blend_g_ptr, px * sizeof(float));
            memcpy(p->blend_rgb[2], p->vk.blend_b_ptr, px * sizeof(float));

            blend_r = p->blend_rgb[0];
            blend_g = p->blend_rgb[1];
            blend_b = p->blend_rgb[2];
        }
        double t_copy = get_time_ms() - t_phase;

        // ---- Phase 3: HTP inference ----
        t_phase = get_time_ms();
        int qnn_ok = 0;
        if (p->qnn.ready) {
            Qnn_ErrorHandle_t err = qnn_execute(&p->qnn);
            if (err == QNN_SUCCESS) {
                qnn_ok = 1;
            } else {
                MP_WARN(f, "QNN: graphExecute failed (0x%lx), using blend only\n",
                        (unsigned long)err);
            }
        }
        double t_p3 = get_time_ms() - t_phase;

        // ---- Phase 4: Fused dequant+residual+clamp+rgb2yuv ----
        t_phase = get_time_ms();
        struct mp_image *out = mp_image_new_copy(curr);
        int gpu_p4 = 0;

        if (p->vk.residual_yuv_pipe && use_quant_path) {
            // ---- GPU Phase 4 path ----
            gpu_p4 = 1;

            // Upload QNN output to SSBO
            memcpy(p->vk.qnn_out_ptr, p->qnn.out_quant_buf, (size_t)W * H * 3);

            // Zero the YUV output buffers (required for atomicOr)
            memset(p->vk.y_ptr, 0, p->vk.y_size);
            memset(p->vk.u_ptr, 0, p->vk.u_size);
            memset(p->vk.v_ptr, 0, p->vk.v_size);

            // GPU dispatch
            struct pc_residual_yuv pc = {
                .W = W, .H = H,
                .y_stride = W,
                .uv_stride = W / 2,
                .in_scale = p->qnn.in_scale,
                .in_offset = p->qnn.in_offset,
                .out_scale = p->qnn.out_scale,
                .out_offset = p->qnn.out_offset,
                .qnn_ok = qnn_ok,
            };
            vk_dispatch_residual_yuv(&p->vk, &pc);

            // Copy from SSBO to output image planes
            for (int y = 0; y < H; y++)
                memcpy(out->planes[0] + y * out->stride[0], p->vk.y_ptr + y * W, W);
            for (int y = 0; y < H / 2; y++) {
                memcpy(out->planes[1] + y * out->stride[1], p->vk.u_ptr + y * (W / 2), W / 2);
                memcpy(out->planes[2] + y * out->stride[2], p->vk.v_ptr + y * (W / 2), W / 2);
            }
        } else {
            // ---- CPU Phase 4 fallback (4 threads) ----
            pthread_t threads[N_THREADS - 1];
            struct phase4_args args[N_THREADS];
            int rows_per = (H / N_THREADS) & ~1;

            for (int t = 0; t < N_THREADS; t++) {
                args[t].y_start = t * rows_per;
                args[t].y_end = (t == N_THREADS - 1) ? H : (t + 1) * rows_per;
                args[t].W = W;
                args[t].H = H;
                args[t].blend_r = blend_r;
                args[t].blend_g = blend_g;
                args[t].blend_b = blend_b;
                args[t].nhwc_quant = use_quant_path ? p->qnn.in_quant_buf : NULL;
                args[t].in_scale = p->qnn.in_scale;
                args[t].in_offset = p->qnn.in_offset;
                args[t].out_quant_buf = p->qnn.out_quant_buf;
                args[t].output_buf = p->qnn.output_buf;
                args[t].out_scale = p->qnn.out_scale;
                args[t].out_offset = p->qnn.out_offset;
                args[t].output_quantized = p->qnn.output_is_quantized;
                args[t].qnn_ok = qnn_ok;
                args[t].out_Y = out->planes[0]; args[t].out_ys = out->stride[0];
                args[t].out_U = out->planes[1]; args[t].out_us = out->stride[1];
                args[t].out_V = out->planes[2]; args[t].out_vs = out->stride[2];
            }

            for (int t = 0; t < N_THREADS - 1; t++)
                pthread_create(&threads[t], NULL, phase4_thread_fn, &args[t]);
            phase4_thread_fn(&args[N_THREADS - 1]);
            for (int t = 0; t < N_THREADS - 1; t++)
                pthread_join(threads[t], NULL);
        }

        out->pts = (prev->pts + curr->pts) / 2.0;
        double t_p4 = get_time_ms() - t_phase;
        double t_all = get_time_ms() - t_total;

        if (p->frame_count % p->log_interval == 0)
            MP_INFO(f, "ANVIL[GPU%s]: total=%.1fms  P1a=%.1f  GPU=%.1f  copy=%.1f  P3=%.1f  P4%s=%.1f\n",
                    use_quant_path ? "/Q" : "", t_all, t_p1a, t_gpu, t_copy, t_p3,
                    gpu_p4 ? "(GPU)" : "", t_p4);

        return out;

    } else {
        // =============== CPU FALLBACK PATH ===============

        // ---- Phase 1: Build dense flow from MVs + prealign v2 ----
        t_phase = get_time_ms();
        zoh_fill(mvs, n_mvs, p->flow_x, p->flow_y, H, W);
        run_prealign_v2(p, W, H);
        double t_p1 = get_time_ms() - t_phase;

        // ---- Phase 2: Fused warp+yuv2rgb+blend+pack+quantize (4 threads) ----
        t_phase = get_time_ms();
        {
            pthread_t threads[N_THREADS - 1];
            struct phase2_args args[N_THREADS];
            int rows_per = H / N_THREADS;

            for (int t = 0; t < N_THREADS; t++) {
                args[t].y_start = t * rows_per;
                args[t].y_end = (t == N_THREADS - 1) ? H : (t + 1) * rows_per;
                args[t].W = W;
                args[t].H = H;
                args[t].prev_Y = prev->planes[0]; args[t].prev_ys = prev->stride[0];
                args[t].prev_U = prev->planes[1]; args[t].prev_us = prev->stride[1];
                args[t].prev_V = prev->planes[2]; args[t].prev_vs = prev->stride[2];
                args[t].curr_Y = curr->planes[0]; args[t].curr_ys = curr->stride[0];
                args[t].curr_U = curr->planes[1]; args[t].curr_us = curr->stride[1];
                args[t].curr_V = curr->planes[2]; args[t].curr_vs = curr->stride[2];
                args[t].flow_x = p->flow_x;
                args[t].flow_y = p->flow_y;
                args[t].blend_r = p->blend_rgb[0];
                args[t].blend_g = p->blend_rgb[1];
                args[t].blend_b = p->blend_rgb[2];

                if (p->qnn.ready && p->qnn.input_is_quantized) {
                    args[t].quantized = 1;
                    args[t].in_quant_buf = p->qnn.in_quant_buf;
                    args[t].in_inv_scale = 1.0f / p->qnn.in_scale;
                    args[t].in_offset = p->qnn.in_offset;
                    args[t].input_buf = NULL;
                } else if (p->qnn.ready) {
                    args[t].quantized = 0;
                    args[t].in_quant_buf = NULL;
                    args[t].in_inv_scale = 0;
                    args[t].in_offset = 0;
                    args[t].input_buf = p->qnn.input_buf;
                } else {
                    args[t].quantized = 0;
                    args[t].in_quant_buf = NULL;
                    args[t].input_buf = NULL;
                }
            }

            for (int t = 0; t < N_THREADS - 1; t++)
                pthread_create(&threads[t], NULL, phase2_thread_fn, &args[t]);
            phase2_thread_fn(&args[N_THREADS - 1]);
            for (int t = 0; t < N_THREADS - 1; t++)
                pthread_join(threads[t], NULL);
        }
        double t_p2 = get_time_ms() - t_phase;

        blend_r = p->blend_rgb[0];
        blend_g = p->blend_rgb[1];
        blend_b = p->blend_rgb[2];

        // ---- Phase 3: HTP inference ----
        t_phase = get_time_ms();
        int qnn_ok = 0;
        if (p->qnn.ready) {
            Qnn_ErrorHandle_t err = qnn_execute(&p->qnn);
            if (err == QNN_SUCCESS) {
                qnn_ok = 1;
            } else {
                MP_WARN(f, "QNN: graphExecute failed (0x%lx), using blend only\n",
                        (unsigned long)err);
            }
        }
        double t_p3 = get_time_ms() - t_phase;

        // ---- Phase 4: Fused dequant+residual+clamp+rgb2yuv (4 threads) ----
        t_phase = get_time_ms();
        struct mp_image *out = mp_image_new_copy(curr);
        {
            pthread_t threads[N_THREADS - 1];
            struct phase4_args args[N_THREADS];
            int rows_per = (H / N_THREADS) & ~1;

            for (int t = 0; t < N_THREADS; t++) {
                args[t].y_start = t * rows_per;
                args[t].y_end = (t == N_THREADS - 1) ? H : (t + 1) * rows_per;
                args[t].W = W;
                args[t].H = H;
                args[t].blend_r = blend_r;
                args[t].blend_g = blend_g;
                args[t].blend_b = blend_b;
                args[t].nhwc_quant = NULL;  // CPU path: blend from Phase 2
                args[t].in_scale = 0;
                args[t].in_offset = 0;
                args[t].out_quant_buf = p->qnn.out_quant_buf;
                args[t].output_buf = p->qnn.output_buf;
                args[t].out_scale = p->qnn.out_scale;
                args[t].out_offset = p->qnn.out_offset;
                args[t].output_quantized = p->qnn.output_is_quantized;
                args[t].qnn_ok = qnn_ok;
                args[t].out_Y = out->planes[0]; args[t].out_ys = out->stride[0];
                args[t].out_U = out->planes[1]; args[t].out_us = out->stride[1];
                args[t].out_V = out->planes[2]; args[t].out_vs = out->stride[2];
            }

            for (int t = 0; t < N_THREADS - 1; t++)
                pthread_create(&threads[t], NULL, phase4_thread_fn, &args[t]);
            phase4_thread_fn(&args[N_THREADS - 1]);
            for (int t = 0; t < N_THREADS - 1; t++)
                pthread_join(threads[t], NULL);
        }

        out->pts = (prev->pts + curr->pts) / 2.0;
        double t_p4 = get_time_ms() - t_phase;
        double t_all = get_time_ms() - t_total;

        if (p->frame_count % p->log_interval == 0)
            MP_INFO(f, "ANVIL[CPU]: total=%.1fms  P1=%.1f  P2=%.1f  P3=%.1f  P4=%.1f\n",
                    t_all, t_p1, t_p2, t_p3, t_p4);

        return out;
    }
}

// ====================================================================
// Section 10: Frame doubling state machine
// ====================================================================

static void f_process(struct mp_filter *f)
{
    struct priv *p = f->priv;

    // --- STATE_HAVE_INTERP: output interpolated frame (cold path only, first pair) ---
    if (p->state == STATE_HAVE_INTERP) {
        if (!mp_pin_in_needs_data(f->ppins[1]))
            return;

        struct mp_image *out;
        int slot = p->pend_cur;
        if (p->pend[slot].active) {
            out = finish_interpolation(p, f, slot);
        } else {
            out = p->interp;
            p->interp = NULL;
        }
        mp_pin_in_write(f->ppins[1], MAKE_FRAME(MP_FRAME_VIDEO, out));

        // Now output the stored original curr frame next
        p->state = STATE_HAVE_CURR;
        mp_filter_internal_mark_progress(f);
        return;
    }

    // --- STATE_HAVE_CURR: output stored original curr frame (PTS = later) ---
    if (p->state == STATE_HAVE_CURR) {
        if (!mp_pin_in_needs_data(f->ppins[1]))
            return;

        if (p->overlap_active) {
            mp_pin_in_write(f->ppins[1], p->overlap_curr);
            p->overlap_curr = (struct mp_frame){0};
            p->overlap_active = 0;
        } else {
            mp_pin_in_write(f->ppins[1], p->stored_curr);
            p->stored_curr = (struct mp_frame){0};
        }
        p->state = STATE_NEED_INPUT;
        return;
    }

    // --- STATE_NEED_INPUT: read a frame from input ---
    if (!mp_pin_can_transfer_data(f->ppins[1], f->ppins[0]))
        return;

    struct mp_frame frame = mp_pin_out_read(f->ppins[0]);

    if (mp_frame_is_signaling(frame)) {
        // Drain any in-flight HTP work
        for (int s = 0; s < 2; s++) {
            if (p->pend[s].active) {
                struct mp_image *orphan = finish_interpolation(p, f, s);
                mp_image_unrefp(&orphan);
            }
        }
        mp_pin_in_write(f->ppins[1], frame);
        return;
    }
    if (frame.type != MP_FRAME_VIDEO) {
        mp_pin_in_write(f->ppins[1], frame);
        return;
    }

    struct mp_image *mpi = frame.data;
    p->frame_count++;
    int W = mpi->w, H = mpi->h;

    // Initialize QNN on first frame
    if (!p->qnn_checked) {
        p->qnn_checked = 1;
        if (qnn_init(&p->qnn, f, W, H) == 0) {
            MP_INFO(f, "ANVIL: QNN HTP ready at %s\n", qnn_get_dir());
        } else {
            MP_INFO(f, "ANVIL: QNN not available, using MV blend fallback\n");
        }
    }

    // Initialize Vulkan compute on first frame
    if (!p->vk_checked) {
        p->vk_checked = 1;
        if (vk_init(&p->vk, f, W, H) == 0) {
            MP_INFO(f, "ANVIL: Vulkan GPU compute ready (%dx%d)\n", W, H);
        } else {
            MP_INFO(f, "ANVIL: Vulkan not available, using CPU fallback\n");
            vk_cleanup(&p->vk);
        }
    }

    // Start HTP async thread on first frame (after QNN init)
    if (p->qnn.ready && !p->htp_thread_created) {
        pthread_mutex_init(&p->htp_mutex, NULL);
        pthread_cond_init(&p->htp_ready_cond, NULL);
        pthread_cond_init(&p->htp_start_cond, NULL);
        p->htp_shutdown = 0;
        p->htp_has_work = 0;
        p->htp_result_ready = 0;
        if (pthread_create(&p->htp_thread, NULL, htp_thread_fn, p) == 0) {
            p->htp_thread_created = 1;
            MP_INFO(f, "ANVIL: HTP async thread started (pipeline parallelism)\n");
        } else {
            MP_WARN(f, "ANVIL: failed to create HTP thread, using synchronous path\n");
        }
    }

    // Get MVs from side data
    const AVMotionVector *mvs = NULL;
    int n_mvs = 0;
    for (int n = 0; n < mpi->num_ff_side_data; n++) {
        if (mpi->ff_side_data[n].type == AV_FRAME_DATA_MOTION_VECTORS) {
            AVBufferRef *buf = mpi->ff_side_data[n].buf;
            if (buf && buf->data) {
                mvs = (const AVMotionVector *)buf->data;
                n_mvs = buf->size / sizeof(AVMotionVector);
            }
            break;
        }
    }

    if (p->frame_count % 60 == 1) {
        MP_INFO(f, "ANVIL: frame %d, %dx%d, %d MVs, qnn=%d, async=%d\n",
                p->frame_count, W, H, n_mvs, p->qnn.ready, p->htp_thread_created);
    }

    // First frame (I-frame, no MVs) or no prev: just pass through
    if (!p->prev || n_mvs == 0) {
        // Drain any in-flight HTP work (e.g., scene change after overlap started)
        for (int s = 0; s < 2; s++) {
            if (p->pend[s].active) {
                struct mp_image *orphan = finish_interpolation(p, f, s);
                mp_image_unrefp(&orphan);
            }
        }
        mp_image_unrefp(&p->prev);
        p->prev = mp_image_new_ref(mpi);
        mp_pin_in_write(f->ppins[1], frame);
        return;
    }

    alloc_workspace(p, W, H);

    // Decide path: async pipeline (GPU/Q + async HTP) or synchronous
    int use_vk = p->vk.ready && p->vk.W == W && p->vk.H == H;
    int use_async = use_vk && p->qnn.ready && p->qnn.input_is_quantized
                    && p->htp_thread_created;

    if (use_async) {
        int prev_slot = p->pend_cur;
        int has_prev = p->pend[prev_slot].active;  // HTP from previous cycle in flight?

        if (has_prev) {
            // ===== STEADY STATE: OVERLAP =====
            // Flip to other pending slot for new work
            int new_slot = 1 - prev_slot;
            p->pend_cur = new_slot;

            // Step 1: Start NEW interpolation (CPU/GPU ~10ms + kick HTP)
            // HTP(prev) is running in parallel during this!
            p->pend[new_slot].t_total = get_time_ms();
            start_interpolation(p, f, p->prev, mpi, mvs, n_mvs);

            // Step 2: Finish PREVIOUS interpolation (wait HTP — should be fast due to overlap)
            struct mp_image *prev_interp = finish_interpolation(p, f, prev_slot);

            // Step 3: Output previous interp frame
            mp_pin_in_write(f->ppins[1], MAKE_FRAME(MP_FRAME_VIDEO, prev_interp));

            // Step 4: Manage curr frames
            // stored_curr currently holds the PREVIOUS pair's curr frame -> output next in HAVE_CURR
            p->overlap_curr = p->stored_curr;
            p->stored_curr = frame;  // save current pair's curr for NEXT cycle
            p->overlap_active = 1;

            // Update prev
            mp_image_unrefp(&p->prev);
            p->prev = mp_image_new_ref(mpi);

            p->state = STATE_HAVE_CURR;
            mp_filter_internal_mark_progress(f);
        } else {
            // ===== COLD START: no overlap =====
            p->pend[p->pend_cur].t_total = get_time_ms();
            start_interpolation(p, f, p->prev, mpi, mvs, n_mvs);

            mp_image_unrefp(&p->prev);
            p->prev = mp_image_new_ref(mpi);
            p->stored_curr = frame;

            p->state = STATE_HAVE_INTERP;
            mp_filter_internal_mark_progress(f);
        }
    } else {
        // Synchronous path (unchanged)
        p->interp = compute_interpolated(p, f, p->prev, mpi, mvs, n_mvs);

        mp_image_unrefp(&p->prev);
        p->prev = mp_image_new_ref(mpi);
        p->stored_curr = frame;

        p->state = STATE_HAVE_INTERP;
        mp_filter_internal_mark_progress(f);
    }
}

static void f_reset(struct mp_filter *f)
{
    struct priv *p = f->priv;
    // Drain any in-flight HTP work before resetting
    for (int s = 0; s < 2; s++) {
        if (p->pend[s].active) {
            struct mp_image *orphan = finish_interpolation(p, f, s);
            mp_image_unrefp(&orphan);
        }
        mp_image_unrefp(&p->pend[s].out);
    }
    mp_image_unrefp(&p->prev);
    mp_image_unrefp(&p->interp);
    mp_frame_unref(&p->stored_curr);
    mp_frame_unref(&p->overlap_curr);
    p->overlap_active = 0;
    p->pend_cur = 0;
    p->state = STATE_NEED_INPUT;
}

static void f_destroy(struct mp_filter *f)
{
    struct priv *p = f->priv;
    // Drain any in-flight HTP work
    for (int s = 0; s < 2; s++) {
        if (p->pend[s].active) {
            struct mp_image *orphan = finish_interpolation(p, f, s);
            mp_image_unrefp(&orphan);
        }
        mp_image_unrefp(&p->pend[s].out);
    }
    // Shut down HTP async thread
    if (p->htp_thread_created) {
        pthread_mutex_lock(&p->htp_mutex);
        p->htp_shutdown = 1;
        pthread_cond_signal(&p->htp_start_cond);
        pthread_mutex_unlock(&p->htp_mutex);
        pthread_join(p->htp_thread, NULL);
        pthread_mutex_destroy(&p->htp_mutex);
        pthread_cond_destroy(&p->htp_ready_cond);
        pthread_cond_destroy(&p->htp_start_cond);
        p->htp_thread_created = 0;
    }
    mp_image_unrefp(&p->prev);
    mp_image_unrefp(&p->interp);
    mp_frame_unref(&p->stored_curr);
    mp_frame_unref(&p->overlap_curr);
    free_workspace(p);
    vk_cleanup(&p->vk);
    qnn_cleanup(&p->qnn);
}

static const struct mp_filter_info filter = {
    .name = "anvil",
    .process = f_process,
    .reset = f_reset,
    .destroy = f_destroy,
    .priv_size = sizeof(struct priv),
};

// ====================================================================
// Section 11: Filter registration
// ====================================================================

static struct mp_filter *f_create(struct mp_filter *parent, void *options)
{
    struct mp_filter *f = mp_filter_create(parent, &filter);
    if (!f) {
        talloc_free(options);
        return NULL;
    }

    struct priv *p = f->priv;
    talloc_free(options);

    p->state = STATE_NEED_INPUT;

    // Timing log interval: 30 = log every 30th frame (~1/s, normal use)
    // Change to 1 to log every frame for paper-grade e2e statistics.
    // Full-frame logging adds ~4°C thermal overhead; see paper Sec. IV-F.
    p->log_interval = 30;

    mp_filter_add_pin(f, MP_PIN_IN, "in");
    mp_filter_add_pin(f, MP_PIN_OUT, "out");

    MP_INFO(f, "ANVIL VFI frame-doubler (Vulkan GPU + HTP, log_interval=%d)\n", p->log_interval);
    return f;
}

const struct mp_user_filter_entry vf_anvil = {
    .desc = {
        .description = "ANVIL video frame interpolation",
        .name = "anvil",
    },
    .create = f_create,
};
