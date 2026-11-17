#!/usr/bin/env python3
"""Bounded consistency checks for the third-party source manifests."""
from __future__ import annotations

import hashlib
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCS = (ROOT / "THIRD_PARTY_SOURCE.md", ROOT / "app/src/main/assets/licenses/third-party-source.md")
VULKAN_HEADERS_SHA = "a4f8ada9f4f97c45b8c89c57997be9cebaae65d2"
MPV_SHA = "645f8ecdd3c94379048e9ee3e7014e174de0bd02"
PLACEBO_SHA = "63659d96d1bff7d7a5a1368e9538056a193fc6fb"
PLACEBO_API_387_SHA = "b89c10e1bdd5d7742ca576ebad94a906ddca91e8"
MOTION_RAW_SHA256 = "756c1da75d274d81c2e84f988bae1785aa6b6a9a813d16d3b47d9656b31016d0"
MOTION_DOWNSAMPLE_SHA256 = "793ac79e8243727bb58286ca6bb6a6b6e539ba3e8abae34c4dae7b6503c5cb2e"
MOTION_PREPARED_SHA256 = "1c0931fd519176ef6280079fb95ded0a6b58d9384f2067166d9bc5e86f4a6c8a"


def test_manifests_match_and_cover_pins() -> None:
    """Keep both copies identical and retain source pins and recipient rights."""
    texts = [path.read_text(encoding="utf-8") for path in DOCS]
    assert texts[0] == texts[1]
    text = texts[0]
    for needle in (
        "build-mpv-lgpl-aar.sh",
        "build-mpvflow-mpv-runtime.sh",
        "Lua 5.2.4",
        "b9e2e4aad6789b3b63a056d442f7b39f0ecfca3ae0f1fc0ae4e9614401b69f4b",
        "OSUOSL mirror",
        "f5bcfb195412e0ca733eac2e850879cd3b1ded18",
        MPV_SHA,
        PLACEBO_SHA,
        PLACEBO_API_387_SHA,
        MOTION_RAW_SHA256,
        MOTION_DOWNSAMPLE_SHA256,
        MOTION_PREPARED_SHA256,
        "12fedd08063a540b69667a51e146d539ebae509b63c07964f573410a7d0ae427",
        "FongMi/libplacebo",
        "FongMi libplacebo API-387 header snapshot",
        "FongMi libplacebo API-391 verifier-only marker",
        "FongMi libplacebo API-391 Native header snapshot",
        "fc7190babf851b8744eac04efa29f733a1ad9c86",
        "shader-mixer API required by MPV 645",
        "paired-evidence contour resolver",
        "scripts/libplacebo-api-391-config.h",
        "not a complete header set",
        "MPVRX_SDK_HEADERS.sha256",
        "include-api-391",
        "49bfdf107bb3690f7235102f5cfc5ccd3b574bd0bd5113879d99fd377fa0d48a",
        "pl_render_params=312",
        "frame_interp_params` offset 48",
        "-Dglslang=enabled",
        "shaderc and LCMS remain undefined",
        "pl_log_create_391",
        "include-api-387",
        "pl_desc=40",
        "pl_shader_desc=72",
        "pl_vulkan_swapchain_set_diagnostics",
        "pl_vulkan_swapchain_get_diagnostics",
        "FongMi/mpv",
        "FongMi/FFmpeg",
        "f2c5162b7c4b3e8835de270ff78cecc524decad1",
        "Shared FFmpeg runtime in the experimental arm64 overlay",
        "8852ac88cd5831b2e7cdb1a19e05bf9e458db63f",
        "accepts the checked-in FFmpeg n9.0 marker or only the exact full FongMi version marker",
        "git-2026-11-10-f2c5162-mpvflow-fongmi-f2c5162b7c4b3e8835de270ff78cecc524decad1",
        "Vulkan-Headers",
        VULKAN_HEADERS_SHA,
        "vulkan-sdk-1.4.328.0",
        "include/vulkan",
        "include/vk_video",
        "prepare-mpvflow-vulkan-headers.py",
        "Apache-2.0",
        "MIT",
        "hzw1199/Android-FFmpeg-Prebuilt",
        "Python-3.13.12.tar.xz",
        "debugging modifications",
        "relink",
        "not a claim that every distributed binary is byte-for-byte reproducible",
        "it does not impose LGPL-style corresponding-source or relinking obligations",
    ):
        assert needle in text, needle
    assert "source change" in text or "source-change" in text


