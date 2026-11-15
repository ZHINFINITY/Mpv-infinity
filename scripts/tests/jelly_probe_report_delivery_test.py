#!/usr/bin/env python3
"""Focused source-contract tests for persistent Jelly probe report delivery."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PLAYER = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt"
STORE = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/JellyProbeReportStore.kt"
MAIN_SCREEN = ROOT / "app/src/main/java/app/infinity/mpvz/ui/browser/MainScreen.kt"
BUILD = ROOT / "app/build.gradle.kts"
MPV_VIEW = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt"


class JellyProbeReportDeliveryTest(unittest.TestCase):
    def test_capture_uses_unique_persistent_path_without_erasing_prior_reports(self):
        player = PLAYER.read_text()
        store = STORE.read_text()
        arm = player.split("internal fun armJellyProbe(): String {", 1)[1].split(
            "internal fun finalizeJellyProbe()", 1
        )[0]
        self.assertIn("JellyProbeReportStore.newReportFile(this)", arm)
        self.assertNotIn("report.delete()", arm)
        self.assertNotIn('File(cacheDir, "jelly-probe-report.json")', arm)
        self.assertIn("File(context.filesDir, REPORT_DIRECTORY)", store)
        self.assertIn("UUID.randomUUID()", store)
        self.assertIn("REPORT_PREFIX", store)
        self.assertIn('File(context.cacheDir, "jelly-probe-report.json")', store)
        self.assertIn("legacy.copyTo(temporary", store)
        self.assertNotIn("legacy.delete()", store)

    def test_arm_accepts_the_exact_persistent_report_directory_before_native_options(self):
        view = MPV_VIEW.read_text()
        store = STORE.read_text()
        arm = view.split("fun armJellyProbe(reportFile:", 1)[1].split(
            "fun finalizeJellyProbe()", 1
        )[0]
        self.assertIn("File(context.filesDir, REPORT_DIRECTORY)", store)
        self.assertIn('java.io.File(context.filesDir, "jelly-probe-reports").canonicalFile', arm)
        self.assertIn("target.parentFile == it", arm)
        self.assertLess(arm.index("allowedRoots.none"), arm.index("setIntegrationOptionString"))

    def test_finalization_monitor_outlives_player_activity_and_emits_fallback_json(self):
        player = PLAYER.read_text()
        store = STORE.read_text()
        self.assertIn("JellyProbeReportStore.awaitFinalized(applicationContext, report, result)", player)
        self.assertIn("CoroutineScope(SupervisorJob() + Dispatchers.IO)", store)
        self.assertIn('"report_type":"mpvrx-jelly-probe-finalization-status"', store)
        self.assertIn('"status":"$status"', store)
        self.assertIn("native_finalize_result", store)
        self.assertIn("completedReportForCapture(report)", store)

    def test_main_screen_exposes_debug_only_share_action_for_a_saved_report(self):
        player = PLAYER.read_text()
        screen = MAIN_SCREEN.read_text()
        build = BUILD.read_text()
        self.assertIn("JellyProbeReportStore.share(this, jellyProbeReport)", player)
        self.assertIn("JellyProbeReportStore.latestReport.collectAsState()", screen)
        self.assertIn("JellyProbeReportStore.refresh(context.applicationContext)", screen)
        self.assertIn("BuildConfig.DEBUG", screen)
        self.assertIn("BuildConfig.MPV_HAS_JELLY_PROBE", screen)
        self.assertIn('Text("Share JSON report")', screen)
        self.assertIn('resValue("string", "app_name", "Mpv∞-Debug")', build)


if __name__ == "__main__":
    unittest.main()
