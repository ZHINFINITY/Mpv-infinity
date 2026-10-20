#!/usr/bin/env python3
"""Regression coverage for the FFmpeg decoder's app build-type variants."""
from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GRADLE_FILE = ROOT / "media3-decoder-ffmpeg/build.gradle.kts"


class Media3DecoderFfmpegVariantTest(unittest.TestCase):
    def test_preview_variant_inherits_release_configuration(self) -> None:
        gradle = GRADLE_FILE.read_text(encoding="utf-8")
        self.assertRegex(
            gradle,
            re.compile(
                r'buildTypes\s*\{\s*create\("preview"\)\s*\{'
                r'\s*initWith\(getByName\("release"\)\)\s*\}\s*\}',
                re.DOTALL,
            ),
        )


if __name__ == "__main__":
    unittest.main()
