#!/usr/bin/env python3
"""Validate and adapt the pinned native FongMi MPV runtime source."""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from mpvrx_motion_source import verify_mpv_source
from patch_fongmi_motion_downsample import apply_patch as apply_motion_downsample_patch
from patch_fongmi_paired_color_mask import (
    apply_patch as apply_motion_paired_color_mask_patch,
    strip_patch as strip_motion_paired_color_mask_patch,
)

ROOT = SCRIPT_DIR.parent
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


def validate_libplacebo_suboptimal_recovery(swapchain_c: str) -> None:
    required = (
        "if (p->suboptimal && !p->params.allow_suboptimal)\n        recreate = true;",
        "case VK_SUBOPTIMAL_KHR:\n        p->suboptimal = true;\n        // fall through\n    case VK_SUCCESS:\n        return true;",
        "case VK_ERROR_OUT_OF_DATE_KHR: {\n            // In these cases try recreating the swapchain",
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


def reset_motion_queue_on_video_reconfig(vo_source: str) -> tuple[str, bool]:
    reset_helper = "static void reset_frame_queue(struct priv *p)\n"
    reconfig_anchor = (
        "static int reconfig(struct vo *vo, struct mp_image_params *params)\n"
        "{\n"
        "    struct priv *p = vo->priv;\n"
    )
    reset_block = (
        "    /* MPVFLOW_RECONFIG_MOTION_QUEUE_RESET */\n"
        "    if (p->motion_fps)\n"
        "        reset_frame_queue(p);\n"
    )
    if reset_helper not in vo_source or "pl_queue_reset(p->queue);" not in vo_source or \
       "mp_motion_reset(p->motion);" not in vo_source:
        raise ValueError("Pinned gpu-next frame-queue/motion reset helper is missing or incomplete")
    if vo_source.count(reconfig_anchor) != 1:
        raise ValueError("Pinned gpu-next video reconfigure function is missing or ambiguous")
    if reconfig_anchor + reset_block in vo_source:
        return vo_source, False
    return vo_source.replace(reconfig_anchor, reconfig_anchor + reset_block, 1), True


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
    motion_header_path = mpv_dir / "video/out/gpu_next/motion.h"
    hwdec_header_path = mpv_dir / "video/out/gpu/hwdec.h"
    vulkan_context_path = mpv_dir / "video/out/vulkan/context.c"
    android_context_path = mpv_dir / "video/out/vulkan/context_android.c"
    glslang_meson_path = libplacebo_dir / "src/glsl/meson.build"
    glslang_source_path = libplacebo_dir / "src/glsl/glslang.cc"
    gpu_timer_path = libplacebo_dir / "src/vulkan/gpu.c"
    placebo_swapchain_path = libplacebo_dir / "src/vulkan/swapchain.c"
    placebo_vulkan_header_path = libplacebo_dir / "src/include/libplacebo/vulkan.h"
    placebo_stubs_path = libplacebo_dir / "src/vulkan/stubs.c"
    for path in (
        meson_path, stats_lua_path, command_path, vo_path, motion_path, motion_header_path,
        hwdec_header_path, vulkan_context_path, android_context_path, glslang_meson_path,
        glslang_source_path, gpu_timer_path,
        placebo_swapchain_path, placebo_vulkan_header_path, placebo_stubs_path,
    ):
        if not path.is_file():
            raise SystemExit(f"Pinned native source is missing required file {path}")

    downsample_antialias_enabled = os.environ.get("MPVFLOW_ANALYSIS_DOWNSAMPLE_AA") == "1"
    try:
        verify_mpv_source(mpv_dir)
        strip_motion_paired_color_mask_patch(motion_path)
        downsample_patch_applied = apply_motion_downsample_patch(
            motion_path, enabled=downsample_antialias_enabled
        )
    except (OSError, ValueError) as exc:
        raise SystemExit(f"Mpv-infinity FongMi source/analysis verification failed: {exc}") from exc
    if downsample_antialias_enabled:
        print(
            "Applied bounded Mpv-infinity motion-analysis downsample antialiasing"
            if downsample_patch_applied
            else "Bounded Mpv-infinity motion-analysis downsample antialiasing already present"
        )
    else:
        print("Mpv-infinity analysis-downsample antialias patch disabled; preserving pinned motion shader")

    try:
        paired_color_mask_applied = apply_motion_paired_color_mask_patch(motion_path)
    except (OSError, ValueError) as exc:
        raise SystemExit(f"Mpv-infinity paired-color mask verification failed: {exc}") from exc
    print(
        "Applied selective paired-color mismatch mask"
        if paired_color_mask_applied
        else "Selective paired-color mismatch mask already present"
    )

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
    try:
        vo, reconfig_reset_applied = reset_motion_queue_on_video_reconfig(vo)
    except ValueError as exc:
        raise SystemExit(f"MPVFlow video-reconfigure queue reset verification failed: {exc}") from exc
    print(
        "Applied motion queue reset on video reconfigure"
        if reconfig_reset_applied
        else "Motion queue reset on video reconfigure already present"
    )
    motion = motion_path.read_text(encoding="utf-8")
    motion_header = motion_header_path.read_text(encoding="utf-8")
    android_context = android_context_path.read_text(encoding="utf-8")
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
        or "MOTION_PASS_PREPARE" not in motion
        or "pl_dispatch_prepare_compute" not in motion
        or "mp_motion_render_owned" not in motion
        or "mp_motion_capture_supported" not in motion_header
        or "mp_motion_create(pl_gpu gpu, int max_dimension)" not in motion_header
        or "mp_motion_create(p->gpu, 480);" not in vo
        or "info->queue_display_time_only = true;" not in android_context
        or "video/out/gpu_next/motion.c" not in meson
        or "libmpv_motion" not in meson
        or '{"motion-interpolation-info", mp_property_motion_info}' not in command
        or 'mp.get_property_native("motion-interpolation-info")' not in stats_lua
    ):
        raise SystemExit("The pinned native FongMi MEMC/Android/OSD source contract is incomplete")
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
    placebo_swapchain = placebo_swapchain_source
    placebo_vulkan_header = placebo_vulkan_header_path.read_text(encoding="utf-8")
    placebo_stubs = placebo_stubs_path.read_text(encoding="utf-8")

    vo_path.write_text(vo, encoding="utf-8")
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
        f"swapchain stability fixes in {libplacebo_dir}. Mpv-infinity analysis-downsample antialias "
        f"candidate {'enabled' if downsample_antialias_enabled else 'disabled'}; 480px cap unchanged."
    )

if __name__ == "__main__":
    main()
