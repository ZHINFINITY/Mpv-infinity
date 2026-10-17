/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Executes the exact embedded MPVFlow synthesis shader on Vulkan (llvmpipe in
 * CI) and checks output pixels. This is a deterministic shader regression test,
 * not a device-performance or Adreno visual-quality benchmark.
 */
#include <vulkan/vulkan.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define WIDTH 7
#define HEIGHT 5
#define GRID_WIDTH (((WIDTH - 4 + 3) / 4) + 1)
#define GRID_HEIGHT (((HEIGHT - 4 + 3) / 4) + 1)
#define GRID_PIXELS ((size_t)GRID_WIDTH * (size_t)GRID_HEIGHT)
#define PIXELS ((size_t)WIDTH * (size_t)HEIGHT)
#define IMAGE_BYTES (PIXELS * 4)

struct Buffer {
    VkBuffer handle;
    VkDeviceMemory memory;
    VkDeviceSize size;
};

struct Image {
    VkImage handle;
    VkImageView view;
    VkDeviceMemory memory;
    VkFormat format;
    uint32_t width;
    uint32_t height;
    VkImageLayout layout;
    int sampled;
};

struct VulkanContext {
    VkInstance instance;
    VkPhysicalDevice physical_device;
    VkDevice device;
    VkQueue queue;
    uint32_t queue_family;
    VkCommandPool command_pool;
    VkDescriptorSetLayout descriptor_layout;
    VkDescriptorPool descriptor_pool;
    VkDescriptorSet descriptor_set;
    VkPipelineLayout pipeline_layout;
    VkPipeline pipeline;
    VkSampler sampler;
    char device_name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE];
    int soft_blend;
    int preserve_motion_blend;
    int decision_mode;
};

static void fail(const char *operation, VkResult result)
{
    fprintf(stderr, "Vulkan synthesis test: %s failed (VkResult=%d)\n",
            operation, result);
    exit(1);
}

#define VK_CHECK(call) do { \
    VkResult result_ = (call); \
    if (result_ != VK_SUCCESS) fail(#call, result_); \
} while (0)

static uint32_t find_memory_type(struct VulkanContext *ctx, uint32_t type_bits,
                                 VkMemoryPropertyFlags required)
{
    VkPhysicalDeviceMemoryProperties properties;
    vkGetPhysicalDeviceMemoryProperties(ctx->physical_device, &properties);
    for (uint32_t i = 0; i < properties.memoryTypeCount; i++) {
        if ((type_bits & (1u << i)) &&
            (properties.memoryTypes[i].propertyFlags & required) == required)
            return i;
    }
    fprintf(stderr, "Vulkan synthesis test: no memory type for flags 0x%x\n",
            required);
    exit(1);
}

static struct Buffer create_buffer(struct VulkanContext *ctx, VkDeviceSize size,
                                   VkBufferUsageFlags usage)
{
    struct Buffer buffer = { .size = size };
    VkBufferCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = size,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
    };
    VK_CHECK(vkCreateBuffer(ctx->device, &info, NULL, &buffer.handle));
    VkMemoryRequirements requirements;
    vkGetBufferMemoryRequirements(ctx->device, buffer.handle, &requirements);
    VkMemoryAllocateInfo allocation = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = find_memory_type(
            ctx, requirements.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                VK_MEMORY_PROPERTY_HOST_COHERENT_BIT),
    };
    VK_CHECK(vkAllocateMemory(ctx->device, &allocation, NULL, &buffer.memory));
    VK_CHECK(vkBindBufferMemory(ctx->device, buffer.handle, buffer.memory, 0));
    return buffer;
}

static void destroy_buffer(struct VulkanContext *ctx, struct Buffer *buffer)
{
    if (buffer->handle)
        vkDestroyBuffer(ctx->device, buffer->handle, NULL);
    if (buffer->memory)
        vkFreeMemory(ctx->device, buffer->memory, NULL);
    memset(buffer, 0, sizeof(*buffer));
}

