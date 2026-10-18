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
#include <math.h>

static int floor_div4(int value)
{
    int quotient = value / 4;
    return value % 4 < 0 ? quotient - 1 : quotient;
}

static int positive_mod48(int value)
{
    int remainder = value % 48;
    return remainder < 0 ? remainder + 48 : remainder;
}

#ifndef WIDTH
#define WIDTH 7
#endif
#ifndef HEIGHT
#define HEIGHT 5
#endif
#ifndef ANALYSIS_WIDTH
#define ANALYSIS_WIDTH WIDTH
#endif
#ifndef ANALYSIS_HEIGHT
#define ANALYSIS_HEIGHT HEIGHT
#endif
#define LUMA_PIXELS ((size_t)ANALYSIS_WIDTH * ANALYSIS_HEIGHT)
#define GRID_WIDTH (((ANALYSIS_WIDTH - 4 + 3) / 4) + 1)
#define GRID_HEIGHT (((ANALYSIS_HEIGHT - 4 + 3) / 4) + 1)
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

static float test_smoothstep(float edge0, float edge1, float x)
{
    float t = fminf(fmaxf((x - edge0) / (edge1 - edge0), 0.0f), 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

static float test_stripe_sample(float p, int extent)
{
    p = fminf(fmaxf(p, 0.0f), (float)(extent - 1));
    float left = floorf(p);
    float t = p - left;
    int a = ((int)left % 16) < 8 ? 200 : 0;
    int b = ((int)fminf(left + 1.0f, (float)(extent - 1)) % 16) < 8 ? 200 : 0;
    return (1.0f - t) * a + t * b;
}

static float test_point_support(float p, int extent)
{
    float margin = fminf(p, (float)(extent - 1) - p);
    return test_smoothstep(0.0f, 1.0f, margin);
}

static int test_stripe_expected(int x, int y, float timestep, int vertical,
                                int *wanted, int *exact)
{
    float coord = (float)(vertical ? y : x);
    float extent = (float)(vertical ? ANALYSIS_HEIGHT : ANALYSIS_WIDTH);
    float q = fminf(fmaxf((coord + 0.5f) / 4.0f - 0.5f, 0.0f), extent - 1.0f);
    float a = q - timestep * 4.0f;
    float z = q - (1.0f - timestep) * -4.0f;
    float cycle0 = a + 4.0f, cycle1 = z - 4.0f;
    float support = fminf(test_point_support(a, (int)extent),
                          fminf(test_point_support(z, (int)extent),
                                fminf(test_point_support(cycle0, (int)extent),
                                      test_point_support(cycle1, (int)extent))));
    float source = ((int)coord % 16) < 8 ? 200.0f : 0.0f;
    int output_extent = vertical ? HEIGHT : WIDTH;
    float ca = test_stripe_sample(coord + (a-q)*4.0f, output_extent);
    float cb = test_stripe_sample(coord + (z-q)*4.0f, output_extent);
    *exact = 0;
    if (support <= 0.0f) { *wanted = (int)floorf(source + 0.5f); *exact = 1; return 1; }
    /* The shader also tests the orthogonal support margin; assert the analytic
     * warp only on fully interior rows/columns where that factor is one. */
    int orthogonal = vertical ? x : y;
    if (orthogonal < 8 || orthogonal >= (vertical ? WIDTH : HEIGHT) - 8)
        return 0;
    if (fabsf(ca - cb) <= 0.01f) {
        float confidence = support;
        float trust = test_smoothstep(0.0f, 0.60f, confidence);
        *wanted = (int)floorf((source + (ca - source) * trust) + 0.5f);
        *exact = 1; return 1;
    }
    return 0;
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

static int run_fixture(struct VulkanContext *ctx, const char *label,
                        const uint8_t frame0_color[4],
                        const uint8_t frame1_color[4], uint16_t scene_delta,
                        uint16_t luma0_value, uint16_t luma1_value,
                        float timestep, const uint8_t expected[4],
                        int check_all_pixels, int special_grid_test)
{
    uint8_t frame0[IMAGE_BYTES], frame1[IMAGE_BYTES];
    uint16_t flow[2][GRID_PIXELS * 4], scene[4], luma0[LUMA_PIXELS * 4], luma1[LUMA_PIXELS * 4];
    fill_rgba8(frame0, frame0_color);
    fill_rgba8(frame1, frame1_color);
    fill_rgba16f(flow[0], GRID_PIXELS, 0x0000, 0x0000, 0x0000, 0x3c00);
    fill_rgba16f(flow[1], GRID_PIXELS, 0x0000, 0x0000, 0x0000, 0x3c00);
    fill_rgba16f(luma0, LUMA_PIXELS, luma0_value, 0x0000, 0x0000, 0x3c00);
    fill_rgba16f(luma1, LUMA_PIXELS, luma1_value, 0x0000, 0x0000, 0x3c00);
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
    if (special_grid_test == 5 || special_grid_test == 6) {
        /* Case 5: probe P.x=3 with F(x)=2*x-4, B(x)=(4-2*x)/3.
         * These fields are exact cycle inverses, but the forward fixed-point
         * iterate oscillates instead of converging. Newton must now recover it;
         * the residual mutation probe deliberately restores the old iteration
         * to prove its final nonconverged sample still cannot bypass validation.
         * Case 6: constant +/-2 translation has zero residual and must still warp. */
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                size_t index = ((size_t)y * WIDTH + x) * 4;
                int translated = x >= 2 ? x - 2 : 0;
                uint8_t v0 = (uint8_t)(special_grid_test == 5 ? x * 10 : x * x * 4);
                uint8_t v1 = (uint8_t)(special_grid_test == 5 ? (x * 10 > 8 ? x * 10 - 8 : 0) : translated * translated * 4);
                for (int c = 0; c < 3; c++) { frame0[index+c] = v0; frame1[index+c] = v1; }
            }
        }
        for (uint32_t y = 0; y < GRID_HEIGHT; y++) {
            for (uint32_t x = 0; x < GRID_WIDTH; x++) {
                size_t index = ((size_t)y * GRID_WIDTH + x) * 4;
                flow[0][index] = special_grid_test == 5 ? (x == 0 ? 0x0000 : 0x4600) : 0x4000;
                flow[1][index] = special_grid_test == 5 ? (x == 0 ? 0x0000 : 0xc000) : 0xc000;
            }
        }
    }
    if (special_grid_test >= 7 && special_grid_test <= 10) {
        /* Same coherent +/-2px motion, with only a half-float confidence step
         * across the former .28 gate, or a 1/255 color step at the .08 gate.
         * A hard fallback used to change the pixel by 10 levels at once. */
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                size_t index = ((size_t)y * WIDTH + x) * 4;
                int translated = x >= 2 ? x - 2 : 0;
                int offset = special_grid_test >= 9 ? (special_grid_test == 9 ? 20 : 21) : 0;
                int v0 = x * x * 10, v1 = translated * translated * 10 + offset;
                if (v0 > 255) v0 = 255;
                if (v1 > 255) v1 = 255;
                for (int c = 0; c < 3; c++) { frame0[index+c] = (uint8_t)v0; frame1[index+c] = (uint8_t)v1; }
            }
        }
        for (size_t cell = 0; cell < GRID_PIXELS; cell++) {
            flow[0][cell*4] = 0x4000;
            flow[1][cell*4] = 0xc000;
            flow[0][cell*4+3] = flow[1][cell*4+3] =
                special_grid_test == 7 ? 12586 : special_grid_test == 8 ? 12587 : 0x3c00;
            if (special_grid_test <= 8) {
                /* Non-exact SAD keeps these probes outside aperture rescue.
                 * Adjacent half floats still bracket final confidence .28. */
                flow[0][cell*4+2] = flow[1][cell*4+2] = 0x2c00;
            }
        }
    }
    if (special_grid_test == 11) {
        /* Forward motion is fully reliable while reverse motion is not. The
         * synthesis confidence must use conservative min(conf0, conf1). */
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                size_t index = ((size_t)y * WIDTH + x) * 4;
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
            flow[0][cell * 4 + 0] = 0x3c00;
            flow[1][cell * 4 + 0] = 0xbc00;
            flow[0][cell * 4 + 3] = 0x3c00;
            flow[1][cell * 4 + 3] = 0x0000;
        }
    }
    if (special_grid_test >= 12) {
        /* Full output-frame fixtures: 16x8 analysis -> 64x32 output, 32px
         * source motion, including a two-pixel outline and independent rows. */
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                size_t i = ((size_t)y * WIDTH + x) * 4;
                int line0 = x >= 20 && x < 22 && y >= 4 && y < HEIGHT-4;
                int line1 = x >= 52 && x < 54 && y >= 4 && y < HEIGHT-4;
                for (int c = 0; c < 3; c++) {
                    frame0[i+c] = line0 ? 255 : 0;
                    frame1[i+c] = line1 ? 255 : 0;
                }
                frame0[i+3] = frame1[i+3] = 255;
            }
        }
        fill_rgba16f(flow[0], GRID_PIXELS, 0x4800, 0, 0, 0x3c00); /* +8 */
        fill_rgba16f(flow[1], GRID_PIXELS, 0xc800, 0, 0, special_grid_test == 12 ? 0 : 0x3c00);
        if (special_grid_test == 12)
            for (size_t cell=0; cell<GRID_PIXELS; cell++)
                flow[1][cell*4+2]=0x3000; /* Actually unsupported: poor reverse SAD, not merely an aperture tie. */
        if (special_grid_test == 13)
            fill_rgba16f(flow[0], GRID_PIXELS, 0x7e00, 0, 0, 0x3c00); /* NaN */
        if (special_grid_test == 15) {
            const uint16_t vectors[] = {0x0000,0x4000,0x4400,0x4600};
            for (uint32_t y = 0; y < GRID_HEIGHT; y++)
                for (uint32_t x = 0; x < GRID_WIDTH; x++) {
                    size_t i = ((size_t)y * GRID_WIDTH + x) * 4;
                    flow[0][i] = vectors[x];
                    flow[0][i+3] = (x & 1) ? 0 : 0x3c00;
                }
        }
        if (special_grid_test == 16 || special_grid_test == 17) {
            /* Coherent period-16 stripes: frame 1 is translated +16 output
             * pixels, so its image is identical while motion still shifts the
             * sampled phase by 4/8/12 pixels at quarter/half times. */
            for (int y = 0; y < HEIGHT; y++)
                for (int x = 0; x < WIDTH; x++) {
                    size_t i = ((size_t)y * WIDTH + x) * 4;
                    int coordinate = special_grid_test == 16 ? x : y;
                    int v = (coordinate % 16) < 8 ? 200 : 0;
                    for (int c = 0; c < 3; c++)
                        frame0[i+c] = frame1[i+c] = (uint8_t)v;
                    frame0[i+3] = frame1[i+3] = 255;
                }
            fill_rgba16f(flow[0], GRID_PIXELS,
                         special_grid_test == 16 ? 0x4400 : 0,
                         special_grid_test == 17 ? 0x4400 : 0, 0, 0x3c00);
            fill_rgba16f(flow[1], GRID_PIXELS,
                         special_grid_test == 16 ? 0xc400 : 0,
                         special_grid_test == 17 ? 0xc400 : 0, 0, 0x3c00);
        }
    }
    if ((special_grid_test >= 18 && special_grid_test <= 21) || special_grid_test == 24) {
        /* A straight outline is ambiguous along its tangent even when its
         * inherited pan vector, inverse and bidirectional cycle are exact.
         * Check the ENTIRE supported edge region, including old ghost positions. */
        for (int y=0; y<HEIGHT; y++) for (int x=0; x<WIDTH; x++) {
            size_t i=((size_t)y*WIDTH+x)*4;
            int a=x>=20&&x<22&&y>=4&&y<HEIGHT-4;
            int b=x>=28&&x<30&&y>=4&&y<HEIGHT-4;
            for (int c=0;c<3;c++) {
                frame0[i+c]=a?255:0;
                frame1[i+c]=b?255:0;
            }
            if (special_grid_test==21 && b)
                for (int c=0;c<3;c++) frame1[i+c]=239;
            frame0[i+3]=frame1[i+3]=255;
        }
        fill_rgba16f(flow[0],GRID_PIXELS,0x4000,0,0,0); /* +2, exact but tied */
        fill_rgba16f(flow[1],GRID_PIXELS,special_grid_test==19?0xbc00:0xc000,0,
                     special_grid_test==20?0x2c00:0,0); /* -1 breaks cycle; high SAD breaks match */
        if (special_grid_test == 24) {
            for (size_t i=0;i<GRID_PIXELS;i++) {
                flow[0][i*4+2]=flow[1][i*4+2]=0x2000; /* SAD .0078125 */
                flow[0][i*4+3]=flow[1][i*4+3]=0x3200; /* uniqueness .1875 */
            }
        }
    }

    if (special_grid_test == 25 || special_grid_test == 26) {
        /* Targeted occlusion/pan fixtures.  Case 25 has a red foreground
         * rectangle moving independently over a translated, high-frequency
         * RGB background; the grid carries +2px for the rectangle and +1px
         * for the background (reverse vectors are supplied in flow[1]).
         * Case 26 is a deliberately fast 24x8-pixel global camera pan over
         * a checker/gradient texture.  These are intentionally not idealized
         * occlusion masks: a bright old+new pair is a source-frame ghost and
         * must be rejected, while unsupported holes remain shader-policy
         * behavior rather than being called a defect. */
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            size_t i = ((size_t)y * WIDTH + x) * 4;
            int fg0 = special_grid_test == 25 && x >= 20 && x < 28 && y >= 8 && y < 24;
            int fg1 = special_grid_test == 25 && x >= 28 && x < 36 && y >= 8 && y < 24;
            /* Frame 1 is the same world texture translated by (24,8)
             * output pixels; frame 0 remains in output coordinates.  The
             * foreground fixture instead has a 4px background pan and an
             * 8px independent foreground pan. */
            int sx0 = x;
            int sy0 = y;
            int sx1 = special_grid_test == 26 ? x - 24 : x - 4;
            int sy1 = special_grid_test == 26 ? y - 8 : y;
            /* C truncates integer division toward zero.  Floor and positive
             * modulo keep negative frame-1 coordinates on the same periodic
             * world texture as frame 0. */
            int checker0 = ((floor_div4(sx0) + floor_div4(sy0)) & 1) ? 1 : 0;
            int checker1 = ((floor_div4(sx1) + floor_div4(sy1)) & 1) ? 1 : 0;
            uint8_t r0 = (uint8_t)(checker0 ? 176 : 28), g0 = (uint8_t)(24 + positive_mod48(sx0 * 3 + sy0 * 5)), b0 = (uint8_t)(checker0 ? 44 : 132);
            uint8_t r1 = (uint8_t)(checker1 ? 176 : 28), g1 = (uint8_t)(24 + positive_mod48(sx1 * 3 + sy1 * 5)), b1 = (uint8_t)(checker1 ? 44 : 132);
            if (special_grid_test == 25 && fg0) r0 = 240, g0 = 20, b0 = 20;
            if (special_grid_test == 25 && fg1) r1 = 240, g1 = 20, b1 = 20;
            frame0[i] = r0; frame0[i + 1] = g0; frame0[i + 2] = b0; frame0[i + 3] = 255;
            frame1[i] = r1; frame1[i + 1] = g1; frame1[i + 2] = b1; frame1[i + 3] = 255;
        }
        if (special_grid_test == 25) {
            for (uint32_t y = 0; y < GRID_HEIGHT; y++) for (uint32_t x = 0; x < GRID_WIDTH; x++) {
                size_t i = ((size_t)y * GRID_WIDTH + x) * 4;
                /* 16x8 analysis produces only a 4x2 flow lattice.  Cells
                 * 1..2 and both rows cover the 16..48px foreground span;
                 * the old x>=5/y>=2 predicate was permanently false. */
                int foreground_cell = x >= 1 && x <= 2;
                flow[0][i] = foreground_cell ? 0x4000 : 0x3c00;
                flow[1][i] = foreground_cell ? 0xc000 : 0xbc00;
                flow[0][i + 1] = flow[1][i + 1] = 0;
                flow[0][i + 2] = flow[1][i + 2] = 0;
                flow[0][i + 3] = flow[1][i + 3] = 0x3c00;
            }
        } else {
            /* Vectors are analysis pixels: 6x2 becomes 24x8 at 4x output. */
            fill_rgba16f(flow[0], GRID_PIXELS, 0x4600, 0x4000, 0, 0x3c00);
            fill_rgba16f(flow[1], GRID_PIXELS, 0xc600, 0xc000, 0, 0x3c00);
        }
    }

    if (special_grid_test == 22 || special_grid_test == 23) {
        /* Exact affine correspondences, not a relaxation of residual checks.
         * 22: 3x horizontal zoom about 31.5; 23: 90-degree camera rotation.
         * Linear RGB ramps are reproduced exactly by bilinear color sampling. */
        for (int y=0;y<HEIGHT;y++) for (int x=0;x<WIDTH;x++) {
            size_t i=((size_t)y*WIDTH+x)*4;
            for (int c=0;c<3;c++) {
                frame0[i+c]=(uint8_t)(special_grid_test==22?3*x:2*x+y);
                frame1[i+c]=(uint8_t)(special_grid_test==22?x+63:2*y+63-x);
            }
            frame0[i+3]=frame1[i+3]=255;
        }
        for (uint32_t gy=0;gy<GRID_HEIGHT;gy++) for (uint32_t gx=0;gx<GRID_WIDTH;gx++) {
            size_t i=((size_t)gy*GRID_WIDTH+gx)*4;
            float dx=fminf((float)(gx*4),ANALYSIS_WIDTH-4)+2.0f-7.5f;
            float dy=fminf((float)(gy*4),ANALYSIS_HEIGHT-4)+2.0f-7.5f;
            _Float16 fx=(_Float16)(special_grid_test==22?2.0f*dx:-dx-dy);
            _Float16 fy=(_Float16)(special_grid_test==22?0:dx-dy);
            _Float16 bx=(_Float16)(special_grid_test==22?-2.0f/3.0f*dx:dy-dx);
            _Float16 by=(_Float16)(special_grid_test==22?0:-dx-dy);
            memcpy(&flow[0][i],&fx,2); memcpy(&flow[0][i+1],&fy,2);
            memcpy(&flow[1][i],&bx,2); memcpy(&flow[1][i+1],&by,2);
            flow[0][i+2]=flow[1][i+2]=0;
            flow[0][i+3]=flow[1][i+3]=0x3c00;
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
        uint32_t w = i == 4 ? 1 : (i == 2 || i == 3 ? GRID_WIDTH : ANALYSIS_WIDTH);
        uint32_t h = i == 4 ? 1 : (i == 2 || i == 3 ? GRID_HEIGHT : ANALYSIS_HEIGHT);
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
    int32_t push[4] = { WIDTH, HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT };
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

    if (special_grid_test == 25) {
        int shift = (int)lroundf(8.0f * timestep), checked = 0;
        int start = 20 + shift, end = 28 + shift;
        for (int y = 10; y < 22; y++) for (int x = start + 2; x < end - 2; x++) {
            size_t i = ((size_t)y * WIDTH + x) * 4;
            if (output[i] < 235 || output[i + 1] > 25 || output[i + 2] > 25) {
                fprintf(stderr, "Moving-foreground pixel oracle mismatch: fixture=%s pixel=(%d,%d) t=%.2f expected=red actual=(%u,%u,%u)\n", label,x,y,timestep,output[i],output[i+1],output[i+2]);
                exit(1);
            }
            checked++;
        }
        /* These are safely outside the translated rectangle at the sampled
         * times. A source-only temporal blend leaves a red old/new ghost. */
        if (timestep >= 0.7f) for (int y = 10; y < 22; y++)
            for (int x = 20; x < 22; x++) {
                size_t i=((size_t)y*WIDTH+x)*4;
                if (output[i] > 200 && output[i+1] < 70 && output[i+2] < 70) {
                    fprintf(stderr,"Moving-foreground old-position ghost: fixture=%s pixel=(%d,%d) t=%.2f\n",label,x,y,timestep); exit(1);
                }
            }
        if (timestep <= 0.2f) for (int y = 10; y < 22; y++)
            for (int x = 34; x < 36; x++) {
                size_t i=((size_t)y*WIDTH+x)*4;
                if (output[i] > 200 && output[i+1] < 70 && output[i+2] < 70) {
                    fprintf(stderr,"Moving-foreground new-position ghost: fixture=%s pixel=(%d,%d) t=%.2f\n",label,x,y,timestep); exit(1);
                }
            }
        if (checked < 48) { fprintf(stderr,"Moving-foreground oracle underconstrained: checked=%d\n",checked); exit(1); }
        printf("Moving-foreground oracle checked exact red pixels=%d and old/new ghost probes\n", checked);
    }
    if (special_grid_test == 26) {
        int checked = 0;
        for (int y = 12; y < 21; y++) for (int x = 24; x < 41; x++) {
            /* Require both inverse endpoints to have a two-pixel support
             * margin and to sit well inside a checker plateau. */
            float ax = (float)x - 24.0f*timestep, ay = (float)y - 8.0f*timestep;
            float zx = (float)x + 24.0f*(1.0f-timestep), zy = (float)y + 8.0f*(1.0f-timestep);
            if (ax < 4.0f || ay < 4.0f || zx < 4.0f || zy < 4.0f ||
                ax > WIDTH-4.0f || ay > HEIGHT-4.0f ||
                zx > WIDTH-4.0f || zy > HEIGHT-4.0f) continue;
            float fax = ax - floorf(ax/4.0f)*4.0f, fay = ay - floorf(ay/4.0f)*4.0f;
            float fzx = zx - floorf(zx/4.0f)*4.0f, fzy = zy - floorf(zy/4.0f)*4.0f;
            if (fax < 1.5f || fax > 2.5f || fay < 1.5f || fay > 2.5f ||
                fzx < 1.5f || fzx > 2.5f || fzy < 1.5f || fzy > 2.5f) continue;
            int source_cell = (((int)floorf(ax / 4.0f) + (int)floorf(ay / 4.0f)) & 1);
            int endpoint_cell = (((int)floorf(zx / 4.0f) + (int)floorf(zy / 4.0f)) & 1);
            if (source_cell != endpoint_cell) continue;
            size_t i=((size_t)y * WIDTH + x) * 4;
            int want_r = source_cell ? 176 : 28, want_b = source_cell ? 44 : 132;
            if (abs((int)output[i]-want_r)>2 || abs((int)output[i+2]-want_b)>2 || output[i+3]!=255) {
                fprintf(stderr,"Fast-camera supported pixel mismatch: fixture=%s pixel=(%d,%d) t=%.2f expected=(%d,*,%d) actual=(%u,%u,%u)\n",label,x,y,timestep,want_r,want_b,output[i],output[i+1],output[i+2]); exit(1);
            }
            checked++;
        }
        if (checked < 8) { fprintf(stderr,"Fast-camera oracle underconstrained: checked=%d\n",checked); exit(1); }
        printf("Fast-camera oracle checked exact endpoint-safe checker plateaus=%d for 24x8 translation\n", checked);
    }
    if (special_grid_test==22 || special_grid_test==23) {
        int checked=0;
        for (int y=24;y<=40;y++) for (int x=24;x<=40;x++) {
            size_t i=((size_t)y*WIDTH+x)*4;
            float ideal;
            if (special_grid_test==22) ideal=3.0f*((x-31.5f)/(1.0f+2.0f*timestep)+31.5f);
            else {
                float u=1.0f-timestep,den=u*u+timestep*timestep;
                ideal=94.5f+((2.0f*u-timestep)*(x-31.5f)+(2.0f*timestep+u)*(y-31.5f))/den;
            }
            int wanted=(int)floorf(ideal+0.5f);
            for (int c=0;c<3;c++) if (abs((int)output[i+c]-wanted)>1) {
                fprintf(stderr,"Affine inverse regression: fixture=%s pixel=(%d,%d) t=%.2f expected=%d actual=%d\n",label,x,y,timestep,wanted,output[i+c]);
                exit(1);
            }
            if (output[i+3]!=255) exit(1);
            checked++;
        }
        printf("Affine inverse oracle checked %d supported output pixels\n",checked);
    }
    if ((special_grid_test >= 12 && special_grid_test <= 21) || special_grid_test == 24) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                size_t i = ((size_t)y * WIDTH + x) * 4;
                int wanted = 0, check = !(special_grid_test == 16 || special_grid_test == 17);
                if ((special_grid_test>=18 && special_grid_test<=21) || special_grid_test==24) {
                    check=x>=16&&x<=36&&y>=8&&y<HEIGHT-8;
                    if (special_grid_test==21) {
                        int shift=(int)lroundf(8.0f*timestep);
                        check=check&&x>=20+shift&&x<22+shift;
                    }
                    if (special_grid_test==18 || special_grid_test==24) {
                        int shift=(int)lroundf(8.0f*timestep);
                        wanted=x>=20+shift&&x<22+shift?255:0;
                    } else wanted=(int)floorf((1.0f-timestep)*frame0[i]+timestep*frame1[i]+0.5f);
                    if (check) for(int c=0;c<3;c++) {
                        int channel_wanted=(special_grid_test==18 || special_grid_test==24)?wanted:(int)floorf((1.0f-timestep)*frame0[i+c]+timestep*frame1[i+c]+0.5f);
                        if(abs((int)output[i+c]-channel_wanted)>1) {
                            fprintf(stderr,"Aperture outline regression: fixture=%s pixel=(%d,%d) channel=%d t=%.2f expected=%d actual=%d\n",label,x,y,c,timestep,channel_wanted,output[i+c]);
                            exit(1);
                        }
                    }
                    continue;
                }
                if (special_grid_test == 12 || special_grid_test == 13) {
                    wanted = (int)floorf((1.0f-timestep)*frame0[i] + timestep*frame1[i] + 0.5f);
                } else if (special_grid_test == 14) {
                    /* Interior pair support: a 32px translation must produce a
                     * WHOLE shifted outline, not mask artifacts away by blending. */
                    check = x >= 32 && x <= 40 && y >= 8 && y < HEIGHT-8;
                    wanted = x >= 36 && x < 38 ? 255 : 0;
                } else if (special_grid_test == 15) {
                    float pos = ((float)x+0.5f)*(float)ANALYSIS_WIDTH/(float)WIDTH-0.5f-2.0f;
                    float grid = fminf(fmaxf(pos/4.0f,0.0f),(float)GRID_WIDTH-1.0f);
                    wanted = (int)floorf((grid*2.0f/8.0f)*255.0f+0.5f);
                }
                if (check && (abs((int)output[i]-wanted)>1 || output[i+1]!=output[i] || output[i+2]!=output[i] || output[i+3]!=255)) {
                    fprintf(stderr,"Full-frame motion regression: fixture=%s pixel=(%d,%d) alpha=%.2f expected=%d actual=%d\n",label,x,y,timestep,wanted,output[i]);
                    exit(1);
                }
            }
        }
        if (special_grid_test == 16 || special_grid_test == 17) {
            int exact_pixels = 0, trusted_warp_pixels = 0;
            for (int y = 0; y < HEIGHT; y++)
                for (int x = 0; x < WIDTH; x++) {
                    int wanted, exact;
                    if (!test_stripe_expected(x, y, timestep, special_grid_test == 17, &wanted, &exact))
                        continue;
                    size_t i = ((size_t)y * WIDTH + x) * 4;
                    if (abs((int)output[i] - wanted) > 1 || output[i+1] != output[i] ||
                        output[i+2] != output[i] || output[i+3] != 255) {
                        fprintf(stderr, "Exact stripe oracle mismatch: fixture=%s pixel=(%d,%d) t=%.2f expected=%d actual=%u\n", label, x, y, timestep, wanted, output[i]);
                        exit(1);
                    }
                    exact_pixels++;
                    int source = frame0[i];
                    if (wanted != source && abs((int)output[i] - wanted) <= 1)
                        trusted_warp_pixels++;
                }
            if (exact_pixels < 100 || trusted_warp_pixels < 8) {
                fprintf(stderr, "Stripe oracle underconstrained: fixture=%s exact=%d trusted_warp=%d\n", label, exact_pixels, trusted_warp_pixels);
                exit(1);
            }
        }
    }
    int first = check_all_pixels ? 0 : special_grid_test == 2 ? 1 : special_grid_test == 5 ? (2 * WIDTH + 3) :
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
                          strstr(label, "low-confidence source 0") ? 11 :
                          strstr(label, "low-confidence source 1") ? 11 :
                          strstr(label, "default conflict") ? 12 :
                          strstr(label, "irregular terminal") ? -2 : -1;
        const uint8_t grid_ids[WIDTH] = { 11, 0, 12, 12, 12, 11, 11 };
        for (int i = 0; i < (int)PIXELS; i++) {
            const uint8_t *m = decisions + (size_t)i * 4;
            if (m[0] > 12 || m[2] > 15 || m[3] != 255) {
                fprintf(stderr, "Invalid decision encoding: %s (%d,%d)\n", label, i % WIDTH, i / WIDTH); exit(1);
            }
            if (strstr(label, "chroma endpoint") && m[0] >= 5 && m[0] <= 8 && !(m[2] & 2)) {
                fprintf(stderr, "Missing chroma conflict reason: %s (%d,%d)\n", label, i % WIDTH, i / WIDTH); exit(1);
            }
            int id = expected_id == -2 ? grid_ids[i % WIDTH] : expected_id;
            /* The smooth one-analysis-pixel support fade intentionally turns
             * the outermost pixels of a zero-motion conflict into the
             * conservative source-blend decision.  Keep the interior
             * conflict decision exact: this exception is only for the
             * directly affected border pixels. */
            int support_fade_border = expected_id == 12 &&
                                      (i % WIDTH == 0 || i % WIDTH == WIDTH - 1 ||
                                       i / WIDTH == 0 || i / WIDTH == HEIGHT - 1);
            if (support_fade_border && m[0] == 11)
                id = 11;
            if (expected_id == -2 &&
                (i / WIDTH == 0 || i / WIDTH == HEIGHT - 1) && m[0] == 11)
                id = 11;
            if (id >= 0 && m[0] != (uint8_t)id) {
                fprintf(stderr, "Decision id mismatch: %s (%d,%d) expected=%d actual=%u\n", label, i % WIDTH, i / WIDTH, id, m[0]); exit(1);
            }
            if (m[0] == 11 || m[0] == 12) {
                float alpha = timestep;
                for (int c = 0; c < 3; c++) {
                    int expected_blend = (int)((1.0f - alpha) * frame0[(size_t)i * 4 + c] + alpha * frame1[(size_t)i * 4 + c] + 0.5f);
                    if (abs((int)output[(size_t)i * 4 + c] - expected_blend) > 1) {
                        fprintf(stderr, "Source-blend/output mismatch: %s (%d,%d) id=%u expected=%d actual=%u\n", label, i % WIDTH, i / WIDTH, m[0], expected_blend, output[(size_t)i * 4 + c]); exit(1);
                    }
                }
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
    if (ctx->decision_mode && (special_grid_test == 5 || special_grid_test == 6)) {
        int required_id = strstr(label,"nonconverged inverse") ? 11 : 0;
        if (decisions[(size_t)first * 4] != required_id) {
            fprintf(stderr, "Inverse-warp decision mismatch: %s expected=%d actual=%u\n", label, required_id, decisions[(size_t)first * 4]);
            exit(1);
        }
    }
    int sampled_pixel = output[(size_t)first * 4];
    printf("Vulkan synthesis pixel fixture passed: %s (probe=%d)\n", label, sampled_pixel);
    free(output);
    free(decisions);
    destroy_buffer(ctx, &readback);
    destroy_buffer(ctx, &decision_readback);
    for (int i = 0; i < 9; i++)
        destroy_image(ctx, &images[i]);
    return sampled_pixel;
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
    if (argc < 2 || argc > 6) return 2;
    struct VulkanContext ctx = {0};
    ctx.soft_blend = argc >= 3 && atoi(argv[2]) != 0;
    ctx.preserve_motion_blend = argc >= 4 && atoi(argv[3]) != 0;
    ctx.decision_mode = argc >= 5 && atoi(argv[4]) != 0;
    initialize_vulkan(&ctx, argv[1]);
    printf("Running exact MPVFlow shader_synthesize on Vulkan device: %s (output=%dx%d analysis=%dx%d soft_blend=%d preserve_motion_blend=%d decision_mode=%d)\n", ctx.device_name, WIDTH, HEIGHT, ANALYSIS_WIDTH, ANALYSIS_HEIGHT, ctx.soft_blend, ctx.preserve_motion_blend, ctx.decision_mode);
    const uint8_t red[4]={255,0,0,255}, blue[4]={0,0,255,255}, green[4]={0,130,0,255};
    const uint8_t neutral0[4]={100,100,100,255}, neutral1[4]={120,110,104,255}, mixed[4]={110,105,102,255};
    const uint8_t soft_chroma[4]={64,98,0,255}, continuous_chroma[4]={50,104,0,255};
    const uint8_t source_blend_low0[4]={191,0,64,255}, source_blend_low1[4]={64,0,191,255};
    const uint8_t soft_low[4]={214,0,41,255}, soft_edge[4]={50,114,50,255}, continuous_edge[4]={56,120,56,255};
    const uint8_t inverse_fallback[4]={26,26,26,255}, translated_pixel[4]={16,16,16,255};
    if (argc > 5 && strcmp(argv[5], "residual") == 0) {
        run_fixture(&ctx,"nonconverged inverse warp retains residual safety guard",neutral0,neutral0,0,0x3400,0x3400,0.5f,inverse_fallback,0,5);
        destroy_vulkan(&ctx);
        return 0;
    }
#ifdef FLOW_LARGE_FIXTURES
    const uint8_t background[4] = {0,0,0,255};
    if (argc > 5 && strcmp(argv[5], "coherent") == 0) {
        for (int step=1;step<=3;step++) {
            float t=(float)step*0.25f;
            run_fixture(&ctx,"coherent tied outline has no old/new-position ghosts",background,background,0,0x3400,0x3400,t,NULL,0,18);
            run_fixture(&ctx,"coherent low-uniqueness outline has no old/new-position ghosts",background,background,0,0x3400,0x3400,t,NULL,0,24);
        }
        destroy_vulkan(&ctx);
        puts("Coherent outline policy tests passed");
        return 0;
    }
    if (argc > 5 && strcmp(argv[5], "affine") == 0) {
        for (int step=1;step<=3;step++) {
            float t=(float)step*0.25f;
            run_fixture(&ctx,"invertible camera zoom retains aligned samples",background,background,0,0x3400,0x3400,t,NULL,0,22);
            run_fixture(&ctx,"invertible camera rotation retains aligned samples",background,background,0,0x3400,0x3400,t,NULL,0,23);
        }
        destroy_vulkan(&ctx);
        puts("Affine camera inverse-warp tests passed");
        return 0;
    }

    if (argc > 5 && strcmp(argv[5], "aperture") == 0) {
        for(int step=1;step<=3;step++) {
            float t=(float)step*0.25f;
            run_fixture(&ctx,"tied straight outline retains coherent motion without source ghosts",background,background,0,0x3400,0x3400,t,NULL,0,18);
            run_fixture(&ctx,"tied but cycle-inconsistent motion stays rejected",background,background,0,0x3400,0x3400,t,NULL,0,19);
            run_fixture(&ctx,"tied high-residual motion stays rejected",background,background,0,0x3400,0x3400,t,NULL,0,20);
            run_fixture(&ctx,"tied full-resolution color conflict stays rejected",background,background,0,0x3400,0x3400,t,NULL,0,21);
        }
        destroy_vulkan(&ctx);
        puts("Aperture-tied outline and rejection tests passed");
        return 0;
    }
    if (argc > 5 && strcmp(argv[5], "field") == 0) {
        run_fixture(&ctx,"confidence-independent continuous geometric field",background,background,0,0x3400,0x3400,0.5f,NULL,0,15);
    } else if (argc > 5 && strcmp(argv[5], "occlusion") == 0) {
        const float samples[] = { 0.20f, 0.40f, 0.60f, 0.80f };
        for (size_t step = 0; step < sizeof(samples) / sizeof(samples[0]); step++) {
            float t = samples[step];
            run_fixture(&ctx,"independent foreground over textured moving background",background,background,0,0x3400,0x3400,t,NULL,0,25);
            run_fixture(&ctx,"fast global camera pan preserves textured support",background,background,0,0x3400,0x3400,t,NULL,0,26);
        }
        destroy_vulkan(&ctx);
        puts("Independent-foreground and fast-camera-pan synthesis tests passed");
        return 0;
    } else if (argc > 5 && strcmp(argv[5], "bounds") == 0) {
        for (int step = 1; step <= 3; step++) {
            float t = (float)step * 0.25f;
            run_fixture(&ctx,"horizontal constant-translation support fade",background,background,0,0x3400,0x3400,t,NULL,0,16);
            run_fixture(&ctx,"vertical constant-translation support fade",background,background,0,0x3400,0x3400,t,NULL,0,17);
        }
    } else if (argc > 5 && strcmp(argv[5], "nan") == 0) {
        for (int step=1; step<=3; step++)
            run_fixture(&ctx,"nonfinite motion preserves complete temporal source blend",background,background,0,0x3400,0x3400,(float)step*0.25f,NULL,0,13);
    } else {
        for (int step=1; step<=3; step++) {
            float t = (float)step*0.25f;
            run_fixture(&ctx,"unsupported reverse motion preserves complete temporal source blend",background,background,0,0x3400,0x3400,t,NULL,0,12);
            run_fixture(&ctx,"nonfinite motion preserves complete temporal source blend",background,background,0,0x3400,0x3400,t,NULL,0,13);
        }
        run_fixture(&ctx,"reliable 32px motion preserves the whole thin outline",background,background,0,0x3400,0x3400,0.5f,NULL,0,14);
    }
    destroy_vulkan(&ctx);
    puts("Full-frame 4x-upscaled motion tests passed");
    return 0;
#endif
    const int seams_only = argc > 5 && strcmp(argv[5], "seams") == 0;
    {
        int seam_failures = 0;
        int c0 = run_fixture(&ctx,"confidence contour below former gate",neutral0,neutral0,0,0x3400,0x3400,0.5f,NULL,0,7);
        int c1 = run_fixture(&ctx,"confidence contour above former gate",neutral0,neutral0,0,0x3400,0x3400,0.5f,NULL,0,8);
        if (abs(c1-c0) > 1) { fprintf(stderr,"Hard confidence contour seam: below=%d above=%d\n",c0,c1); seam_failures++; }
        int b0 = run_fixture(&ctx,"boundary contour below former gate",neutral0,neutral0,0,0x3400,0x3400,0.5f,NULL,0,9);
        int b1 = run_fixture(&ctx,"boundary contour above former gate",neutral0,neutral0,0,0x3400,0x3400,0.5f,NULL,0,10);
        if (abs(b1-b0) > 2) { fprintf(stderr,"Hard boundary contour seam: below=%d above=%d\n",b0,b1); seam_failures++; }
        if (seam_failures) { destroy_vulkan(&ctx); return 1; }
    }
    if (seams_only) {
        destroy_vulkan(&ctx);
        puts("Confidence and boundary seam tests passed");
        return 0;
    }
    run_fixture(&ctx,"reliable constant translation retains warped detail",neutral0,neutral0,0,0x3400,0x3400,0.5f,translated_pixel,0,6);
    run_fixture(&ctx,"converged affine inverse now retains valid warp support",neutral0,neutral0,0,0x3400,0x3400,0.5f,inverse_fallback,0,5);
    run_fixture(&ctx,"scene-cut alpha selects first source",red,blue,0x3800,0x3400,0x3800,0.25f,red,1,0);
    run_fixture(&ctx,"scene-cut alpha selects second source",red,blue,0x3800,0x3400,0x3800,0.75f,blue,1,0);
    run_fixture(&ctx,"static identical frame is preserved exactly",neutral0,neutral0,0,0x3400,0x3400,0.37f,neutral0,1,0);
    run_fixture(&ctx,ctx.soft_blend?"soft mode blends the chroma boundary":"default conflict fallback blends source frames",red,green,0,0x3400,0x3800,0.75f,ctx.soft_blend?(ctx.preserve_motion_blend?continuous_chroma:soft_chroma):soft_chroma,0,0);
    run_fixture(&ctx,"low-contrast reliable motion retains temporal color blend",neutral0,neutral1,0,0x3400,0x3500,0.5f,mixed,0,0);
    const uint8_t asymmetric_confidence_blend[4]={60,124,60,255};
    const uint8_t asymmetric_soft_blend[4]={50,114,50,255};
    const uint8_t asymmetric_continuous_blend[4]={56,120,56,255};
    run_fixture(&ctx,"asymmetric endpoint confidence uses conservative min",red,blue,0,0x3400,0x3800,0.5f,ctx.soft_blend?(ctx.preserve_motion_blend?asymmetric_continuous_blend:asymmetric_soft_blend):asymmetric_confidence_blend,0,11);
    run_fixture(&ctx,"soft fallback keeps a continuous blend across the moving edge",red,blue,0,0x3400,0x3800,0.5f,ctx.soft_blend?(ctx.preserve_motion_blend?continuous_edge:soft_edge):NULL,0,4);
    const uint8_t soft_grid[4]={31,31,31,255}, cont_grid[4]={27,27,27,255};
    const uint8_t terminal_grid_source[4]={24,24,24,255};
    run_fixture(&ctx,"irregular terminal block anchors use conservative source fallback",neutral0,neutral0,0,0x3400,0x3400,0.5f,ctx.soft_blend?(ctx.preserve_motion_blend?cont_grid:soft_grid):terminal_grid_source,0,1);
    const uint8_t outlier[4]={2,2,2,255};
    run_fixture(&ctx,"ambiguous fast vector cannot warp a coherent region",neutral0,neutral0,0,0x3400,0x3400,0.25f,outlier,0,2);
    run_fixture(&ctx,ctx.soft_blend?"soft mode favors temporal detail at low confidence":"low-confidence source 0 weighted blend",red,blue,0,0x3400,0x3800,0.25f,ctx.soft_blend?soft_low:source_blend_low0,1,3);
    if (ctx.decision_mode) run_fixture(&ctx,"low-confidence source 1 weighted blend",red,blue,0,0x3400,0x3800,0.75f,source_blend_low1,1,3);
    destroy_vulkan(&ctx);
    puts("MPVFlow Vulkan synthesis pixel-oracle tests passed");
    return 0;
}
