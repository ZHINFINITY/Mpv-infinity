#!/usr/bin/env python3
"""Guard arm64 CI speedups against stale native code or skipped validation."""
from pathlib import Path
import re
import sys
import unittest

DEFAULT = Path(__file__).resolve().parents[2] / '.github/workflows/mpvflow-debug-arm64.yml'
WORKFLOW = Path(sys.argv.pop()) if len(sys.argv) > 1 else DEFAULT
TEXT = WORKFLOW.read_text()
STEPS = dict(re.findall(r'^      - name: ([^\n]+)\n(.*?)(?=^      - name: |\Z)', TEXT, re.M | re.S))
MISS = "if: ${{ steps.mpvflow-runtime-cache.outputs.cache-hit != 'true' && steps.mpvflow-runtime-legacy-cache.outputs.cache-hit != 'true' }}"


class Arm64WorkflowTest(unittest.TestCase):
    def test_checkouts_and_native_setup_require_both_cache_misses(self):
        for name in ('Checkout pinned Android MPV build tools', 'Checkout pinned MPV source', 'Install Android MPV native build dependencies', 'Build MPVFlow-only MPV native arm64 debug package'):
            with self.subTest(step=name):
                self.assertIn(MISS, STEPS[name])
                self.assertGreater(TEXT.index('- name: ' + name), TEXT.index('- name: Restore verified legacy Flow-only MPVFlow runtime'))

    def test_validation_dependencies_and_vulkan_checks_are_unconditional(self):
        dependencies = STEPS['Install Vulkan and shader validation dependencies']
        self.assertNotIn('        if:', dependencies)
        for package in ('binutils', 'ffmpeg', 'glslang-tools', 'libvulkan-dev', 'mesa-vulkan-drivers'):
            self.assertIn(package, dependencies)
        tests = STEPS['Run MPVFlow core and GPU shader regression tests']
        self.assertNotIn('        if:', tests)
        self.assertIn('./scripts/test-mpvflow-core.sh', tests)
        self.assertIn("MPVFLOW_REQUIRE_VULKAN_PIXEL_TEST: '1'", tests)

    def test_shader_changes_invalidate_runtime_cache(self):
        cache = STEPS['Restore verified Flow-only MPVFlow arm64 runtime']
        self.assertIn("hashFiles('scripts/build-mpvflow-mpv-runtime.sh', 'scripts/prepare-mpvflow.py', 'scripts/patch-ffmpeg-symver.py', 'app/src/main/cpp/**', 'THIRD_PARTY_LICENSES/**', 'app/src/main/assets/mpv/licenses/**')", cache)
        self.assertNotIn('restore-keys:', cache)
        legacy = STEPS['Check legacy runtime cache source compatibility']
        self.assertIn('git diff --quiet "$seed_sha" HEAD -- scripts/build-mpvflow-mpv-runtime.sh scripts/prepare-mpvflow.py scripts/patch-ffmpeg-symver.py app/src/main/cpp THIRD_PARTY_LICENSES app/src/main/assets/mpv/licenses', legacy)
        for pin in ('1efc82739f6f7034cd63e7ee8b3ec5431df27467', 'c1529642089bfebfc928a1c1664638a7a5d219ba'):
            self.assertIn('ref: ' + pin, TEXT)
        self.assertIn("MPVFLOW_MPV_BUILD_CORES: '2'", TEXT)

    def test_single_gradle_invocation_keeps_all_tasks_and_limits(self):
        runs = re.findall(r'^        run: (\./gradlew .+)$', TEXT, re.M)
        self.assertEqual(len(runs), 1)
        for token in ('testStandardDebugUnitTest', ':media3-decoder-ffmpeg:testDebugUnitTest', 'assembleStandardDebug', '-PtargetAbi=arm64-v8a', '-PmpvFlowMpvBuild=true', '--stacktrace', '--no-daemon', '--max-workers=2'):
            self.assertIn(token, runs[0].split())
        self.assertNotIn('-x', runs[0].split())
        self.assertNotIn('continue-on-error:', TEXT)

    def test_packaged_runtime_checks_precede_upload(self):
        upload = TEXT.index('- name: Upload arm64-v8a debug APK')
        for name, command in (
            ('Verify GPL-free MPVFlow arm64 runtime', 'scripts/verify-mpv-aar-lgpl.py'),
            ('Verify LGPL-only DTS decoder runtime for Media3', 'scripts/verify-media3-dca-runtime.py'),
            ('Verify arm64 DTS wrapper dynamically links the existing FFmpeg runtime', 'scripts/verify-media3-dca-apk.py'),
        ):
            with self.subTest(step=name):
                self.assertIn(command, STEPS[name])
                self.assertNotIn('        if:', STEPS[name])
                self.assertLess(TEXT.index('- name: ' + name), upload)
        self.assertIn('if-no-files-found: error', STEPS['Upload arm64-v8a debug APK'])


if __name__ == '__main__':
    unittest.main()
