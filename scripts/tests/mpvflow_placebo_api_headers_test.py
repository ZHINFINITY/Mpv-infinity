#!/usr/bin/env python3
"""Static contract tests for the Media3/libplacebo ABI header selector."""

from __future__ import annotations

import re
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


# The selector must be driven by exported symbols, not architecture or the old
# fixed API-374 config header.
assert "MPVFLOW_PLACEBO_API_VERSIONS" in CMAKE
assert "pl_log_create_([0-9]+)" in CMAKE
assert "MPVFLOW_PLACEBO_API_COUNT" in CMAKE
assert "Unsupported libplacebo logging ABI" in CMAKE
assert "MPVFLOW_PLACEBO_INCLUDE_DIR" in CMAKE
assert "MPVFLOW_PLACEBO_CONFIG_HEADER" in CMAKE
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
assert "PL_HAVE_GLSLANG" in (API_HEADERS[387] / "config.h").read_text(encoding="utf-8")

print("libplacebo API 374/387 header-selection contract OK")
