#!/usr/bin/env python3
"""Regression tests for task-time Android NDK path resolution."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from android_ndk_path import resolve_android_ndk  # noqa: E402


NDK_VERSION = "27.3.13750724"


class AndroidNdkPathTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        self.project = self.root / "project"
        self.project.mkdir()

    def make_ndk(self, sdk: Path) -> Path:
        ndk = sdk / "ndk" / NDK_VERSION
        (ndk / "toolchains" / "llvm").mkdir(parents=True)
        return ndk.resolve()

    def test_resolves_pinned_ndk_from_android_home(self) -> None:
        sdk = self.root / "Android SDK"
        expected = self.make_ndk(sdk)
        self.assertEqual(
            expected,
            resolve_android_ndk(self.project, NDK_VERSION, {"ANDROID_HOME": str(sdk)}),
        )

    def test_falls_back_to_android_sdk_root(self) -> None:
        sdk = self.root / "sdk-root"
        expected = self.make_ndk(sdk)
        self.assertEqual(
            expected,
            resolve_android_ndk(
                self.project, NDK_VERSION, {"ANDROID_SDK_ROOT": str(sdk)}
            ),
        )

    def test_reads_sdk_dir_from_local_properties(self) -> None:
        sdk = self.root / "local sdk"
        expected = self.make_ndk(sdk)
        (self.project / "local.properties").write_text(
            f"sdk.dir={sdk}\n", encoding="utf-8"
        )
        self.assertEqual(expected, resolve_android_ndk(self.project, NDK_VERSION, {}))

    def test_prefers_configured_local_sdk_when_both_sources_exist(self) -> None:
        local_sdk = self.root / "local-sdk"
        env_sdk = self.root / "env-sdk"
        expected = self.make_ndk(local_sdk)
        self.make_ndk(env_sdk)
        (self.project / "local.properties").write_text(
            f"sdk.dir={local_sdk}\n", encoding="utf-8"
        )
        self.assertEqual(
            expected,
            resolve_android_ndk(self.project, NDK_VERSION, {"ANDROID_HOME": str(env_sdk)}),
        )

    def test_skips_sdk_roots_without_the_pinned_ndk(self) -> None:
        empty_sdk = self.root / "empty-sdk"
        installed_sdk = self.root / "installed-sdk"
        expected = self.make_ndk(installed_sdk)
        self.assertEqual(
            expected,
            resolve_android_ndk(
                self.project,
                NDK_VERSION,
                {
                    "ANDROID_HOME": str(empty_sdk),
                    "ANDROID_SDK_ROOT": str(installed_sdk),
                },
            ),
        )

    def test_reports_missing_sdk_path(self) -> None:
        with self.assertRaisesRegex(FileNotFoundError, "Android SDK path unavailable"):
            resolve_android_ndk(self.project, NDK_VERSION, {})

    def test_reports_missing_pinned_ndk(self) -> None:
        sdk = self.root / "sdk"
        sdk.mkdir()
        with self.assertRaisesRegex(FileNotFoundError, "Pinned Android NDK"):
            resolve_android_ndk(self.project, NDK_VERSION, {"ANDROID_HOME": str(sdk)})

    def test_rejects_path_traversal_as_ndk_version(self) -> None:
        with self.assertRaisesRegex(ValueError, "Invalid pinned Android NDK version"):
            resolve_android_ndk(self.project, "../ndk", {"ANDROID_HOME": str(self.root)})

    def test_gradle_task_does_not_access_agp_sdk_components(self) -> None:
        gradle_file = (ROOT / "media3-decoder-ffmpeg/build.gradle.kts").read_text(
            encoding="utf-8"
        )
        prepare_script = (ROOT / "scripts/prepare-media3-dca-native.sh").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("android.sdkComponents.ndkDirectory", gradle_file)
        self.assertIn('"androidNdkVersion"', gradle_file)
        self.assertIn("android_ndk_path.py", prepare_script)


if __name__ == "__main__":
    unittest.main()
