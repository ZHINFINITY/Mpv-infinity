#!/usr/bin/env python3
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
"""Speed optimizations must preserve native validation, full tests and APK gates."""
from pathlib import Path
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / '.github/workflows/debug-apk.yml').read_text()
JOBS = yaml.safe_load(WORKFLOW)['jobs']
APT = (ROOT / 'scripts/ci/apt-install-with-fallback.sh').read_text()


def job_steps(job):
    return {s['name']: s for s in JOBS[job]['steps']}


TESTS = job_steps('kotlin-tests')
BUILD = job_steps('build-debug')


class BuildSpeedContractTest(unittest.TestCase):
    def test_native_subtitle_uses_explicit_existing_target_only(self):
        build = (ROOT / 'subtitle-libass/build.gradle.kts').read_text()
        self.assertIn('providers.gradleProperty("targetAbi").orNull', build)
        self.assertIn('.orEmpty()', build)
        self.assertIn('ndk { abiFilters += requestedAbis }', build)
        self.assertNotIn('abiFilters += listOf("arm64-v8a")', build)

    def test_subtitle_build_tools_are_not_skipped_on_runtime_cache_hit(self):
        install = BUILD['Install native subtitle build dependencies']
        self.assertNotIn('if', install)
        self.assertIn('scripts/ci/apt-install-with-fallback.sh', install['run'])
        for package in ['autoconf', 'automake', 'libtool', 'gettext', 'autopoint', 'gperf', 'pkg-config']:
            self.assertIn(package, install['run'])

    def test_host_vulkan_tests_keep_all_explicit_dependencies(self):
        install = BUILD['Install native interpolation build dependencies']['run']
        self.assertIn('scripts/ci/apt-install-with-fallback.sh', install)
        for package in ['ninja-build', 'cmake', 'pipx', 'nasm', 'yasm', 'binutils', 'zlib1g-dev',
                        'libssl-dev', 'zip', 'unzip', 'wget', 'curl', 'ffmpeg', 'glslang-tools',
                        'libvulkan-dev', 'mesa-vulkan-drivers']:
            self.assertIn(package, install)
        self.assertNotIn('--allow-unauthenticated', WORKFLOW + APT)
        self.assertIn('MPVFLOW_REQUIRE_VULKAN_PIXEL_TEST=1 ./scripts/test-mpvflow-core.sh',
                      BUILD['Run focused interpolation regression tests']['run'])

    def test_apt_network_phases_are_bounded_with_mirror_fallback_and_safe_install(self):
        self.assertIn('sudo apt-get update', APT)
        self.assertIn('--no-install-recommends', APT)
        self.assertIn('--download-only', APT)
        self.assertIn('timeout --kill-after=15 "$update_timeout"', APT)
        self.assertIn('timeout --kill-after=15 "$download_timeout"', APT)
        self.assertIn('Acquire::http::Timeout=20', APT)
        self.assertIn('http://archive.ubuntu.com/ubuntu/', APT)
        self.assertIn('http://mirrors.edge.kernel.org/ubuntu/', APT)
        self.assertIn('mirror\\+file:/etc/apt/apt-mirrors\\.txt', APT)
        # The dpkg install itself is never killed by a timeout.
        final = APT.rstrip().splitlines()[-1]
        self.assertTrue(final.startswith('sudo apt-get install -y -q --no-install-recommends'), final)
        self.assertNotIn('timeout', final)
        self.assertTrue((ROOT / 'scripts/ci/apt-install-with-fallback.sh').stat().st_mode & 0o111)

    def test_download_cache_is_platform_keyed_with_release_level_fallback(self):
        restore = BUILD['Restore native APT download archive cache']
        self.assertEqual('actions/cache/restore@v4', restore['uses'])
        self.assertEqual('${{ runner.temp }}/mpv-native-apt-archives/*.deb', restore['with']['path'])
        for field in ['runner.os', 'outputs.arch', 'outputs.os_id', 'outputs.version_id', 'outputs.image_version',
                      "hashFiles('.github/workflows/debug-apk.yml')"]:
            self.assertIn(field, restore['with']['key'])
        fallback = restore['with']['restore-keys'].strip()
        self.assertTrue(fallback.endswith('outputs.version_id }}-'), fallback)
        self.assertNotIn('image_version', fallback)
        self.assertEqual('actions/cache/save@v4', BUILD['Save native APT download archive cache']['uses'])
        self.assertLess(WORKFLOW.index('- name: Save native APT download archive cache'),
                        WORKFLOW.index('- name: Run focused interpolation regression tests'))

    def test_kotlin_compile_fails_fast_in_a_parallel_job_without_native_dependencies(self):
        test_job = JOBS['kotlin-tests']
        self.assertEqual('Kotlin compile and full unit tests', test_job['name'])
        self.assertNotIn('needs', test_job)
        self.assertNotIn('needs', JOBS['build-debug'])
        names = list(TESTS)
        compile_step = TESTS['Fail-fast compile of all main and unit-test Kotlin']['run']
        self.assertIn(':app:compileStandardDebugKotlin', compile_step)
        self.assertIn(':app:compileStandardDebugUnitTestKotlin', compile_step)
        self.assertIn('-PmpvFlowMpvBuild=true', compile_step)
        self.assertLess(names.index('Fail-fast compile of all main and unit-test Kotlin'),
                        names.index('Run Standard arm64 debug unit tests'))
        for name in names:
            self.assertNotIn('apt-install', str(TESTS[name].get('run', '')), name)
        # All source contract suites run here, early, instead of after native setup.
        self.assertTrue(any(n.startswith('Test ') for n in names))
        self.assertFalse(any(n.startswith('Test ') for n in BUILD))
        cancel = TESTS['Cancel the parallel APK build after a test or compile failure']
        self.assertEqual('${{ failure() }}', cancel['if'])
        self.assertIn('gh run cancel "$GITHUB_RUN_ID"', cancel['run'])
        self.assertEqual('write', test_job['permissions']['actions'])

    def test_apk_is_published_only_after_the_full_test_job_succeeds(self):
        names = list(BUILD)
        gate = BUILD['Wait for fail-fast compile and full unit tests before publishing APK']
        self.assertIn('select(.name == "Kotlin compile and full unit tests")', gate['run'])
        self.assertIn('"completed success") break', gate['run'])
        self.assertNotIn('if', gate)
        self.assertLess(names.index('Verify APK package, ABI, integrity and signing identity'),
                        names.index('Wait for fail-fast compile and full unit tests before publishing APK'))
        self.assertLess(names.index('Wait for fail-fast compile and full unit tests before publishing APK'),
                        names.index('Upload debug APKs'))
        self.assertEqual('read', JOBS['build-debug']['permissions']['actions'])

    def test_full_tests_build_and_all_artifact_gates_remain(self):
        unit = TESTS['Run Standard arm64 debug unit tests']['run']
        self.assertIn('testStandardDebugUnitTest', unit)
        self.assertIn('--build-cache', unit)
        self.assertIn('combined-radio-ui-validation-evidence',
                      TESTS['Upload combined validation evidence']['with']['name'])
        self.assertIn('assembleStandardDebug', BUILD['Build debug APK']['run'])
        self.assertIn('--build-cache', BUILD['Build debug APK']['run'])
        self.assertIn('verify-debug-apk-artifact.py', BUILD['Verify APK package, ABI, integrity and signing identity']['run'])
        self.assertIn('verify-mpv-aar-lgpl.py', BUILD['Verify Flow-only LGPL MPV runtime']['run'])
        for name in ['Verify Flow-only arm64 APK contents', 'Verify native library symbol hygiene',
                     'Verify combined radio and UI phone-test candidate',
                     'Verify permission-only and third-party source boundaries']:
            self.assertIn(name, BUILD)
        self.assertIn('python3 scripts/tests/apk_signing_identity_test.py', WORKFLOW)


if __name__ == '__main__':
    unittest.main()