static struct Image create_image(struct VulkanContext *ctx, VkFormat format,
                                 uint32_t width, uint32_t height,
                                 VkImageUsageFlags usage, int sampled)
{
    struct Image image = {
        .format = format,
        .width = width,
        .height = height,
        .layout = VK_IMAGE_LAYOUT_UNDEFINED,
        .sampled = sampled,
    };
    VkImageCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = { width, height, 1 },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    VK_CHECK(vkCreateImage(ctx->device, &info, NULL, &image.handle));
    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(ctx->device, image.handle, &requirements);
    VkMemoryAllocateInfo allocation = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = find_memory_type(
            ctx, requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT),
    };
    VK_CHECK(vkAllocateMemory(ctx->device, &allocation, NULL, &image.memory));
    VK_CHECK(vkBindImageMemory(ctx->device, image.handle, image.memory, 0));
    VkImageViewCreateInfo view = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = image.handle,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format,
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    VK_CHECK(vkCreateImageView(ctx->device, &view, NULL, &image.view));
    return image;
}

static void destroy_image(struct VulkanContext *ctx, struct Image *image)
{
    if (image->view)
        vkDestroyImageView(ctx->device, image->view, NULL);
    if (image->handle)
        vkDestroyImage(ctx->device, image->handle, NULL);
    if (image->memory)
        vkFreeMemory(ctx->device, image->memory, NULL);
    memset(image, 0, sizeof(*image));
}

static VkCommandBuffer begin_commands(struct VulkanContext *ctx)
{
    VkCommandBufferAllocateInfo allocation = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = ctx->command_pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    VkCommandBuffer command;
    VK_CHECK(vkAllocateCommandBuffers(ctx->device, &allocation, &command));
    VkCommandBufferBeginInfo begin = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    VK_CHECK(vkBeginCommandBuffer(command, &begin));
    return command;
}

static void end_commands(struct VulkanContext *ctx, VkCommandBuffer command)
{
    VK_CHECK(vkEndCommandBuffer(command));
    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1,
        .pCommandBuffers = &command,
    };
    VK_CHECK(vkQueueSubmit(ctx->queue, 1, &submit, VK_NULL_HANDLE));
    VK_CHECK(vkQueueWaitIdle(ctx->queue));
    vkFreeCommandBuffers(ctx->device, ctx->command_pool, 1, &command);
}

