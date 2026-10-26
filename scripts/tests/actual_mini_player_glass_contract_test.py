#!/usr/bin/env python3
"""Contracts for actual navigation, retained source ownership and rendered mini-player."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"
MAIN = (JAVA / "MainActivity.kt").read_text()
SCENE = (JAVA / "ui/theme/BrowserGlassScene.kt").read_text()
MINI = (JAVA / "ui/browser/components/MiniPlayer.kt").read_text()


class ActualMiniPlayerGlassContracts(unittest.TestCase):
    def test_reference_navigation_host_is_screen_local_and_mini_player_remains_on_stack(self):
        screen = (JAVA / "ui/browser/MainScreen.kt").read_text()
        self.assertNotIn("BrowserGlassSceneHost(", MAIN)
        self.assertNotIn("browserGlassSceneSource()", MAIN)
        self.assertIn("NavDisplay(", MAIN)
        self.assertIn("MiniPlayer(modifier = miniPlayerModifier)", MAIN)
        self.assertIn("BrowserGlassSceneHost(", screen)
        self.assertIn("liquidGlassSurfacesForBrowser && !hideNavigationBar", screen)
        self.assertIn("BrowserGlassRouteContentHost(", screen)

    def test_retained_producer_can_never_record_a_glass_consumer(self):
        code = re.sub(r"/\*.*?\*/|//[^\n]*", "", SCENE, flags=re.S)
        self.assertIn("inheritedScene ?: remember(layer)", code)
        self.assertEqual(1, code.count("scene.layer.record("))
        record = re.search(r"scene\.layer\.record\(size = IntSize\([^\n]+\)\)\s*\{([^{}]*)\}", code)
        self.assertIsNotNone(record)
        self.assertEqual("this@drawWithContent.drawContent()", record.group(1).strip())
        producer = code.split("if (ownsScene && enabled)", 1)[1].split("content()", 1)[0]
        self.assertIn("LocalBrowserScene provides null", producer)
        self.assertIn("background()", producer)
        self.assertNotIn("AudioPaletteGlassScene(", MAIN)
        self.assertIn("fun Modifier.browserGlassSceneSource(): Modifier = this", code)
        self.assertIn("if (ownsScene && enabled)", code)
        self.assertIn("if (!allowed || !GlassScenePolicy.validCaptureSize", code)
        self.assertIn("finally", code)
        self.assertIn("scene.isRecording = false", code)
        self.assertNotIn("var isRecording by mutableStateOf", code)
        self.assertIn("GlassScenePolicy.validCaptureSize(size.width, size.height)", code)
        self.assertIn("GlassScenePolicy.canDrawRecordedScene(scene.isRecording, scene.hasValidDisplayList)", code)

    def test_invalid_source_still_draws_readable_bounded_fallback(self):
        latest = SCENE.split('internal fun Modifier.browserGlassPane(', 1)[1].split('private fun windowScreenOffset', 1)[0]
        draw = latest[latest.index('val hasScene ='):]
        self.assertIn("GlassSurfacePolicy.NavigationNoSceneFilmAlpha", draw)
        self.assertIn("if (floating) drawRoundRect(edge", draw)
        self.assertNotIn("return@onDrawBehind", draw)
        self.assertIn("if (it.isAttached)", SCENE)
        self.assertIn('(if (selected) 7.dp else 12.dp) * tuning.depth', latest)
        self.assertIn("float2 outer = max(q, 0.0)", SCENE)

    def test_actual_mini_player_has_palette_glass_vertical_expand_and_real_callbacks(self):
        # Build 1944: the owner asked for nav-pill Liquid Glass on the mini player, so it uses the
        # shared BrowserGlassSurface (global glass settings + device fallback) with a flat fill when off.
        for token in ("BrowserGlassSurface(", "enabled = glassEnabled",
                      "if (!glassEnabled) Modifier.background(", "detectVerticalDragGestures",
                      "verticalDrag < -expandThresholdPx", "remember(context, isAudioOnlyItem, useInAppSheet, musicSheet)",
                      "musicSheet.expand()", "Modifier.browserMusicSheetDrag(", "fromMiniPlayer = true",
                      "ACTION_NOTIFICATION_STOP", "MiniPlayerTransitionAnchor.publish",
                      "miniPlayerScreenBounds(hostView)", "hostView.width.toFloat()", "if (isAudioOnlyItem)"):
            self.assertIn(token, MINI)


if __name__ == "__main__":
    unittest.main()
