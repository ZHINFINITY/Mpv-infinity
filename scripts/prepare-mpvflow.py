#!/usr/bin/env python3
"""Install the direct GPU MPVFlow renderer stage into the pinned Android mpv checkout."""
from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "app/src/main/cpp/mpvflow"
MPV_VULKAN_DEPENDENCY = "vulkan = dependency('vulkan', version: '>= 1.3.238', required: vulkan_opt)"
ANDROID_VULKAN_DEPENDENCY = "vulkan = dependency('vulkan', required: vulkan_opt)"


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
    if meson.count(MPV_VULKAN_DEPENDENCY) != 1:
        raise SystemExit("Pinned MPV Vulkan dependency check changed; refusing an unverified Android Vulkan build")
    if "vulkan/vulkan_core.h" not in meson or "VK_VERSION_1_3" not in meson:
        raise SystemExit("Pinned MPV Vulkan header-version guard is missing; refusing an unverified Android Vulkan build")
    return meson.replace(MPV_VULKAN_DEPENDENCY, ANDROID_VULKAN_DEPENDENCY, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", required=True, type=Path)
    args = parser.parse_args()
    mpv_dir = args.mpv_dir.resolve()
    meson_path = mpv_dir / "meson.build"
    vo_path = mpv_dir / "video/out/vo_gpu_next.c"
    for path in (meson_path, vo_path):
        if not path.is_file():
            raise SystemExit(f"Pinned mpv source is missing {path}")

    required = (
        VENDOR / "mpvflow_gpu.c",
        VENDOR / "mpvflow_gpu.h",
        VENDOR / "mpvflow-vo-gpu-next.patch",
    )
    for path in required:
        if not path.is_file():
            raise SystemExit(f"GPU Flow source is missing: {path}")

    video_out_dir = mpv_dir / "video/out"
    shutil.copy2(VENDOR / "mpvflow_gpu.c", video_out_dir / "mpvflow_gpu.c")
    shutil.copy2(VENDOR / "mpvflow_gpu.h", video_out_dir / "mpvflow_gpu.h")

    vo = vo_path.read_text()
    meson = meson_path.read_text()
    meson = enable_android_vulkan_dependency(meson)
    vo_marker = "MPVFLOW_GPU_INTEGRATION"
    meson_marker = "# MPVFLOW_ANDROID_GPU"
    if vo_marker not in vo:
        subprocess.run(
            ["patch", "-p1", "--forward", "--input", str(required[2])],
            cwd=mpv_dir,
            check=True,
        )
        vo = vo_path.read_text()
    if vo_marker not in vo:
        raise SystemExit("GPU Flow patch did not mark vo_gpu_next.c")

    gpu_source = "video/out/mpvflow_gpu.c"
    if meson_marker not in meson:
        close = base_sources_close(meson)
        block = (
            "\n# The direct MPVFlow compute stage is built only for Android.\n"
            "if host_machine.system() == 'android'\n"
            f"    sources += files('{gpu_source}')\n"
            "endif\n"
            f"{meson_marker}\n"
        )
        meson = meson[: close + 1] + block + meson[close + 1 :]
        meson_path.write_text(meson)
    elif gpu_source not in meson:
        raise SystemExit("GPU Flow Meson marker exists without its source registration")

    print(
        f"Installed direct GPU MPVFlow into {mpv_dir}; the legacy CPU filter is "
        "not compiled or registered by this build."
    )


if __name__ == "__main__":
    main()
