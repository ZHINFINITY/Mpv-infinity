#!/usr/bin/env python3
"""Install the direct GPU MPVFlow renderer stage into the pinned Android mpv checkout."""
from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "app/src/main/cpp/mpvflow"
VO_PATCH = VENDOR / "mpvflow-vo-gpu-next.patch"
AIMAGE_VULKAN_PATCH = VENDOR / "mpvflow-aimagereader-vulkan.patch"
MEDIACODEC_VULKAN_DIR = VENDOR / "mediacodec-vulkan"
MEDIACODEC_VULKAN_FILES = (
    "LICENSE.LGPL",
    "hwdec_aimagereader.c",
    "hwdec_aimagereader.comp",
    "hwdec_aimagereader.frag",
    "hwdec_aimagereader.vert",
    "hwdec_aimagereader_comp.h",
    "hwdec_aimagereader_frag.h",
    "hwdec_aimagereader_vert.h",
    "hwdec_aimagereader_vk.c",
    "hwdec_aimagereader_vk.h",
    "hwdec_aimagereader_vk_convert.c",
    "hwdec_aimagereader_vk_private.h",
)
MPV_VULKAN_DEPENDENCY = "vulkan = dependency('vulkan', version: '>= 1.3.238', required: vulkan_opt)"
ANDROID_VULKAN_DEPENDENCY = "vulkan = dependency('vulkan', required: vulkan_opt)"
PLACEBO_SPIRV_LIBRARY_LOOKUP = "spirv = cxx.find_library('SPIRV', required: required, static: static, dirs: vulkan_lib_dirs)"
PLACEBO_GLSLANG_LIBRARY_LOOKUP = "cxx.find_library('glslang', required: required, static: static)"
ANDROID_PLACEBO_GLSLANG_LIBRARY_LOOKUP = "cxx.find_library('glslang', required: required, static: static, dirs: vulkan_lib_dirs)"
NV_OPTICAL_FORMAT_MARKER = "/* MPVFLOW_NV_OPTICAL_FLOW_SFIXED5_FORMAT */"
VULKAN_OPT_EXTENSION_LIST = "    const char *opt_extensions[] = {\n"
ANDROID_AHB_EXTENSIONS = (
    "#if defined(__ANDROID__)\n"
    '        "VK_ANDROID_external_memory_android_hardware_buffer",\n'
    '        "VK_KHR_external_semaphore_fd",\n'
    '        "VK_EXT_queue_family_foreign",\n'
    "#endif\n"
)
PLACEBO_TIMER_RING_OLD = (
    "// Gives us enough queries for 8 results\n#define QUERY_POOL_SIZE 16",
    "atomic_uint_fast8_t pending; // bitmask of queries that are still running",
    "static inline uint_fast8_t timer_bit(int index)",
)
PLACEBO_TIMER_RING_NEW = (
    "// Gives us enough queries for 32 results\n#define QUERY_POOL_SIZE 64",
    "atomic_uint_fast32_t pending; // bitmask of queries that are still running",
    "static inline uint_fast32_t timer_bit(int index)",
)


def base_sources_close(meson: str) -> int:
    start = meson.index("sources = files(")
    depth = 0
    quote = None
    escaped = False
    comment = False
    for pos in range(start, len(meson)):
        char = meson[pos]
        if comment:
            if char == "\n":
                comment = False
            continue
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char == "#":
            comment = True
        elif char in "'\"":
            quote = char
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return pos
    raise SystemExit("Could not find end of mpv base source list")


def enable_android_vulkan_dependency(meson: str) -> str:
    if "vulkan/vulkan_core.h" not in meson or "VK_VERSION_1_3" not in meson:
        raise SystemExit("Pinned MPV Vulkan header-version guard is missing; refusing an unverified Android Vulkan build")
    old_count = meson.count(MPV_VULKAN_DEPENDENCY)
    new_count = meson.count(ANDROID_VULKAN_DEPENDENCY)
    if old_count == 1 and new_count == 0:
        return meson.replace(MPV_VULKAN_DEPENDENCY, ANDROID_VULKAN_DEPENDENCY, 1)
    if old_count == 0 and new_count == 1:
        return meson
    raise SystemExit("Pinned MPV Vulkan dependency check changed; refusing an unverified Android Vulkan build")


