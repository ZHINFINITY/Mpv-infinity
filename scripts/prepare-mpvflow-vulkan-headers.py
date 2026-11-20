#!/usr/bin/env python3
"""Stage pinned Khronos Vulkan headers ahead of the Android NDK sysroot."""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

VULKAN_HEADERS_REPOSITORY = "https://github.com/KhronosGroup/Vulkan-Headers.git"
VULKAN_HEADERS_REVISION = "a4f8ada9f4f97c45b8c89c57997be9cebaae65d2"
REQUIRED_EXTENSION_MACROS = (
    "VK_EXT_SHADER_REPLICATED_COMPOSITES_EXTENSION_NAME",
    "VK_KHR_SHADER_EXPECT_ASSUME_EXTENSION_NAME",
    "VK_KHR_SHADER_MAXIMAL_RECONVERGENCE_EXTENSION_NAME",
    "VK_KHR_SHADER_RELAXED_EXTENDED_INSTRUCTION_EXTENSION_NAME",
    "VK_KHR_SHADER_SUBGROUP_ROTATE_EXTENSION_NAME",
    "VK_KHR_VIDEO_DECODE_AV1_EXTENSION_NAME",
    "VK_KHR_VIDEO_MAINTENANCE_2_EXTENSION_NAME",
)
REQUIRED_TYPE_DECLARATIONS = (
    "VkPhysicalDeviceVideoMaintenance2FeaturesKHR",
    "VkPhysicalDeviceShaderRelaxedExtendedInstructionFeaturesKHR",
)
REQUIRED_HEADER_SYMBOLS = REQUIRED_EXTENSION_MACROS + REQUIRED_TYPE_DECLARATIONS


def git_output(repository: Path, *args: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(repository), *args],
        text=True,
        stderr=subprocess.PIPE,
    ).strip()


def run_git(repository: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(repository), *args], check=True)


def require_real_directory(path: Path, description: str) -> None:
    if path.is_symlink():
        raise SystemExit(f"Refusing symlinked {description}: {path}")
    if not path.is_dir():
        raise SystemExit(f"Expected a real {description} directory: {path}")


def ensure_real_directory(path: Path, description: str) -> None:
    if path.is_symlink():
        raise SystemExit(f"Refusing symlinked {description}: {path}")
    if path.exists() and not path.is_dir():
        raise SystemExit(f"Expected {description} to be a directory: {path}")
    path.mkdir(parents=True, exist_ok=True)
    require_real_directory(path, description)


def verify_clean_checkout(source_dir: Path) -> None:
    status = git_output(
        source_dir,
        "status",
        "--porcelain=v1",
        "--untracked-files=all",
        "--ignored=matching",
        "--ignore-submodules=none",
    )
    if status:
        first_entry = status.splitlines()[0]
        raise SystemExit(
            f"Refusing dirty, untracked, or ignored Vulkan-Headers checkout at {source_dir}: {first_entry}"
        )


def fetch_pinned_headers(source_dir: Path) -> Path:
    ensure_real_directory(source_dir.parent, "Vulkan-Headers dependency parent")
    if source_dir.is_symlink():
        raise SystemExit(f"Refusing symlinked Vulkan-Headers source tree: {source_dir}")
    if source_dir.exists() and not source_dir.is_dir():
        raise SystemExit(f"Expected Vulkan-Headers source tree to be a directory: {source_dir}")
    source_dir.mkdir(parents=True, exist_ok=True)

    git_dir = source_dir / ".git"
    if git_dir.is_symlink():
        raise SystemExit(f"Refusing symlinked Vulkan-Headers git metadata: {git_dir}")
    if not git_dir.exists():
        if any(source_dir.iterdir()):
            raise SystemExit(f"Refusing to reuse non-git Vulkan-Headers source tree: {source_dir}")
        run_git(source_dir, "init", "--quiet")
        run_git(source_dir, "remote", "add", "origin", VULKAN_HEADERS_REPOSITORY)
    elif not git_dir.is_dir():
        raise SystemExit(f"Expected real Vulkan-Headers git metadata directory: {git_dir}")

    origin = git_output(source_dir, "remote", "get-url", "origin")
    if origin != VULKAN_HEADERS_REPOSITORY:
        raise SystemExit(f"Unexpected Vulkan-Headers origin: expected {VULKAN_HEADERS_REPOSITORY}, got {origin}")
    verify_clean_checkout(source_dir)

    run_git(source_dir, "fetch", "--depth=1", "origin", VULKAN_HEADERS_REVISION)
    run_git(source_dir, "checkout", "--detach", "FETCH_HEAD", "--quiet")
    actual_revision = git_output(source_dir, "rev-parse", "HEAD")
    if actual_revision != VULKAN_HEADERS_REVISION:
        raise SystemExit(
            "Unexpected Vulkan-Headers revision: "
            f"expected {VULKAN_HEADERS_REVISION}, got {actual_revision}"
        )
    verify_clean_checkout(source_dir)
    return source_dir


