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
    ffmpeg_runtime_version,
    verify_runtime_evidence,
)


FONGMI_SHA = "f2c5162b7c4b3e8835de270ff78cecc524decad1"
FONGMI_VERSION = f"git-2026-11-10-f2c5162-mpvflow-fongmi-{FONGMI_SHA}"
BASE_RUNTIME = """FFmpeg version n9.0.2
--target-os=android --enable-version3 --disable-everything --enable-decoder=dca
libavcodec license: LGPL version 3 or later
DCA (DTS Coherent Acoustics)
"""
FONGMI_RUNTIME = BASE_RUNTIME.replace("n9.0.2", FONGMI_VERSION).replace(
    "--enable-version3", "--disable-gpl --disable-nonfree --enable-version3", 1
)


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

    def test_pinned_fongmi_runtime_requires_the_exact_source_marker(self) -> None:
        flags = verify_runtime_evidence(FONGMI_RUNTIME)
        self.assertEqual("n9.1-FongMi-f2c5162b", ffmpeg_runtime_version(FONGMI_RUNTIME))
        self.assertEqual({"CONFIG_GPL": 0, "CONFIG_NONFREE": 0}, flags)

    def test_unpinned_or_wrong_ffmpeg_markers_are_rejected(self) -> None:
        for version in (
            "n8.0.2",
            "n9.1.0",
            "n9.2.0",
            "n9.10.0",
            "9.1.git",
            f"n8.0.2-mpvflow-fongmi-{FONGMI_SHA}",
            f"n9.1.0-mpvflow-fongmi-{FONGMI_SHA}",
            f"9.1.git-mpvflow-fongmi-{FONGMI_SHA}",
            f"git-2026-11-10-deadbee-mpvflow-fongmi-{FONGMI_SHA}",
            f"garbage-mpvflow-fongmi-{FONGMI_SHA}",
            "git-2026-11-10-f2c5162-mpvflow-fongmi-wrongcommit",
        ):
            with self.subTest(version=version):
                runtime = BASE_RUNTIME.replace("n9.0.2", version)
                with self.assertRaisesRegex(RuntimeEvidenceError, "exact FongMi version marker"):
                    verify_runtime_evidence(runtime)

    def test_runtime_requires_a_version_marker(self) -> None:
        runtime = BASE_RUNTIME.replace("FFmpeg version n9.0.2\n", "")
        with self.assertRaisesRegex(RuntimeEvidenceError, "found none"):
            verify_runtime_evidence(runtime)

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