def enable_android_glslang_library_search(meson: str) -> str:
    if meson.count(PLACEBO_SPIRV_LIBRARY_LOOKUP) != 1:
        raise SystemExit("Pinned libplacebo SPIR-V Vulkan-SDK lookup changed; refusing an unverified compiler search path")
    old_count = meson.count(PLACEBO_GLSLANG_LIBRARY_LOOKUP)
    new_count = meson.count(ANDROID_PLACEBO_GLSLANG_LIBRARY_LOOKUP)
    if old_count == 1 and new_count == 0:
        return meson.replace(PLACEBO_GLSLANG_LIBRARY_LOOKUP, ANDROID_PLACEBO_GLSLANG_LIBRARY_LOOKUP, 1)
    if old_count == 0 and new_count == 1:
        return meson
    raise SystemExit("Pinned libplacebo glslang lookup changed; refusing a Vulkan build with an unverified SPIR-V compiler path")


def enlarge_libplacebo_timer_ring(gpu_c: str) -> str:
    old_counts = [gpu_c.count(marker) for marker in PLACEBO_TIMER_RING_OLD]
    new_counts = [gpu_c.count(marker) for marker in PLACEBO_TIMER_RING_NEW]
    if old_counts == [0, 0, 0] and new_counts == [1, 1, 1]:
        return gpu_c
    if old_counts != [1, 1, 1] or new_counts != [0, 0, 0]:
        raise SystemExit(
            "Pinned libplacebo Vulkan timer ring changed; refusing an unverified query-pool or pending-mask patch"
        )
    for old, new in zip(PLACEBO_TIMER_RING_OLD, PLACEBO_TIMER_RING_NEW, strict=True):
        gpu_c = gpu_c.replace(old, new, 1)
    return gpu_c


def add_libplacebo_swapchain_diagnostics_api(vulkan_header: str) -> str:
    old = "PL_API bool pl_vulkan_swapchain_suboptimal(pl_swapchain sw);\n"
    set_api = "PL_API bool pl_vulkan_swapchain_set_diagnostics(pl_swapchain sw, bool enabled);\n"
    get_api = (
        "PL_API bool pl_vulkan_swapchain_get_diagnostics(\n"
        "    pl_swapchain sw, int *requested_mode,\n"
        "    int *created_mode, int *requested_depth,\n"
        "    uint32_t *create_min_images, uint32_t *actual_images);\n"
    )
    new = old + set_api + get_api
    set_count = vulkan_header.count(set_api)
    get_count = vulkan_header.count(get_api)
    if set_count == 1 and get_count == 1:
        return vulkan_header
    if get_count or set_count > 1:
        raise SystemExit("Pinned libplacebo swapchain API patch is incomplete")
    if set_count == 1 and vulkan_header.count(old) == 1:
        return vulkan_header.replace(set_api, set_api + get_api, 1)
    if vulkan_header.count(old) == 1:
        return vulkan_header.replace(old, new, 1)
    raise SystemExit("Pinned libplacebo swapchain API changed; refusing an unverified diagnostic declaration")


