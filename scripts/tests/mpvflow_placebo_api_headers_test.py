#!/usr/bin/env python3
"""Static contract tests for the Media3/libplacebo ABI header selector."""

from __future__ import annotations

import hashlib
import os
import re
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
    391: PLACEBO / "include-api-391/libplacebo",
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


def _compile_probe(api: int, source: str) -> None:
    compiler = shlex.split(os.environ.get("CC", "cc"))
    if not compiler:
        raise AssertionError("CC must name a host C compiler for the libplacebo ABI probe")
    with tempfile.TemporaryDirectory(prefix=f"mpvflow-placebo-{api}-") as tmp:
        source_path = Path(tmp) / f"fongmi_api{api}_layout.c"
        output_path = Path(tmp) / f"fongmi_api{api}_layout"
        source_path.write_text(source, encoding="utf-8")
        result = subprocess.run(
            [*compiler, "-std=c11", f"-I{API_HEADERS[api].parent}", str(source_path), "-o", str(output_path)],
            check=False,
            capture_output=True,
            text=True,
        )
        assert result.returncode == 0, result.stderr
        run = subprocess.run([str(output_path)], check=False, capture_output=True, text=True)
        assert run.returncode == 0, run.stderr


def assert_fongmi_struct_layouts() -> None:
    """Compile probes for the fork-specific layouts passed across the shared-library ABI."""
    for api, render_size, extra in (
        (387, 304, ""),
        (391, 312, "_Static_assert(offsetof(struct pl_render_params, frame_interp_params) == 48, \"API-391 interpolation field offset mismatch\");"),
    ):
        source = f"""
#include <stddef.h>
#include <libplacebo/config.h>
#include <libplacebo/gpu.h>
#include <libplacebo/shaders.h>
#include <libplacebo/renderer.h>
_Static_assert(PL_API_VER == {api}, "wrong libplacebo API version");
_Static_assert(sizeof(struct pl_desc) == 40, "FongMi pl_desc ABI mismatch");
_Static_assert(offsetof(struct pl_desc, sampler_signature) == 24, "missing FongMi sampler signature");
_Static_assert(offsetof(struct pl_desc, address_mode) == 32, "missing FongMi address mode");
_Static_assert(offsetof(struct pl_desc, sample_mode) == 36, "missing FongMi sample mode");
_Static_assert(sizeof(struct pl_desc_binding) == 16, "descriptor binding ABI mismatch");
_Static_assert(sizeof(struct pl_shader_desc) == 72, "FongMi shader descriptor ABI mismatch");
_Static_assert(offsetof(struct pl_shader_desc, binding) == 40, "FongMi shader descriptor offset mismatch");
_Static_assert(sizeof(struct pl_render_params) == {render_size}, "render-parameter ABI mismatch");
_Static_assert(offsetof(struct pl_render_params, frame_mixer) == 40, "frame mixer ABI offset mismatch");
{extra}
_Static_assert(PL_SAMPLER_EXTERNAL_YUV >= 0, "missing FongMi external YUV sampler");
int main(void) {{ return 0; }}
"""
        _compile_probe(api, source)


def assert_snapshot_hashes(api: int) -> None:
    """Require each complete FongMi header tree and generated config to stay pinned."""
    include_root = API_HEADERS[api].parent
    manifest = include_root / "FONGMI_HEADERS.sha256"
    assert manifest.is_file(), f"missing FongMi API-{api} header hash manifest: {manifest}"
    expected_files: set[str] = set()
    for line in manifest.read_text(encoding="utf-8").splitlines():
        expected, relative = line.split("  ", maxsplit=1)
        path = include_root / relative
        assert path.is_file(), f"missing pinned FongMi API-{api} header {relative}"
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        assert actual == expected, (relative, actual, expected)
        expected_files.add(relative)
    actual_files = {
        path.relative_to(include_root).as_posix()
        for path in API_HEADERS[api].parent.joinpath("libplacebo").rglob("*")
        if path.is_file()
    }
    assert expected_files == actual_files, (expected_files - actual_files, actual_files - expected_files)


