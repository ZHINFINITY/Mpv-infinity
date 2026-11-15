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
MPV_SHA = "95ec8e929f8ad2f97c8415c486971493a0b408df"
PLACEBO_SHA = "63659d96d1bff7d7a5a1368e9538056a193fc6fb"
FFMPEG_SHA = "f2c5162b7c4b3e8835de270ff78cecc524decad1"
VULKAN_HEADERS_SHA = "a4f8ada9f4f97c45b8c89c57997be9cebaae65d2"


class Arm64WorkflowTest(unittest.TestCase):
    def test_source_checkouts_and_native_compilation_require_runtime_cache_miss(self):
        names = (
            "Checkout pinned Android MPV build tools",
            "Build MPVFlow-only MPV native arm64 debug package",
        )
        for name in names:
            with self.subTest(step=name):
                self.assertIn(CACHE_MISS, STEPS[name])
                self.assertGreater(TEXT.index("- name: " + name), TEXT.index("- name: Restore verified Flow-only MPVFlow arm64 runtime"))
        source_checkout = STEPS["Checkout pinned MPV source"]
        self.assertNotIn(CACHE_MISS, source_checkout)
        self.assertLess(TEXT.index("- name: Checkout pinned MPV source"), TEXT.index("- name: Compile selected opt-in Jelly shader for MPV Infinity"))

    def test_runtime_cache_is_source_pinned_and_has_no_legacy_fallback(self):
        restore = STEPS["Restore verified Flow-only MPVFlow arm64 runtime"]
        for pin in (
            "mpvflow-arm64-runtime-v7-builder-1efc82739f6f7034",
            f"mpv-fongmi-{MPV_SHA}",
            f"libplacebo-fongmi-{PLACEBO_SHA}",
            f"ffmpeg-fongmi-{FFMPEG_SHA}",
            f"vulkan-headers-khronos-{VULKAN_HEADERS_SHA}",
            "scripts/mpvrx_motion_source.py",
            "scripts/patch_fongmi_motion_downsample.py",
            "scripts/patch_fongmi_motion_jelly.py",
            "scripts/patch_fongmi_vo_jelly_probe.py",
            "jelly-preview-",
            "jelly-probe-",
            "scripts/prepare-mpvflow-vulkan-headers.py",
            "scripts/prepare-mpvflow.py",
            "scripts/patches/mpvlibandroid-fongmi/**",
            "app/src/main/cpp/**",
        ):
            self.assertIn(pin, restore)
        for obsolete in (
            "runtime-legacy",
            "mpvflow-arm64-runtime-v4",
            "mpvflow-arm64-runtime-v5",
            "mpvflow-arm64-runtime-v6",
            "14b3999a1a3e322c217bc90ee4df5a8f1bc88f69",
            "b89c10e1bdd5d7742ca576ebad94a906ddca91e8",
            "c1529642089bfebfc928a1c1664638a7a5d219ba",
            "restore-keys:",
        ):
            self.assertNotIn(obsolete, restore + TEXT)
        self.assertIn("id: mpvflow-runtime-cache", restore)
        self.assertIn("inputs.jelly_probe == true && 'mpvflow-arm64-runtime-v7-jelly-probe-builder-1efc82739f6f7034'", restore)
        self.assertIn("scripts/patch_fongmi_motion_jelly_probe.py", restore)
        self.assertIn("hashFiles('scripts/patch_fongmi_motion_jelly_probe.py', 'scripts/patch_fongmi_vo_jelly_probe.py')", restore)
        self.assertEqual(TEXT.count("uses: actions/cache/restore@v4"), 1)
        self.assertIn("steps.mpvflow-runtime-cache.outputs.cache-hit == 'true'", STEPS["Use cached Flow-only MPVFlow arm64 runtime"])

    def test_focused_invariants_run_before_the_runtime_build(self):
        focused = STEPS["Test complete MPVFlow focused invariants"]
        for script in (
            "scripts/tests/mpvflow_flow_only_test.py",
            "scripts/tests/mpvflow_native_build_test.py",
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
            "scripts/tests/mpvrx_motion_source_test.py",
            "scripts/tests/mpvrx_motion_downsample_test.py",
            "scripts/tests/mpvflow_android_subprocess_test.py",
            "scripts/tests/mpvflow_vulkan_headers_prepare_test.py",
            "scripts/tests/mpvflow_placebo_api_headers_test.py",
            "scripts/tests/verify_mpv_aar_lgpl_test.py",
            "scripts/tests/verify_region3_shader_apk_test.py",
            "scripts/tests/verify_pair_ghost_apk_test.py",
        ):
            self.assertIn(script, core_runner.read_text(encoding="utf-8"))
        self.assertLess(TEXT.index("- name: Run MPVFlow core and GPU shader regression tests"), TEXT.index("- name: Assemble one arm64-v8a diagnostic APK after Kotlin checks"))

    def test_built_runtime_must_contain_the_pinned_downsample_patch(self):
        verify = STEPS["Verify MPV Infinity motion source after preparation"]
        self.assertIn("--require-analysis-downsample-aa", verify)
        self.assertIn("--require-jelly-experimental", verify)
        self.assertIn("--require-jelly-preview", verify)
        self.assertIn("--require-jelly-probe", verify)
        build = STEPS["Build MPVFlow-only MPV native arm64 debug package"]
        self.assertIn("MPVFLOW_MPVRX_ANALYSIS_DOWNSAMPLE_AA: '1'", build)
        self.assertIn("MPVFLOW_JELLY_EXPERIMENTAL", build)
        self.assertIn("MPVFLOW_JELLY_PREVIEW", build)
        self.assertIn("MPVFLOW_JELLY_PROBE", build)

    def test_jelly_candidate_and_blend_test_are_distinct_manual_diagnostics(self):
        self.assertIn("jelly_test", TEXT)
        self.assertIn("jelly_preview", TEXT)
        self.assertIn("default: false", TEXT)
        candidate = STEPS["Compile selected opt-in Jelly shader for MPV Infinity"]
        self.assertIn("scripts/tests/mpvrx_motion_jelly_test.py", candidate)
        self.assertIn("mpvrx_motion_jelly_probe_test.py --mpv-dir mpv-source --require-glslang", candidate)
        self.assertIn("--preview-only", candidate)
        self.assertIn("MOTION_ALIGNED_MAJORITY_V2", (Path(__file__).resolve().parents[2] / "scripts/patch_fongmi_motion_jelly.py").read_text(encoding="utf-8"))
        cache = STEPS["Restore verified Flow-only MPVFlow arm64 runtime"]
        self.assertIn("jelly-test-", cache)
        self.assertIn("jelly-preview-", cache)
        self.assertIn("baseline-", cache)
        upload = STEPS["Upload single arm64-v8a diagnostic APK"]
        self.assertIn("'jelly-test'", upload)
        self.assertIn("jelly-blend-test", upload)
        self.assertIn("retention-days: 3", upload)
        self.assertIn("*arm64-v8a*.apk", upload)

    def test_android_build_tools_are_installed_even_on_runtime_cache_hits(self):
        name = "Install Android MPV native build dependencies"
        dependencies = STEPS[name]
        self.assertNotIn("        if:", dependencies)
        for package in ("autoconf", "automake", "libtool", "gettext", "autopoint", "gperf", "pkg-config", "ninja-build", "cmake", "pipx", "nasm", "yasm"):
            self.assertIn(package, dependencies)
        self.assertIn("pipx install meson==1.6.1", dependencies)
        self.assertLess(TEXT.index("- name: " + name), TEXT.index("- name: Assemble one arm64-v8a diagnostic APK after Kotlin checks"))

    def test_kotlin_unit_tests_complete_before_arm64_apk_assembly(self):
        kotlin_run = STEPS["Run Kotlin unit tests before APK packaging"]
        assemble_run = STEPS["Assemble one arm64-v8a diagnostic APK after Kotlin checks"]
        for token in ("testStandardDebugUnitTest", ":media3-decoder-ffmpeg:testDebugUnitTest", "-PtargetAbi=arm64-v8a", "-PmpvFlowMpvBuild=true", "--stacktrace", "--no-daemon", "--max-workers=2"):
            self.assertIn(token, kotlin_run)
        self.assertIn(":app:testStandardDebugUnitTest", kotlin_run)
        self.assertNotIn("testStandardJellyProbeUnitTest", kotlin_run)
        self.assertNotIn("testStandardJellyPreviewUnitTest", kotlin_run)
        self.assertIn("inputs.jelly_probe == true && '-PmpvFlowJellyProbe=true'", kotlin_run)
        for token in ("assembleStandard${{ inputs.jelly_probe", "inputs.jelly_preview == true && 'JellyPreview'", "-PtargetAbi=arm64-v8a", "-PmpvFlowMpvBuild=true", "--stacktrace", "--no-daemon", "--max-workers=2"):
            self.assertIn(token, assemble_run)
        self.assertIn("inputs.jelly_probe == true && '-PmpvFlowJellyProbe=true'", assemble_run)
        self.assertNotIn("-x", kotlin_run.split() + assemble_run.split())
        self.assertLess(TEXT.index("- name: Run Kotlin unit tests before APK packaging"), TEXT.index("- name: Assemble one arm64-v8a diagnostic APK after Kotlin checks"))
        self.assertNotIn("continue-on-error:", TEXT)

    def test_jelly_probe_is_exclusive_and_has_one_distinct_debug_artifact(self):
        self.assertIn("      jelly_probe:", TEXT)
        self.assertIn("inputs.jelly_probe", STEPS["Validate diagnostic selection"])
        self.assertIn("MPVFLOW_JELLY_PROBE: ${{ inputs.jelly_probe == true && '1' || '0' }}", STEPS["Build MPVFlow-only MPV native arm64 debug package"])
        self.assertIn("inputs.jelly_probe == true && 'JellyProbe'", STEPS["Assemble one arm64-v8a diagnostic APK after Kotlin checks"])
        self.assertIn("app.infinity.mpvz.debug.jellyprobe", STEPS["Verify arm64 DTS wrapper dynamically links the existing FFmpeg runtime"])
        upload = STEPS["Upload single arm64-v8a diagnostic APK"]
        self.assertIn("jelly-probe", upload)
        self.assertIn("jellyProbe", upload)
        self.assertIn("retention-days: 3", upload)
        self.assertEqual(TEXT.count("uses: actions/upload-artifact@v7"), 1)

    def test_jelly_probe_inherits_the_debug_launcher_label(self):
        app_gradle = Path(__file__).resolve().parents[2] / "app/build.gradle.kts"
        app_text = app_gradle.read_text(encoding="utf-8")
        probe = app_text.split('create("jellyProbe") {', 1)[1].split('create("jellyPreview")', 1)[0]
        self.assertIn('initWith(getByName("debug"))', probe)
        self.assertNotIn('resValue("string", "app_name"', probe)
        self.assertIn('resValue("string", "app_name", "Mpv∞-Debug")', app_text)

    def test_public_workflow_and_artifact_use_mpv_infinity_branding(self):
        self.assertIn("name: MPV Infinity arm64 Debug APK", TEXT.splitlines())
        self.assertIn("name: Build MPV Infinity arm64-v8a debug APK", TEXT)
        self.assertIn("mpv-infinity-arm64-v8a-", STEPS["Upload single arm64-v8a diagnostic APK"])
        labels = [line for line in TEXT.splitlines() if re.match(r"^\s*(?:- )?name:", line)]
        self.assertFalse(any("MPVRx" in line for line in labels))

    def test_packaged_runtime_checks_precede_artifact_upload(self):
        upload = TEXT.index("- name: Upload single arm64-v8a diagnostic APK")
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
        self.assertIn("if-no-files-found: error", STEPS["Upload single arm64-v8a diagnostic APK"])
        self.assertIn("Expected exactly one Standard arm64 APK", STEPS["Verify arm64 DTS wrapper dynamically links the existing FFmpeg runtime"])


if __name__ == "__main__":
    unittest.main()
