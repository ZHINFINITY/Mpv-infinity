#!/usr/bin/env python3
"""Source wiring guards; behavioral policies are covered by JVM tests in CI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"


class RadioMusicUiContracts(unittest.TestCase):
    def test_radio_metadata_drives_service_and_player(self):
        for relative in ("ui/player/MediaPlaybackService.kt", "ui/player/controls/AudioPlayerControls.kt"):
            source = (JAVA / relative).read_text()
            self.assertIn("RadioMetadataPolicy", source)
        self.assertIn("metadata/by-key/", (JAVA / "ui/player/MediaPlaybackService.kt").read_text())

    def test_music_header_does_not_duplicate_navigation_icons(self):
        source = (JAVA / "ui/browser/music/MusicLibraryContent.kt").read_text()
        header = source[source.index("leadingActions = {"):source.index("onSelectAll =", source.index("leadingActions = {"))]
        self.assertIn("MusicSourceChooser(", header)
        self.assertNotIn("Icons.RoundedFilled.Audiotrack", header)
        self.assertNotIn("Icons.RoundedFilled.AudiobookWave", header)

    def test_source_picker_is_bounded_and_accessible(self):
        source = (JAVA / "ui/browser/music/MusicSourceChooser.kt").read_text()
        self.assertIn("widthIn(max = maxSourceWidth)", source)
        self.assertIn("heightIn(min = 48.dp)", source)
        self.assertIn("TextOverflow.Ellipsis", source)
        self.assertIn("onManageServers()", source)
        for provider in ("LOCAL", "JELLYFIN", "NAVIDROME"):
            self.assertIn(f"preferences.musicSourceProvider.set(MusicSourceProvider.{provider})", source)

    def test_target_120_and_soft_blend_off_remain_supported(self):
        source = (JAVA / "preferences/DecoderPreferences.kt").read_text()
        self.assertRegex(source, r"MPVFLOW_TARGET_FPS_OPTIONS[^\n]*120")
        self.assertIn('getBoolean("mpvflow_soft_blend_debug", false)', source)

    def test_global_engine_auto_remains_available(self):
        source = (JAVA / "ui/preferences/DecoderPreferencesScreen.kt").read_text()
        self.assertIn("values = PlaybackEngineMode.entries", source)


if __name__ == "__main__":
    unittest.main()