static void upload_image(struct VulkanContext *ctx, struct Image *image,
                         const void *pixels, size_t byte_count)
{
    struct Buffer staging = create_buffer(ctx, byte_count,
                                         VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    void *mapped = NULL;
    VK_CHECK(vkMapMemory(ctx->device, staging.memory, 0, byte_count, 0, &mapped));
    memcpy(mapped, pixels, byte_count);
    vkUnmapMemory(ctx->device, staging.memory);

    VkCommandBuffer command = begin_commands(ctx);
    VkImageMemoryBarrier to_transfer = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = image->layout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 :
                         VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .oldLayout = image->layout,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image->handle,
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    vkCmdPipelineBarrier(command,
        image->layout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT :
                                                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &to_transfer);
    VkBufferImageCopy copy = {
        .bufferOffset = 0,
        .bufferRowLength = 0,
        .bufferImageHeight = 0,
        .imageSubresource = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .mipLevel = 0,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
        .imageOffset = { 0, 0, 0 },
        .imageExtent = { image->width, image->height, 1 },
    };
    vkCmdCopyBufferToImage(command, staging.handle, image->handle,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    VkImageLayout final_layout = image->sampled ?
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_GENERAL;
    VkImageMemoryBarrier to_shader = to_transfer;
    to_shader.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    to_shader.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    to_shader.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    to_shader.newLayout = final_layout;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                         0, NULL, 0, NULL, 1, &to_shader);
    end_commands(ctx, command);
    image->layout = final_layout;
    destroy_buffer(ctx, &staging);
}

static void initialize_vulkan(struct VulkanContext *ctx, const char *spirv_path)
{
    VkApplicationInfo application = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "mpvflow-synthesis-regression",
        .applicationVersion = 1,
        .pEngineName = "mpvflow-test",
        .engineVersion = 1,
        .apiVersion = VK_API_VERSION_1_1,
    };
    VkInstanceCreateInfo instance_info = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &application,
    };
    VK_CHECK(vkCreateInstance(&instance_info, NULL, &ctx->instance));

    uint32_t physical_count = 0;
    VK_CHECK(vkEnumeratePhysicalDevices(ctx->instance, &physical_count, NULL));
    if (!physical_count) {
        fprintf(stderr, "Vulkan synthesis test: no physical device\n");
        exit(1);
    }
    VkPhysicalDevice *physical = calloc(physical_count, sizeof(*physical));
    if (!physical)
        exit(1);
    VK_CHECK(vkEnumeratePhysicalDevices(ctx->instance, &physical_count, physical));
    for (uint32_t p = 0; p < physical_count && !ctx->physical_device; p++) {
        uint32_t family_count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(physical[p], &family_count, NULL);
        VkQueueFamilyProperties *families = calloc(family_count, sizeof(*families));
        if (!families)
            exit(1);
        vkGetPhysicalDeviceQueueFamilyProperties(physical[p], &family_count, families);
        for (uint32_t q = 0; q < family_count; q++) {
            if (families[q].queueCount && (families[q].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
                ctx->physical_device = physical[p];
                ctx->queue_family = q;
                break;
            }
        }
        free(families);
    }
    free(physical);
    if (!ctx->physical_device) {
        fprintf(stderr, "Vulkan synthesis test: no compute-capable queue\n");
        exit(1);
    }
    VkPhysicalDeviceProperties properties;
    vkGetPhysicalDeviceProperties(ctx->physical_device, &properties);
    snprintf(ctx->device_name, sizeof(ctx->device_name), "%s", properties.deviceName);

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = ctx->queue_family,
        .queueCount = 1,
        .pQueuePriorities = &priority,
    };
    VkDeviceCreateInfo device_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queue_info,
    };
    VK_CHECK(vkCreateDevice(ctx->physical_device, &device_info, NULL, &ctx->device));
    vkGetDeviceQueue(ctx->device, ctx->queue_family, 0, &ctx->queue);

    VkCommandPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT,
        .queueFamilyIndex = ctx->queue_family,
    };
    VK_CHECK(vkCreateCommandPool(ctx->device, &pool_info, NULL, &ctx->command_pool));

    VkDescriptorSetLayoutBinding bindings[9];
    memset(bindings, 0, sizeof(bindings));
    for (uint32_t i = 0; i < 9; i++) {
        bindings[i].binding = i;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        bindings[i].descriptorType = i < 2 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER :
                                             VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    }
    VkDescriptorSetLayoutCreateInfo descriptor_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 9,
        .pBindings = bindings,
    };
    VK_CHECK(vkCreateDescriptorSetLayout(ctx->device, &descriptor_info, NULL,
                                         &ctx->descriptor_layout));
    VkDescriptorPoolSize pool_sizes[2] = {
        { .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .descriptorCount = 2 },
        { .type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, .descriptorCount = 7 },
    };
    VkDescriptorPoolCreateInfo descriptor_pool_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = 1,
        .poolSizeCount = 2,
        .pPoolSizes = pool_sizes,
    };
    VK_CHECK(vkCreateDescriptorPool(ctx->device, &descriptor_pool_info, NULL,
                                    &ctx->descriptor_pool));
    VkDescriptorSetAllocateInfo descriptor_allocate = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = ctx->descriptor_pool,
        .descriptorSetCount = 1,
        .pSetLayouts = &ctx->descriptor_layout,
    };
    VK_CHECK(vkAllocateDescriptorSets(ctx->device, &descriptor_allocate,
                                      &ctx->descriptor_set));
    VkSamplerCreateInfo sampler_info = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter = VK_FILTER_LINEAR,
        .minFilter = VK_FILTER_LINEAR,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .maxLod = 0.0f,
    };
    VK_CHECK(vkCreateSampler(ctx->device, &sampler_info, NULL, &ctx->sampler));

    VkPushConstantRange push_range = {
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0,
        .size = 20,
    };
    VkPipelineLayoutCreateInfo pipeline_layout_info = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &ctx->descriptor_layout,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &push_range,
    };
    VK_CHECK(vkCreatePipelineLayout(ctx->device, &pipeline_layout_info, NULL,
                                    &ctx->pipeline_layout));

    FILE *file = fopen(spirv_path, "rb");
    if (!file) {
        perror("opening SPIR-V shader");
        exit(1);
    }
    if (fseek(file, 0, SEEK_END) != 0)
        exit(1);
    long file_size = ftell(file);
    if (file_size <= 0 || file_size % 4 != 0 || fseek(file, 0, SEEK_SET) != 0)
        exit(1);
    uint32_t *code = malloc((size_t)file_size);
    if (!code || fread(code, 1, (size_t)file_size, file) != (size_t)file_size)
        exit(1);
    fclose(file);
    VkShaderModuleCreateInfo module_info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = (size_t)file_size,
        .pCode = code,
    };
    VkShaderModule module;
    VK_CHECK(vkCreateShaderModule(ctx->device, &module_info, NULL, &module));
    free(code);
    VkPipelineShaderStageCreateInfo stage = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
        .stage = VK_SHADER_STAGE_COMPUTE_BIT,
        .module = module,
        .pName = "main",
    };
    VkComputePipelineCreateInfo pipeline_info = {
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = stage,
        .layout = ctx->pipeline_layout,
    };
    VK_CHECK(vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1,
                                      &pipeline_info, NULL, &ctx->pipeline));
    vkDestroyShaderModule(ctx->device, module, NULL);
}

