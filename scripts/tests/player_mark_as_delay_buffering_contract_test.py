#!/usr/bin/env python3
"""Source wiring contracts for Mark-as, delay controls, buffering, and glass cadence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"


class PlayerMarkAsDelayBufferingContracts(unittest.TestCase):
    def test_mark_as_uses_the_shared_glass_sheet_and_keeps_all_actions(self):
        mark_as = (JAVA / "ui/browser/videolist/VideoMarkAs.kt").read_text()
        sheet = (JAVA / "ui/components/GlassOptionSheet.kt").read_text()
        self.assertIn("GlassModalBottomSheet(", mark_as)
        self.assertNotIn("import androidx.compose.material3.ModalBottomSheet", mark_as)
        self.assertNotIn(".liquidGlassPane(", mark_as)
        self.assertIn("containerColor = Color.Transparent", sheet)
        self.assertIn("paneModifier: Modifier = glassOptionSheetModifier()", sheet)
        self.assertIn("containerColor: Color = glassOptionSheetContainerColor", sheet)
        self.assertIn(".heightIn(max = maxHeight)", sheet)
        self.assertIn(".background(containerColor, GlassSheetTopShape)", sheet)
        self.assertIn("WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)", sheet)
        for action in ("VideoMarkAs.New", "VideoMarkAs.LastPlayed", "VideoMarkAs.Finished", "VideoMarkAs.None"):
            self.assertIn(f"onMarkAs({action})", mark_as)

    def test_audio_and_subtitle_delay_share_the_live_two_decimal_chooser(self):
        delay = (JAVA / "ui/player/controls/components/panels/SubtitleDelayPanel.kt").read_text()
        audio = (JAVA / "ui/player/controls/components/panels/AudioDelayPanel.kt").read_text()
        self.assertIn('String.format(Locale.US, "%.2f", value)', delay)
        self.assertIn("valueFormatter = { formatDelaySeconds(it) }", delay)
        self.assertNotIn('valueFormatter = { "%.1f"', delay)
        self.assertIn("SubtitleDelayCardContent(", delay)
        self.assertIn("DelayCardContent(", audio)
        self.assertIn('PlaybackSession.propDouble["audio-delay"]', audio)
        self.assertIn('PlaybackSession.setPropertyDouble("audio-delay", delayInSeconds)', audio)
        self.assertIn("PlaybackSession.propDouble[delayProperty]", delay)
        self.assertIn("PlaybackSession.setPropertyDouble(delayProperty, it.toDouble())", delay)

    def test_local_startup_is_distinguished_without_dropping_real_network_stalls(self):
        controls = (JAVA / "ui/player/controls/PlayerControls.kt").read_text()
        native = (JAVA / "ui/player/NativeMedia3Engine.kt").read_text()
        policy = (JAVA / "ui/player/controls/PlayerControlVisibilityPolicy.kt").read_text()
        buffering = (JAVA / "ui/player/controls/components/BufferingState.kt").read_text()
        self.assertIn("suppressStartupStalls = isLocalPlaybackStartup", controls)
        self.assertIn("bufferingState.isCacheStall && !isLocalPlaybackStartup", controls)
        self.assertIn("!shouldPauseForCache(it)", controls)
        self.assertIn("val isLocalNativeSource =", controls)
        self.assertIn("isLocalSource = isLocalNativeSource", controls)
        self.assertIn("shouldForceMpvBufferingIndicator(", controls)
        self.assertNotIn("playbackSessionState.phase == PlaybackPhase.LOADING),", controls)
        self.assertIn("isNativePlaybackStalled(", controls)
        self.assertIn("isLocalSource = isPreparedSourceLocal()", native)
        self.assertIn("hasRenderedFirstFrame = _hasRenderedFirstFrame.value", native)
        self.assertIn("publishPlaybackSnapshot()", native[native.index('Log.d(logTag, "first frame rendered'):])
        self.assertIn("!isLocalSource || hasRenderedFirstFrame", policy)
        self.assertIn("if (suppressStartupStalls) return false", buffering)
        self.assertIn("(isCacheStall || coreIdle == true)", buffering)

    def test_large_sheet_refresh_preserves_motion_while_controls_limit_redundant_copies(self):
        policy = (JAVA / "ui/theme/GlassSamplingPolicy.kt").read_text()
        self.assertIn("const val PlayingIntervalMs = 66L", policy)
        self.assertIn("const val ControlsPlayingIntervalMs = 100L", policy)
        self.assertIn("const val WarmPlayingIntervalMs = 120L", policy)


if __name__ == "__main__":
    unittest.main()
