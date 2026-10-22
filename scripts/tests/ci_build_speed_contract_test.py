#!/usr/bin/env python3
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
"""Speed optimizations must preserve native validation and non-target build defaults."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / '.github/workflows/debug-apk.yml').read_text()


def step(name):
    return WORKFLOW.split('      - name: ' + name + '\n', 1)[1].split('\n      - ', 1)[0]


class BuildSpeedContractTest(unittest.TestCase):
    def test_native_subtitle_uses_explicit_existing_target_only(self):
        build = (ROOT / 'subtitle-libass/build.gradle.kts').read_text()
        self.assertIn('providers.gradleProperty("targetAbi").orNull', build)
        self.assertIn('.orEmpty()', build)
        self.assertIn('ndk { abiFilters += requestedAbis }', build)
        self.assertNotIn('abiFilters += listOf("arm64-v8a")', build)

    def test_subtitle_build_tools_are_not_skipped_on_runtime_cache_hit(self):
        install = step('Install native subtitle build dependencies')
        self.assertNotIn('        if:', install)
        for package in ['autoconf', 'automake', 'libtool', 'gettext', 'autopoint', 'gperf', 'pkg-config']:
            self.assertIn(package, install)

    def test_host_vulkan_tests_keep_all_explicit_dependencies(self):
        install = step('Install native interpolation build dependencies')
        for package in ['ninja-build', 'cmake', 'pipx', 'nasm', 'yasm', 'binutils', 'zlib1g-dev',
                        'libssl-dev', 'zip', 'unzip', 'wget', 'curl', 'ffmpeg', 'glslang-tools',
                        'libvulkan-dev', 'mesa-vulkan-drivers']:
            self.assertIn(package, install)
        self.assertIn('sudo apt-get update', install)
        self.assertIn('--no-install-recommends', install)
        self.assertNotIn('--allow-unauthenticated', WORKFLOW)
        self.assertIn('MPVFLOW_REQUIRE_VULKAN_PIXEL_TEST=1 ./scripts/test-mpvflow-core.sh',
            step('Run focused interpolation regression tests'))

    def test_download_cache_is_platform_keyed_not_system_binary_cache(self):
        restore = step('Restore native APT download archive cache')
        self.assertIn('actions/cache/restore@v4', restore)
        self.assertIn('path: ${{ runner.temp }}/mpv-native-apt-archives/*.deb', restore)
        for field in ['runner.os', 'outputs.arch', 'outputs.os_id', 'outputs.version_id', 'outputs.image_version',
                      "hashFiles('.github/workflows/debug-apk.yml')"]:
            self.assertIn(field, restore)
        self.assertIn('actions/cache/save@v4', step('Save native APT download archive cache'))
        self.assertLess(WORKFLOW.index('- name: Save native APT download archive cache'),
            WORKFLOW.index('- name: Run focused interpolation regression tests'))

    def test_full_tests_build_and_all_artifact_gates_remain(self):
        self.assertIn('testStandardDebugUnitTest', step('Run Standard arm64 debug unit tests'))
        self.assertIn('--build-cache', step('Run Standard arm64 debug unit tests'))
        self.assertIn('assembleStandardDebug', step('Build debug APK'))
        self.assertIn('--build-cache', step('Build debug APK'))
        self.assertIn('verify-debug-apk-artifact.py', step('Verify APK package, ABI, integrity and signing identity'))
        self.assertIn('verify-mpv-aar-lgpl.py', step('Verify Flow-only LGPL MPV runtime'))
        self.assertIn('python3 scripts/tests/apk_signing_identity_test.py', WORKFLOW)


if __name__ == '__main__':
    unittest.main()
