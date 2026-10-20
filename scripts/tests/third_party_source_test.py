#!/usr/bin/env python3
"""Bounded consistency checks for the third-party source manifests."""
from __future__ import annotations

import hashlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCS = (ROOT / "THIRD_PARTY_SOURCE.md", ROOT / "app/src/main/assets/licenses/third-party-source.md")


def test_manifests_match_and_cover_pins() -> None:
    """Keep both copies identical and retain source pins and recipient rights."""
    texts = [path.read_text(encoding="utf-8") for path in DOCS]
    assert texts[0] == texts[1]
    text = texts[0]
    for needle in (
        "build-mpv-lgpl-aar.sh",
        "build-mpvflow-mpv-runtime.sh",
        "f5bcfb195412e0ca733eac2e850879cd3b1ded18",
        "c1529642089bfebfc928a1c1664638a7a5d219ba",
        "hzw1199/Android-FFmpeg-Prebuilt",
        "Python-3.13.12.tar.xz",
        "debugging modifications",
        "relink",
        "not a claim that every distributed binary is byte-for-byte reproducible",
        "it does not impose LGPL-style corresponding-source or relinking obligations",
    ):
        assert needle in text, needle


def test_manifest_hashes_match_files() -> None:
    """Verify the recorded inventory describes the actual checked-in binaries."""
    text = DOCS[0].read_text(encoding="utf-8")
    rows = re.findall(r"\| `([^`]+)` \|[^|]*\| `([0-9a-f]{64})` \|", text)
    assert len(rows) == 9, rows
    for relative, expected in rows:
        path = ROOT / relative
        assert path.is_file(), path
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        assert actual == expected, (relative, actual, expected)


def test_source_links_are_location_independent() -> None:
    """Require usable HTTPS links in both root and APK-packaged manifests."""
    text = DOCS[0].read_text(encoding="utf-8")
    targets = re.findall(r"\]\(([^)]+)\)", text)
    assert targets and all(target.startswith("https://") for target in targets)
    pin = "3c81ff6109d3f90f298d81805bbcc81824dbf735"
    for name in ("build-mpv-lgpl-aar.sh", "build-mpvflow-mpv-runtime.sh",
                 "prepare-mpvflow.py", "verify-mpv-aar-lgpl.py"):
        assert any(f"/blob/{pin}/scripts/{name}" in target for target in targets), name
    assert any("/buildscripts/patches/mpv_video_shaders.patch" in target for target in targets)


if __name__ == "__main__":
    test_manifests_match_and_cover_pins()
    test_manifest_hashes_match_files()
    test_source_links_are_location_independent()
    print("third-party source manifests verified")
