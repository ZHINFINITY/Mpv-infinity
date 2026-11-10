#!/usr/bin/env python3
"""Static checks for the coordinated native FongMi MPV runtime upgrade."""
import hashlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PREPARE = (ROOT / "scripts/prepare-mpvflow.py").read_text(encoding="utf-8")
BUILD = (ROOT / "scripts/build-mpvflow-mpv-runtime.sh").read_text(encoding="utf-8")
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

MPV_SHA = "14b3999a1a3e322c217bc90ee4df5a8f1bc88f69"
BUILDER_SHA = "1efc82739f6f7034cd63e7ee8b3ec5431df27467"
PLACEBO_SHA = "275f7dea27924cdd34ab2e6c20e448b6bc4fab65"
GPL_SHA = "edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6"
MPVLIB_ANDROID_SHA = "d04217abe8e72e4a099269f045855bf69b87d53b"
PATCH_DIR = ROOT / "scripts/patches/mpvlibandroid-fongmi"
PATCHER = (PATCH_DIR / "patch-libbluray.sh").read_text(encoding="utf-8")
PATCH_README = (PATCH_DIR / "README.md").read_text(encoding="utf-8")
PATCH_HASHES = {
    "libbluray-1.4.1-still-eof.patch": "cc0a15ee88b1ead665dadbc3ac363c2686e5184fc1e04a76713665cad57c0fc7",
    "libbluray-1.4.1-checked-seek.patch": "1055d99f0940b3077e9f7171a220f242396e6bf5196415f3fc987b316d51447c",
    "libbluray-1.4.1-menu-info.patch": "17a1b88c72c3b467ba6ac8d9419f003c93f694733b9214deee13bad5ebfedb5c",
    "libbluray-1.4.1-bdj-pointer.patch": "232ce1e634335af5d69a705745a57ea5cd5da8d7160b9441b5ba154e7b3dec85",
}

assert "FONGMI_PATCH" not in PREPARE
assert "subprocess.run" not in PREPARE
assert "shutil.copy" not in PREPARE
for marker in (
    "version: '>=7.387.0'",
    "video/out/gpu_next/motion.c",
    "libmpv_motion = static_library('mpv-motion'",
    "video/out/hwdec/hwdec_aimagereader.c",
    "video/out/hwdec/hwdec_aimagereader_vk_direct.c",
    "mp_motion_prepare_view",
    "mp_motion_render_views",
):
    assert marker in PREPARE, marker
assert '{"motion-interpolation-info", mp_property_motion_info}' in PREPARE
assert 'mp.get_property_native("motion-interpolation-info")' in PREPARE
assert PLACEBO_SHA in BUILD
assert f'EXPECTED_MPV_REVISION="{MPV_SHA}"' in BUILD
assert f'EXPECTED_BUILDER_REVISION="{BUILDER_SHA}"' in BUILD
assert 'git -C "$MPV_SOURCE_DIR" rev-parse HEAD' in BUILD
assert 'git -C "$MPV_BUILDER_DIR" rev-parse HEAD' in BUILD
assert "-Dgpl=false" in BUILD and "-Ddvdnav=disabled" in BUILD
assert "-Ddefault_library=static" in BUILD
assert 'PlaybackSession.setOptionString("motion-interpolation", targetFps.toString())' in MPV_VIEW
assert not re.search(r'PlaybackSession\.setOptionString\(\s*"interpolation"', MPV_VIEW), "FongMi must not enable mpv's separate interpolation mixer"

patch_order = list(PATCH_HASHES)
for patch_name, expected_hash in PATCH_HASHES.items():
    patch_path = PATCH_DIR / patch_name
    assert patch_path.is_file(), f"missing MPV-RX FongMi patch: {patch_path}"
    assert hashlib.sha256(patch_path.read_bytes()).hexdigest() == expected_hash, patch_name
    assert expected_hash in PATCHER, f"patcher does not pin {patch_name}"
assert [PATCHER.index(name) for name in patch_order] == sorted(PATCHER.index(name) for name in patch_order)
assert "patch --batch --forward --fuzz=0 --dry-run" in PATCHER
assert "patch --batch --reverse --fuzz=0 --dry-run" in PATCHER
assert "bd_seek_time_checked" in PATCHER and "bd_get_titles_with_menu_info" in PATCHER
patch_call = '"$ROOT/scripts/patches/mpvlibandroid-fongmi/patch-libbluray.sh" "$LIBBLURAY_SOURCE"'
assert patch_call in BUILD
assert BUILD.index('tar -xJf "$LIBBLURAY_ARCHIVE"') < BUILD.index(patch_call) < BUILD.index('./include/ci.sh install')
assert MPVLIB_ANDROID_SHA in PATCH_README

for workflow in WORKFLOWS:
    assert MPV_SHA in workflow
    assert PLACEBO_SHA in workflow
    assert "scripts/patches/mpvlibandroid-fongmi/**" in workflow
    assert "repository: FongMi/mpv" in workflow
    assert "runtime-v3-builder" in workflow
    assert "runtime-legacy" not in workflow
    assert "c1529642089bfebfc928a1c1664638a7a5d219ba" not in workflow

for text in (MANIFEST, PACKAGED):
    assert MPV_SHA in text
    assert PLACEBO_SHA in text
    assert MPVLIB_ANDROID_SHA in text
    for patch_name, expected_hash in PATCH_HASHES.items():
        assert patch_name in text and expected_hash in text
    assert "https://github.com/FongMi/mpv" in text
    assert "GPL-2.0" in text
    assert "does not apply the obsolete local patch or duplicate source files" in text

assert MPV_SHA in LICENSES
assert PLACEBO_SHA in LICENSES
assert MPVLIB_ANDROID_SHA in LICENSES
assert "LICENSE.GPL" in LICENSES
assert GPL_LICENSE.is_file()
assert hashlib.sha256(GPL_LICENSE.read_bytes()).hexdigest() == GPL_SHA
gpl_text = GPL_LICENSE.read_text(encoding="utf-8")
assert "GNU GENERAL PUBLIC LICENSE" in gpl_text and "Version 2, June 1991" in gpl_text
print("native FongMi MPV source, libplacebo pin, libbluray patch set, provenance, and cache guards passed")
