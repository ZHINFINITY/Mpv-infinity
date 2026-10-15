#!/usr/bin/env python3
"""Regression tests for the Media3 DTS FFmpeg runtime license verifier."""

from __future__ import annotations

from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from ffmpeg_runtime_config import (  # noqa: E402
    RuntimeEvidenceError,
    effective_ffmpeg_flags,
    verify_runtime_evidence,
)


BASE_RUNTIME = """FFmpeg version n9.0.2
--target-os=android --enable-version3 --disable-everything --enable-decoder=dca
libavcodec license: LGPL version 3 or later
DCA (DTS Coherent Acoustics)
"""


class EffectiveRuntimeConfigTest(unittest.TestCase):
    def test_omitted_disable_switches_mean_gpl_and_nonfree_are_off(self) -> None:
        flags = effective_ffmpeg_flags(
            "--target-os=android --enable-version3 --disable-everything "
            "--enable-decoder=dca"
        )
        self.assertEqual({"CONFIG_GPL": 0, "CONFIG_NONFREE": 0}, flags)

    def test_explicit_disable_switches_are_off(self) -> None:
        flags = effective_ffmpeg_flags(
            "--target-os=android --disable-gpl --disable-nonfree --enable-version3"
        )
        self.assertEqual({"CONFIG_GPL": 0, "CONFIG_NONFREE": 0}, flags)

    def test_enable_switches_and_braced_enable_are_detected(self) -> None:
        self.assertEqual(
            {"CONFIG_GPL": 1, "CONFIG_NONFREE": 1},
            effective_ffmpeg_flags(
                "--target-os=android --enable-{gpl,version3} --enable-nonfree"
            ),
        )

    def test_runtime_without_disable_nonfree_marker_passes_with_effective_defaults(self) -> None:
        flags = verify_runtime_evidence(BASE_RUNTIME)
        self.assertEqual({"CONFIG_GPL": 0, "CONFIG_NONFREE": 0}, flags)

    def test_gpl_enabled_runtime_is_rejected(self) -> None:
        runtime = BASE_RUNTIME.replace(
            "--enable-version3", "--enable-gpl --enable-version3", 1
        )
        with self.assertRaisesRegex(RuntimeEvidenceError, "CONFIG_GPL=1"):
            verify_runtime_evidence(runtime)

    def test_nonfree_enabled_runtime_is_rejected(self) -> None:
        runtime = BASE_RUNTIME.replace(
            "--enable-version3", "--enable-nonfree --enable-version3", 1
        )
        with self.assertRaisesRegex(RuntimeEvidenceError, "CONFIG_NONFREE=1"):
            verify_runtime_evidence(runtime)

    def test_runtime_requires_dca_and_lgpl_evidence(self) -> None:
        with self.assertRaisesRegex(RuntimeEvidenceError, "DCA decoder marker"):
            verify_runtime_evidence(BASE_RUNTIME.replace("DCA (DTS Coherent Acoustics)\n", ""))
        with self.assertRaisesRegex(RuntimeEvidenceError, "LGPLv3-or-later"):
            verify_runtime_evidence(BASE_RUNTIME.replace("LGPL version 3 or later", "GPL version 3"))


if __name__ == "__main__":
    unittest.main()