def _manifest_bytes(path: Path) -> bytes:
    if path.is_file():
        return path.read_bytes()
    relative = str(path.relative_to(ROOT))
    index_entry = subprocess.run(
        ["git", "ls-files", "-v", "--", relative],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    if index_entry.returncode == 0 and index_entry.stdout.startswith("S "):
        blob = subprocess.run(
            ["git", "show", f"HEAD:{relative}"],
            cwd=ROOT,
            check=False,
            capture_output=True,
        )
        if blob.returncode == 0:
            return blob.stdout
    raise AssertionError(f"manifest file is missing outside sparse checkout: {path}")


def test_manifest_hashes_match_files() -> None:
    """Verify the recorded inventory describes the actual checked-in binaries."""
    text = DOCS[0].read_text(encoding="utf-8")
    rows = re.findall(r"\| `([^`]+)` \|[^|]*\| `([0-9a-f]{64})` \|", text)
    assert len(rows) == 9, rows
    for relative, expected in rows:
        path = ROOT / relative
        actual = hashlib.sha256(_manifest_bytes(path)).hexdigest()
        assert actual == expected, (relative, actual, expected)


def test_source_links_are_location_independent() -> None:
    """Require usable HTTPS links in both root and APK-packaged manifests."""
    text = DOCS[0].read_text(encoding="utf-8")
    targets = re.findall(r"\]\(([^)]+)\)", text)
    assert targets and all(target.startswith("https://") for target in targets)
    pin = "3c81ff6109d3f90f298d81805bbcc81824dbf735"
    experimental_branch = "fix/Mpv-infinity-cadence-phase-artifacts-20261117"
    for name in ("build-mpv-lgpl-aar.sh", "build-mpvflow-mpv-runtime.sh",
                 "prepare-mpvflow.py", "verify-mpv-aar-lgpl.py"):
        pinned_link = any(f"/blob/{pin}/scripts/{name}" in target for target in targets)
        experimental_link = name in ("build-mpvflow-mpv-runtime.sh", "prepare-mpvflow.py") and any(
            f"/blob/{experimental_branch}/scripts/{name}" in target for target in targets
        )
        assert pinned_link or experimental_link, name
    assert any("/buildscripts/patches/mpv_video_shaders.patch" in target for target in targets)
    assert any("github.com/FongMi/FFmpeg/tree/f2c5162b7c4b3e8835de270ff78cecc524decad1" in target for target in targets)
    assert any(
        f"github.com/FongMi/libplacebo/tree/{PLACEBO_API_387_SHA}/src/include/libplacebo" in target
        for target in targets
    )
    assert any(f"github.com/FongMi/mpv/tree/{MPV_SHA}" in target for target in targets)
    assert any(f"github.com/FongMi/libplacebo/tree/{PLACEBO_SHA}" in target for target in targets)
    assert any(
        f"github.com/FongMi/libplacebo/blob/{PLACEBO_SHA}/src/include/libplacebo/config.h.in" in target
        for target in targets
    )
    assert any("github.com/Riteshp2001/mpvlibAndroid/releases/tag/v1.1.8.2" in target for target in targets)
    assert any(f"github.com/FongMi/libplacebo/blob/{PLACEBO_SHA}/meson.build" in target for target in targets)
    experimental_branch = "fix/Mpv-infinity-local-warp-trust-20261117"
    assert any(f"/blob/{experimental_branch}/scripts/libplacebo-api-391-config.h" in target for target in targets)
    assert any(f"/blob/{experimental_branch}/scripts/verify-mpv-aar-lgpl.py" in target for target in targets)
    assert any(f"github.com/KhronosGroup/Vulkan-Headers/tree/{VULKAN_HEADERS_SHA}" in target for target in targets)
    assert any(f"github.com/KhronosGroup/Vulkan-Headers/archive/{VULKAN_HEADERS_SHA}.tar.gz" in target for target in targets)


def test_media3_runtime_notices_match_fongmi_overlay() -> None:
    """Keep source and packaged Media3 notices accurate for the experimental shared runtime."""
    notices = (
        ROOT / "app/src/main/assets/licenses/media3/NOTICE.txt",
        ROOT / "media3-decoder-ffmpeg/NOTICE",
    )
    for path in notices:
        text = _manifest_bytes(path).decode("utf-8")
        assert "FFmpeg n9.0" in text
        assert "release-9.1-fongmi" in text
        assert "f2c5162b7c4b3e8835de270ff78cecc524decad1" in text
        assert "https://github.com/FongMi/FFmpeg/tree/f2c5162b7c4b3e8835de270ff78cecc524decad1" in text
        assert "MPVFlow arm64 overlay" in text
        assert "MPVFlow arm64 build pins n9.0.2" not in text
        assert "LGPL-3.0-or-later" in text
        assert "DCA decoder" in text


if __name__ == "__main__":
    test_manifests_match_and_cover_pins()
    test_manifest_hashes_match_files()
    test_source_links_are_location_independent()
    test_media3_runtime_notices_match_fongmi_overlay()
    print("third-party source manifests verified")
