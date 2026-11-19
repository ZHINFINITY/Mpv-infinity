#!/usr/bin/env python3
"""Static contract tests for the Media3/libplacebo ABI header selector."""

from __future__ import annotations

import hashlib
import re
import os
import shlex
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CMAKE = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
PLACEBO = ROOT / "app/src/main/cpp/third_party/libplacebo"


API_HEADERS = {
    374: PLACEBO / "include/libplacebo",
    387: PLACEBO / "include-api-387/libplacebo",
}


def exported_apis(nm_output: str) -> list[int]:
    """Mirror the CMake symbol shape for representative nm output lines."""
    found: list[int] = []
    for line in nm_output.splitlines():
        match = re.search(r"\s+pl_log_create_([0-9]+)(?:@[^\s]+)?$", line)
        if match:
            api = int(match.group(1))
            if api not in found:
                found.append(api)
    return found


def select_header(nm_output: str) -> Path:
    versions = exported_apis(nm_output)
    assert len(versions) == 1, f"expected exactly one logging ABI, got {versions}"
    api = versions[0]
    assert api in API_HEADERS, f"unknown logging ABI {api} must fail closed"
    return API_HEADERS[api]


def config_api(header_dir: Path) -> int:
    config = (header_dir / "config.h").read_text(encoding="utf-8")
    match = re.search(r"^#define PL_API_VER\s+(\d+)\s*$", config, re.MULTILINE)
    assert match, f"missing PL_API_VER in {header_dir / 'config.h'}"
    return int(match.group(1))


def assert_fongmi_api387_struct_layout() -> None:
    """Compile a probe so API 387 cannot silently use upstream struct layouts."""
    source = r"""
#include <stddef.h>
#include <libplacebo/config.h>
#include <libplacebo/gpu.h>
#include <libplacebo/shaders.h>
_Static_assert(PL_API_VER == 387, "wrong libplacebo API version");
_Static_assert(sizeof(struct pl_desc) == 40, "FongMi pl_desc ABI mismatch");
_Static_assert(offsetof(struct pl_desc, sampler_signature) == 24, "missing FongMi sampler signature");
_Static_assert(offsetof(struct pl_desc, address_mode) == 32, "missing FongMi address mode");
_Static_assert(offsetof(struct pl_desc, sample_mode) == 36, "missing FongMi sample mode");
_Static_assert(sizeof(struct pl_desc_binding) == 16, "descriptor binding ABI mismatch");
_Static_assert(sizeof(struct pl_shader_desc) == 72, "FongMi shader descriptor ABI mismatch");
_Static_assert(offsetof(struct pl_shader_desc, binding) == 40, "FongMi shader descriptor offset mismatch");
_Static_assert(PL_SAMPLER_EXTERNAL_YUV >= 0, "missing FongMi external YUV sampler");
int main(void) { return 0; }
"""
    compiler = shlex.split(os.environ.get("CC", "cc"))
    if not compiler:
        raise AssertionError("CC must name a host C compiler for the libplacebo ABI probe")
    with tempfile.TemporaryDirectory(prefix="mpvflow-placebo-abi-") as tmp:
        source_path = Path(tmp) / "fongmi_api387_layout.c"
        output_path = Path(tmp) / "fongmi_api387_layout"
        source_path.write_text(source, encoding="utf-8")
        result = subprocess.run(
            [*compiler, "-std=c11", f"-I{API_HEADERS[387].parent}", str(source_path), "-o", str(output_path)],
            check=False,
            capture_output=True,
            text=True,
        )
        assert result.returncode == 0, result.stderr
        run = subprocess.run([str(output_path)], check=False, capture_output=True, text=True)
        assert run.returncode == 0, run.stderr


def assert_fongmi_api387_snapshot_hashes() -> None:
    """Require the complete fork header snapshot and generated config to stay pinned."""
    include_root = API_HEADERS[387].parent
    manifest = include_root / "FONGMI_HEADERS.sha256"
    assert manifest.is_file(), f"missing FongMi header hash manifest: {manifest}"
    expected_files: set[str] = set()
    for line in manifest.read_text(encoding="utf-8").splitlines():
        expected, relative = line.split("  ", maxsplit=1)
        path = include_root / relative
        assert path.is_file(), f"missing pinned FongMi API-387 header {relative}"
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        assert actual == expected, (relative, actual, expected)
        expected_files.add(relative)
    actual_files = {
        path.relative_to(include_root).as_posix()
        for path in API_HEADERS[387].parent.joinpath("libplacebo").rglob("*")
        if path.is_file()
    }
    assert expected_files == actual_files, (expected_files - actual_files, actual_files - expected_files)


# The selector must be driven by exported symbols, not architecture or the old
# fixed API-374 config header.
assert "MPVFLOW_PLACEBO_API_VERSIONS" in CMAKE
assert "pl_log_create_([0-9]+)" in CMAKE
assert "MPVFLOW_PLACEBO_API_COUNT" in CMAKE
assert "Unsupported libplacebo logging ABI" in CMAKE
assert "MPVFLOW_PLACEBO_INCLUDE_DIR" in CMAKE
assert "MPVFLOW_PLACEBO_CONFIG_HEADER" in CMAKE
assert "pl_vulkan_swapchain_set_diagnostics" in CMAKE
assert "pl_vulkan_swapchain_get_diagnostics" in CMAKE
assert "does not export ${MPVFLOW_REQUIRED_PLACEBO_SYMBOL}" in CMAKE
assert '"${MPVFLOW_PLACEBO_INCLUDE_DIR}"' in CMAKE
assert '"${CMAKE_CURRENT_SOURCE_DIR}/third_party/libplacebo/include"' in CMAKE
assert '"${CMAKE_CURRENT_SOURCE_DIR}/third_party/libplacebo/include-api-387"' in CMAKE
assert "header/API mismatch" in CMAKE

# Both supported nm forms (plain and ELF symbol-version suffixed) map correctly.
assert select_header("00000000 T pl_log_create_374") == API_HEADERS[374]
assert select_header("00000000 T pl_log_create_387@LIBPLACEBO_7.387") == API_HEADERS[387]
assert config_api(select_header("00000000 T pl_log_create_374")) == 374
assert config_api(select_header("00000000 T pl_log_create_387")) == 387

# No symbol, multiple ABIs, and an unknown ABI must all fail closed.
for bad_nm in (
    "00000000 T pl_vulkan_create",
    "00000000 T pl_log_create_374\n00000000 T pl_log_create_387",
    "00000000 T pl_log_create_999",
):
    try:
        select_header(bad_nm)
    except AssertionError:
        pass
    else:
        raise AssertionError(f"selector unexpectedly accepted invalid exports: {bad_nm!r}")

# The API-387 snapshot is complete enough for every public include used by the
# Vulkan JNI target and retains the generated template alongside config.h.
required_headers = (
    "config.h",
    "config.h.in",
    "common.h",
    "log.h",
    "renderer.h",
    "shaders.h",
    "vulkan.h",
)
for name in required_headers:
    assert (API_HEADERS[387] / name).is_file(), f"missing API-387 header {name}"

# Existing API-374 headers remain the legacy snapshot and are not rewritten.
assert config_api(API_HEADERS[374]) == 374
config387 = (API_HEADERS[387] / "config.h").read_text(encoding="utf-8")
assert "PL_HAVE_FONGMI_VULKAN_YCBCR" in config387
assert "PL_HAVE_FONGMI_CHECKED_DOVI" in config387
assert_fongmi_api387_snapshot_hashes()
assert_fongmi_api387_struct_layout()

print("libplacebo API 374 and FongMi API-387 header ABI contracts OK")
