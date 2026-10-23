#!/usr/bin/env python3
"""Static coverage guards for the video player liquid-glass control contract.

These checks intentionally inspect source rather than rendering pixels: the latter
belongs to the app/UI test suite.  They protect the shared-pane boundary while
leaving legacy Material fallbacks and video black-bar treatment intact.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"
PLAYER = JAVA / "ui/player/controls"
COMPONENTS = PLAYER / "components"


def source(relative: str) -> str:
    return (JAVA / relative).read_text()


class PlayerGlassCoverage(unittest.TestCase):
    def test_control_button_uses_one_shared_pane_and_preserves_fallback(self):
        text = (COMPONENTS / "ControlsButton.kt").read_text()
        self.assertEqual(text.count(".playerGlassPane("), 1)
        self.assertIn("color = if (glassEnabled || hideBackground) Color.Transparent", text)
        self.assertIn("playerButtonContainerColor()", text)
        self.assertIn("if (hideBackground || glassEnabled)", text)

    def test_video_control_regions_have_shared_pane_coverage(self):
        text = (PLAYER / "PlayerControls.kt").read_text()
        # Top, center and bottom capsules retain glass; seekbar optics belong to its track/thumb.
        self.assertGreaterEqual(text.count(".playerGlassPane("), 10)
        self.assertIn("useLiquidGlassSeekbar", text)
        self.assertNotIn(".playerGlassPane(enabled = useLiquidGlassSeekbar", text)
        self.assertIn("useLiquidGlassSeekbar -> Color.Transparent", text)
        self.assertIn("CurrentChapter", text)

    def test_chapter_is_an_overlay_with_an_opaque_off_fallback(self):
        text = (COMPONENTS / "CurrentChapter.kt").read_text()
        self.assertIn("playerGlassOverlay(999.dp)", text)
        self.assertIn("color = androidx.compose.ui.graphics.Color.Transparent", text)
        overlay = (ROOT / "app/src/main/java/app/infinity/mpvz/presentation/components/PlayerGlassOverlays.kt").read_text()
        self.assertIn(".liquidGlassPane(enabled, opacity, highlight, cornerRadius,", overlay)
        self.assertIn("materialRole = GlassSurfacePolicy.MaterialRole.Regular", overlay)
        self.assertIn("MaterialTheme.colorScheme.surfaceContainerHigh", overlay)

    def test_glass_toggle_is_not_bypassed_by_legacy_hide_background_local(self):
        button = (COMPONENTS / "ControlsButton.kt").read_text()
        controls = (PLAYER / "PlayerButtonTheme.kt").read_text()
        self.assertIn("val glassEnabled = liquidGlassSurfaces", button)
        self.assertIn("LocalHidePlayerButtonsBackground provides (hideBackground && !liquidGlassSurfaces)", controls)

    def test_shared_specialized_surfaces_are_all_glass_aware(self):
        """Every non-ControlsButton surface must use the shared pane contract."""
        text = (PLAYER / "PlayerControlsShared.kt").read_text()
        self.assertEqual(text.count("liquidGlassSurfaces.collectAsState()"), 1)
        self.assertEqual(text.count("Surface("), text.count(".playerGlassPane("))
        self.assertIn("if (liquidGlassSurfaces || hideBackground)", text)
        self.assertNotRegex(text, r"playerGlassPane\([^)]*\b(?:opacity|highlight)\s*=")
        self.assertNotIn("border = BorderStroke(1.dp,", text)

    def test_no_new_opaque_material_surfaces_in_the_shared_button_path(self):
        """Reject the common regression: an opaque Material surface over a glass pane."""
        text = (COMPONENTS / "ControlsButton.kt").read_text()
        self.assertNotRegex(text, r"color\s*=\s*MaterialTheme\.colorScheme\.(surface|surfaceContainer\w*)\b")
        self.assertNotIn("background(MaterialTheme.colorScheme", text)

    def test_cast_option_controls_are_not_mistaken_for_video_backdrop_panes(self):
        """Cast's black remote screen is an intentional non-video fallback."""
        text = (JAVA / "ui/cast/CastRemoteControllerScreen.kt").read_text()
        self.assertIn(".background(Color.Black)", text)
        self.assertIn("CastOptionButton", text)


if __name__ == "__main__":
    unittest.main()