static void fill_rgba8(uint8_t *pixels, const uint8_t color[4])
{
    for (size_t i = 0; i < PIXELS; i++)
        memcpy(pixels + i * 4, color, 4);
}

static void fill_rgba16f(uint16_t *pixels, size_t count, uint16_t r, uint16_t g,
                         uint16_t b, uint16_t a)
{
    for (size_t i = 0; i < count; i++) {
        pixels[i * 4 + 0] = r;
        pixels[i * 4 + 1] = g;
        pixels[i * 4 + 2] = b;
        pixels[i * 4 + 3] = a;
    }
}

static void update_descriptors(struct VulkanContext *ctx,
                               struct Image images[9])
{
    VkDescriptorImageInfo image_info[9];
    VkWriteDescriptorSet writes[9];
    memset(image_info, 0, sizeof(image_info));
    memset(writes, 0, sizeof(writes));
    for (uint32_t i = 0; i < 9; i++) {
        image_info[i].imageView = images[i].view;
        image_info[i].imageLayout = i < 2 ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL :
                                            VK_IMAGE_LAYOUT_GENERAL;
        if (i < 2)
            image_info[i].sampler = ctx->sampler;
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = ctx->descriptor_set;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = i < 2 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER :
                                           VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[i].pImageInfo = &image_info[i];
    }
    vkUpdateDescriptorSets(ctx->device, 9, writes, 0, NULL);
}

