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
MPV_VULKAN_DEPENDENCY = "vulkan = dependency('vulkan', version: '>= 1.3.238', required: vulkan_opt)"
ANDROID_VULKAN_DEPENDENCY = "vulkan = dependency('vulkan', required: vulkan_opt)"
PLACEBO_SPIRV_LIBRARY_LOOKUP = "spirv = cxx.find_library('SPIRV', required: required, static: static, dirs: vulkan_lib_dirs)"
PLACEBO_GLSLANG_LIBRARY_LOOKUP = "cxx.find_library('glslang', required: required, static: static)"
ANDROID_PLACEBO_GLSLANG_LIBRARY_LOOKUP = "cxx.find_library('glslang', required: required, static: static, dirs: vulkan_lib_dirs)"
VULKAN_OPT_EXTENSION_LIST = "    const char *opt_extensions[] = {\n"
ANDROID_AHB_EXTENSIONS = (
    "#if defined(__ANDROID__)\n"
    '        "VK_ANDROID_external_memory_android_hardware_buffer",\n'
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
    for path in (meson_path, vo_path, vulkan_context_path, glslang_meson_path, gpu_timer_path):
        if not path.is_file():
            raise SystemExit(f"Pinned native source is missing required file {path}")

    required = (
        VENDOR / "mpvflow_gpu.c",
        VENDOR / "mpvflow_gpu.h",
        VENDOR / "mpvflow_opencl.c",
        VENDOR / "mpvflow_opencl.h",
        VENDOR / "mpvflow_opencl_vk_bridge.c",
        VENDOR / "mpvflow_opencl_vk_bridge.h",
        VO_PATCH,
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
        "mpvflow_opencl_vk_bridge.c",
        "mpvflow_opencl_vk_bridge.h",
    ):
        shutil.copy2(VENDOR / filename, video_out_dir / filename)

    vo = vo_path.read_text()
    meson = meson_path.read_text()
    vulkan_context = enable_android_external_memory_extensions(
        vulkan_context_path.read_text()
    )
    meson = enable_android_vulkan_dependency(meson)
    glslang_meson = enable_android_glslang_library_search(glslang_meson_path.read_text())
    gpu_timer = enlarge_libplacebo_timer_ring(gpu_timer_path.read_text())
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

    gpu_source = "video/out/mpvflow_gpu.c"
    opencl_sources = (
        "video/out/mpvflow_opencl.c",
        "video/out/mpvflow_opencl_vk_bridge.c",
    )
    if meson_marker not in meson:
        close = base_sources_close(meson)
        block = (
            "\n# The direct MPVFlow compute stage is built only for Android.\n"
            "if host_machine.system() == 'android'\n"
            f"    sources += files('{gpu_source}', '{opencl_sources[0]}', '{opencl_sources[1]}')\n"
            "endif\n"
            f"{meson_marker}\n"
        )
        meson = meson[: close + 1] + block + meson[close + 1 :]
        meson_path.write_text(meson)
    elif any(source not in meson for source in (gpu_source, *opencl_sources)):
        raise SystemExit("GPU Flow Meson marker exists without every required Flow/OpenCL source")

    vulkan_context_path.write_text(vulkan_context)
    glslang_meson_path.write_text(glslang_meson)
    gpu_timer_path.write_text(gpu_timer)
    print(
        f"Installed direct GPU/OpenCL MPVFlow into {mpv_dir} and corrected libplacebo's "
        f"Android glslang search path in {glslang_meson_path} and Vulkan timestamp "
        f"ring in {gpu_timer_path}; the legacy CPU filter is not compiled or registered by this build."
    )


if __name__ == "__main__":
    main()
