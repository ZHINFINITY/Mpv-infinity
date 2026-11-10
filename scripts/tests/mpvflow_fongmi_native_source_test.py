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

for workflow in WORKFLOWS:
    assert MPV_SHA in workflow
    assert PLACEBO_SHA in workflow
    assert "repository: FongMi/mpv" in workflow
    assert "runtime-v3-builder" in workflow
    assert "runtime-legacy" not in workflow
    assert "c1529642089bfebfc928a1c1664638a7a5d219ba" not in workflow

for text in (MANIFEST, PACKAGED):
    assert MPV_SHA in text
    assert PLACEBO_SHA in text
    assert "https://github.com/FongMi/mpv" in text
    assert "GPL-2.0" in text
    assert "does not apply the obsolete local patch or duplicate source files" in text

assert MPV_SHA in LICENSES
assert PLACEBO_SHA in LICENSES
assert "LICENSE.GPL" in LICENSES
assert GPL_LICENSE.is_file()
assert hashlib.sha256(GPL_LICENSE.read_bytes()).hexdigest() == GPL_SHA
gpl_text = GPL_LICENSE.read_text(encoding="utf-8")
assert "GNU GENERAL PUBLIC LICENSE" in gpl_text and "Version 2, June 1991" in gpl_text
print("native FongMi MPV source, libplacebo pin, provenance, and cache guards passed")