static void run_fixture(struct VulkanContext *ctx, const char *label,
                        const uint8_t frame0_color[4],
                        const uint8_t frame1_color[4], uint16_t scene_delta,
                        uint16_t luma0_value, uint16_t luma1_value,
                        float timestep, const uint8_t expected[4],
                        int check_all_pixels, int special_grid_test)
{
    uint8_t frame0[IMAGE_BYTES], frame1[IMAGE_BYTES];
    uint16_t flow[2][GRID_PIXELS * 4], scene[4], luma0[PIXELS * 4], luma1[PIXELS * 4];
    fill_rgba8(frame0, frame0_color);
    fill_rgba8(frame1, frame1_color);
    fill_rgba16f(flow[0], GRID_PIXELS, 0x0000, 0x0000, 0x0000, 0x3c00);
    fill_rgba16f(flow[1], GRID_PIXELS, 0x0000, 0x0000, 0x0000, 0x3c00);
    fill_rgba16f(luma0, PIXELS, luma0_value, 0x0000, 0x0000, 0x3c00);
    fill_rgba16f(luma1, PIXELS, luma1_value, 0x0000, 0x0000, 0x3c00);
    if (special_grid_test == 1) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                size_t index = ((size_t)y * WIDTH + (size_t)x) * 4;
                uint8_t value = (uint8_t)(x * x * 6);
                frame0[index + 0] = frame0[index + 1] = frame0[index + 2] = value;
                frame0[index + 3] = 255;
                frame1[index + 0] = frame1[index + 1] = frame1[index + 2] = value;
                frame1[index + 3] = 255;
            }
        }
        for (uint32_t y = 0; y < GRID_HEIGHT; y++) {
            for (uint32_t x = 0; x < GRID_WIDTH; x++) {
                size_t index = ((size_t)y * GRID_WIDTH + x) * 4;
                flow[0][index + 0] = x == 0 ? 0x3c00 : 0x4200;
                flow[0][index + 1] = 0x0000;
                flow[0][index + 2] = 0x0000;
                flow[0][index + 3] = 0x3c00;
                flow[1][index + 0] = x == 0 ? 0xbc00 : 0xc200;
                flow[1][index + 1] = 0x0000;
                flow[1][index + 2] = 0x0000;
                flow[1][index + 3] = 0x3c00;
            }
        }
    } else if (special_grid_test == 2) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                size_t index = ((size_t)y * WIDTH + (size_t)x) * 4;
                uint8_t value = (uint8_t)(x * x * 2 + y * 3);
                frame0[index + 0] = frame0[index + 1] = frame0[index + 2] = value;
                frame0[index + 3] = 255;
                frame1[index + 0] = frame1[index + 1] = frame1[index + 2] = value;
                frame1[index + 3] = 255;
            }
        }
        size_t outlier = ((size_t)(GRID_HEIGHT - 1) * GRID_WIDTH + (GRID_WIDTH - 1)) * 4;
        flow[0][outlier + 0] = 0x4000; // +2px, high-motion but ambiguous
        flow[0][outlier + 2] = 0x3000; // normalized SAD = 0.125
        flow[0][outlier + 3] = 0x0000; // tied/ambiguous match
        flow[1][outlier + 0] = 0xc000; // matching reverse outlier
        flow[1][outlier + 2] = 0x3000;
        flow[1][outlier + 3] = 0x0000;
    }
    if (special_grid_test == 3) {
        fill_rgba16f(flow[0], GRID_PIXELS, 0x0000, 0x0000, 0x0000, 0x0000);
        fill_rgba16f(flow[1], GRID_PIXELS, 0x0000, 0x0000, 0x0000, 0x0000);
    } else if (special_grid_test == 4) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                size_t index = ((size_t)y * WIDTH + (size_t)x) * 4;
                uint8_t gray = (uint8_t)(x * 40);
                frame0[index + 0] = frame0[index + 1] = frame0[index + 2] = gray;
                frame0[index + 3] = 255;
                frame1[index + 0] = 0;
                frame1[index + 1] = 128;
                frame1[index + 2] = 0;
                frame1[index + 3] = 255;
            }
        }
        for (size_t cell = 0; cell < GRID_PIXELS; cell++) {
            flow[0][cell * 4 + 0] = 0x3c00; // forward +1px, reliable
            flow[1][cell * 4 + 0] = 0xbc00; // backward -1px
            flow[1][cell * 4 + 3] = 0x3200; // less reliable reverse motion
        }
    }
    scene[0] = scene_delta;
    scene[1] = scene[2] = 0x0000;
    scene[3] = 0x3c00;

    struct Image images[9] = {0};
    images[0] = create_image(ctx, VK_FORMAT_R8G8B8A8_UNORM, WIDTH, HEIGHT,
                             VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, 1);
    images[1] = create_image(ctx, VK_FORMAT_R8G8B8A8_UNORM, WIDTH, HEIGHT,
                             VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, 1);
    for (int i = 2; i <= 6; i++) {
        uint32_t w = i == 4 ? 1 : (i == 2 || i == 3 ? GRID_WIDTH : WIDTH);
        uint32_t h = i == 4 ? 1 : (i == 2 || i == 3 ? GRID_HEIGHT : HEIGHT);
        images[i] = create_image(ctx, VK_FORMAT_R16G16B16A16_SFLOAT, w, h,
                                 VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT, 0);
    }
    images[7] = create_image(ctx, VK_FORMAT_R8G8B8A8_UNORM, WIDTH, HEIGHT,
                             VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, 0);

    images[8] = create_image(ctx, VK_FORMAT_R8G8B8A8_UNORM, WIDTH, HEIGHT,
                             VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, 0);

    upload_image(ctx, &images[0], frame0, sizeof(frame0));
    upload_image(ctx, &images[1], frame1, sizeof(frame1));
    upload_image(ctx, &images[2], flow[0], sizeof(flow[0]));
    upload_image(ctx, &images[3], flow[1], sizeof(flow[1]));
    upload_image(ctx, &images[4], scene, sizeof(scene));
    upload_image(ctx, &images[5], luma0, sizeof(luma0));
    upload_image(ctx, &images[6], luma1, sizeof(luma1));
    VkImageMemoryBarrier output_ready = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = 0,
        .dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = images[7].handle,
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
    VkCommandBuffer command = begin_commands(ctx);
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                         0, NULL, 0, NULL, 1, &output_ready);
    end_commands(ctx, command);
    images[7].layout = VK_IMAGE_LAYOUT_GENERAL;
    VkImageMemoryBarrier decision_ready = output_ready;
    decision_ready.image = images[8].handle;
    command = begin_commands(ctx);
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                         0, NULL, 0, NULL, 1, &decision_ready);
    end_commands(ctx, command);
    images[8].layout = VK_IMAGE_LAYOUT_GENERAL;
    update_descriptors(ctx, images);

    command = begin_commands(ctx);
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeline);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE,
                            ctx->pipeline_layout, 0, 1, &ctx->descriptor_set, 0, NULL);
    int32_t push[4] = { WIDTH, HEIGHT, WIDTH, HEIGHT };
    if (ctx->soft_blend)
        push[0] |= 0x40000000;
    if (ctx->soft_blend && ctx->preserve_motion_blend)
        push[0] |= 0x20000000;
    vkCmdPushConstants(command, ctx->pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(push), push);
    vkCmdPushConstants(command, ctx->pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT,
                       sizeof(push), sizeof(timestep), &timestep);
    vkCmdDispatch(command, (WIDTH + 7) / 8, (HEIGHT + 7) / 8, 1);

    VkImageMemoryBarrier output_to_copy = output_ready;
    output_to_copy.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    output_to_copy.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    output_to_copy.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    output_to_copy.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                         0, NULL, 0, NULL, 1, &output_to_copy);
    struct Buffer readback = create_buffer(ctx, IMAGE_BYTES,
                                           VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    struct Buffer decision_readback = create_buffer(ctx, IMAGE_BYTES,
                                           VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    VkImageMemoryBarrier decision_to_copy = output_to_copy;
    decision_to_copy.image = images[8].handle;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                         0, NULL, 0, NULL, 1, &decision_to_copy);
    VkBufferImageCopy read_copy = {
        .bufferOffset = 0,
        .bufferRowLength = 0,
        .bufferImageHeight = 0,
        .imageSubresource = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .mipLevel = 0,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
        .imageOffset = { 0, 0, 0 },
        .imageExtent = { WIDTH, HEIGHT, 1 },
    };
    vkCmdCopyImageToBuffer(command, images[7].handle,
                           VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           readback.handle, 1, &read_copy);
    vkCmdCopyImageToBuffer(command, images[8].handle,
                           VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           decision_readback.handle, 1, &read_copy);
    VkBufferMemoryBarrier host_ready = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .buffer = readback.handle,
        .offset = 0,
        .size = VK_WHOLE_SIZE,
    };
    VkBufferMemoryBarrier host_barriers[2] = { host_ready, host_ready };
    host_barriers[1].buffer = decision_readback.handle;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0,
                         0, NULL, 2, host_barriers, 0, NULL);
    end_commands(ctx, command);

    uint8_t *output = malloc(IMAGE_BYTES);
    if (!output)
        exit(1);
    void *mapped = NULL;
    VK_CHECK(vkMapMemory(ctx->device, readback.memory, 0, IMAGE_BYTES, 0, &mapped));
    memcpy(output, mapped, IMAGE_BYTES);
    vkUnmapMemory(ctx->device, readback.memory);
    uint8_t *decisions = malloc(IMAGE_BYTES);
    if (!decisions) exit(1);
    VK_CHECK(vkMapMemory(ctx->device, decision_readback.memory, 0, IMAGE_BYTES, 0, &mapped));
    memcpy(decisions, mapped, IMAGE_BYTES);
    vkUnmapMemory(ctx->device, decision_readback.memory);
    int first = check_all_pixels ? 0 : special_grid_test == 2 ? 1 :
                (2 * WIDTH + (special_grid_test == 1 ? 2 : 3));
    int last = check_all_pixels ? (int)PIXELS : first + 1;
    if (expected) {
        for (int i = first; i < last; i++) {
            for (int channel = 0; channel < 4; channel++) {
                int actual = output[(size_t)i * 4 + (size_t)channel];
                if (abs(actual - (int)expected[channel]) > 1) {
                    fprintf(stderr, "Vulkan synthesis pixel mismatch: fixture=%s pixel=(%d,%d) channel=%d expected=%u actual=%d\\n", label, i % WIDTH, i / WIDTH, channel, expected[channel], actual); exit(1);
                }
            }
        }
    }
    if (ctx->decision_mode) {
        int expected_id = strstr(label, "scene-cut") ? 1 :
                          strstr(label, "static identical") ? 2 :
                          strstr(label, "low-confidence source 0") ? 3 :
                          strstr(label, "low-confidence source 1") ? 4 :
                          strstr(label, "irregular terminal") ? -2 : -1;
        const uint8_t grid_ids[WIDTH] = { 4, 5, 5, 8, 8, 4, 4 };
        for (int i = 0; i < (int)PIXELS; i++) {
            const uint8_t *m = decisions + (size_t)i * 4;
            if (m[0] > 10 || m[2] > 15 || m[3] != 255) {
                fprintf(stderr, "Invalid decision encoding: %s (%d,%d)\n", label, i % WIDTH, i / WIDTH); exit(1);
            }
            if (strstr(label, "chroma endpoint") && m[0] >= 5 && m[0] <= 8 && !(m[2] & 2)) {
                fprintf(stderr, "Missing chroma conflict reason: %s (%d,%d)\n", label, i % WIDTH, i / WIDTH); exit(1);
            }
            int id = expected_id == -2 ? grid_ids[i % WIDTH] : expected_id;
            if (id >= 0 && m[0] != (uint8_t)id) {
                fprintf(stderr, "Decision id mismatch: %s (%d,%d) expected=%d actual=%u\n", label, i % WIDTH, i / WIDTH, id, m[0]); exit(1);
            }
            if (m[0] == 3 || m[0] == 7 || m[0] == 9 || m[0] == 4 || m[0] == 8 || m[0] == 10) {
                const uint8_t *src = (m[0] == 3 || m[0] == 7 || m[0] == 9) ? frame0 : frame1;
                for (int c = 0; c < 3; c++) if (output[(size_t)i * 4 + c] != src[(size_t)i * 4 + c]) {
                    fprintf(stderr, "Fallback/output mismatch: %s (%d,%d) id=%u\n", label, i % WIDTH, i / WIDTH, m[0]); exit(1);
                }
            }
            if (strstr(label, "low-confidence") && m[1] != 0) {
                fprintf(stderr, "Expected zero G confidence for %s; got %u\n", label, m[1]); exit(1);
            }
        }
    }
    printf("Vulkan synthesis pixel fixture passed: %s\n", label);
    free(output);
    free(decisions);
    destroy_buffer(ctx, &readback);
    destroy_buffer(ctx, &decision_readback);
    for (int i = 0; i < 9; i++)
        destroy_image(ctx, &images[i]);
}

