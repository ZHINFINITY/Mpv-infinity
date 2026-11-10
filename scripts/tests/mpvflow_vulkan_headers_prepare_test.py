#!/usr/bin/env python3
"""Exercise pinned Vulkan-Headers staging without network access."""
from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER_PATH = ROOT / "scripts/prepare-mpvflow-vulkan-headers.py"
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("prepare_mpvflow_vulkan_headers", HELPER_PATH)
assert spec and spec.loader
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def valid_core_text() -> str:
    declarations = [f'#define {symbol} "{symbol}"' for symbol in helper.REQUIRED_EXTENSION_MACROS]
    declarations.extend(
        f"typedef struct {symbol} {{ int supported; }} {symbol};"
        for symbol in helper.REQUIRED_TYPE_DECLARATIONS
    )
    return "\n".join(declarations) + "\n"


def make_include_tree(root: Path, core_text: str | None = None) -> Path:
    include_dir = root / "include"
    vulkan_dir = include_dir / "vulkan"
    video_dir = include_dir / "vk_video"
    vulkan_dir.mkdir(parents=True)
    video_dir.mkdir(parents=True)
    (vulkan_dir / "vulkan_core.h").write_text(core_text if core_text is not None else valid_core_text(), encoding="utf-8")
    (vulkan_dir / "vulkan_android.h").write_text("#define VK_USE_PLATFORM_ANDROID_KHR 1\n", encoding="utf-8")
    (video_dir / "vulkan_video_codecs_common.h").write_text("/* staged video header */\n", encoding="utf-8")
    return include_dir


def expect_failure(action, expected: str) -> None:
    try:
        action()
    except SystemExit as error:
        assert expected in str(error), (expected, str(error))
    else:
        raise AssertionError(f"expected fail-closed error containing {expected!r}")


def test_staging_replaces_complete_include_trees_and_preserves_prefix_layout() -> None:
    with tempfile.TemporaryDirectory(prefix="mpvflow-vulkan-headers-test-") as temporary:
        root = Path(temporary)
        source_include = make_include_tree(root / "source")
        prefix = root / "prefix" / "arm64"
        staged = helper.stage_vulkan_headers(source_include, prefix)

        assert (prefix / "usr").is_symlink() and (prefix / "usr").readlink() == Path(".")
        assert (prefix / "local").is_symlink() and (prefix / "local").readlink() == Path(".")
        assert (prefix / "usr" / "local" / "include" / "vulkan" / "vulkan_core.h").is_file()
        assert (staged / "vulkan" / "vulkan_android.h").is_file()
        assert (staged / "vk_video" / "vulkan_video_codecs_common.h").is_file()

        (source_include / "vulkan" / "vulkan_core.h").write_text(
            valid_core_text() + "#define STAGING_REFRESHED 1\n",
            encoding="utf-8",
        )
        helper.stage_vulkan_headers(source_include, prefix)
        staged_text = (staged / "vulkan" / "vulkan_core.h").read_text(encoding="utf-8")
        assert "STAGING_REFRESHED" in staged_text, "restaging must replace the prior Khronos tree"


def test_staging_rejects_symlinked_prefix_and_include_destinations() -> None:
    with tempfile.TemporaryDirectory(prefix="mpvflow-vulkan-headers-symlink-") as temporary:
        root = Path(temporary)
        source_include = make_include_tree(root / "source")
        outside = root / "outside"
        outside.mkdir()
        sentinel = outside / "keep.txt"
        sentinel.write_text("do not touch\n", encoding="utf-8")

        prefix_root = root / "prefix-one"
        prefix_root.mkdir()
        (prefix_root / "arm64").symlink_to(outside, target_is_directory=True)
        expect_failure(
            lambda: helper.stage_vulkan_headers(source_include, prefix_root / "arm64"),
            "Refusing symlinked Android arm64 prefix",
        )
        assert sentinel.read_text(encoding="utf-8") == "do not touch\n"

        include_prefix = root / "prefix-two" / "arm64"
        include_prefix.mkdir(parents=True)
        outside_include = root / "outside-include"
        outside_include.mkdir()
        outside_sentinel = outside_include / "keep.txt"
        outside_sentinel.write_text("do not overwrite\n", encoding="utf-8")
        (include_prefix / "include").symlink_to(outside_include, target_is_directory=True)
        expect_failure(
            lambda: helper.stage_vulkan_headers(source_include, include_prefix),
            "Refusing symlinked Android arm64 include prefix",
        )
        assert outside_sentinel.read_text(encoding="utf-8") == "do not overwrite\n"


def test_staging_rejects_symlinked_source_headers_and_comment_only_names() -> None:
    with tempfile.TemporaryDirectory(prefix="mpvflow-vulkan-headers-source-") as temporary:
        root = Path(temporary)
        source_include = make_include_tree(root / "source")
        external_header = root / "external.h"
        external_header.write_text(valid_core_text(), encoding="utf-8")
        (source_include / "vulkan" / "extra.h").symlink_to(external_header)
        expect_failure(
            lambda: helper.stage_vulkan_headers(source_include, root / "prefix" / "arm64"),
            "Refusing symlink inside Vulkan-Headers include tree",
        )

    with tempfile.TemporaryDirectory(prefix="mpvflow-vulkan-headers-comments-") as temporary:
        root = Path(temporary)
        comments_only = "/* " + " ".join(helper.REQUIRED_HEADER_SYMBOLS) + " */\n"
        source_include = make_include_tree(root / "source", comments_only)
        expect_failure(
            lambda: helper.stage_vulkan_headers(source_include, root / "prefix" / "arm64"),
            "missing required declarations",
        )


def test_dirty_untracked_and_ignored_header_checkouts_are_rejected() -> None:
    with tempfile.TemporaryDirectory(prefix="mpvflow-vulkan-headers-git-") as temporary:
        repository = Path(temporary) / "vulkan-headers"
        repository.mkdir()
        subprocess.run(["git", "-C", str(repository), "init", "--quiet"], check=True)
        (repository / ".gitignore").write_text("ignored-header.h\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(repository), "add", ".gitignore"], check=True)
        subprocess.run(
            [
                "git",
                "-C",
                str(repository),
                "-c",
                "user.name=Vulkan header test",
                "-c",
                "user.email=vulkan-header-test@example.invalid",
                "commit",
                "--quiet",
                "-m",
                "clean fixture",
            ],
            check=True,
        )
        helper.verify_clean_checkout(repository)

        untracked = repository / "untracked-header.h"
        untracked.write_text("untrusted header\n", encoding="utf-8")
        expect_failure(lambda: helper.verify_clean_checkout(repository), "dirty, untracked, or ignored")
        untracked.unlink()

        ignored = repository / "ignored-header.h"
        ignored.write_text("ignored untrusted header\n", encoding="utf-8")
        expect_failure(lambda: helper.verify_clean_checkout(repository), "dirty, untracked, or ignored")


test_staging_replaces_complete_include_trees_and_preserves_prefix_layout()
test_staging_rejects_symlinked_prefix_and_include_destinations()
test_staging_rejects_symlinked_source_headers_and_comment_only_names()
test_dirty_untracked_and_ignored_header_checkouts_are_rejected()
print("MPVFlow pinned Vulkan-Headers staging and adversarial safety tests passed")
