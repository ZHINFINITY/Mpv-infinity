#!/usr/bin/env python3
"""Static checks for the MPVRx-pinned MPV runtime and its source safeguards."""
from __future__ import annotations

import hashlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = (ROOT / "scripts/build-mpvflow-mpv-runtime.sh").read_text(encoding="utf-8")
PREPARE = (ROOT / "scripts/prepare-mpvflow.py").read_text(encoding="utf-8")
SOURCE_VERIFIER = (ROOT / "scripts/mpvrx_motion_source.py").read_text(encoding="utf-8")
SOURCE_TEST = (ROOT / "scripts/tests/mpvrx_motion_source_test.py").read_text(encoding="utf-8")
VULKAN_HEADERS = (ROOT / "scripts/prepare-mpvflow-vulkan-headers.py").read_text(encoding="utf-8")
WORKFLOWS = [
    (ROOT / ".github/workflows/debug-apk.yml").read_text(encoding="utf-8"),
    (ROOT / ".github/workflows/mpvflow-debug-arm64.yml").read_text(encoding="utf-8"),
    (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8"),
]
MANIFEST = (ROOT / "THIRD_PARTY_SOURCE.md").read_text(encoding="utf-8")
PACKAGED = (ROOT / "app/src/main/assets/licenses/third-party-source.md").read_text(encoding="utf-8")
LICENSES = (ROOT / "app/src/main/assets/mpv/licenses/README.md").read_text(encoding="utf-8")
GPL_LICENSE = ROOT / "app/src/main/assets/mpv/licenses/LICENSE.GPL"
MPV_VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")
MEDIA3_NATIVE = (ROOT / "app/src/main/cpp/media3flow_vulkan_jni.cpp").read_text(encoding="utf-8")
CMAKE = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
PATCH_DIR = ROOT / "scripts/patches/mpvlibandroid-fongmi"
PATCHER = (PATCH_DIR / "patch-libbluray.sh").read_text(encoding="utf-8")
PATCH_README = (PATCH_DIR / "README.md").read_text(encoding="utf-8")

MPV_SHA = "95ec8e929f8ad2f97c8415c486971493a0b408df"
PLACEBO_SHA = "63659d96d1bff7d7a5a1368e9538056a193fc6fb"
MOTION_SHA256 = "ca7870c21f7a499d03ab1f7d73f1b9bcbccee534398d8a6f01957556e7d61932"
MOTION_HEADER_SHA256 = "12fedd08063a540b69667a51e146d539ebae509b63c07964f573410a7d0ae427"
BUILDER_SHA = "1efc82739f6f7034cd63e7ee8b3ec5431df27467"
MPVLIB_ANDROID_SHA = "d04217abe8e72e4a099269f045855bf69b87d53b"
FFMPEG_SHA = "f2c5162b7c4b3e8835de270ff78cecc524decad1"
VULKAN_HEADERS_SHA = "a4f8ada9f4f97c45b8c89c57997be9cebaae65d2"
GPL_SHA = "edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6"
PATCH_HASHES = {
    "libbluray-1.4.1-still-eof.patch": "cc0a15ee88b1ead665dadbc3ac363c2686e5184fc1e04a76713665cad57c0fc7",
    "libbluray-1.4.1-checked-seek.patch": "1055d99f0940b3077e9f7171a220f242396e6bf5196415f3fc987b316d51447c",
    "libbluray-1.4.1-menu-info.patch": "17a1b88c72c3b467ba6ac8d9419f003c93f694733b9214deee13bad5ebfedb5c",
    "libbluray-1.4.1-bdj-pointer.patch": "232ce1e634335af5d69a705745a57ea5cd5da8d7160b9441b5ba154e7b3dec85",
}

assert f'EXPECTED_MPV_REVISION="{MPV_SHA}"' in BUILD
assert f'libplacebo_revision = "{PLACEBO_SHA}"' in BUILD
assert f'EXPECTED_BUILDER_REVISION="{BUILDER_SHA}"' in BUILD
assert FFMPEG_SHA in BUILD and VULKAN_HEADERS_SHA in VULKAN_HEADERS
assert 'python3 "$MPVFLOW_ROOT/scripts/prepare-mpvflow.py" --mpv-dir deps/mpv --libplacebo-dir deps/libplacebo' in BUILD
assert "patch_fongmi_motion_artifact.py" not in BUILD
assert "motion_artifact_patch" not in BUILD
assert "verify_mpv_source(mpv_dir)" in PREPARE
assert "MOTION_PASS_PREPARE" in PREPARE and "pl_dispatch_prepare_compute" in PREPARE
assert "mp_motion_render_owned" in PREPARE and "mp_motion_capture_supported" in PREPARE
assert "MPVFLOW_DEBUG_CAPTURE is unsupported with the MPVRx-pinned FongMi 95ec motion core" in PREPARE
assert MPV_SHA in SOURCE_VERIFIER and MOTION_SHA256 in SOURCE_VERIFIER
assert MOTION_HEADER_SHA256 in SOURCE_VERIFIER
assert '"mp_motion_create(p->gpu, 480);" in vo' in SOURCE_VERIFIER
assert '"info->queue_display_time_only = true;" in android_context' in SOURCE_VERIFIER
assert "verify_mpv_source(args.mpv_dir)" in SOURCE_TEST

for marker in (
    "MPVFLOW_MPV_BUILD_CORES",
    "dep_mpv=(ffmpeg libass lua libplacebo libbluray)",
    "v_libbluray=1.4.1",
    "-Dgpl=false",
    "-Ddvdnav=disabled",
    "-Ddefault_library=static",
):
    assert marker in BUILD, marker

for workflow in WORKFLOWS:
    for marker in (
        MPV_SHA,
        PLACEBO_SHA,
        FFMPEG_SHA,
        VULKAN_HEADERS_SHA,
        "scripts/mpvrx_motion_source.py",
        "scripts/prepare-mpvflow.py",
        "scripts/patches/mpvlibandroid-fongmi/**",
        "mpvflow-arm64-runtime-v7",
    ):
        assert marker in workflow, marker
    assert "scripts/patch_fongmi_motion_artifact.py" not in workflow
    assert "MPVFLOW_DEBUG_CAPTURE" not in workflow
    assert "mpvFlowFongmiDiagnosticCapture=true" not in workflow
    assert "fongmi-diagnostic-capture-v1" not in workflow
    assert "runtime-legacy" not in workflow
    assert "c1529642089bfebfc928a1c1664638a7a5d219ba" not in workflow
assert "scripts/tests/mpvrx_motion_source_test.py" in WORKFLOWS[0]
assert "scripts/tests/mpvrx_motion_source_test.py" in WORKFLOWS[1]

assert MANIFEST == PACKAGED
for text in (MANIFEST, LICENSES):
    for marker in (MPV_SHA, PLACEBO_SHA, FFMPEG_SHA):
        assert marker in text, marker
    assert "FongMi/mpv" in text and "FongMi/libplacebo" in text
    assert "GPL-2.0" in text
    assert "does not apply" in text or "No local" in text
for marker in (MOTION_SHA256, MOTION_HEADER_SHA256):
    assert marker in MANIFEST
for patch_name, expected_hash in PATCH_HASHES.items():
    patch_path = PATCH_DIR / patch_name
    assert patch_path.is_file()
    assert hashlib.sha256(patch_path.read_bytes()).hexdigest() == expected_hash
    assert expected_hash in MANIFEST
assert MPVLIB_ANDROID_SHA in PATCH_README
assert "patch_fongmi_motion_artifact.py" not in MANIFEST
assert "patch_fongmi_motion_artifact.py" not in LICENSES
assert "FongMi/FFmpeg" in LICENSES and "release-9.1-fongmi" in LICENSES
assert f"https://github.com/FongMi/FFmpeg/commit/{FFMPEG_SHA}" in (ROOT / "app/src/main/java/app/infinity/mpvz/ui/preferences/AboutScreen.kt").read_text(encoding="utf-8")
assert "LICENSE.GPL" in LICENSES
assert GPL_LICENSE.is_file() and hashlib.sha256(GPL_LICENSE.read_bytes()).hexdigest() == GPL_SHA
license_text = GPL_LICENSE.read_text(encoding="utf-8")
assert "GNU GENERAL PUBLIC LICENSE" in license_text and "Version 2, June 1991" in license_text

assert 'PlaybackSession.setOptionString("motion-interpolation", targetFps.toString())' in MPV_VIEW
assert not re.search(r'PlaybackSession\.setOptionString\(\s*"interpolation"', MPV_VIEW)
assert "mp_motion_" not in MEDIA3_NATIVE
assert "mpvflow_gpu_synthesize_with_policy_to_texture(" in MEDIA3_NATIVE
assert "mpvflow/fongmi/motion.c" not in CMAKE
print("MPVRx MPV motion source, 480px cap, build pins, MPV-only boundary, and source/licensing guards passed")
