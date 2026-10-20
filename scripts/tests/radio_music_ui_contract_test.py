#!/usr/bin/env python3
"""Source wiring guards; behavioral policies are covered by JVM tests in CI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"
RES_VALUES = ROOT / "app/src/main/res/values/strings.xml"


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

    def test_player_sheet_has_no_profile_quality_slider_surface(self):
        source = (JAVA / "ui/player/controls/components/sheets/DecodersSheet.kt").read_text()
        for forbidden in (
            "androidx.compose.material3.Slider",
            "Slider(",
            "mpvFlowQuality",
            "Profile",
            "Performance",
            "Quality",
        ):
            self.assertNotIn(forbidden, source)

    def test_player_engine_tabs_exclude_auto_but_global_engine_auto_remains_available(self):
        policy = (JAVA / "ui/player/DecoderUiPolicy.kt").read_text()
        source = (JAVA / "ui/preferences/DecoderPreferencesScreen.kt").read_text()
        sheet = (JAVA / "ui/player/controls/components/sheets/DecodersSheet.kt").read_text()
        self.assertIn("playerEngineTabs()", sheet)
        self.assertIn("filterNot { it == PlaybackEngineMode.AUTO }", policy)
        self.assertIn("items(Decoder.entries.minusElement(Decoder.Auto)", sheet)
        self.assertNotIn("items(Decoder.entries,", sheet)
        self.assertIn("values = PlaybackEngineMode.entries", source)

    def test_global_frame_interpolation_is_direct_switch_with_summary_and_same_handler(self):
        source = (JAVA / "ui/preferences/DecoderPreferencesScreen.kt").read_text()
        title = source.index("R.string.pref_decoder_mpvflow_title")
        switch_start = source.rfind("SwitchPreference(", 0, title)
        divider_before = source.rfind("PreferenceDivider()", 0, switch_start)
        divider_after = source.index("PreferenceDivider()", title)
        container = source[divider_before:divider_after]
        switch = source[switch_start:divider_after]

        before_switch = source[divider_before + len("PreferenceDivider()"):switch_start]
        self.assertEqual("", before_switch.strip())
        self.assertNotIn("Surface(", container)
        self.assertNotIn("padding(horizontal", container)
        self.assertIn("modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_mpvflow_title)", switch)
        self.assertIn("value = mpvFlowFrameInterpolationEnabled", switch)
        self.assertIn("preferences.mpvFlowFrameInterpolation.set(enabled)", switch)
        self.assertIn("R.string.pref_decoder_mpvflow_summary", switch)

        strings = RES_VALUES.read_text()
        self.assertRegex(
            strings,
            r'<string name="pref_decoder_mpvflow_summary">Creates in-between frames for smoother motion\.</string>',
        )

    def test_player_interpolation_stats_keep_compact_flow_summary(self):
        source = (JAVA / "ui/player/controls/PlayerControls.kt").read_text()
        self.assertIn("NativeStatsDisplay.interpolationFps(", source)
        self.assertIn("flow.capturedSourceFps", source)
        self.assertIn('"Media3 Flow: ${flow.state} · $flowInterpolationFps "', source)
        self.assertIn("formatMpvFlowTimingSummary(DebugLogReader.readSnapshot(", source)
        summary = (JAVA / "presentation/crash/DebugLogRepository.kt").read_text()
        self.assertIn("compact(sample.synthesizedSubmitFps)", summary)
        self.assertIn("sample.targetFps?.let", summary)


if __name__ == "__main__":
    unittest.main()
