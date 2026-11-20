#!/usr/bin/env python3
"""Regression tests for the LGPL AAR verifier's API-aware libplacebo headers."""
from __future__ import annotations

from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = spec_from_file_location("verify_mpv_aar_lgpl", ROOT / "scripts/verify-mpv-aar-lgpl.py")
assert SPEC and SPEC.loader
verifier = module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


def verify_mocked(exports: str) -> None:
    original = verifier.run_tool
    verifier.run_tool = lambda _args: exports
    try:
        verifier.verify_placebo_logger_export("arm64-v8a", Path("mock-libplacebo.so"), "libplacebo.so")
    finally:
        verifier.run_tool = original


# Runtime APIs must map to their exact header configuration or verifier marker.
assert verifier.expected_placebo_api_ver("374") == "374"
assert verifier.expected_placebo_api_ver("387") == "387"
assert verifier.expected_placebo_api_ver("391") == "391"
api391_marker = verifier.PLACEBO_CONFIG_HEADERS["391"].read_text(encoding="utf-8")
assert "63659d96d1bff7d7a5a1368e9538056a193fc6fb" in api391_marker
assert "not a complete header set" in api391_marker
verify_mocked("00000000 T pl_log_create_374\n")
verify_mocked(
    "00000000 T pl_log_create_387@@LIBPLACEBO_7.387\n"
    "00000000 T pl_vulkan_swapchain_set_diagnostics\n"
    "00000000 T pl_vulkan_swapchain_get_diagnostics\n"
)
verify_mocked(
    "00000000 T pl_log_create_391@@LIBPLACEBO_7.391\n"
    "00000000 T pl_vulkan_swapchain_set_diagnostics\n"
    "00000000 T pl_vulkan_swapchain_get_diagnostics\n"
)

# Unknown, absent, or ambiguous APIs fail closed rather than guessing a header.
for exports in (
    "00000000 T pl_log_create_999\n",
    "00000000 T pl_vulkan_create\n",
    "00000000 T pl_log_create_374.trailing\n",
    "00000000 T pl_log_create_374\n00000000 T pl_log_create_387\n",
    "00000000 T pl_log_create_387\n"
    "00000000 T pl_vulkan_swapchain_set_diagnostics\n",
    "00000000 T pl_log_create_391\n"
    "00000000 T pl_vulkan_swapchain_set_diagnostics\n",
):
    try:
        verify_mocked(exports)
    except SystemExit:
        pass
    else:
        raise AssertionError(f"verifier unexpectedly accepted unsupported exports: {exports!r}")

# Preserve validation of the checked-in API-374 runtime profile as well.
aar = ROOT / "app/libs/mpvlib.aar"
with zipfile.ZipFile(aar) as archive:
    names = set(archive.namelist())
    candidates = (
        "jni/arm64-v8a/libplacebo.so",
        "jni/arm64-v8a/libmpv.so",
    )
    entry = next((name for name in candidates if name in names), None)
    assert entry, "checked-in AAR has no libplacebo API carrier for arm64-v8a"
    with tempfile.TemporaryDirectory(prefix="mpvflow-placebo-api-test-") as td:
        library = Path(td) / Path(entry).name
        library.write_bytes(archive.read(entry))
        verifier.verify_placebo_logger_export("arm64-v8a", library, Path(entry).name)

print("AAR libplacebo API 374/387/391 verifier profiles OK")
