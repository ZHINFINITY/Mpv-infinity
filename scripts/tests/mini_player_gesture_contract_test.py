#!/usr/bin/env python3
"""Runtime-source contracts for gesture presentation and the actually rendered mini-player."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"


class MiniPlayerGestureContracts(unittest.TestCase):
    def test_policy_covers_distance_velocity_cancel_and_real_target_geometry(self):
        policy = (JAVA / "ui/player/controls/MiniPlayerGesturePolicy.kt").read_text()
        for token in ("fun drag(", "fun cancel(", "fun settle(", "velocityY", "fun transform(",
                      "object MiniPlayerTransitionAnchor", "fun forViewport("):
            self.assertIn(token, policy)
        self.assertNotIn("preservePlayback", policy)
        self.assertNotIn("PlaybackSession", policy)

    def test_full_player_tracks_directly_and_settles_from_release_position(self):
        controls = (JAVA / "ui/player/controls/AudioPlayerControls.kt").read_text()
        gesture = controls.split("var miniGesture by remember", 1)[1].split("  AudioPaletteGlassScene(", 1)[0]
        for token in ("MiniPlayerGesturePolicy.drag(miniGesture", "MusicMinimizeTransitionPolicy.releaseTargetMiniProgress(",
                      "VelocityTracker()", "calculateVelocity().y", "onDragCancel", "settleStart = miniGesture.progress",
                      "settleAnimation.snapTo(settleStart)", "settleAnimation.animateTo(target", "LaunchedEffect(settleGeneration)",
                      "settleGeneration == generation", "currentOnBackPress()"):
            self.assertIn(token, gesture)
        self.assertNotIn("currentGesture.value", gesture)
        self.assertNotIn("rememberSaveable", gesture)
        self.assertNotIn("delay(", gesture)
        # The live frame is computed per draw from finger/settle progress and the live anchor.
        layer = gesture.split("val currentTransitionFrame", 1)[1]
        # Progress comes from one source: finger, settle, or the in-app sheet.
        self.assertIn("currentMiniProgress()", layer)
        self.assertIn("else if (settleTarget == null) miniGesture.progress", gesture)
        self.assertIn("MusicMinimizeTransitionPolicy.currentAnchor(windowWidth, windowWidth, windowHeight)", layer)
        self.assertIn("MusicMinimizeTransitionPolicy.frame(", layer)
        for forbidden in ("PlaybackSession.", "ACTION_NOTIFICATION_STOP", "load(", "recreate(", "seek("):
            self.assertNotIn(forbidden, gesture)

    def test_rendered_browser_anchor_measures_and_expands_without_changing_queue(self):
        main = (JAVA / "MainActivity.kt").read_text()
        mini = (JAVA / "ui/browser/components/MiniPlayer.kt").read_text()
        self.assertIn("MiniPlayer(modifier = miniPlayerModifier)", main)
        self.assertIn("PlayerActivity::class.java", mini)
        self.assertIn("ACTION_OPEN_PLAYER", mini)
        self.assertIn('putExtra("is_audio", isAudioOnlyItem)', mini)
        self.assertIn("detectVerticalDragGestures", mini)
        self.assertIn("MiniPlayerTransitionAnchor.publish(", mini)
        self.assertIn("miniPlayerScreenBounds(hostView)", mini)
        self.assertIn("ACTION_NOTIFICATION_PLAY_PAUSE", mini)
        self.assertIn("ACTION_NOTIFICATION_NEXT", mini)
        self.assertIn("detectHorizontalDragGestures", mini)


if __name__ == "__main__":
    unittest.main()
