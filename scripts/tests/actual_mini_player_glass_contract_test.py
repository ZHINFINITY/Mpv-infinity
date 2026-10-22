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
    def test_root_host_covers_navigation_and_actual_mini_player(self):
        host = MAIN[MAIN.index("BrowserGlassSceneHost("):MAIN.index("// Display the update sheet")]
        for token in ("NavDisplay(",
                      "MiniPlayer(modifier = miniPlayerModifier)", "browserGlassSceneSource()"):
            self.assertIn(token, host)
        self.assertIn("if (liquidGlassSurfacesForBrowser) androidx.compose.ui.graphics.Color.Transparent", host)

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
        draw = SCENE[SCENE.index('val hasScene ='):SCENE.index('private const val BROWSER_LENS')]
        self.assertIn("else GlassSurfacePolicy.browserFallbackAlpha(film)", draw)
        self.assertIn("drawRoundRect(edge", draw)
        self.assertNotIn("return@onDrawBehind", draw)
        self.assertIn("if (it.isAttached)", SCENE)
        self.assertIn('shader.setFloatUniform("thickness", tuning.depth)', SCENE)
        self.assertIn("float2 outer = max(q, 0.0)", SCENE)

    def test_actual_mini_player_has_palette_glass_vertical_expand_and_real_callbacks(self):
        for token in ("settingsGlassSurface", "settingsGlassContainerColor",
                      "tonalElevation = if (glassEnabled) 0.dp", "detectVerticalDragGestures",
                      "verticalDrag < -expandThresholdPx", "remember(context, isAudioOnlyItem)",
                      "ACTION_NOTIFICATION_STOP", "MiniPlayerTransitionAnchor.publish",
                      "miniPlayerScreenBounds(hostView)", "hostView.width.toFloat()", "if (isAudioOnlyItem)"):
            self.assertIn(token, MINI)


if __name__ == "__main__":
    unittest.main()
