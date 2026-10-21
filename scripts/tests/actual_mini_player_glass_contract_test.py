#!/usr/bin/env python3
"""Source contracts for the rendered browser route and actual MiniPlayer."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"
MAIN = (JAVA / "MainActivity.kt").read_text()
SCENE = (JAVA / "ui/theme/BrowserGlassScene.kt").read_text()
MINI = (JAVA / "ui/browser/components/MiniPlayer.kt").read_text()


class ActualMiniPlayerGlassContracts(unittest.TestCase):
    def test_root_host_covers_nav_display_and_actual_mini_player(self):
        host = MAIN[MAIN.index("BrowserGlassSceneHost("):MAIN.index("// Display the update sheet")]
        self.assertIn("AudioPaletteGlassScene(", host)
        self.assertIn("NavDisplay(", host)
        self.assertIn("MiniPlayer(modifier = miniPlayerModifier)", host)
        self.assertIn("route != MainScreen", host)
        self.assertIn("browserGlassSceneSource()", host)

    def test_nested_host_reuses_scene_and_capture_is_non_reentrant(self):
        self.assertIn("LocalBrowserScene.current ?: remember(layer)", SCENE)
        self.assertIn("if (scene.isRecording)", SCENE)
        self.assertIn("try {", SCENE)
        self.assertIn("finally", SCENE)
        self.assertIn("scene.isRecording = false", SCENE)
        self.assertIn("var isRecording = false", SCENE)
        self.assertNotIn("var isRecording by mutableStateOf", SCENE)
        self.assertIn("if (scene?.isRecording == true) return@onDrawBehind", SCENE)

    def test_actual_mini_player_has_palette_glass_and_vertical_expand(self):
        self.assertIn("settingsGlassSurface", MINI)
        self.assertIn("settingsGlassContainerColor", MINI)
        self.assertIn("tonalElevation = if (glassEnabled) 0.dp", MINI)
        self.assertIn("detectVerticalDragGestures", MINI)
        self.assertIn("verticalDrag < -expandThresholdPx", MINI)
        self.assertIn("remember(context, isAudioOnlyItem)", MINI)
        self.assertIn("ACTION_NOTIFICATION_STOP", MINI)

    def test_actual_mini_player_publishes_measured_audio_anchor(self):
        self.assertIn("MiniPlayerTransitionAnchor.publish", MINI)
        self.assertIn("boundsInWindow()", MINI)
        self.assertIn("hostView.width.toFloat()", MINI)
        self.assertIn("if (isAudioOnlyItem)", MINI)


if __name__ == "__main__":
    unittest.main()
