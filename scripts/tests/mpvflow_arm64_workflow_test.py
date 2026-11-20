#!/usr/bin/env python3
"""Guard the isolated FongMi 645 Standard arm64 Debug workflow."""
from pathlib import Path
import re
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT / ".github/workflows/mpvflow-debug-arm64.yml"
WORKFLOW = Path(sys.argv.pop()) if len(sys.argv) > 1 else DEFAULT
TEXT = WORKFLOW.read_text(encoding="utf-8")

def parse_steps(text: str) -> dict[str, str]:
    return dict(re.findall(r"^      - name: ([^\n]+)\n(.*?)(?=^      - name: |\Z)", text, re.M | re.S))

STEPS = parse_steps(TEXT)
DEBUG_APK_TEXT = (ROOT / ".github/workflows/debug-apk.yml").read_text(encoding="utf-8")
DEBUG_APK_STEPS = parse_steps(DEBUG_APK_TEXT)
RELEASE_STEPS = parse_steps((ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8"))
CACHE_MISS = "if: ${{ steps.mpvflow-runtime-cache.outputs.cache-hit != 'true' }}"
MPV_SHA = "645f8ecdd3c94379048e9ee3e7014e174de0bd02"
PLACEBO_SHA = "63659d96d1bff7d7a5a1368e9538056a193fc6fb"
FFMPEG_SHA = "f2c5162b7c4b3e8835de270ff78cecc524decad1"
VULKAN_HEADERS_SHA = "a4f8ada9f4f97c45b8c89c57997be9cebaae65d2"


class Arm64WorkflowTest(unittest.TestCase):
    def test_workflow_is_only_standard_arm64_debug(self):
        self.assertIn("Mpv-infinity FongMi 645 arm64 Debug APK", TEXT)
        self.assertIn("workflow_dispatch:", TEXT)
        self.assertNotIn("inputs:", TEXT)
        self.assertNotIn("jelly_test", TEXT)
        self.assertNotIn("jelly_preview", TEXT)
        self.assertNotIn("MPVFLOW_JELLY", TEXT)
        self.assertNotIn("MPVFLOW_DEBUG_CAPTURE", TEXT)
        self.assertNotIn("MPVRx", TEXT)
        self.assertIn("assembleStandardDebug", STEPS["Assemble one Standard arm64-v8a Debug APK"])
        self.assertNotIn("Jelly", TEXT)

    def test_source_checkouts_and_build_require_runtime_cache_miss(self):
        names = (
            "Checkout pinned Android MPV build tools",
            "Build Mpv-infinity FongMi 645 MPV native arm64 Debug runtime",
        )
        for name in names:
            with self.subTest(step=name):
                self.assertIn(CACHE_MISS, STEPS[name])
                self.assertGreater(TEXT.index("- name: " + name), TEXT.index("- name: Restore verified Mpv-infinity FongMi 645 arm64 runtime"))
        source_checkout = STEPS["Checkout pinned FongMi MPV source"]
        self.assertNotIn(CACHE_MISS, source_checkout)
        self.assertLess(TEXT.index("- name: Checkout pinned FongMi MPV source"), TEXT.index("- name: Verify exact FongMi motion source and 480px integration"))

    def test_runtime_cache_is_exactly_pinned_and_has_no_legacy_fallback(self):
        restore = STEPS["Restore verified Mpv-infinity FongMi 645 arm64 runtime"]
        for pin in (
            "mpvflow-arm64-runtime-v8-builder-1efc82739f6f7034",
            f"mpv-fongmi-{MPV_SHA}",
            f"libplacebo-fongmi-{PLACEBO_SHA}",
            f"ffmpeg-fongmi-{FFMPEG_SHA}",
            f"vulkan-headers-khronos-{VULKAN_HEADERS_SHA}",
            "scripts/mpvrx_motion_source.py",
            "scripts/patch_fongmi_motion_downsample.py",
            "scripts/patch_fongmi_paired_color_mask.py",
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
            "mpvflow-arm64-runtime-v7",
            "14b3999a1a3e322c217bc90ee4df5a8f1bc88f69",
            "b89c10e1bdd5d7742ca576ebad94a906ddca91e8",
            "c1529642089bfebfc928a1c1664638a7a5d219ba",
            "restore-keys:",
            "patch_fongmi_motion_jelly.py",
            "jelly-test-",
            "jelly-preview-",
        ):
            self.assertNotIn(obsolete, restore + TEXT)
        self.assertIn("id: mpvflow-runtime-cache", restore)
        self.assertEqual(TEXT.count("uses: actions/cache/restore@v4"), 1)
        self.assertIn("steps.mpvflow-runtime-cache.outputs.cache-hit == 'true'", STEPS["Use cached Mpv-infinity FongMi 645 arm64 runtime"])

    def test_vendored_api391_headers_are_verified_in_build_workflows(self):
        generated = "app/mpvflow-generated/libplacebo-api-391"
        cmake = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        self.assertIn("include-api-391", cmake)
        self.assertNotIn(generated, cmake)
        for workflow_steps in (STEPS, DEBUG_APK_STEPS, RELEASE_STEPS):
            cache_text = "\n".join(workflow_steps.values())
            self.assertNotIn(generated, cache_text)
            self.assertIn("Verify vendored libplacebo API headers", workflow_steps)
            self.assertIn(
                "scripts/tests/mpvflow_placebo_api_headers_test.py",
                workflow_steps["Verify vendored libplacebo API headers"],
            )

    def test_focused_invariants_run_before_the_runtime_build(self):
        focused = STEPS["Test complete MPVFlow focused invariants"]
        for script in (
            "scripts/tests/mpvflow_flow_only_test.py",
            "scripts/tests/mpvflow_native_build_test.py",
        ):
            self.assertIn("python3 " + script, focused)
        self.assertLess(TEXT.index("- name: Test complete MPVFlow focused invariants"), TEXT.index("- name: Set up JDK 17"))

    def test_core_gpu_suite_is_unconditional_and_precedes_native_build(self):
        tests = STEPS["Run MPVFlow core and GPU shader regression tests"]
        self.assertNotIn("        if:", tests)
        self.assertIn("./scripts/test-mpvflow-core.sh", tests)
        self.assertIn("MPVFLOW_REQUIRE_VULKAN_PIXEL_TEST: '1'", tests)
        self.assertIn("MPVFLOW_MPV_SOURCE_DIR: mpv-source", tests)
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
        self.assertLess(TEXT.index("- name: Run MPVFlow core and GPU shader regression tests"), TEXT.index("- name: Build Mpv-infinity FongMi 645 MPV native arm64 Debug runtime"))

    def test_pinned_source_and_preserved_safeguards_are_verified(self):
        source = STEPS["Verify exact FongMi motion source and 480px integration"]
        self.assertIn(f"ref: {MPV_SHA}", STEPS["Checkout pinned FongMi MPV source"])
        self.assertIn("--mpv-dir mpv-source", source)
        verify = STEPS["Verify Mpv-infinity FongMi motion source after preparation"]
        self.assertIn("--require-analysis-downsample-aa", verify)
        self.assertIn("--require-paired-color-mask", verify)
        build = STEPS["Build Mpv-infinity FongMi 645 MPV native arm64 Debug runtime"]
        self.assertIn("MPVFLOW_ANALYSIS_DOWNSAMPLE_AA: '1'", build)
        self.assertNotIn("JELLY", build)
        self.assertNotIn("MPVFLOW_DEBUG_CAPTURE", build)

    def test_android_build_tools_are_installed_even_on_cache_hits(self):
        name = "Install Android MPV native build dependencies"
        dependencies = STEPS[name]
        self.assertNotIn("        if:", dependencies)
        for package in ("autoconf", "automake", "libtool", "gettext", "autopoint", "gperf", "pkg-config", "ninja-build", "cmake", "pipx", "nasm", "yasm"):
            self.assertIn(package, dependencies)
        self.assertIn("pipx install meson==1.6.1", dependencies)
        self.assertLess(TEXT.index("- name: " + name), TEXT.index("- name: Assemble one Standard arm64-v8a Debug APK"))

    def test_kotlin_tests_and_packaging_produce_one_arm64_debug_apk(self):
        unit = STEPS["Run Standard arm64 Debug unit tests"]
        for token in ("testStandardDebugUnitTest", ":media3-decoder-ffmpeg:testDebugUnitTest", "-PtargetAbi=arm64-v8a", "-PmpvFlowMpvBuild=true", "--stacktrace", "--no-daemon", "--max-workers=2"):
            self.assertIn(token, unit)
        build = STEPS["Assemble one Standard arm64-v8a Debug APK"]
        for token in ("assembleStandardDebug", "-PtargetAbi=arm64-v8a", "-PmpvFlowMpvBuild=true", "--stacktrace", "--no-daemon", "--max-workers=2"):
            self.assertIn(token, build)
        self.assertNotIn("Jelly", build)
        verify = STEPS["Verify Standard arm64 Debug APK contents"]
        self.assertIn('app/build/outputs/apk/standard/debug', verify)
        self.assertIn("Expected exactly one Standard Debug APK", verify)
        for script in ("scripts/verify-media3-dca-apk.py", "scripts/verify-region3-shader-apk.py", "scripts/verify-pair-ghost-apk.py"):
            self.assertIn(script, verify)
        upload = STEPS["Upload single Standard arm64-v8a Debug APK"]
        self.assertIn("mpv-infinity-fongmi-645f8ec-arm64-v8a-debug-${{ github.sha }}", upload)
        self.assertIn("standard/debug/*arm64-v8a*.apk", upload)
        self.assertIn("retention-days: 3", upload)
        self.assertLess(TEXT.index("- name: Verify Standard arm64 Debug APK contents"), TEXT.index("- name: Upload single Standard arm64-v8a Debug APK"))
        self.assertEqual(TEXT.count("uses: actions/upload-artifact@v7"), 1)

    def test_standard_arm64_debug_apk_verifies_motion_payload_before_upload(self):
        verification = DEBUG_APK_STEPS["Verify APK package, ABI, integrity and signing identity"]
        for script in (
            "scripts/verify-debug-apk-artifact.py",
            "scripts/verify-region3-shader-apk.py",
            "scripts/verify-pair-ghost-apk.py",
        ):
            with self.subTest(script=script):
                self.assertIn(f'python3 {script} "$apk"', verification)
        self.assertLess(
            DEBUG_APK_TEXT.index("- name: Verify APK package, ABI, integrity and signing identity"),
            DEBUG_APK_TEXT.index("- name: Upload debug APKs"),
        )


if __name__ == "__main__":
    unittest.main()