def reject_symlinks(root: Path, description: str) -> None:
    if root.is_symlink():
        raise SystemExit(f"Refusing symlinked {description}: {root}")
    if not root.is_dir():
        raise SystemExit(f"Missing real {description} directory: {root}")
    for path in root.rglob("*"):
        if path.is_symlink():
            raise SystemExit(f"Refusing symlink inside {description}: {path}")


def validate_headers(include_dir: Path) -> None:
    reject_symlinks(include_dir, "Vulkan-Headers include tree")
    vulkan_dir = include_dir / "vulkan"
    video_dir = include_dir / "vk_video"
    reject_symlinks(vulkan_dir, "Vulkan API headers")
    reject_symlinks(video_dir, "Vulkan video headers")

    core_header = vulkan_dir / "vulkan_core.h"
    android_header = vulkan_dir / "vulkan_android.h"
    if core_header.is_symlink() or not core_header.is_file():
        raise SystemExit(f"Missing regular Khronos Vulkan core header: {core_header}")
    if android_header.is_symlink() or not android_header.is_file():
        raise SystemExit(f"Missing regular Android Vulkan platform header: {android_header}")

    core_text = core_header.read_text(encoding="utf-8")
    missing_macros = [
        symbol
        for symbol in REQUIRED_EXTENSION_MACROS
        if not re.search(rf"^[ \t]*#[ \t]*define[ \t]+{re.escape(symbol)}\b", core_text, re.MULTILINE)
    ]
    missing_types = [
        symbol
        for symbol in REQUIRED_TYPE_DECLARATIONS
        if not re.search(rf"^[ \t]*typedef[ \t]+struct[ \t]+{re.escape(symbol)}\b", core_text, re.MULTILINE)
    ]
    missing = missing_macros + missing_types
    if missing:
        raise SystemExit(f"Pinned Vulkan-Headers source is missing required declarations: {', '.join(missing)}")


def ensure_flat_prefix(prefix_dir: Path) -> None:
    ensure_real_directory(prefix_dir.parent, "Android prefix root")
    ensure_real_directory(prefix_dir, "Android arm64 prefix")
    for name in ("usr", "local"):
        link = prefix_dir / name
        if link.is_symlink():
            if link.readlink() != Path("."):
                raise SystemExit(f"Unexpected Android prefix link {link}: expected symlink to .")
        elif link.exists():
            raise SystemExit(f"Unexpected non-symlink Android prefix path: {link}")
        else:
            link.symlink_to(".")


def stage_vulkan_headers(source_include: Path, prefix_dir: Path) -> Path:
    validate_headers(source_include)
    ensure_flat_prefix(prefix_dir)
    destination = prefix_dir / "include"
    ensure_real_directory(destination, "Android arm64 include prefix")

    # Keep the complete Khronos Vulkan and video include trees, including
    # vulkan_android.h; replace only these source-owned trees in the prefix.
    for name in ("vulkan", "vk_video"):
        source_tree = source_include / name
        target_tree = destination / name
        if target_tree.is_symlink():
            raise SystemExit(f"Refusing symlinked staged Vulkan include tree: {target_tree}")
        if target_tree.exists():
            if not target_tree.is_dir():
                raise SystemExit(f"Expected staged Vulkan include tree to be a directory: {target_tree}")
            shutil.rmtree(target_tree)
        shutil.copytree(source_tree, target_tree)

    validate_headers(destination)
    return destination


def prepare(buildscripts_dir: Path) -> Path:
    buildscripts_dir = buildscripts_dir.resolve()
    source_dir = fetch_pinned_headers(buildscripts_dir / "deps" / "vulkan-headers")
    include_dir = source_dir / "include"
    prefix_dir = buildscripts_dir / "prefix" / "arm64"
    staged_include = stage_vulkan_headers(include_dir, prefix_dir)
    print(
        "Staged Khronos Vulkan-Headers "
        f"{VULKAN_HEADERS_REVISION} at {staged_include}; Android Vulkan loader linkage is unchanged"
    )
    return staged_include


def main(argv: list[str] | None = None) -> int:
    if argv is None:
        argv = sys.argv[1:]
    if len(argv) != 1:
        raise SystemExit("usage: prepare-mpvflow-vulkan-headers.py <android-mpv-buildscripts-dir>")
    prepare(Path(argv[0]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
