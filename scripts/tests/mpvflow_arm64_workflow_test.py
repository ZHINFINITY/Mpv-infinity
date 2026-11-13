#!/usr/bin/env python3
"""Guard the arm64 runtime workflow against stale code, cache reuse, or skipped validation."""
from pathlib import Path
import re
import sys
import unittest

DEFAULT = Path(__file__).resolve().parents[2] / ".github/workflows/mpvflow-debug-arm64.yml"
WORKFLOW = Path(sys.argv.pop()) if len(sys.argv) > 1 else DEFAULT
TEXT = WORKFLOW.read_text(encoding="utf-8")
STEPS = dict(re.findall(r"^      - name: ([^\n]+)\n(.*?)(?=^      - name: |\Z)", TEXT, re.M | re.S))
CACHE_MISS = "if: ${{ steps.mpvflow-runtime-cache.outputs.cache-hit != 'true' }}"
FFMPEG_SHA = "f2c5162b7c4b3e8835de270ff78cecc524decad1"
VULKAN_HEADERS_SHA = "a4f8ada9f4f97c45b8c89c57997be9cebaae65d2"


class Arm64WorkflowTest(unittest.TestCase):
    def test_source_checkouts_and_native_compilation_require_runtime_cache_miss(self):
        names = (
            "Checkout pinned Android MPV build tools",
            "Checkout pinned MPV source",
            "Build MPVFlow-only MPV native arm64 debug package",
        )
        for name in names:
            with self.subTest(step=name):
                self.assertIn(CACHE_MISS, STEPS[name])
                self.assertGreater(TEXT.index("- name: " + name), TEXT.index("- name: Restore verified Flow-only MPVFlow arm64 runtime"))

    def test_runtime_cache_is_source_pinned_and_has_no_legacy_fallback(self):
        restore = STEPS["Restore verified Flow-only MPVFlow arm64 runtime"]
        for pin in (
            "mpvflow-arm64-runtime-v6-builder-1efc82739f6f7034",
            "mpv-fongmi-14b3999a1a3e322c217bc90ee4df5a8f1bc88f69",
            "libplacebo-fongmi-b89c10e1bdd5d7742ca576ebad94a906ddca91e8",
            f"ffmpeg-fongmi-{FFMPEG_SHA}",
            f"vulkan-headers-khronos-{VULKAN_HEADERS_SHA}",
            "scripts/prepare-mpvflow-vulkan-headers.py",
            "scripts/prepare-mpvflow.py",
            "scripts/patches/mpvlibandroid-fongmi/**",
            "app/src/main/cpp/**",
        ):
            self.assertIn(pin, restore)
        for obsolete in ("runtime-legacy", "mpvflow-arm64-runtime-v4", "mpvflow-arm64-runtime-v5", "c1529642089bfebfc928a1c1664638a7a5d219ba", "restore-keys:"):
            self.assertNotIn(obsolete, restore + TEXT)
        self.assertIn("id: mpvflow-runtime-cache", restore)
        self.assertEqual(TEXT.count("uses: actions/cache/restore@v4"), 1)
        self.assertIn("steps.mpvflow-runtime-cache.outputs.cache-hit == 'true'", STEPS["Use cached Flow-only MPVFlow arm64 runtime"])

    def test_focused_invariants_run_before_the_runtime_build(self):
        focused = STEPS["Test complete MPVFlow focused invariants"]
        for script in (
            "scripts/tests/mpvflow_flow_only_test.py",
            "scripts/tests/mpvflow_native_build_test.py",
            "scripts/tests/fongmi_motion_resolution_patch_test.py",
        ):
            self.assertIn("python3 " + script, focused)
        self.assertLess(TEXT.index("- name: Test complete MPVFlow focused invariants"), TEXT.index("- name: Set up JDK 17"))

    def test_core_gpu_suite_runs_unconditionally_before_packaging(self):
        tests = STEPS["Run MPVFlow core and GPU shader regression tests"]
        self.assertNotIn("        if:", tests)
        self.assertIn("./scripts/test-mpvflow-core.sh", tests)
        self.assertIn("MPVFLOW_REQUIRE_VULKAN_PIXEL_TEST: '1'", tests)
        core_runner = Path(__file__).resolve().parents[2] / "scripts/test-mpvflow-core.sh"
        self.assertTrue(core_runner.stat().st_mode & 0o111, "the workflow invokes the core runner directly")
        for script in (
            "scripts/tests/mpvflow_fongmi_native_source_test.py",
            "scripts/tests/fongmi_motion_resolution_patch_test.py",
            "scripts/tests/mpvflow_android_subprocess_test.py",
            "scripts/tests/mpvflow_vulkan_headers_prepare_test.py",
            "scripts/tests/mpvflow_placebo_api_headers_test.py",
            "scripts/tests/verify_mpv_aar_lgpl_test.py",
            "scripts/tests/verify_region3_shader_apk_test.py",
            "scripts/tests/verify_pair_ghost_apk_test.py",
        ):
            self.assertIn(script, core_runner.read_text(encoding="utf-8"))
        self.assertLess(TEXT.index("- name: Run MPVFlow core and GPU shader regression tests"), TEXT.index("- name: Assemble arm64-v8a debug APK after Kotlin checks"))

    def test_android_build_tools_are_installed_even_on_runtime_cache_hits(self):
        name = "Install Android MPV native build dependencies"
        dependencies = STEPS[name]
        self.assertNotIn("        if:", dependencies)
        for package in ("autoconf", "automake", "libtool", "gettext", "autopoint", "gperf", "pkg-config", "ninja-build", "cmake", "pipx", "nasm", "yasm"):
            self.assertIn(package, dependencies)
        self.assertIn("pipx install meson==1.6.1", dependencies)
        self.assertLess(TEXT.index("- name: " + name), TEXT.index("- name: Assemble arm64-v8a debug APK after Kotlin checks"))

    def test_kotlin_checks_complete_before_arm64_apk_assembly(self):
        runs = re.findall(r"^        run: (\./gradlew .+)$", TEXT, re.M)
        self.assertEqual(len(runs), 2)
        kotlin_run, assemble_run = runs
        for token in ("testStandardDebugUnitTest", ":media3-decoder-ffmpeg:testDebugUnitTest", ":app:compileStandardDebugKotlin", "-PtargetAbi=arm64-v8a", "-PmpvFlowMpvBuild=true", "--stacktrace", "--no-daemon", "--max-workers=2"):
            self.assertIn(token, kotlin_run.split())
        for token in ("assembleStandardDebug", "-PtargetAbi=arm64-v8a", "-PmpvFlowMpvBuild=true", "--stacktrace", "--no-daemon", "--max-workers=2"):
            self.assertIn(token, assemble_run.split())
        self.assertNotIn("-x", kotlin_run.split() + assemble_run.split())
        self.assertLess(TEXT.index("- name: Run complete Kotlin compile and unit tests before APK packaging"), TEXT.index("- name: Assemble arm64-v8a debug APK after Kotlin checks"))
        self.assertNotIn("continue-on-error:", TEXT)

    def test_packaged_runtime_checks_precede_artifact_upload(self):
        upload = TEXT.index("- name: Upload arm64-v8a debug APK")
        for name, command in (
            ("Verify GPL-free MPVFlow arm64 runtime", "scripts/verify-mpv-aar-lgpl.py"),
            ("Verify LGPL-only DTS decoder runtime for Media3", "scripts/verify-media3-dca-runtime.py"),
            ("Verify arm64 DTS wrapper dynamically links the existing FFmpeg runtime", "scripts/verify-media3-dca-apk.py"),
        ):
            with self.subTest(step=name):
                self.assertIn(command, STEPS[name])
                self.assertNotIn("        if:", STEPS[name])
                self.assertLess(TEXT.index("- name: " + name), upload)
        shader_step = STEPS["Verify arm64 DTS wrapper dynamically links the existing FFmpeg runtime"]
        self.assertIn("scripts/verify-region3-shader-apk.py", shader_step)
        self.assertIn("scripts/verify-pair-ghost-apk.py", shader_step)
        self.assertIn("if-no-files-found: error", STEPS["Upload arm64-v8a debug APK"])


if __name__ == "__main__":
    unittest.main()
