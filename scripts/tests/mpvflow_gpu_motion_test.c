/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Standalone production shader_flow motion/tie regression oracle. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>
#define W 64
#define H 48
#define N4(n) ((size_t)(n) * 4)
typedef struct {
  VkImage im;
  VkImageView view;
  VkDeviceMemory mem;
  uint32_t w, h;
  VkImageLayout lay;
  VkFormat fmt;
} Img;
typedef struct {
  VkBuffer b;
  VkDeviceMemory m;
  VkDeviceSize n;
} Buf;
typedef struct {
  VkInstance ins;
  VkPhysicalDevice pd;
  VkDevice dev;
  VkQueue q;
  uint32_t qf;
  VkCommandPool cp;
  VkDescriptorSetLayout dl;
  VkDescriptorPool dp;
  VkDescriptorSet ds;
  VkPipelineLayout pl;
  VkPipeline pipe;
} Ctx;
static void die(const char *s, VkResult r) {
  fprintf(stderr, "motion harness: %s (%d)\n", s, r);
  exit(2);
}
#define VK(x)                                                                  \
  do {                                                                         \
    VkResult r = (x);                                                          \
    if (r != VK_SUCCESS)                                                       \
      die(#x, r);                                                              \
  } while (0)
static uint32_t mt(Ctx *c, uint32_t bits, VkMemoryPropertyFlags f) {
  VkPhysicalDeviceMemoryProperties p;
  vkGetPhysicalDeviceMemoryProperties(c->pd, &p);
  for (uint32_t i = 0; i < p.memoryTypeCount; i++)
    if ((bits >> i & 1) && (p.memoryTypes[i].propertyFlags & f) == f)
      return i;
  die("memory", -1);
  return 0;
}
static VkCommandBuffer cmd(Ctx *c) {
  VkCommandBufferAllocateInfo a = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
  a.commandPool = c->cp;
  a.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  a.commandBufferCount = 1;
  VkCommandBuffer x;
  VK(vkAllocateCommandBuffers(c->dev, &a, &x));
  VkCommandBufferBeginInfo b = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
  b.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  VK(vkBeginCommandBuffer(x, &b));
  return x;
}
static void submit(Ctx *c, VkCommandBuffer x) {
  VK(vkEndCommandBuffer(x));
  VkSubmitInfo s = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO};
  s.commandBufferCount = 1;
  s.pCommandBuffers = &x;
  VK(vkQueueSubmit(c->q, 1, &s, VK_NULL_HANDLE));
  VK(vkQueueWaitIdle(c->q));
  vkFreeCommandBuffers(c->dev, c->cp, 1, &x);
}
static Buf buffer(Ctx *c, VkDeviceSize n, VkBufferUsageFlags u) {
  Buf b = {0};
  b.n = n;
  VkBufferCreateInfo i = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
  i.size = n;
  i.usage = u;
  i.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  VK(vkCreateBuffer(c->dev, &i, 0, &b.b));
  VkMemoryRequirements r;
  vkGetBufferMemoryRequirements(c->dev, b.b, &r);
  VkMemoryAllocateInfo a = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  a.allocationSize = r.size;
  a.memoryTypeIndex = mt(c, r.memoryTypeBits,
                         VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                             VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
  VK(vkAllocateMemory(c->dev, &a, 0, &b.m));
  VK(vkBindBufferMemory(c->dev, b.b, b.m, 0));
  return b;
}
static Img image(Ctx *c, VkFormat f, uint32_t w, uint32_t h,
                 VkImageUsageFlags u) {
  Img x = {.w = w, .h = h, .fmt = f, .lay = VK_IMAGE_LAYOUT_UNDEFINED};
  VkImageCreateInfo i = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  i.imageType = VK_IMAGE_TYPE_2D;
  i.format = f;
  i.extent = (VkExtent3D){w, h, 1};
  i.mipLevels = 1;
  i.arrayLayers = 1;
  i.samples = VK_SAMPLE_COUNT_1_BIT;
  i.tiling = VK_IMAGE_TILING_OPTIMAL;
  i.usage = u;
  i.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  VK(vkCreateImage(c->dev, &i, 0, &x.im));
  VkMemoryRequirements r;
  vkGetImageMemoryRequirements(c->dev, x.im, &r);
  VkMemoryAllocateInfo a = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  a.allocationSize = r.size;
  a.memoryTypeIndex =
      mt(c, r.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  VK(vkAllocateMemory(c->dev, &a, 0, &x.mem));
  VK(vkBindImageMemory(c->dev, x.im, x.mem, 0));
  VkImageViewCreateInfo v = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
  v.image = x.im;
  v.viewType = VK_IMAGE_VIEW_TYPE_2D;
  v.format = f;
  v.subresourceRange =
      (VkImageSubresourceRange){VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  VK(vkCreateImageView(c->dev, &v, 0, &x.view));
  return x;
}
static void destroy_buffer(Ctx *c, Buf *b) {
  if (b->b)
    vkDestroyBuffer(c->dev, b->b, NULL);
  if (b->m)
    vkFreeMemory(c->dev, b->m, NULL);
  memset(b, 0, sizeof(*b));
}
static void destroy_image(Ctx *c, Img *x) {
  if (x->view)
    vkDestroyImageView(c->dev, x->view, NULL);
  if (x->im)
    vkDestroyImage(c->dev, x->im, NULL);
  if (x->mem)
    vkFreeMemory(c->dev, x->mem, NULL);
  memset(x, 0, sizeof(*x));
}

static void upload(Ctx *c, Img *x, const void *p, size_t n) {
  Buf b = buffer(c, n, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
  void *m;
  VK(vkMapMemory(c->dev, b.m, 0, n, 0, &m));
  memcpy(m, p, n);
  vkUnmapMemory(c->dev, b.m);
  VkCommandBuffer z = cmd(c);
  VkImageMemoryBarrier q = {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  q.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  q.oldLayout = x->lay;
  q.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  q.srcQueueFamilyIndex = q.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  q.image = x->im;
  q.subresourceRange =
      (VkImageSubresourceRange){VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  vkCmdPipelineBarrier(z,
                       x->lay == VK_IMAGE_LAYOUT_UNDEFINED
                           ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                           : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, 0, 0, 0, 1, &q);
  VkBufferImageCopy cp = {0};
  cp.imageSubresource =
      (VkImageSubresourceLayers){VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  cp.imageExtent = (VkExtent3D){x->w, x->h, 1};
  vkCmdCopyBufferToImage(z, b.b, x->im, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                         &cp);
  q.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  q.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
  q.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  q.newLayout = VK_IMAGE_LAYOUT_GENERAL;
  vkCmdPipelineBarrier(z, VK_PIPELINE_STAGE_TRANSFER_BIT,
                       VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, 0, 0, 0, 1,
                       &q);
  submit(c, z);
  x->lay = VK_IMAGE_LAYOUT_GENERAL;
  vkDestroyBuffer(c->dev, b.b, 0);
  vkFreeMemory(c->dev, b.m, 0);
}
static uint16_t half(float f) {
  union {
    float f;
    uint32_t u;
  } v = {f};
  uint32_t s = v.u >> 31, e = (v.u >> 23 & 255) - 127 + 15, m = v.u & 0x7fffff;
  if ((int)e <= 0)
    return (uint16_t)(s << 15);
  if (e >= 31)
    return (uint16_t)((s << 15) | 0x7c00);
  return (uint16_t)((s << 15) | (e << 10) | (m >> 13));
}
static float unhalf(uint16_t h) {
  uint32_t s = (h >> 15) & 1, e = (h >> 10) & 31, m = h & 1023, u;
  if (!e)
    u = s << 31;
  else if (e == 31)
    u = (s << 31) | 0x7f800000 | (m << 13);
  else
    u = (s << 31) | ((e - 15 + 127) << 23) | (m << 13);
  union {
    uint32_t u;
    float f;
  } v = {u};
  return v.f;
}
static void init(Ctx *c, const char *spv) {
  VkApplicationInfo ai = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO};
  ai.pApplicationName = "motion";
  ai.apiVersion = VK_API_VERSION_1_1;
  VkInstanceCreateInfo ii = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  ii.pApplicationInfo = &ai;
  VK(vkCreateInstance(&ii, 0, &c->ins));
  uint32_t n = 0;
  VK(vkEnumeratePhysicalDevices(c->ins, &n, 0));
  VkPhysicalDevice *p = calloc(n, sizeof(*p));
  VK(vkEnumeratePhysicalDevices(c->ins, &n, p));
  for (uint32_t k = 0; k < n && !c->pd; k++) {
    uint32_t qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(p[k], &qn, 0);
    VkQueueFamilyProperties *q = calloc(qn, sizeof(*q));
    vkGetPhysicalDeviceQueueFamilyProperties(p[k], &qn, q);
    for (uint32_t j = 0; j < qn; j++)
      if (q[j].queueFlags & VK_QUEUE_COMPUTE_BIT) {
        c->pd = p[k];
        c->qf = j;
        break;
      }
    free(q);
  }
  free(p);
  float pr = 1;
  VkDeviceQueueCreateInfo qi = {.sType =
                                    VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
  qi.queueFamilyIndex = c->qf;
  qi.queueCount = 1;
  qi.pQueuePriorities = &pr;
  VkDeviceCreateInfo di = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
  di.queueCreateInfoCount = 1;
  di.pQueueCreateInfos = &qi;
  VK(vkCreateDevice(c->pd, &di, 0, &c->dev));
  vkGetDeviceQueue(c->dev, c->qf, 0, &c->q);
  VkCommandPoolCreateInfo pi = {.sType =
                                    VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
  pi.queueFamilyIndex = c->qf;
  VK(vkCreateCommandPool(c->dev, &pi, 0, &c->cp));
  VkDescriptorSetLayoutBinding b[7];
  for (int i = 0; i < 7; i++)
    b[i] = (VkDescriptorSetLayoutBinding){i, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                          1, VK_SHADER_STAGE_COMPUTE_BIT, 0};
  VkDescriptorSetLayoutCreateInfo li = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
  li.bindingCount = 7;
  li.pBindings = b;
  VK(vkCreateDescriptorSetLayout(c->dev, &li, 0, &c->dl));
  VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 7};
  VkDescriptorPoolCreateInfo dpi = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
  dpi.maxSets = 1;
  dpi.poolSizeCount = 1;
  dpi.pPoolSizes = &ps;
  VK(vkCreateDescriptorPool(c->dev, &dpi, 0, &c->dp));
  VkDescriptorSetAllocateInfo da = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
  da.descriptorPool = c->dp;
  da.descriptorSetCount = 1;
  da.pSetLayouts = &c->dl;
  VK(vkAllocateDescriptorSets(c->dev, &da, &c->ds));
  VkPushConstantRange prc = {VK_SHADER_STAGE_COMPUTE_BIT, 0, 20};
  VkPipelineLayoutCreateInfo pl = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
  pl.setLayoutCount = 1;
  pl.pSetLayouts = &c->dl;
  pl.pushConstantRangeCount = 1;
  pl.pPushConstantRanges = &prc;
  VK(vkCreatePipelineLayout(c->dev, &pl, 0, &c->pl));
  FILE *f = fopen(spv, "rb");
  if (!f)
    die("open SPIR-V", VK_ERROR_INITIALIZATION_FAILED);
  fseek(f, 0, SEEK_END);
  long sz = ftell(f);
  if (sz <= 0 || sz % 4)
    die("SPIR-V size", VK_ERROR_INITIALIZATION_FAILED);
  fseek(f, 0, SEEK_SET);
  uint32_t *code = malloc(sz);
  if (!code || fread(code, 1, sz, f) != (size_t)sz)
    die("read SPIR-V", VK_ERROR_INITIALIZATION_FAILED);
  fclose(f);
  VkShaderModuleCreateInfo sm = {
      .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
  sm.codeSize = sz;
  sm.pCode = code;
  VkShaderModule mod;
  VK(vkCreateShaderModule(c->dev, &sm, 0, &mod));
  VkComputePipelineCreateInfo cp = {
      .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
  cp.stage = (VkPipelineShaderStageCreateInfo){
      VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
      0,
      0,
      VK_SHADER_STAGE_COMPUTE_BIT,
      mod,
      "main",
      0};
  cp.layout = c->pl;
  VK(vkCreateComputePipelines(c->dev, 0, 1, &cp, 0, &c->pipe));
  vkDestroyShaderModule(c->dev, mod, 0);
  free(code);
}
int main(int ac, char **av) {
  if (ac < 2) {
    fprintf(stderr, "usage: %s flow.spv [child-block-size 4|8]\n", av[0]);
    return 2;
  }
  int child_block = ac >= 3 ? atoi(av[2]) : 4;
  if (child_block != 4 && child_block != 8) {
    fprintf(stderr, "child block size must be 4 or 8\n");
    return 2;
  }
  const int parent_case = ac >= 4 && strcmp(av[3], "parent") == 0;
  const int gw = (W - child_block + 3) / 4 + 1;
  const int gh = (H - child_block + 3) / 4 + 1;
  const int parent_block = 8;
  int pw = ((W / 2 - parent_block + 3) / 4 + 1);
  int ph = ((H / 2 - parent_block + 3) / 4 + 1);
  Ctx c = {0};
  init(&c, av[1]);
  size_t pix = (size_t)W * H, grid = (size_t)gw * gh, parent_grid = (size_t)pw * ph;
  uint16_t *src = calloc(pix * 4, 2), *dst = calloc(pix * 4, 2),
           *par = calloc(parent_grid * 4, 2), *out = calloc(grid * 4, 2);
  uint8_t *col0 = calloc(pix * 4, 1), *col1 = calloc(pix * 4, 1);
  uint16_t *scene = calloc(4, 2);
  for (int y = 0; y < H; y++)
    for (int x = 0; x < W; x++) {
      int k = (y * W + x) * 4;
      float a = 0.06f + 0.015f * ((x / 3 + y / 5) & 1),
            b = 0.06f + 0.015f * (((x - 3) / 3 + y / 5) & 1);
      int xa = x - 10, xb = x - 13;
      int shape = (xa >= 0 && xa < 22 && y >= 10 && y < 36),
          shape1 = (xb >= 0 && xb < 22 && y >= 10 && y < 36);
      int edge = shape && (xa < 2 || xa > 19 || y < 12 || y > 33),
          edge1 = shape1 && (xb < 2 || xb > 19 || y < 12 || y > 33);
      float v = edge ? 0.02f : (shape ? 0.92f : a),
            w = edge1 ? 0.02f : (shape1 ? 0.92f : b);
      src[k] = src[k + 1] = src[k + 2] = half(v);
      dst[k] = dst[k + 1] = dst[k + 2] = half(w);
      src[k + 3] = dst[k + 3] = half(1);
      col0[k] = col0[k + 1] = col0[k + 2] =
          (uint8_t)lroundf(255.0f * (edge ? 0.02f : (shape ? 0.92f : a)));
      col1[k] = col1[k + 1] = col1[k + 2] =
          (uint8_t)lroundf(255.0f * (edge1 ? 0.02f : (shape1 ? 0.92f : b)));
      col0[k + 3] = col1[k + 3] = 255;
    }
  for (size_t i = 0; i < parent_grid; i++) {
    int px = (int)(i % pw), py = (int)(i / pw);
    /* Adversarial parent-field sentinel, independent of the pan image oracle.
     * For the fine (3,3) probe the correct anchor is parent pixel (3,3),
     * whereas stale -2 reads (4,4). The values make that misregistration
     * observable even with the real radius-4 search, not a radius-zero stub.
     * This is a coordinate regression, not an end-to-end two-layer render. */
    float parent_dx = 1.5f;
    if (parent_case && child_block == 4 && px <= 2 && py <= 2) {
      if (px == 2 && py == 2) parent_dx = -8.0f;
      else if (px <= 1 && py <= 1)
        parent_dx = (px == 1 && py == 1) ? -8.0f : 13.714285f;
    }
    if (parent_case && child_block == 8 && px <= 2 && py <= 2) {
      /* The 8x8 child's aligned parent coordinate is exactly cell (1,1).
       * Old origin-only sampling averages in the independently moving region
       * at cells (2,1)/(1,2)/(2,2), outside the radius-8 recovery budget. */
      parent_dx = (px <= 1 && py <= 1) ? -16.0f : 12.0f;
      if (px == 1 && py == 1) parent_dx = 1.5f;
    }
    par[i * 4] = half(parent_dx);
    par[i * 4 + 1] = half(0);
    par[i * 4 + 2] = half(0.03f);
    par[i * 4 + 3] = half(0.0f);
  }
  Img im[7];
  im[0] = image(&c, VK_FORMAT_R16G16B16A16_SFLOAT, W, H,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
  im[1] = image(&c, im[0].fmt, W, H,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
  im[2] = image(&c, im[0].fmt, pw, ph,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
  im[3] = image(&c, im[0].fmt, gw, gh,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
  im[4] = image(&c, im[0].fmt, 1, 1,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
  im[5] = image(&c, VK_FORMAT_R8G8B8A8_UNORM, W, H,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
  im[6] = image(&c, VK_FORMAT_R8G8B8A8_UNORM, W, H,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
  upload(&c, &im[0], src, pix * 8);
  upload(&c, &im[1], dst, pix * 8);
  upload(&c, &im[2], par, parent_grid * 8);
  upload(&c, &im[3], out, grid * 8);
  upload(&c, &im[4], scene, 8);
  upload(&c, &im[5], col0, pix * 4);
  upload(&c, &im[6], col1, pix * 4);
  VkDescriptorImageInfo info[7];
  VkWriteDescriptorSet wr[7];
  for (int i = 0; i < 7; i++) {
    info[i] = (VkDescriptorImageInfo){0, im[i].view, VK_IMAGE_LAYOUT_GENERAL};
    wr[i] = (VkWriteDescriptorSet){
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .dstSet = c.ds,
        .dstBinding = (uint32_t)i,
        .descriptorCount = 1,
        .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
        .pImageInfo = &info[i]};
  }
  vkUpdateDescriptorSets(c.dev, 7, wr, 0, 0);
  VkCommandBuffer z = cmd(&c);
  vkCmdBindPipeline(z, VK_PIPELINE_BIND_POINT_COMPUTE, c.pipe);
  vkCmdBindDescriptorSets(z, VK_PIPELINE_BIND_POINT_COMPUTE, c.pl, 0, 1, &c.ds,
                          0, 0);
  int32_t pc[4] = {W, H, child_block == 4 ? 4 : 8, (child_block << 8) | 1 | 8};
  float timestep = 0.5f;
  vkCmdPushConstants(z, c.pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 16, pc);
  vkCmdPushConstants(z, c.pl, VK_SHADER_STAGE_COMPUTE_BIT, 16, 4, &timestep);
  vkCmdDispatch(z, (gw + 7) / 8, (gh + 7) / 8, 1);
  VkImageMemoryBarrier q = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
      .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
      .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
      .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
      .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
      .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .image = im[3].im,
      .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
  vkCmdPipelineBarrier(z, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, 0, 0, 0, 1, &q);
  Buf rb = buffer(&c, grid * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
  VkBufferImageCopy cp = {0};
  cp.imageSubresource =
      (VkImageSubresourceLayers){VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  cp.imageExtent = (VkExtent3D){gw, gh, 1};
  vkCmdCopyImageToBuffer(z, im[3].im, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                         rb.b, 1, &cp);
  VkBufferMemoryBarrier hb = {.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
                              .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                              .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
                              .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                              .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                              .buffer = rb.b,
                              .size = VK_WHOLE_SIZE};
  vkCmdPipelineBarrier(z, VK_PIPELINE_STAGE_TRANSFER_BIT,
                       VK_PIPELINE_STAGE_HOST_BIT, 0, 0, 0, 1, &hb, 0, 0);
  submit(&c, z);
  void *m;
  VK(vkMapMemory(c.dev, rb.m, 0, grid * 8, 0, &m));
  memcpy(out, m, grid * 8);
  vkUnmapMemory(c.dev, rb.m);
  /* Run the same production pass backward as well: swap both luma and RGB
   * bindings. */
  for (size_t i = 0; i < parent_grid; i++)
    par[i * 4] = half(-1.5f);
  upload(&c, &im[2], par, parent_grid * 8);
  VkDescriptorImageInfo bi[2] = {{0, im[1].view, VK_IMAGE_LAYOUT_GENERAL},
                                 {0, im[0].view, VK_IMAGE_LAYOUT_GENERAL}};
  VkWriteDescriptorSet bw[2] = {
      {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
       .dstSet = c.ds,
       .dstBinding = 0,
       .descriptorCount = 1,
       .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
       .pImageInfo = &bi[0]},
      {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
       .dstSet = c.ds,
       .dstBinding = 1,
       .descriptorCount = 1,
       .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
       .pImageInfo = &bi[1]}};
  VkDescriptorImageInfo bc[2] = {{0, im[6].view, VK_IMAGE_LAYOUT_GENERAL},
                                 {0, im[5].view, VK_IMAGE_LAYOUT_GENERAL}};
  bw[0].dstBinding = 0;
  vkUpdateDescriptorSets(c.dev, 2, bw, 0, NULL);
  bw[0].dstBinding = 5;
  bw[0].pImageInfo = &bc[0];
  bw[1].dstBinding = 6;
  bw[1].pImageInfo = &bc[1];
  vkUpdateDescriptorSets(c.dev, 2, bw, 0, NULL);
  VkCommandBuffer back = cmd(&c);
  VkImageMemoryBarrier to_write = q;
  to_write.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
  to_write.newLayout = VK_IMAGE_LAYOUT_GENERAL;
  to_write.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
  to_write.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
  vkCmdPipelineBarrier(back, VK_PIPELINE_STAGE_TRANSFER_BIT,
                       VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, NULL, 0,
                       NULL, 1, &to_write);
  vkCmdBindPipeline(back, VK_PIPELINE_BIND_POINT_COMPUTE, c.pipe);
  vkCmdBindDescriptorSets(back, VK_PIPELINE_BIND_POINT_COMPUTE, c.pl, 0, 1,
                          &c.ds, 0, NULL);
  vkCmdPushConstants(back, c.pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 16, pc);
  vkCmdPushConstants(back, c.pl, VK_SHADER_STAGE_COMPUTE_BIT, 16, 4, &timestep);
  vkCmdDispatch(back, (gw + 7) / 8, (gh + 7) / 8, 1);
  q.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
  q.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
  q.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
  q.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
  q.image = im[3].im;
  vkCmdPipelineBarrier(back, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1,
                       &q);
  Buf rb_back = buffer(&c, grid * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
  vkCmdCopyImageToBuffer(back, im[3].im, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                         rb_back.b, 1, &cp);
  hb.buffer = rb_back.b;
  vkCmdPipelineBarrier(back, VK_PIPELINE_STAGE_TRANSFER_BIT,
                       VK_PIPELINE_STAGE_HOST_BIT, 0, 0, NULL, 1, &hb, 0, NULL);
  submit(&c, back);
  uint16_t *out_back = calloc(grid * 4, 2);
  VK(vkMapMemory(c.dev, rb_back.m, 0, grid * 8, 0, &m));
  memcpy(out_back, m, grid * 8);
  vkUnmapMemory(c.dev, rb_back.m);
  double err = 0, uniq = 0, res = 0;
  int tied = 0, bad = 0, tied_correct = 0, bad_back = 0, tied_back = 0,
      tied_back_correct = 0;
  int outline_tied = 0, backward_outline_tied = 0, parent_probe_bad = 0;
  const size_t probe_index = (3 * gw + 3) * 4;
  if (parent_case) printf("parent_anchor_probe block=%d vector=(%.4f,%.4f) expected=(3,0)\n",
                          child_block, unhalf(out[probe_index]), unhalf(out[probe_index+1]));
  for (size_t i = 0; i < grid; i++) {
    float dx = unhalf(out[i * 4]), dy = unhalf(out[i * 4 + 1]),
          rr = unhalf(out[i * 4 + 2]), uu = unhalf(out[i * 4 + 3]);
    float bdx = unhalf(out_back[i * 4]), bdy = unhalf(out_back[i * 4 + 1]);
    if (getenv("MPVFLOW_TEST_MOTION_DEBUG") && i / gw >= 3 && i / gw <= 7 &&
        (i % gw == 2 || i % gw == 7 || i % gw == 3 || i % gw == 8))
      fprintf(
          stderr,
          "cell=(%zu,%zu) F=(%.3f,%.3f) r=%.4f w=%.4f B=(%.3f,%.3f) w=%.4f\n",
          i % gw, i / gw, dx, dy, rr, uu, bdx, bdy,
          unhalf(out_back[i * 4 + 3]));
    if (parent_case && i % gw == 3 && i / gw == 3 && fabs(dx - 3) > 0.01)
      parent_probe_bad = 1;
    err += fabs(dx - 3) + fabs(dy) + fabs(bdx + 3) + fabs(bdy);
    uniq += uu;
    res += rr;
    if (uu < 0.0005) {
      tied++;
      if (fabs(dx - 3) < 0.01 && fabs(dy) < 0.01) {
        tied_correct++;
        if ((i % gw == 2 || i % gw == 7) && i / gw >= 3 && i / gw <= 7)
          outline_tied++;
      }
    }
    if (fabs(dx - 3) > 0.51 || fabs(dy) > 0.51)
      bad++;
    if (fabs(bdx + 3) > 0.51 || fabs(bdy) > 0.51)
      bad_back++;
    if (unhalf(out_back[i * 4 + 3]) < 0.0005) {
      tied_back++;
      if (fabs(bdx + 3) < 0.01 && fabs(bdy) < 0.01) {
        tied_back_correct++;
        if ((i % gw == 3 || i % gw == 8) && i / gw >= 3 && i / gw <= 7)
          backward_outline_tied++;
      }
    }
  }
  if (bad > (int)(grid / 3) || bad_back > (int)(grid / 3) || tied_correct < 8 ||
      tied_back_correct < 8 ||
      (!parent_case && (outline_tied < 4 || backward_outline_tied < 4)) ||
      parent_probe_bad || !isfinite(err) || !isfinite(res) || !isfinite(uniq)) {
    fprintf(stderr,
            "motion regression failed: forward bad=%d tied=%d/%d "
            "outline_tied=%d parent_probe_bad=%d backward "
            "bad=%d tied=%d/%d outline_tied=%d\n",
            bad, tied, tied_correct, outline_tied, parent_probe_bad, bad_back, tied_back,
            tied_back_correct, backward_outline_tied);
    return 1;
  }
  printf("device=Vulkan shader_flow fixture=anime-outline-pan parent_case=%d child_block=%d parent_grid=%dx%d size=%dx%d "
         "grid=%dx%d expected_forward=(3,0) expected_backward=(-3,0) "
         "mean_abs_error=%.4f mean_residual=%.4f "
         "mean_uniqueness=%.4f tied=%d/%zu bad_matches=%d "
         "backward_bad_matches=%d tied_correct=%d backward_tied_correct=%d "
         "outline_tied=%d backward_outline_tied=%d\n",
         parent_case, child_block, pw, ph, W, H, gw, gh, err / (4 * grid), res / grid, uniq / grid, tied, grid,
         bad, bad_back, tied_correct, tied_back_correct, outline_tied,
         backward_outline_tied);
  destroy_buffer(&c, &rb);
  destroy_buffer(&c, &rb_back);
  free(out_back);
  free(src);
  free(dst);
  free(par);
  free(out);
  free(col0);
  free(col1);
  free(scene);
  for (int i = 0; i < 7; i++)
    destroy_image(&c, &im[i]);
  vkDestroyPipeline(c.dev, c.pipe, NULL);
  vkDestroyPipelineLayout(c.dev, c.pl, NULL);
  vkDestroyDescriptorPool(c.dev, c.dp, NULL);
  vkDestroyDescriptorSetLayout(c.dev, c.dl, NULL);
  vkDestroyCommandPool(c.dev, c.cp, NULL);
  vkDestroyDevice(c.dev, NULL);
  vkDestroyInstance(c.ins, NULL);
  return 0;
}