def assert_api391_upstream_snapshot() -> None:
    """Verify API-391 files against the published SDK manifest, allowing only the known local header extension."""
    include_root = API_HEADERS[391].parent
    source_manifest = include_root / "MPVRX_SDK_HEADERS.sha256"
    assert source_manifest.is_file(), f"missing published SDK header inventory: {source_manifest}"
    source_rows: dict[str, str] = {}
    for line in source_manifest.read_text(encoding="utf-8").splitlines():
        digest, relative = line.split("  ", maxsplit=1)
        source_rows[relative] = digest
        path = include_root / relative
        assert path.is_file(), f"missing API-391 SDK header {relative}"
        data = path.read_bytes()
        if relative == "libplacebo/vulkan.h":
            text = data.decode("utf-8")
            patch = (
                "PL_API bool pl_vulkan_swapchain_set_diagnostics(pl_swapchain sw, bool enabled);\n"
                "PL_API bool pl_vulkan_swapchain_get_diagnostics(\n"
                "    pl_swapchain sw, int *requested_mode,\n"
                "    int *created_mode, int *requested_depth,\n"
                "    uint32_t *create_min_images, uint32_t *actual_images);\n"
            )
            assert text.count(patch) == 1, "API-391 diagnostics header extension changed"
            data = text.replace(patch, "", 1).encode("utf-8")
        elif relative == "libplacebo/config.h":
            text = data.decode("utf-8")
            changes = (
                ("#define PL_HAVE_GLSLANG 1\n", "#undef PL_HAVE_GLSLANG\n"),
                ("#undef PL_HAVE_LCMS\n", "#define PL_HAVE_LCMS 1\n"),
                ("#undef PL_HAVE_SHADERC\n", "#define PL_HAVE_SHADERC 1\n"),
            )
            for runtime_line, sdk_line in changes:
                assert text.count(runtime_line) == 1, f"API-391 runtime config adaptation changed: {runtime_line.strip()}"
                text = text.replace(runtime_line, sdk_line, 1)
            data = text.encode("utf-8")
        assert hashlib.sha256(data).hexdigest() == digest, relative
    api391_config = (API_HEADERS[391] / "config.h").read_text(encoding="utf-8")
    api387_config = (API_HEADERS[387] / "config.h").read_text(encoding="utf-8")
    for feature in ("PL_HAVE_GLSLANG", "PL_HAVE_LCMS", "PL_HAVE_SHADERC", "PL_HAVE_OPENGL", "PL_HAVE_VULKAN"):
        pattern = rf"^#(?:define|undef) {feature}(?:\s+1)?$"
        api391_state = re.findall(pattern, api391_config, re.MULTILINE)
        api387_state = re.findall(pattern, api387_config, re.MULTILINE)
        assert len(api391_state) == len(api387_state) == 1, f"missing {feature} build feature macro"
        assert api391_state == api387_state, f"API-391 {feature} config must match the shared runtime profile"
    template = API_HEADERS[391] / "config.h.in"
    assert hashlib.sha256(template.read_bytes()).hexdigest() == "85f4b07a4e6699029437a421320c1018882e066bad00618a8cd9b80023f2c9ab"
    actual_source_files = {
        path.relative_to(include_root).as_posix()
        for path in API_HEADERS[391].parent.joinpath("libplacebo").rglob("*")
        if path.is_file() and path.name != "config.h.in"
    }
    assert actual_source_files == set(source_rows), (actual_source_files ^ set(source_rows))


# The selector must be driven by exported symbols, not architecture or a fixed config header.
assert "MPVFLOW_PLACEBO_API_VERSIONS" in CMAKE
assert "pl_log_create_([0-9]+)" in CMAKE
assert "MPVFLOW_PLACEBO_API_COUNT" in CMAKE
assert "Unsupported libplacebo logging ABI" in CMAKE
assert "MPVFLOW_PLACEBO_INCLUDE_DIR" in CMAKE
assert "MPVFLOW_PLACEBO_CONFIG_HEADER" in CMAKE
assert "pl_vulkan_swapchain_set_diagnostics" in CMAKE
assert "pl_vulkan_swapchain_get_diagnostics" in CMAKE
assert 'MATCHES "^(387|391)$"' in CMAKE
assert "does not export ${MPVFLOW_REQUIRED_PLACEBO_SYMBOL}" in CMAKE
assert '"${MPVFLOW_PLACEBO_INCLUDE_DIR}"' in CMAKE
assert '"${CMAKE_CURRENT_SOURCE_DIR}/third_party/libplacebo/include"' in CMAKE
assert '"${CMAKE_CURRENT_SOURCE_DIR}/third_party/libplacebo/include-api-387"' in CMAKE
assert '"${CMAKE_CURRENT_SOURCE_DIR}/third_party/libplacebo/include-api-391"' in CMAKE
assert 'elseif(MPVFLOW_DETECTED_PLACEBO_API_VER STREQUAL "391")' in CMAKE
assert "header/API mismatch" in CMAKE

# Both plain and ELF symbol-version-suffixed forms map to exact headers.
for api in (374, 387, 391):
    assert config_api(select_header(f"00000000 T pl_log_create_{api}")) == api
assert select_header("00000000 T pl_log_create_387@LIBPLACEBO_7.387") == API_HEADERS[387]
assert select_header("00000000 T pl_log_create_391@LIBPLACEBO_7.391") == API_HEADERS[391]

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

required_headers = (
    "config.h",
    "config.h.in",
    "common.h",
    "log.h",
    "renderer.h",
    "shaders.h",
    "vulkan.h",
)
for api in (387, 391):
    for name in required_headers:
        assert (API_HEADERS[api] / name).is_file(), f"missing API-{api} header {name}"
    config = (API_HEADERS[api] / "config.h").read_text(encoding="utf-8")
    assert "PL_HAVE_FONGMI_VULKAN_YCBCR" in config
    assert "PL_HAVE_FONGMI_CHECKED_DOVI" in config
    vulkan_header = (API_HEADERS[api] / "vulkan.h").read_text(encoding="utf-8")
    assert "pl_vulkan_swapchain_set_diagnostics" in vulkan_header
    assert "pl_vulkan_swapchain_get_diagnostics" in vulkan_header

assert config_api(API_HEADERS[374]) == 374
assert_snapshot_hashes(387)
assert_snapshot_hashes(391)
assert_api391_upstream_snapshot()
assert_fongmi_struct_layouts()

print("libplacebo API 374/387/391 selection and FongMi header/runtime ABI contracts OK")
