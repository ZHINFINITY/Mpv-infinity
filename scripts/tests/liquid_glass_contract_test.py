#!/usr/bin/env python3
"""Source integration guards; pure sizing/safety behavior is tested by JVM tests in CI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"


class LiquidGlassContracts(unittest.TestCase):
    def test_source_hash_audit_precedes_verified_runtime_overlay(self):
        workflow = (ROOT / ".github/workflows/debug-apk.yml").read_text()
        self.assertLess(workflow.index("Verify permission-only and third-party source boundaries"),
                        workflow.index("Overlay Flow-only runtime into Standard MPV AAR"))

    def test_video_has_one_bounded_shared_sampler(self):
        source = (JAVA / "ui/theme/LiquidGlassBackdrop.kt").read_text()
        self.assertEqual(1, source.count("PixelCopy.request("))
        self.assertIn("GlassSamplingPolicy.sampleSize", source)
        self.assertIn("repeatOnLifecycle(Lifecycle.State.RESUMED)", source)
        self.assertIn("failures >= GlassSamplingPolicy.FailureLimit", source)
        self.assertIn("ActivityManager", source)
        self.assertIn("currentThermalStatus", source)
        self.assertIn("Build.VERSION.SDK_INT >= 33", source)
        self.assertIn("catch (_: RuntimeException)", source)
        self.assertIn("frame.takeIf { allowed }", source)

    def test_artwork_blur_is_background_only_and_off_means_off(self):
        source = (JAVA / "ui/theme/GlassSurface.kt").read_text()
        self.assertIn("liquidGlassPane", source)
        self.assertIn("if (enabled && backdrop != null)", source)
        self.assertIn("matchParentSize().graphicsLayer", source)
        self.assertLess(source.index("backdrop.invoke()"), source.index("    content()"))
        self.assertNotIn("PixelCopy", source)

    def test_controls_preserve_material_semantics_and_have_real_optical_thumbs(self):
        source = (JAVA / "ui/player/controls/components/GlassControls.kt").read_text()
        for expected in ("Switch(", "Slider(", "onCheckedChange", "onValueChangeFinished",
                         "interactionSource = interactionSource", "thumbContent =", "thumb =",
                         "liquidGlassPane", "enabled && preferenceEnabled"):
            self.assertIn(expected, source)
        self.assertIn("thumbContent: (@Composable () -> Unit)? = null", source)
        self.assertIn("thumbContent?.invoke()", source)
        for relative in ("ui/player/controls/PlayerButtonTheme.kt",
                         "ui/player/controls/components/ControlsButton.kt"):
            self.assertIn("import androidx.compose.runtime.getValue", (JAVA / relative).read_text())

    def test_panels_and_sheets_leave_sampled_glass_visible(self):
        for path in ("presentation/components/PlayerSheet.kt",
                     "ui/player/controls/components/panels/DraggablePanel.kt"):
            source = (JAVA / path).read_text()
            self.assertIn("liquidGlassPane", source)
            self.assertIn("Color.Transparent", source)
            self.assertLess(source.index(".offset {"), source.index(".liquidGlassPane("))

    def test_main_player_master_covers_transport_custom_controls_and_seekbar(self):
        source = (JAVA / "ui/player/controls/PlayerControls.kt").read_text()
        self.assertGreaterEqual(source.count(".playerGlassPane("), 10)
        self.assertIn("onGlassVisibilityChange(isPlayerDrawerShown)", source)
        self.assertNotIn("onGlassVisibilityChange(!isPlayerDrawerShown)", source)
        source = (JAVA / "ui/player/controls/components/ControlsButton.kt").read_text()
        self.assertIn("playerGlassPane", source)
        self.assertIn("Color.Transparent", source)

    def test_player_sheets_do_not_use_raw_material_controls(self):
        sheets = JAVA / "ui/player/controls/components/sheets"
        for path in sheets.glob("*.kt"):
            source = path.read_text()
            self.assertNotIn("androidx.compose.material3.Switch", source, path.name)
            self.assertNotIn("androidx.compose.material3.Slider\n", source, path.name)

    def test_audio_backdrops_use_artwork_not_extra_fake_animations(self):
        audio = (JAVA / "ui/player/controls/AudioPlayerControls.kt").read_text()
        mini = (JAVA / "ui/browser/components/AudioMiniPlayer.kt").read_text()
        self.assertIn("AudioArtworkBackdrop", audio)
        self.assertIn("albumArtBitmap", audio)
        self.assertEqual(2, audio.count("if (isAudiobook) audiobookModeButtons() else {"))
        self.assertIn("artworkUri", mini)
        self.assertNotIn("repeat(18)", mini)


if __name__ == "__main__":
    unittest.main()