def instrument_libplacebo_swapchain(swapchain_c: str) -> str:
    marker = "/* MPVFLOW_WSI_DIAGNOSTICS */"
    if marker in swapchain_c:
        required = (
            "atomic_bool flow_diagnostics",
            "VkPresentModeKHR flow_created_present_mode",
            "uint32_t flow_created_image_count",
            "uint32_t flow_surface_min_image_count, flow_surface_max_image_count",
            "flow_created_info_valid, flow_created_info_reported",
            "flow_report_created_swapchain(p);",
            "create_min_images=%u",
            "actual_images=%u",
            "surface_min_images=%u surface_max_images=%u",
            "p->flow_surface_min_image_count = caps.minImageCount;",
            "p->flow_surface_max_image_count = caps.maxImageCount;",
            "event=wsi_swapchain_created",
            "event=wsi_timing stage=%s avg_ms=%.3f max_ms=%.3f ",
            'FLOW_WSI_ACQUIRE, "vkAcquireNextImageKHR"',
            'FLOW_WSI_FENCE_WAIT, "present_fence_wait"',
            'FLOW_WSI_PRESENT, "vkQueuePresentKHR"',
            "pl_vulkan_swapchain_set_diagnostics(pl_swapchain sw, bool enabled)",
            "pl_vulkan_swapchain_get_diagnostics(",
        )
        if all(item in swapchain_c for item in required):
            return swapchain_c
        raise SystemExit("Pinned libplacebo WSI diagnostic patch is incomplete")

    def replace_once(source: str, old: str, new: str, name: str) -> str:
        if source.count(old) != 1:
            raise SystemExit(f"Pinned libplacebo {name} changed; refusing unverified WSI instrumentation")
        return source.replace(old, new, 1)

    swapchain_c = replace_once(
        swapchain_c,
        "    bool has_swapchain_maintenance1;\n    struct pl_color_repr color_repr;",
        "    bool has_swapchain_maintenance1;\n    atomic_bool flow_diagnostics;\n"
        "    VkPresentModeKHR flow_created_present_mode;\n"
        "    uint32_t flow_created_min_image_count;\n"
        "    uint32_t flow_created_image_count;\n"
        "    uint32_t flow_surface_min_image_count, flow_surface_max_image_count;\n"
        "    bool flow_created_info_valid, flow_created_info_reported;\n"
        "    pl_clock_t flow_diag_last_report[3];\n"
        "    uint64_t flow_diag_total_ns[3], flow_diag_max_ns[3], flow_diag_samples[3];\n"
        "    VkResult flow_diag_last_result[3];\n    struct pl_color_repr color_repr;",
        "swapchain diagnostic state",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    pl_mutex_init(&p->lock);\n    p->impl = vulkan_swapchain;",
        "    pl_mutex_init(&p->lock);\n    atomic_init(&p->flow_diagnostics, false);\n"
        "    p->flow_created_info_valid = false;\n"
        "    p->flow_created_info_reported = false;\n"
        "    p->impl = vulkan_swapchain;",
        "swapchain diagnostic initialization",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "caps_ready:\n    // Check for hidden/invisible window",
        "caps_ready:\n"
        "    p->flow_surface_min_image_count = caps.minImageCount;\n"
        "    p->flow_surface_max_image_count = caps.maxImageCount;\n"
        "    // Check for hidden/invisible window",
        "surface image-count capability capture",
    )
    helper = '''/* MPVFLOW_WSI_DIAGNOSTICS */
enum { FLOW_WSI_ACQUIRE, FLOW_WSI_FENCE_WAIT, FLOW_WSI_PRESENT };
static const char *flow_present_mode_name(VkPresentModeKHR mode)
{
    switch (mode) {
    case VK_PRESENT_MODE_IMMEDIATE_KHR: return "immediate";
    case VK_PRESENT_MODE_MAILBOX_KHR: return "mailbox";
    case VK_PRESENT_MODE_FIFO_KHR: return "fifo";
    case VK_PRESENT_MODE_FIFO_RELAXED_KHR: return "fifo_relaxed";
    default: return "other";
    }
}
static void flow_report_wsi_call(struct priv *p, unsigned int stage,
                                 const char *name, pl_clock_t start,
                                 pl_clock_t stop,
                                 VkResult result)
{
    if (!atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed))
        return;
    uint64_t elapsed_ns = pl_clock_diff(stop, start) * 1e9;
    p->flow_diag_total_ns[stage] += elapsed_ns;
    if (elapsed_ns > p->flow_diag_max_ns[stage])
        p->flow_diag_max_ns[stage] = elapsed_ns;
    p->flow_diag_samples[stage]++;
    p->flow_diag_last_result[stage] = result;
    if (!p->flow_diag_last_report[stage])
        p->flow_diag_last_report[stage] = stop;
    double window_s = pl_clock_diff(stop, p->flow_diag_last_report[stage]);
    if (window_s < 5.0)
        return;
    p->flow_diag_last_report[stage] = stop;
    double avg_ms = p->flow_diag_samples[stage]
        ? (double) p->flow_diag_total_ns[stage] / p->flow_diag_samples[stage] / 1e6 : 0.0;
    PL_INFO(p->vk, "MPVFLOW_DIAGNOSTIC event=wsi_timing stage=%s avg_ms=%.3f max_ms=%.3f "
            "samples=%llu last_result=%d window_s=%.2f metric=host_call_duration_not_scanout",
            name, avg_ms, p->flow_diag_max_ns[stage] / 1e6,
            (unsigned long long) p->flow_diag_samples[stage],
            p->flow_diag_last_result[stage], window_s);
    p->flow_diag_total_ns[stage] = 0;
    p->flow_diag_max_ns[stage] = 0;
    p->flow_diag_samples[stage] = 0;
}
static void flow_report_created_swapchain(struct priv *p)
{
    if (!atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed) ||
        !p->flow_created_info_valid || p->flow_created_info_reported)
        return;
    PL_INFO(p->vk, "MPVFLOW_DIAGNOSTIC event=wsi_swapchain_created requested_mode=%s requested_vk=%d "
            "created_mode=%s created_vk=%d fallback=%d requested_depth=%d create_min_images=%u "
            "actual_images=%u surface_min_images=%u surface_max_images=%u "
            "source=successful_vkCreateSwapchainKHR_create_info metric=created_config_not_scanout",
            flow_present_mode_name(p->params.present_mode), p->params.present_mode,
            flow_present_mode_name(p->flow_created_present_mode), p->flow_created_present_mode,
            p->params.present_mode != p->flow_created_present_mode,
            p->swapchain_depth, p->flow_created_min_image_count,
            p->flow_created_image_count, p->flow_surface_min_image_count,
            p->flow_surface_max_image_count);
    p->flow_created_info_reported = true;
}
'''
    swapchain_c = replace_once(
        swapchain_c,
        "static const struct pl_sw_fns vulkan_swapchain;\n\n\nstatic bool map_color_space(",
        "static const struct pl_sw_fns vulkan_swapchain;\n" + helper + "static bool map_color_space(",
        "diagnostic helpers",
    )
    swapchain_c = replace_once(
        swapchain_c,
        '    PL_VK_ASSERT(res, "vk->CreateSwapchainKHR(...)");\n\n    // Get the new swapchain images',
        '    PL_VK_ASSERT(res, "vk->CreateSwapchainKHR(...)");\n\n'
        '    p->flow_created_present_mode = sinfo.presentMode;\n'
        '    p->flow_created_min_image_count = sinfo.minImageCount;\n'
        '    p->flow_created_image_count = 0;\n'
        '    p->flow_created_info_valid = true;\n'
        '    p->flow_created_info_reported = false;\n'
        '    // Get the new swapchain images',
        "created WSI present-mode report",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    VK(vk->GetSwapchainImagesKHR(vk->dev, current->swapchain, &num_images, current->vkimages.elem));\n",
        "    VK(vk->GetSwapchainImagesKHR(vk->dev, current->swapchain, &num_images, current->vkimages.elem));\n"
        "    p->flow_created_image_count = num_images;\n"
        "    flow_report_created_swapchain(p);\n",
        "created WSI image count report",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "        VkResult res = vk->AcquireNextImageKHR(vk->dev, p->current->swapchain, UINT64_MAX,\n"
        "                                               sem_in, VK_NULL_HANDLE, &imgidx);",
        "        pl_clock_t flow_acquire_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "            ? pl_clock_now() : 0;\n"
        "        VkResult res = vk->AcquireNextImageKHR(vk->dev, p->current->swapchain, UINT64_MAX,\n"
        "                                               sem_in, VK_NULL_HANDLE, &imgidx);\n"
        "        if (flow_acquire_start)\n"
        "            flow_report_wsi_call(p, FLOW_WSI_ACQUIRE, \"vkAcquireNextImageKHR\", flow_acquire_start, pl_clock_now(), res);",
        "vkAcquireNextImageKHR timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "                vk->WaitForFences(vk->dev, 1, pfence, VK_TRUE, UINT64_MAX);\n"
        "                vk->ResetFences(vk->dev, 1, pfence);",
        "                pl_clock_t flow_fence_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "                    ? pl_clock_now() : 0;\n"
        "                VkResult flow_fence_result = vk->WaitForFences(vk->dev, 1, pfence, VK_TRUE, UINT64_MAX);\n"
        "                if (flow_fence_start)\n"
        "                    flow_report_wsi_call(p, FLOW_WSI_FENCE_WAIT, \"present_fence_wait\", flow_fence_start, pl_clock_now(), flow_fence_result);\n"
        "                vk->ResetFences(vk->dev, 1, pfence);",
        "present-fence wait timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    VkResult res = vk->QueuePresentKHR(queue, &pinfo);\n    vk->unlock_queue(vk->queue_ctx, pool->qf, qidx);",
        "    pl_clock_t flow_present_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "        ? pl_clock_now() : 0;\n"
        "    VkResult res = vk->QueuePresentKHR(queue, &pinfo);\n"
        "    pl_clock_t flow_present_end = flow_present_start ? pl_clock_now() : 0;\n"
        "    vk->unlock_queue(vk->queue_ctx, pool->qf, qidx);\n"
        "    if (flow_present_start)\n"
        "        flow_report_wsi_call(p, FLOW_WSI_PRESENT, \"vkQueuePresentKHR\", flow_present_start, flow_present_end, res);",
        "vkQueuePresentKHR timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "bool pl_vulkan_swapchain_suboptimal(pl_swapchain sw)\n{\n"
        "    struct priv *p = PL_PRIV(sw);\n    return p->suboptimal;\n}\n",
        "bool pl_vulkan_swapchain_suboptimal(pl_swapchain sw)\n{\n"
        "    struct priv *p = PL_PRIV(sw);\n    return p->suboptimal;\n}\n"
        "bool pl_vulkan_swapchain_set_diagnostics(pl_swapchain sw, bool enabled)\n{\n"
        "    if (!sw)\n        return false;\n"
        "    struct priv *p = PL_PRIV(sw);\n"
        "    atomic_store_explicit(&p->flow_diagnostics, enabled, memory_order_relaxed);\n"
        "    if (enabled)\n        flow_report_created_swapchain(p);\n"
        "    return true;\n}\n"
        "bool pl_vulkan_swapchain_get_diagnostics(\n"
        "    pl_swapchain sw, int *requested_mode,\n"
        "    int *created_mode, int *requested_depth,\n"
        "    uint32_t *create_min_images, uint32_t *actual_images)\n"
        "{\n"
        "    if (!sw)\n        return false;\n"
        "    struct priv *p = PL_PRIV(sw);\n"
        "    if (!p->flow_created_info_valid || !p->flow_created_image_count)\n"
        "        return false;\n"
        "    if (requested_mode) *requested_mode = (int) p->params.present_mode;\n"
        "    if (created_mode) *created_mode = (int) p->flow_created_present_mode;\n"
        "    if (requested_depth) *requested_depth = p->swapchain_depth;\n"
        "    if (create_min_images) *create_min_images = p->flow_created_min_image_count;\n"
        "    if (actual_images) *actual_images = p->flow_created_image_count;\n"
        "    return true;\n}\n",
        "diagnostic toggle API",
    )
    return swapchain_c


def add_libplacebo_swapchain_diagnostics_stub(stubs_c: str) -> str:
    old = "bool pl_vulkan_swapchain_suboptimal(pl_swapchain sw)\n{\n    pl_unreachable();\n}\n"
    set_stub_definition = "bool pl_vulkan_swapchain_set_diagnostics(pl_swapchain sw, bool enabled)\n{\n    pl_unreachable();\n}\n"
    get_stub_definition = (
        "bool pl_vulkan_swapchain_get_diagnostics(\n"
        + "    pl_swapchain sw, int *requested_mode,\n"
        + "    int *created_mode, int *requested_depth,\n"
        + "    uint32_t *create_min_images, uint32_t *actual_images)\n{\n    pl_unreachable();\n}\n"
    )
    new = old + set_stub_definition + get_stub_definition
    set_marker = "bool pl_vulkan_swapchain_set_diagnostics(pl_swapchain sw, bool enabled)"
    get_marker = "bool pl_vulkan_swapchain_get_diagnostics("
    set_count = stubs_c.count(set_marker)
    get_count = stubs_c.count(get_marker)
    if set_count == 1 and get_count == 1:
        return stubs_c
    if get_count or set_count > 1:
        raise SystemExit("Pinned libplacebo no-Vulkan diagnostic stubs are incomplete")
    if set_count == 1 and stubs_c.count(old) == 1:
        if stubs_c.count(set_stub_definition) != 1:
            raise SystemExit("Pinned libplacebo legacy no-Vulkan stub changed; refusing an unverified getter")
        return stubs_c.replace(set_stub_definition, set_stub_definition + get_stub_definition, 1)
    if stubs_c.count(old) == 1 and new not in stubs_c:
        return stubs_c.replace(old, new, 1)
    raise SystemExit("Pinned libplacebo no-Vulkan stubs changed; refusing an unverified diagnostic stub")


def enable_nv_optical_flow_vector_format(formats_c: str) -> str:
    needle = '    {VK_FORMAT_R16G16_SINT,           REGFMT("rg16i",    2, 16, SINT)},\n'
    entry = (
        "#ifdef VK_NV_optical_flow\n"
        '    {VK_FORMAT_R16G16_S10_5_NV, REGFMT("rg16sfix5", 2, 16, SINT)},\n'
        "#endif\n"
        f"{NV_OPTICAL_FORMAT_MARKER}\n"
    )
    if NV_OPTICAL_FORMAT_MARKER in formats_c:
        required = (
            "#ifdef VK_NV_optical_flow",
            'VK_FORMAT_R16G16_S10_5_NV, REGFMT("rg16sfix5", 2, 16, SINT)',
            NV_OPTICAL_FORMAT_MARKER,
        )
        if all(item in formats_c for item in required):
            return formats_c
        raise SystemExit("Pinned libplacebo NV optical-flow format patch is incomplete")
    if formats_c.count(needle) != 1:
        raise SystemExit("Pinned libplacebo signed-integer format table changed; refusing an unverified SFIXED5 mapping")
    return formats_c.replace(needle, needle + entry, 1)


def enable_nv_optical_flow_sampleability(formats_c: str) -> str:
    marker = "/* MPVFLOW_NV_OPTICAL_FLOW_SFIXED5_SAMPLEABILITY */"
    required = (
        "vk->GetPhysicalDeviceImageFormatProperties2",
        'strcmp(vk->exts.elem[i], "VK_NV_optical_flow")',
        "VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV",
        "VK_IMAGE_USAGE_SAMPLED_BIT",
        "fmt->caps |= PL_FMT_CAP_SAMPLEABLE",
        marker,
    )
    if marker in formats_c:
        if all(item in formats_c for item in required):
            return formats_c
        raise SystemExit("Pinned libplacebo NV sampleability patch is incomplete")

    anchor = (
        "        if (fmt->caps) {\n"
        '            fmt->glsl_type = pl_var_glsl_type_name(pl_var_from_fmt(fmt, ""));\n'
        "            pl_assert(fmt->glsl_type);\n"
        "        }\n"
    )
    if formats_c.count(anchor) != 1:
        raise SystemExit("Pinned libplacebo format capability setup changed; refusing an unverified NV sampleability patch")

    query = '''#ifdef VK_NV_optical_flow
        /* The NV vector format may not expose SAMPLED_IMAGE through the
         * generic format-feature query. Only advertise sampling when the
         * extension-aware image-usage query accepts this exact output image. */
        if (vk_fmt->tfmt == VK_FORMAT_R16G16_S10_5_NV &&
            vk->GetPhysicalDeviceImageFormatProperties2) {
            bool optical_flow_enabled = false;
            for (int i = 0; i < vk->exts.num; i++) {
                if (strcmp(vk->exts.elem[i], "VK_NV_optical_flow") == 0) {
                    optical_flow_enabled = true;
                    break;
                }
            }
            if (optical_flow_enabled) {
                VkOpticalFlowImageFormatInfoNV flow_info = {
                    .sType = VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_INFO_NV,
                    .usage = VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV,
                };
                VkPhysicalDeviceImageFormatInfo2 flow_image_info = {
                    .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2,
                    .pNext = &flow_info,
                    .format = VK_FORMAT_R16G16_S10_5_NV,
                    .type = VK_IMAGE_TYPE_2D,
                    .tiling = VK_IMAGE_TILING_OPTIMAL,
                    .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
                };
                VkImageFormatProperties2 flow_image_properties = {
                    .sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2,
                };
                if (vk->GetPhysicalDeviceImageFormatProperties2(
                        vk->physd, &flow_image_info, &flow_image_properties) == VK_SUCCESS) {
                    fmt->caps |= PL_FMT_CAP_SAMPLEABLE;
                }
            }
        }
#endif
        /* MPVFLOW_NV_OPTICAL_FLOW_SFIXED5_SAMPLEABILITY */

'''
    return formats_c.replace(anchor, query + anchor, 1)


def enable_android_external_memory_extensions(vulkan_context: str) -> str:
    old_count = vulkan_context.count(VULKAN_OPT_EXTENSION_LIST)
    ext_count = vulkan_context.count(ANDROID_AHB_EXTENSIONS)
    if old_count == 1 and ext_count == 0:
        return vulkan_context.replace(
            VULKAN_OPT_EXTENSION_LIST,
            VULKAN_OPT_EXTENSION_LIST + ANDROID_AHB_EXTENSIONS,
            1,
        )
    if old_count == 1 and ext_count == 1:
        return vulkan_context
    raise SystemExit(
        "Pinned MPV Vulkan optional-extension list changed; refusing an unverified Android AHardwareBuffer bridge"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", required=True, type=Path)
    parser.add_argument("--libplacebo-dir", required=True, type=Path)
    args = parser.parse_args()
    mpv_dir = args.mpv_dir.resolve()
    libplacebo_dir = args.libplacebo_dir.resolve()
    meson_path = mpv_dir / "meson.build"
    vo_path = mpv_dir / "video/out/vo_gpu_next.c"
    vulkan_context_path = mpv_dir / "video/out/vulkan/context.c"
    glslang_meson_path = libplacebo_dir / "src/glsl/meson.build"
    gpu_timer_path = libplacebo_dir / "src/vulkan/gpu.c"
    placebo_swapchain_path = libplacebo_dir / "src/vulkan/swapchain.c"
    placebo_vulkan_header_path = libplacebo_dir / "src/include/libplacebo/vulkan.h"
    placebo_stubs_path = libplacebo_dir / "src/vulkan/stubs.c"
    placebo_formats_path = libplacebo_dir / "src/vulkan/formats.c"
    for path in (
        meson_path, vo_path, vulkan_context_path, glslang_meson_path, gpu_timer_path,
        placebo_swapchain_path, placebo_vulkan_header_path, placebo_stubs_path,
        placebo_formats_path,
    ):
        if not path.is_file():
            raise SystemExit(f"Pinned native source is missing required file {path}")

    required = (
        VENDOR / "mpvflow_gpu.c",
        VENDOR / "mpvflow_gpu.h",
        VENDOR / "mpvflow_opencl.c",
        VENDOR / "mpvflow_opencl.h",
        VENDOR / "mpvflow_opencl_vk_bridge.c",
        VENDOR / "mpvflow_opencl_vk_bridge.h",
        VENDOR / "mpvflow_vulkan_optical.c",
        VENDOR / "mpvflow_vulkan_optical.h",
        VO_PATCH,
        AIMAGE_VULKAN_PATCH,
        VENDOR / "mpvflow_opencl_validation.h",
        *(MEDIACODEC_VULKAN_DIR / filename for filename in MEDIACODEC_VULKAN_FILES),
    )
    for path in required:
        if not path.is_file():
            raise SystemExit(f"GPU Flow source is missing: {path}")

    video_out_dir = mpv_dir / "video/out"
    shutil.copy2(VENDOR / "mpvflow_gpu.c", video_out_dir / "mpvflow_gpu.c")
    shutil.copy2(VENDOR / "mpvflow_gpu.h", video_out_dir / "mpvflow_gpu.h")
    for filename in (
        "mpvflow_opencl.c",
        "mpvflow_opencl.h",
        "mpvflow_opencl_validation.h",
        "mpvflow_opencl_vk_bridge.c",
        "mpvflow_opencl_vk_bridge.h",
        "mpvflow_vulkan_optical.c",
        "mpvflow_vulkan_optical.h",
    ):
        shutil.copy2(VENDOR / filename, video_out_dir / filename)
    hwdec_dir = video_out_dir / "hwdec"
    for filename in MEDIACODEC_VULKAN_FILES:
        if filename != "LICENSE.LGPL":
            shutil.copy2(MEDIACODEC_VULKAN_DIR / filename, hwdec_dir / filename)

    vo = vo_path.read_text()
    meson = meson_path.read_text()
    vulkan_context = enable_android_external_memory_extensions(
        vulkan_context_path.read_text()
    )
    meson = enable_android_vulkan_dependency(meson)
    glslang_meson = enable_android_glslang_library_search(glslang_meson_path.read_text())
    gpu_timer = enlarge_libplacebo_timer_ring(gpu_timer_path.read_text())
    placebo_swapchain = instrument_libplacebo_swapchain(placebo_swapchain_path.read_text())
    placebo_vulkan_header = add_libplacebo_swapchain_diagnostics_api(placebo_vulkan_header_path.read_text())
    placebo_stubs = add_libplacebo_swapchain_diagnostics_stub(placebo_stubs_path.read_text())
    placebo_formats = enable_nv_optical_flow_sampleability(
        enable_nv_optical_flow_vector_format(placebo_formats_path.read_text())
    )
    vo_marker = "MPVFLOW_GPU_INTEGRATION"
    meson_marker = "# MPVFLOW_ANDROID_GPU"
    if vo_marker not in vo:
        subprocess.run(
            ["patch", "-p1", "--forward", "--input", str(VO_PATCH)],
            cwd=mpv_dir,
            check=True,
        )
        vo = vo_path.read_text()
    if vo_marker not in vo:
        raise SystemExit("GPU Flow patch did not mark vo_gpu_next.c")

    hwdec_header_path = mpv_dir / "video/out/gpu/hwdec.h"
    mapper_marker = "#define RA_HWDEC_MAP_RETRY (-2)"
    if mapper_marker not in hwdec_header_path.read_text():
        subprocess.run(
            ["patch", "-p1", "--forward", "--input", str(AIMAGE_VULKAN_PATCH)],
            cwd=mpv_dir,
            check=True,
        )
        vo = vo_path.read_text()
    meson = enable_android_vulkan_dependency(meson_path.read_text())
    if mapper_marker not in hwdec_header_path.read_text() or "# MPVFLOW_AIMAGE_VULKAN_MAPPER" not in meson:
        raise SystemExit("Android MediaCodec/Vulkan mapper patch was not fully applied")

    gpu_source = "video/out/mpvflow_gpu.c"
    opencl_sources = (
        "video/out/mpvflow_opencl.c",
        "video/out/mpvflow_opencl_vk_bridge.c",
        "video/out/mpvflow_vulkan_optical.c",
    )
    if meson_marker not in meson:
        close = base_sources_close(meson)
        block = (
            "\n# The direct MPVFlow compute stage is built only for Android.\n"
            "if host_machine.system() == 'android'\n"
            f"    sources += files('{gpu_source}', '{opencl_sources[0]}', '{opencl_sources[1]}', '{opencl_sources[2]}')\n"
            "endif\n"
            f"{meson_marker}\n"
        )
        meson = meson[: close + 1] + block + meson[close + 1 :]
        meson_path.write_text(meson)
    elif any(source not in meson for source in (gpu_source, *opencl_sources)):
        raise SystemExit("GPU Flow Meson marker exists without every required Flow/OpenCL/optical source")

    vulkan_context_path.write_text(vulkan_context)
    glslang_meson_path.write_text(glslang_meson)
    gpu_timer_path.write_text(gpu_timer)
    placebo_swapchain_path.write_text(placebo_swapchain)
    placebo_vulkan_header_path.write_text(placebo_vulkan_header)
    placebo_stubs_path.write_text(placebo_stubs)
    placebo_formats_path.write_text(placebo_formats)
    print(
        f"Installed direct GPU/OpenCL MPVFlow into {mpv_dir} and corrected libplacebo's "
        f"Android glslang search path in {glslang_meson_path} and Vulkan timestamp "
        f"ring in {gpu_timer_path}, plus Flow-gated Vulkan WSI diagnostics in {placebo_swapchain_path}; "
        f"the optional NV optical-flow vector format is registered with an extension-aware sampleability check; "
        f"the legacy CPU filter is not compiled or registered by this build."
    )


if __name__ == "__main__":
    main()
