#!/usr/bin/env python3
"""Validate and adapt the pinned native FongMi MPV runtime source."""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "app/src/main/cpp/mpvflow"
MPV_VULKAN_DEPENDENCY = "vulkan = dependency('vulkan', version: '>= 1.3.238', required: vulkan_opt)"
ANDROID_VULKAN_DEPENDENCY = "vulkan = dependency('vulkan', required: vulkan_opt)"
PLACEBO_SPIRV_LIBRARY_LOOKUP = "spirv = cxx.find_library('SPIRV', required: required, static: static, dirs: vulkan_lib_dirs)"
PLACEBO_GLSLANG_LIBRARY_LOOKUP = "cxx.find_library('glslang', required: required, static: static)"
ANDROID_PLACEBO_GLSLANG_LIBRARY_LOOKUP = "cxx.find_library('glslang', required: required, static: static, dirs: vulkan_lib_dirs)"
MPV_CLONE_FEATURE = "features += {'clone': cc.has_header_symbol('sched.h', 'clone', args: '-D_GNU_SOURCE')}"
MPV_ANDROID_CLONE_FEATURE = "features += {'clone': host_machine.system() != 'android' and cc.has_header_symbol('sched.h', 'clone', args: '-D_GNU_SOURCE')}"
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


def validate_fongmi_frame_priv_members(vo_source: str) -> None:
    start = vo_source.find("struct frame_priv {")
    if start < 0:
        raise SystemExit("Pinned MPV vo_gpu_next.c has no struct frame_priv")
    end = vo_source.find("\n};", start)
    if end < 0:
        raise SystemExit("Pinned MPV struct frame_priv is not closed")
    body = vo_source[start:end]
    required = (
        "struct source_frame *source;",
        "bool motion_capture;",
        "pl_tex motion_texture;",
        "struct mp_motion_frame motion_frame;",
        "bool mapped_coordinates;",
        "struct mp_image_params mapped_params;",
    )
    missing = [member for member in required if member not in body]
    if missing:
        raise SystemExit(
            "Pinned FongMi frame ownership/MEMC fields must be members of struct frame_priv; "
            "missing: " + ", ".join(missing)
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


def configure_debug_capture_source(source: str, enabled: bool) -> str:
    """Toggle the capture define after the source's SPDX notice."""
    marker = "\n#define MPVFLOW_DEBUG_CAPTURE 1\n"
    source = source.replace(marker, "")
    if not enabled:
        return source
    notice_end = source.find("*/")
    if not source.startswith("/*") or notice_end < 0:
        raise SystemExit("MPVFlow GPU source is missing its leading SPDX notice")
    return source[:notice_end + 2] + marker + source[notice_end + 2:]


def enable_android_vulkan_dependency(meson: str) -> str:
    if "vulkan/vulkan_core.h" not in meson or "VK_VERSION_1_3" not in meson:
        raise SystemExit("Pinned MPV Vulkan header-version guard is missing; refusing an unverified Android Vulkan build")
    lines = meson.splitlines(keepends=True)
    dependency_lines = [
        index for index, line in enumerate(lines)
        if line.strip().startswith("vulkan = dependency('vulkan',")
    ]
    if len(dependency_lines) == 1:
        index = dependency_lines[0]
        line = lines[index]
        if line.strip() == ANDROID_VULKAN_DEPENDENCY:
            return meson
        version_start = line.find(", version: '")
        required_start = line.find(", required: vulkan_opt)", version_start + 1)
        if version_start >= 0 and required_start > version_start:
            version = line[version_start + len(", version: '"):line.find("'", version_start + len(", version: '"))]
            if version.startswith(">= "):
                lines[index] = line[:version_start] + line[required_start:]
                return "".join(lines)
    raise SystemExit("Pinned MPV Vulkan dependency check changed; refusing an unverified Android Vulkan build")


def prefer_fork_for_android_mpv_subprocesses(meson: str) -> str:
    """Use MPV's existing fork/exec path instead of raw CLONE_VM on Android."""
    old_count = meson.count(MPV_CLONE_FEATURE)
    new_count = meson.count(MPV_ANDROID_CLONE_FEATURE)
    if old_count == 1 and new_count == 0:
        return meson.replace(MPV_CLONE_FEATURE, MPV_ANDROID_CLONE_FEATURE, 1)
    if old_count == 0 and new_count == 1:
        return meson
    raise SystemExit(
        "Pinned MPV clone feature detection changed; refusing an unverified Android subprocess configuration"
    )


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


def validate_libplacebo_suboptimal_recovery(swapchain_c: str) -> None:
    if "/* MPVFLOW_WSI_DIAGNOSTICS */" in swapchain_c:
        acquire_out_of_date = (
            "case VK_ERROR_OUT_OF_DATE_KHR: {\n"
            "            // In these cases try recreating the swapchain",
            "bool flow_retry_recreate_ok = vk_sw_recreate(sw, 0, 0);",
            "if (!flow_retry_recreate_ok) {\n"
            "                pl_mutex_unlock(&p->lock);\n"
            "                return false;\n"
            "            }\n"
            "            continue;",
        )
    else:
        acquire_out_of_date = (
            "case VK_ERROR_OUT_OF_DATE_KHR: {\n"
            "            // In these cases try recreating the swapchain\n"
            "            if (!vk_sw_recreate(sw, 0, 0)) {",
        )
    required = (
        "if (p->suboptimal && !p->params.allow_suboptimal)\n        recreate = true;",
        "case VK_SUBOPTIMAL_KHR:\n        p->suboptimal = true;\n        // fall through\n    case VK_SUCCESS:\n        return true;",
        *acquire_out_of_date,
        "case VK_ERROR_OUT_OF_DATE_KHR:\n        // We can silently ignore this error, since the next start_frame will\n        // recreate the swapchain automatically.\n        return true;",
    )
    resize_recovery = (
        "if (p->suboptimal || p->needs_recreate || width_changed || height_changed)\n"
        "        ok = vk_sw_recreate(sw, *width, *height);" in swapchain_c
        or (
            "bool recreate = p->suboptimal || p->needs_recreate || width_changed || height_changed;" in swapchain_c
            and "if (ok && recreate)\n        ok = vk_sw_recreate(sw, *width, *height);" in swapchain_c
        )
    )
    if not all(item in swapchain_c for item in required) or not resize_recovery:
        raise SystemExit(
            "Pinned libplacebo suboptimal/out-of-date/resize recovery changed; refusing an unverified Mailbox tolerance policy"
        )


def instrument_libplacebo_swapchain(swapchain_c: str) -> str:
    marker = "/* MPVFLOW_WSI_DIAGNOSTICS */"
    if marker in swapchain_c:
        required = (
            "atomic_bool flow_diagnostics",
            "VkPresentModeKHR flow_created_present_mode",
            "VkFormat flow_created_format",
            "uint32_t flow_created_image_count",
            "uint32_t flow_surface_min_image_count, flow_surface_max_image_count",
            "flow_created_info_valid, flow_created_info_reported",
            "flow_report_created_swapchain(p);",
            "create_min_images=%u",
            "actual_images=%u",
            "surface_min_images=%u surface_max_images=%u",
            "format_vk=%d color_space_vk=%d extent=%ux%u usage=0x%x",
            "present_feedback=unsupported_by_pinned_libplacebo",
            "p->flow_surface_min_image_count = caps.minImageCount;",
            "p->flow_surface_max_image_count = caps.maxImageCount;",
            "event=wsi_swapchain_created",
            "event=wsi_timing stage=%s avg_ms=%.3f max_ms=%.3f",
            "last_result_name=%s window_s=%.2f",
            "last_timeout_ns=%llu last_image_index=%u",
            "FLOW_WSI_MUTEX_LOCK, \"swapchain_mutex_lock\"",
            "FLOW_WSI_RECREATE, \"swapchain_recreate\"",
            "FLOW_WSI_CREATE_SWAPCHAIN, \"vkCreateSwapchainKHR\"",
            "FLOW_WSI_GET_IMAGE_COUNT, \"vkGetSwapchainImagesKHR_count\"",
            "FLOW_WSI_GET_IMAGES, \"vkGetSwapchainImagesKHR\"",
            'FLOW_WSI_ACQUIRE, "vkAcquireNextImageKHR"',
            'FLOW_WSI_FENCE_WAIT, "present_fence_wait"',
            'FLOW_WSI_FENCE_RESET, "vkResetFences"',
            'FLOW_WSI_IMAGE_INIT, "vk_sw_image_init"',
            'FLOW_WSI_COMMAND_SUBMIT, "vk_cmd_submit_path"',
            'FLOW_WSI_PRESENT_QUEUE_LOCK, "present_queue_lock"',
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

    state_anchors = (
        "    bool has_swapchain_maintenance1;\n    struct pl_color_repr color_repr;",
        "    bool has_swapchain_maintenance1;\n    uint64_t generation;\n"
        "    struct pl_color_repr color_repr;",
    )
    state_anchor = next((anchor for anchor in state_anchors if swapchain_c.count(anchor) == 1), None)
    if state_anchor is None:
        raise SystemExit("Pinned libplacebo swapchain diagnostic state changed; refusing unverified WSI instrumentation")
    state_fields = (
        "    atomic_bool flow_diagnostics;\n"
        "    VkPresentModeKHR flow_created_present_mode;\n"
        "    VkFormat flow_created_format;\n"
        "    VkColorSpaceKHR flow_created_color_space;\n"
        "    VkExtent2D flow_created_extent;\n"
        "    VkImageUsageFlags flow_created_usage;\n"
        "    uint32_t flow_created_min_image_count;\n"
        "    uint32_t flow_created_image_count;\n"
        "    uint32_t flow_surface_min_image_count, flow_surface_max_image_count;\n"
        "    bool flow_created_info_valid, flow_created_info_reported;\n"
        "    pl_clock_t flow_diag_last_report[12];\n"
        "    uint64_t flow_diag_total_ns[12], flow_diag_max_ns[12], flow_diag_samples[12];\n"
        "    VkResult flow_diag_last_result[12];\n"
        "    uint64_t flow_diag_last_timeout_ns[12];\n"
        "    uint32_t flow_diag_last_image_index[12], flow_diag_image_index_valid[12];\n"
    )
    state_replacement = state_anchor.replace(
        "    struct pl_color_repr color_repr;",
        state_fields + "    struct pl_color_repr color_repr;",
        1,
    )
    swapchain_c = replace_once(
        swapchain_c, state_anchor, state_replacement, "swapchain diagnostic state"
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
enum {
    FLOW_WSI_MUTEX_LOCK, FLOW_WSI_RECREATE, FLOW_WSI_CREATE_SWAPCHAIN,
    FLOW_WSI_GET_IMAGE_COUNT, FLOW_WSI_GET_IMAGES, FLOW_WSI_ACQUIRE,
    FLOW_WSI_FENCE_WAIT, FLOW_WSI_FENCE_RESET, FLOW_WSI_IMAGE_INIT,
    FLOW_WSI_COMMAND_SUBMIT, FLOW_WSI_PRESENT_QUEUE_LOCK, FLOW_WSI_PRESENT,
    FLOW_WSI_STAGE_COUNT,
};
static const char *flow_vk_result_name(VkResult result)
{
    switch (result) {
    case VK_SUCCESS: return "VK_SUCCESS";
    case VK_SUBOPTIMAL_KHR: return "VK_SUBOPTIMAL_KHR";
    case VK_NOT_READY: return "VK_NOT_READY";
    case VK_TIMEOUT: return "VK_TIMEOUT";
    case VK_ERROR_OUT_OF_DATE_KHR: return "VK_ERROR_OUT_OF_DATE_KHR";
    case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
    default: return "VK_ERROR_OTHER";
    }
}
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
    bool first_sample = !p->flow_diag_last_report[stage];
    if (first_sample)
        p->flow_diag_last_report[stage] = stop;
    double window_s = pl_clock_diff(stop, p->flow_diag_last_report[stage]);
    // vk_sw_image_init runs per acquired image; keep measuring it, but aggregate its reports.
    bool low_frequency_stage = stage == FLOW_WSI_RECREATE ||
        stage == FLOW_WSI_CREATE_SWAPCHAIN || stage == FLOW_WSI_GET_IMAGE_COUNT ||
        stage == FLOW_WSI_GET_IMAGES;
    if (window_s < 5.0 && !first_sample && !low_frequency_stage)
        return;
    p->flow_diag_last_report[stage] = stop;
    double avg_ms = p->flow_diag_samples[stage]
        ? (double) p->flow_diag_total_ns[stage] / p->flow_diag_samples[stage] / 1e6 : 0.0;
    PL_INFO(p->vk, "MPVFLOW_DIAGNOSTIC event=wsi_timing stage=%s avg_ms=%.3f max_ms=%.3f "
            "samples=%llu last_result=%d last_result_name=%s window_s=%.2f "
            "last_timeout_ns=%llu last_image_index=%u image_index_valid=%u "
            "metric=host_call_duration_not_scanout",
            name, avg_ms, p->flow_diag_max_ns[stage] / 1e6,
            (unsigned long long) p->flow_diag_samples[stage],
            p->flow_diag_last_result[stage], flow_vk_result_name(p->flow_diag_last_result[stage]), window_s,
            (unsigned long long) p->flow_diag_last_timeout_ns[stage],
            p->flow_diag_last_image_index[stage], p->flow_diag_image_index_valid[stage]);
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
            "format_vk=%d color_space_vk=%d extent=%ux%u usage=0x%x "
            "present_fence_completion=%d present_feedback=unsupported_by_pinned_libplacebo "
            "refresh_source=AndroidDisplayManager_MPV_config_event "
            "source=successful_vkCreateSwapchainKHR_create_info metric=created_config_not_scanout",
            flow_present_mode_name(p->params.present_mode), p->params.present_mode,
            flow_present_mode_name(p->flow_created_present_mode), p->flow_created_present_mode,
            p->params.present_mode != p->flow_created_present_mode,
            p->swapchain_depth, p->flow_created_min_image_count,
            p->flow_created_image_count, p->flow_surface_min_image_count,
            p->flow_surface_max_image_count, p->flow_created_format,
            p->flow_created_color_space, p->flow_created_extent.width,
            p->flow_created_extent.height, p->flow_created_usage,
            p->has_swapchain_maintenance1);
    p->flow_created_info_reported = true;
}
'''
    swapchain_c = replace_once(
        swapchain_c,
        "static bool map_color_space(",
        helper + "static bool map_color_space(",
        "diagnostic helpers",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    VkResult res = vk->CreateSwapchainKHR(vk->dev, &sinfo, PL_VK_ALLOC, &current->swapchain);\n"
        "    PL_VK_ASSERT(res, \"vk->CreateSwapchainKHR(...)\");",
        "    pl_clock_t flow_create_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "        ? pl_clock_now() : 0;\n"
        "    VkResult res = vk->CreateSwapchainKHR(vk->dev, &sinfo, PL_VK_ALLOC, &current->swapchain);\n"
        "    if (flow_create_start)\n"
        "        flow_report_wsi_call(p, FLOW_WSI_CREATE_SWAPCHAIN, \"vkCreateSwapchainKHR\", flow_create_start, pl_clock_now(), res);\n"
        "    PL_VK_ASSERT(res, \"vk->CreateSwapchainKHR(...)\");",
        "vkCreateSwapchainKHR host timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    // Get the new swapchain images",
        '    p->flow_created_present_mode = sinfo.presentMode;\n'
        '    p->flow_created_format = sinfo.imageFormat;\n'
        '    p->flow_created_color_space = sinfo.imageColorSpace;\n'
        '    p->flow_created_extent = sinfo.imageExtent;\n'
        '    p->flow_created_usage = sinfo.imageUsage;\n'
        '    p->flow_created_min_image_count = sinfo.minImageCount;\n'
        '    p->flow_created_image_count = 0;\n'
        '    p->flow_created_info_valid = true;\n'
        '    p->flow_created_info_reported = false;\n'
        '    // Get the new swapchain images',
        "created WSI present-mode report",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    VK(vk->GetSwapchainImagesKHR(vk->dev, current->swapchain, &num_images, NULL));",
        "    pl_clock_t flow_get_count_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "        ? pl_clock_now() : 0;\n"
        "    VkResult flow_get_count_result = vk->GetSwapchainImagesKHR(vk->dev, current->swapchain, &num_images, NULL);\n"
        "    if (flow_get_count_start)\n"
        "        flow_report_wsi_call(p, FLOW_WSI_GET_IMAGE_COUNT, \"vkGetSwapchainImagesKHR_count\", flow_get_count_start, pl_clock_now(), flow_get_count_result);\n"
        "    VK(flow_get_count_result);",
        "swapchain image-count query timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    VK(vk->GetSwapchainImagesKHR(vk->dev, current->swapchain, &num_images, current->vkimages.elem));\n",
        "    pl_clock_t flow_get_images_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "        ? pl_clock_now() : 0;\n"
        "    VkResult flow_get_images_result = vk->GetSwapchainImagesKHR(vk->dev, current->swapchain, &num_images, current->vkimages.elem);\n"
        "    if (flow_get_images_start)\n"
        "        flow_report_wsi_call(p, FLOW_WSI_GET_IMAGES, \"vkGetSwapchainImagesKHR\", flow_get_images_start, pl_clock_now(), flow_get_images_result);\n"
        "    VK(flow_get_images_result);\n"
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
        "        if (flow_acquire_start) {\n"
        "            p->flow_diag_last_timeout_ns[FLOW_WSI_ACQUIRE] = UINT64_MAX;\n"
        "            p->flow_diag_last_image_index[FLOW_WSI_ACQUIRE] = imgidx;\n"
        "            p->flow_diag_image_index_valid[FLOW_WSI_ACQUIRE] = res == VK_SUCCESS || res == VK_SUBOPTIMAL_KHR;\n"
        "            flow_report_wsi_call(p, FLOW_WSI_ACQUIRE, \"vkAcquireNextImageKHR\", flow_acquire_start, pl_clock_now(), res);\n"
        "        }",
        "vkAcquireNextImageKHR timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "                vk->WaitForFences(vk->dev, 1, pfence, VK_TRUE, UINT64_MAX);\n"
        "                vk->ResetFences(vk->dev, 1, pfence);",
        "                pl_clock_t flow_fence_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "                    ? pl_clock_now() : 0;\n"
        "                VkResult flow_fence_result = vk->WaitForFences(vk->dev, 1, pfence, VK_TRUE, UINT64_MAX);\n"
        "                if (flow_fence_start) {\n"
        "                    p->flow_diag_last_timeout_ns[FLOW_WSI_FENCE_WAIT] = UINT64_MAX;\n"
        "                    p->flow_diag_last_image_index[FLOW_WSI_FENCE_WAIT] = imgidx;\n"
        "                    p->flow_diag_image_index_valid[FLOW_WSI_FENCE_WAIT] = 1;\n"
        "                    flow_report_wsi_call(p, FLOW_WSI_FENCE_WAIT, \"present_fence_wait\", flow_fence_start, pl_clock_now(), flow_fence_result);\n"
        "                }\n"
        "                pl_clock_t flow_reset_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "                    ? pl_clock_now() : 0;\n"
        "                VkResult flow_reset_result = vk->ResetFences(vk->dev, 1, pfence);\n"
        "                if (flow_reset_start)\n"
        "                    flow_report_wsi_call(p, FLOW_WSI_FENCE_RESET, \"vkResetFences\", flow_reset_start, pl_clock_now(), flow_reset_result);",
        "present-fence wait timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    vk->lock_queue(vk->queue_ctx, pool->qf, qidx);\n",
        "    pl_clock_t flow_queue_lock_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "        ? pl_clock_now() : 0;\n"
        "    vk->lock_queue(vk->queue_ctx, pool->qf, qidx);\n"
        "    pl_clock_t flow_present_start = flow_queue_lock_start ? pl_clock_now() : 0;\n"
        "    if (flow_queue_lock_start)\n"
        "        flow_report_wsi_call(p, FLOW_WSI_PRESENT_QUEUE_LOCK, \"present_queue_lock\", flow_queue_lock_start, flow_present_start, VK_SUCCESS);\n",
        "present queue-lock timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    VkResult res = vk->QueuePresentKHR(queue, &pinfo);\n",
        "    VkResult res = vk->QueuePresentKHR(queue, &pinfo);\n"
        "    pl_clock_t flow_present_end = flow_queue_lock_start ? pl_clock_now() : 0;\n",
        "vkQueuePresentKHR call timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    vk->unlock_queue(vk->queue_ctx, pool->qf, qidx);\n",
        "    vk->unlock_queue(vk->queue_ctx, pool->qf, qidx);\n"
        "    if (flow_queue_lock_start)\n"
        "        flow_report_wsi_call(p, FLOW_WSI_PRESENT, \"vkQueuePresentKHR\", flow_present_start, flow_present_end, res);\n",
        "vkQueuePresentKHR duration report",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    struct vk_ctx *vk = p->vk;\n    pl_mutex_lock(&p->lock);\n\n    bool recreate = !p->current || p->needs_recreate;",
        "    struct vk_ctx *vk = p->vk;\n"
        "    pl_clock_t flow_lock_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "        ? pl_clock_now() : 0;\n"
        "    pl_mutex_lock(&p->lock);\n"
        "    if (flow_lock_start)\n"
        "        flow_report_wsi_call(p, FLOW_WSI_MUTEX_LOCK, \"swapchain_mutex_lock\", flow_lock_start, pl_clock_now(), VK_SUCCESS);\n\n"
        "    bool recreate = !p->current || p->needs_recreate;",
        "start-frame mutex wait timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    if (recreate && !vk_sw_recreate(sw, 0, 0)) {\n        pl_mutex_unlock(&p->lock);\n        return false;\n    }",
        "    if (recreate) {\n"
        "        pl_clock_t flow_recreate_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "            ? pl_clock_now() : 0;\n"
        "        bool flow_recreate_ok = vk_sw_recreate(sw, 0, 0);\n"
        "        if (flow_recreate_start)\n"
        "            flow_report_wsi_call(p, FLOW_WSI_RECREATE, \"swapchain_recreate\", flow_recreate_start, pl_clock_now(),\n"
        "                                 flow_recreate_ok ? VK_SUCCESS : VK_ERROR_UNKNOWN);\n"
        "        if (!flow_recreate_ok) {\n"
        "            pl_mutex_unlock(&p->lock);\n"
        "            return false;\n"
        "        }\n"
        "    }",
        "start-frame swapchain recreation timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "            if (!vk_sw_recreate(sw, 0, 0)) {\n                pl_mutex_unlock(&p->lock);\n                return false;\n            }",
        "            pl_clock_t flow_retry_recreate_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "                ? pl_clock_now() : 0;\n"
        "            bool flow_retry_recreate_ok = vk_sw_recreate(sw, 0, 0);\n"
        "            if (flow_retry_recreate_start)\n"
        "                flow_report_wsi_call(p, FLOW_WSI_RECREATE, \"swapchain_recreate_out_of_date\",\n"
        "                                     flow_retry_recreate_start, pl_clock_now(),\n"
        "                                     flow_retry_recreate_ok ? VK_SUCCESS : VK_ERROR_UNKNOWN);\n"
        "            if (!flow_retry_recreate_ok) {\n"
        "                pl_mutex_unlock(&p->lock);\n"
        "                return false;\n"
        "            }",
        "out-of-date WSI recreation timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "            if (!vk_sw_image_init(sw, imgidx))\n                goto error;",
        "            pl_clock_t flow_image_init_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "                ? pl_clock_now() : 0;\n"
        "            bool flow_image_init_ok = vk_sw_image_init(sw, imgidx);\n"
        "            if (flow_image_init_start)\n"
        "                flow_report_wsi_call(p, FLOW_WSI_IMAGE_INIT, \"vk_sw_image_init\", flow_image_init_start, pl_clock_now(),\n"
        "                                     flow_image_init_ok ? VK_SUCCESS : VK_ERROR_UNKNOWN);\n"
        "            if (!flow_image_init_ok)\n                goto error;",
        "acquired swapchain image initialization timing",
    )
    swapchain_c = replace_once(
        swapchain_c,
        "    int qidx = cmd->qindex;\n    if (!CMD_SUBMIT(&cmd)) {\n        pl_mutex_unlock(&p->lock);\n        return false;\n    }",
        "    int qidx = cmd->qindex;\n"
        "    pl_clock_t flow_submit_start = atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed)\n"
        "        ? pl_clock_now() : 0;\n"
        "    bool flow_submit_ok = CMD_SUBMIT(&cmd);\n"
        "    if (flow_submit_start)\n"
        "        flow_report_wsi_call(p, FLOW_WSI_COMMAND_SUBMIT, \"vk_cmd_submit_path\", flow_submit_start, pl_clock_now(),\n"
        "                             flow_submit_ok ? VK_SUCCESS : VK_ERROR_UNKNOWN);\n"
        "    if (!flow_submit_ok) {\n"
        "        pl_mutex_unlock(&p->lock);\n"
        "        return false;\n"
        "    }",
        "swapchain command-submit host timing",
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


def enable_qcom_image_processing(vulkan_context: str) -> str:
    marker = "/* MPVFLOW_QCOM_IMAGE_PROCESSING_FEATURES */"
    required = (
        "VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME",
        "VK_QCOM_IMAGE_PROCESSING_2_EXTENSION_NAME",
        "VkPhysicalDeviceImageProcessingFeaturesQCOM",
        "VkPhysicalDeviceImageProcessing2FeaturesQCOM",
        "textureBlockMatch = true",
        "textureBlockMatch2 = true",
        "MPVFLOW_QCOM_IMAGE_PROCESSING_FEATURES",
    )
    if marker in vulkan_context:
        if all(item in vulkan_context for item in required):
            return vulkan_context
        raise SystemExit("Pinned MPV QCOM image-processing patch is incomplete")

    extension_anchor = "        VK_NV_OPTICAL_FLOW_EXTENSION_NAME,\n"
    extension_insert = extension_anchor + (
        "#ifdef VK_QCOM_image_processing\n"
        "        VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME,\n"
        "#ifdef VK_QCOM_image_processing2\n"
        "        VK_QCOM_IMAGE_PROCESSING_2_EXTENSION_NAME,\n"
        "#endif\n"
        "#endif\n"
    )
    feature_anchor = (
        "    VkPhysicalDeviceOpticalFlowFeaturesNV optical_flow_feature = {\n"
        "        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV,\n"
        "        .pNext = &zero_init_feature,\n"
    )
    feature_insert = (
        "#if defined(VK_QCOM_image_processing) && defined(VK_QCOM_image_processing2)\n"
        "    VkPhysicalDeviceImageProcessing2FeaturesQCOM image_processing2_feature = {\n"
        "        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_2_FEATURES_QCOM,\n"
        "        .pNext = &zero_init_feature,\n"
        "        .textureBlockMatch2 = true,\n"
        "    };\n"
        "#endif\n"
        "#ifdef VK_QCOM_image_processing\n"
        "    VkPhysicalDeviceImageProcessingFeaturesQCOM image_processing_feature = {\n"
        "        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_FEATURES_QCOM,\n"
        "#ifdef VK_QCOM_image_processing2\n"
        "        .pNext = &image_processing2_feature,\n"
        "#else\n"
        "        .pNext = &zero_init_feature,\n"
        "#endif\n"
        "        .textureBlockMatch = true,\n"
        "    };\n"
        "#endif\n"
        f"{marker}\n"
        "    VkPhysicalDeviceOpticalFlowFeaturesNV optical_flow_feature = {\n"
        "        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV,\n"
        "        .pNext =\n"
        "#ifdef VK_QCOM_image_processing\n"
        "            &image_processing_feature,\n"
        "#else\n"
        "            &zero_init_feature,\n"
        "#endif\n"
    )

    if vulkan_context.count(extension_anchor) != 1 or vulkan_context.count(feature_anchor) != 1:
        raise SystemExit("Pinned MPV Vulkan extension/feature chain changed; refusing an unverified QCOM enablement patch")
    patched = vulkan_context.replace(extension_anchor, extension_insert, 1)
    return patched.replace(feature_anchor, feature_insert, 1)


def enable_mailbox_suboptimal_tolerance(vulkan_context: str) -> str:
    marker = "/* MPVFLOW_MAILBOX_SUBOPTIMAL_TOLERANCE */"
    policy = (
        "    pl_params.allow_suboptimal =\n"
        "        pl_params.present_mode == VK_PRESENT_MODE_MAILBOX_KHR;\n"
    )
    required = (
        "pl_params.present_mode = p->opts->swap_mode;",
        policy,
        marker,
    )
    if marker in vulkan_context:
        if all(item in vulkan_context for item in required):
            return vulkan_context
        raise SystemExit("Pinned MPV mailbox suboptimal-tolerance patch is incomplete")

    anchor = (
        "    if (p->opts->swap_mode >= 0) // user override\n"
        "        pl_params.present_mode = p->opts->swap_mode;\n"
    )
    replacement = anchor + (
        "\n"
        "    /* Tolerate non-fatal SUBOPTIMAL presents on the high-rate mailbox path.\n"
        "     * Surface-size changes still use pl_swapchain_resize; OUT_OF_DATE\n"
        "     * retains libplacebo's normal recreation path. */\n"
        + policy
        + f"    {marker}\n"
    )
    if vulkan_context.count(anchor) != 1:
        raise SystemExit(
            "Pinned MPV Vulkan swap-mode override changed; refusing an unverified mailbox suboptimal policy"
        )
    return vulkan_context.replace(anchor, replacement, 1)


def has_source_visibility_output_contract(motion: str) -> bool:
    if "return source_blend * (1.0 - visibility_sum) +" in motion:
        return True
    return (
        "float applied_trust = clamp(visibility_sum, 0.0, 1.0);" in motion
        and "vec4 final_output = source_blend * (1.0 - applied_trust) +" in motion
        and "warped0 * visibility.x + warped1 * visibility.y;" in motion
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", required=True, type=Path)
    parser.add_argument("--libplacebo-dir", required=True, type=Path)
    args = parser.parse_args()
    mpv_dir = args.mpv_dir.resolve()
    libplacebo_dir = args.libplacebo_dir.resolve()
    meson_path = mpv_dir / "meson.build"
    stats_lua_path = mpv_dir / "player/lua/stats.lua"
    command_path = mpv_dir / "player/command.c"
    vo_path = mpv_dir / "video/out/vo_gpu_next.c"
    motion_path = mpv_dir / "video/out/gpu_next/motion.c"
    hwdec_header_path = mpv_dir / "video/out/gpu/hwdec.h"
    vulkan_context_path = mpv_dir / "video/out/vulkan/context.c"
    glslang_meson_path = libplacebo_dir / "src/glsl/meson.build"
    glslang_source_path = libplacebo_dir / "src/glsl/glslang.cc"
    gpu_timer_path = libplacebo_dir / "src/vulkan/gpu.c"
    placebo_swapchain_path = libplacebo_dir / "src/vulkan/swapchain.c"
    placebo_vulkan_header_path = libplacebo_dir / "src/include/libplacebo/vulkan.h"
    placebo_stubs_path = libplacebo_dir / "src/vulkan/stubs.c"
    for path in (
        meson_path, stats_lua_path, command_path, vo_path, motion_path, hwdec_header_path,
        vulkan_context_path, glslang_meson_path, glslang_source_path, gpu_timer_path,
        placebo_swapchain_path, placebo_vulkan_header_path, placebo_stubs_path,
    ):
        if not path.is_file():
            raise SystemExit(f"Pinned native source is missing required file {path}")

    debug_capture_enabled = os.environ.get("MPVFLOW_DEBUG_CAPTURE") == "1"
    if debug_capture_enabled:
        for patcher in (
            ROOT / "scripts/patch_fongmi_motion_diagnostics.py",
            ROOT / "scripts/patch_fongmi_vo_diagnostics.py",
        ):
            if not patcher.is_file():
                raise SystemExit(f"FongMi one-shot diagnostic patcher is missing: {patcher}")
            subprocess.run([sys.executable, str(patcher), str(mpv_dir)], cwd=ROOT, check=True)
        print("Enabled bounded one-shot FongMi diagnostics for this debug runtime")
    else:
        print("FongMi one-shot diagnostics disabled; leaving motion/VO sources uninstrumented")

    native_fongmi_markers = (
        "version: '>=7.387.0'",
        "video/out/gpu_next/motion.c",
        "libmpv_motion = static_library('mpv-motion'",
        "video/out/hwdec/hwdec_aimagereader.c",
        "video/out/hwdec/hwdec_aimagereader_vk_direct.c",
        "mp_motion_prepare_view",
        "mp_motion_render_views",
    )
    meson = meson_path.read_text(encoding="utf-8")
    stats_lua = stats_lua_path.read_text(encoding="utf-8")
    command = command_path.read_text(encoding="utf-8")
    vo = vo_path.read_text(encoding="utf-8")
    motion = motion_path.read_text(encoding="utf-8")
    motion_option_marker = '{"motion-interpolation", OPT_INT(motion_fps), M_RANGE(0, 240)}'
    if not all(marker in (meson + vo) for marker in native_fongmi_markers):
        raise SystemExit(
            "Pinned MPV source is not the coordinated FongMi native source; "
            "refusing to apply the obsolete mpv-fongmi-android.patch or copy duplicate motion/AImageReader files"
        )
    if (
        motion_option_marker not in vo
        or "mp_motion_capture" not in vo
        or "mp_motion_render" not in vo
        or "mp_motion_prepare_view" not in motion
        or "mp_motion_render_views" not in motion
        or "MPVFLOW_ARTIFACT_GUARDS_V4" not in motion
        or "MPVFLOW_WARP_TRAJECTORY_COHERENCE_V1" not in motion
        or "MPVFLOW_WARP_PRIMARY_TRUST_CAP_V1" not in motion
        or "MPVFLOW_WARP_TRAJECTORY_TRUST_V2" in motion
        or "MPVFLOW_INSHOT_NEAREST_SOURCE_FALLBACK_V1" in motion
        or "trust *= trajectory_quality;" in motion
        or "nearest_frame_weight" in motion
        or "MPVFLOW_SCENE_CUT_SPATIAL_V1" not in motion
        or "MPVFLOW_FINE_MOTION_GRID_V1" not in motion
        or "MPVFLOW_HIGH_RES_FINE_GRID_V1" not in motion
        or "MPVFLOW_ANALYSIS_DOWNSAMPLE_ANTIALIAS_V1" not in motion
        or "vec2 tap_count = clamp(ceil(source_scale / 2.0), vec2(1.0), vec2(4.0));" not in motion
        or "sampled += sample_color(source, p + subpixel * source_scale);" not in motion
        or "MPVFLOW_REFINEMENT_APERTURE_NORMAL_V1" not in motion
        or "MPVFLOW_SPATIAL_TRUST_DOWNWARD_CAP_V3" not in motion
        or "MPVFLOW_SPATIAL_MEDOID_FALLBACK_V1" not in motion
        or "MPVFLOW_SPATIAL_VISIBILITY_CAP_V1" not in motion
        or "MPVFLOW_SOURCE_SPECIFIC_VISIBILITY_V1" not in motion
        or "float relative_fit(float stationary_error, float warped_error)" not in motion
        or "vec2 projection_ratio = vec2(phase_remaining / phase_alpha," not in motion
        or "phase_alpha / phase_remaining);" not in motion
        or "f.xy * scale * projection_ratio.x)" not in motion
        or "r.xy * scale * projection_ratio.y)" not in motion
        or "blend > 0.000001 && blend < 0.999999" not in motion
        or "visibility += conflict_weight * phase * vec2(f.z, r.z) * evidence;" not in motion
        or "visibility *= clamp(spatial_visibility_scale, 0.0, 1.0);" not in motion
        or not has_source_visibility_output_contract(motion)
        or "MPVFLOW_GLOBAL_TRANSLATION_CYCLE_GUARD_V1" not in motion
        or "MPVFLOW_CAMERA_SIMILARITY_PRIOR_V1" not in motion
        or "static bool fit_camera_model(struct mp_motion *m," not in motion
        or "camera_prior.z * centered" not in motion
        or "camera_cycle < 1.5" not in motion
        or ".num_descriptors = 7," not in motion
        or "int spacing = (level == LEVELS - 1 || level == 0) ? 4 : 8;" not in motion
        or "if (level == 0 && scale < 0.333334)" not in motion
        or "spacing = 3;" not in motion
        or "video/out/gpu_next/motion.c" not in meson
        or "libmpv_motion" not in meson
        or '{"motion-interpolation-info", mp_property_motion_info}' not in command
        or 'mp.get_property_native("motion-interpolation-info")' not in stats_lua
    ):
        raise SystemExit("The pinned native FongMi MEMC/Android/OSD source contract is incomplete")
    capture_markers = (
        "MPVFLOW_FONGMI_SINGLE_OUTPUT_CAPTURE_V2",
        "MPVFLOW_FONGMI_VO_SINGLE_OUTPUT_CAPTURE_V2",
        "video/out/gpu_next/mpvflow_capture_io.c",
        "fongmi-diagnostic-control-file",
        "mp_motion_source_is_hdr",
        "diagnostic_hdr_rejected = m->diagnostic_source_hdr",
        "fongmi_capture_source_frame_route(mix, pair)",
        "active_route",
        "source_is_hdr",
        "fongmi_diagnostic_actual_hdr_source_unsupported",
    )
    capture_source = motion + vo + meson
    if debug_capture_enabled and not all(marker in capture_source for marker in capture_markers):
        raise SystemExit("Debug runtime requested FongMi one-shot capture but its full source contract is missing")
    if not debug_capture_enabled and any(marker in capture_source for marker in capture_markers):
        raise SystemExit("FongMi one-shot capture unexpectedly present in a non-debug runtime")
    validate_fongmi_frame_priv_members(vo)
    mapper_sources = (
        mpv_dir / "video/out/hwdec/hwdec_aimagereader.c",
        mpv_dir / "video/out/hwdec/hwdec_aimagereader_vk.c",
        mpv_dir / "video/out/hwdec/hwdec_aimagereader_vk_convert.c",
        mpv_dir / "video/out/hwdec/hwdec_aimagereader_vk_direct.c",
    )
    missing_mapper_sources = [path for path in mapper_sources if not path.is_file()]
    if missing_mapper_sources:
        raise SystemExit(
            "Pinned FongMi MPV source is missing native AImageReader/Vulkan mapper files: "
            + ", ".join(str(path) for path in missing_mapper_sources)
        )
    mapper_header = hwdec_header_path.read_text(encoding="utf-8")
    if (
        "#define RA_HWDEC_MAP_RETRY (-2)" not in mapper_header
        or "bool retry = result == RA_HWDEC_MAP_RETRY;" not in vo
        or "p->hwdec_map_retry |= retry;" not in vo
        or "fp->source->failed = ret != RA_HWDEC_MAP_RETRY;" not in vo
    ):
        raise SystemExit("Pinned FongMi VO is missing the transient hwdec-map retry/ownership contract")

    vulkan_context = enable_android_external_memory_extensions(
        vulkan_context_path.read_text(encoding="utf-8")
    )
    vulkan_context = enable_mailbox_suboptimal_tolerance(vulkan_context)
    meson = prefer_fork_for_android_mpv_subprocesses(
        enable_android_vulkan_dependency(meson)
    )
    glslang_meson = enable_android_glslang_library_search(
        glslang_meson_path.read_text(encoding="utf-8")
    )
    glslang_source = glslang_source_path.read_text(encoding="utf-8")
    optimizer_enabled = "options.disableOptimizer = false;"
    optimizer_disabled = "options.disableOptimizer = true;"
    enabled_count = glslang_source.count(optimizer_enabled)
    disabled_count = glslang_source.count(optimizer_disabled)
    if enabled_count == 0 and disabled_count == 1:
        glslang_source = glslang_source.replace(optimizer_disabled, optimizer_enabled, 1)
    elif enabled_count != 1 or disabled_count != 0:
        raise SystemExit(
            "Pinned libplacebo glslang optimizer setting is ambiguous; refusing an unverified SPIR-V build"
        )

    gpu_timer = enlarge_libplacebo_timer_ring(gpu_timer_path.read_text(encoding="utf-8"))
    placebo_swapchain_source = placebo_swapchain_path.read_text(encoding="utf-8")
    validate_libplacebo_suboptimal_recovery(placebo_swapchain_source)
    placebo_swapchain = instrument_libplacebo_swapchain(placebo_swapchain_source)
    placebo_vulkan_header = add_libplacebo_swapchain_diagnostics_api(
        placebo_vulkan_header_path.read_text(encoding="utf-8")
    )
    placebo_stubs = add_libplacebo_swapchain_diagnostics_stub(
        placebo_stubs_path.read_text(encoding="utf-8")
    )

    meson_path.write_text(meson, encoding="utf-8")
    vulkan_context_path.write_text(vulkan_context, encoding="utf-8")
    glslang_meson_path.write_text(glslang_meson, encoding="utf-8")
    glslang_source_path.write_text(glslang_source, encoding="utf-8")
    gpu_timer_path.write_text(gpu_timer, encoding="utf-8")
    placebo_swapchain_path.write_text(placebo_swapchain, encoding="utf-8")
    placebo_vulkan_header_path.write_text(placebo_vulkan_header, encoding="utf-8")
    placebo_stubs_path.write_text(placebo_stubs, encoding="utf-8")
    print(
        f"Validated native FongMi GPU MEMC and Android MediaCodec/Vulkan mapping in {mpv_dir}; "
        f"kept SPIR-V optimization enabled and applied Android Vulkan synchronization and "
        f"swapchain stability fixes in {libplacebo_dir}."
    )

if __name__ == "__main__":
    main()