static void destroy_vulkan(struct VulkanContext *ctx)
{
    if (ctx->device)
        VK_CHECK(vkDeviceWaitIdle(ctx->device));
    if (ctx->pipeline)
        vkDestroyPipeline(ctx->device, ctx->pipeline, NULL);
    if (ctx->pipeline_layout)
        vkDestroyPipelineLayout(ctx->device, ctx->pipeline_layout, NULL);
    if (ctx->sampler)
        vkDestroySampler(ctx->device, ctx->sampler, NULL);
    if (ctx->descriptor_pool)
        vkDestroyDescriptorPool(ctx->device, ctx->descriptor_pool, NULL);
    if (ctx->descriptor_layout)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->descriptor_layout, NULL);
    if (ctx->command_pool)
        vkDestroyCommandPool(ctx->device, ctx->command_pool, NULL);
    if (ctx->device)
        vkDestroyDevice(ctx->device, NULL);
    if (ctx->instance)
        vkDestroyInstance(ctx->instance, NULL);
}

int main(int argc, char **argv)
{
    if (argc < 2 || argc > 5) return 2;
    struct VulkanContext ctx = {0};
    ctx.soft_blend = argc >= 3 && atoi(argv[2]) != 0;
    ctx.preserve_motion_blend = argc >= 4 && atoi(argv[3]) != 0;
    ctx.decision_mode = argc >= 5 && atoi(argv[4]) != 0;
    initialize_vulkan(&ctx, argv[1]);
    printf("Running exact MPVFlow shader_synthesize on Vulkan device: %s (%dx%d odd extent, soft_blend=%d preserve_motion_blend=%d decision_mode=%d)\n", ctx.device_name, WIDTH, HEIGHT, ctx.soft_blend, ctx.preserve_motion_blend, ctx.decision_mode);
    const uint8_t red[4]={255,0,0,255}, blue[4]={0,0,255,255}, green[4]={0,130,0,255};
    const uint8_t neutral0[4]={100,100,100,255}, neutral1[4]={120,110,104,255}, mixed[4]={110,105,102,255};
    const uint8_t soft_chroma[4]={64,98,0,255}, continuous_chroma[4]={50,104,0,255};
    const uint8_t soft_low[4]={214,0,41,255}, soft_edge[4]={50,114,50,255}, continuous_edge[4]={56,120,56,255};
    run_fixture(&ctx,"scene-cut alpha selects first source",red,blue,0x3800,0x3400,0x3800,0.25f,red,1,0);
    run_fixture(&ctx,"scene-cut alpha selects second source",red,blue,0x3800,0x3400,0x3800,0.75f,blue,1,0);
    run_fixture(&ctx,"static identical frame is preserved exactly",neutral0,neutral0,0,0x3400,0x3400,0.37f,neutral0,1,0);
    run_fixture(&ctx,ctx.soft_blend?"soft mode blends the chroma boundary":"default conflict fallback blends source frames",red,green,0,0x3400,0x3800,0.75f,ctx.soft_blend?(ctx.preserve_motion_blend?continuous_chroma:soft_chroma):soft_chroma,0,0);
    run_fixture(&ctx,"low-contrast reliable motion retains temporal color blend",neutral0,neutral1,0,0x3400,0x3500,0.5f,mixed,0,0);
    run_fixture(&ctx,"soft fallback keeps a continuous blend across the moving edge",red,blue,0,0x3400,0x3800,0.5f,ctx.soft_blend?(ctx.preserve_motion_blend?continuous_edge:soft_edge):NULL,0,4);
    const uint8_t soft_grid[4]={42,42,42,255}, cont_grid[4]={31,31,31,255};
    const uint8_t terminal_grid_source[4]={24,24,24,255};
    run_fixture(&ctx,"irregular terminal block anchors use conservative source fallback",neutral0,neutral0,0,0x3400,0x3400,0.5f,ctx.soft_blend?(ctx.preserve_motion_blend?cont_grid:soft_grid):terminal_grid_source,0,1);
    const uint8_t outlier[4]={2,2,2,255};
    run_fixture(&ctx,"ambiguous fast vector cannot warp a coherent region",neutral0,neutral0,0,0x3400,0x3400,0.25f,outlier,0,2);
    run_fixture(&ctx,ctx.soft_blend?"soft mode favors temporal detail at low confidence":"low-confidence source 0 at endpoint",red,blue,0,0x3400,0x3800,0.25f,ctx.soft_blend?soft_low:red,1,3);
    if (ctx.decision_mode) run_fixture(&ctx,"low-confidence source 1 at endpoint",red,blue,0,0x3400,0x3800,0.75f,blue,1,3);
    destroy_vulkan(&ctx);
    puts("MPVFlow Vulkan synthesis pixel-oracle tests passed");
    return 0;
}
