from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
CONTROLS = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt").read_text()
POLICY = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlVisibilityPolicy.kt").read_text()
VIEW_MODEL = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerViewModel.kt").read_text()


class PlayerControlStartupVisibilityTest(unittest.TestCase):
    def test_shared_landscape_row_is_the_only_buffering_exception(self):
        compact_policy = re.sub(r"\s+", " ", POLICY)
        compact_controls = re.sub(r"\s+", " ", CONTROLS)

        self.assertIn(
            "controlsVisible && (sharedLandscapeRow || !bufferingIndicatorVisible)",
            compact_policy,
        )
        self.assertIn(
            "val sharedLandscapeRow = !isPortrait && playbackControlsPosition == PlaybackControlsPosition.BelowSeekbar",
            compact_controls,
        )
        self.assertIn("sharedLandscapeRow = sharedLandscapeRow", compact_controls)

    def test_seekbar_visibility_remains_independent_of_center_transport_gate(self):
        compact_controls = re.sub(r"\s+", " ", CONTROLS)
        self.assertIn(
            "val seekBarControlsVisible = (controlsShown || (!isPortrait && seekBarShown)) && !areControlsLocked",
            compact_controls,
        )
        self.assertIn("visible = { visibility -> visibility.second }", compact_controls)
        self.assertIn("nativeSnapshot.durationMs.takeIf { it > 0L }?.div(1000f)", compact_controls)

    def test_video_start_still_reveals_controls_before_waiting_for_video(self):
        start_handler = VIEW_MODEL.split("fun onVideoLoadStarted()", 1)[1].split("fun onVideoLoadCompleted()", 1)[0]
        start_body = start_handler.split("{", 1)[1]
        self.assertTrue(start_body.lstrip().startswith("showControls()"))
        self.assertIn("isWaitingForVideo = true", start_handler)


if __name__ == "__main__":
    unittest.main()
