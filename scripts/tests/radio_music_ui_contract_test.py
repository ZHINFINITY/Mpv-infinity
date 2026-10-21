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

    def test_real_station_headers_survive_late_subscribers_and_station_changes(self):
        probe = (JAVA / "utils/media/RadioStreamProbe.kt").read_text()
        session = (JAVA / "ui/player/PlaybackSession.kt").read_text()
        audio = (JAVA / "ui/player/controls/AudioPlayerControls.kt").read_text()
        service = (JAVA / "ui/player/MediaPlaybackService.kt").read_text()
        self.assertIn('header("Icy-MetaData", "1")', probe)
        self.assertIn('response.header("icy-logo")', probe)
        self.assertIn('response.header("icy-name")', probe)
        self.assertIn('add("Icy-MetaData: 1")', session)
        self.assertIn('_radioHeaders.value = null', session)
        self.assertIn('resolvedItem.stableId to it', session)
        self.assertNotIn('emitRadioHeaderMetadata', session)
        self.assertIn('PlaybackSession.radioHeaders.collectAsStateWithLifecycle()', audio)
        self.assertIn('it.first == currentItem?.stableId', audio)
        self.assertIn('stationHeaders?.icyLogo', audio)
        self.assertIn('PlaybackSession.radioHeaders.value?.takeIf { it.first == item.stableId }', service)

    def test_music_header_has_requested_note_but_no_source_chip(self):
        source = (JAVA / "ui/browser/music/MusicLibraryContent.kt").read_text()
        header_start = source.find("leadingActions = {")
        header_end = source.find("onSelectAll =", header_start) if header_start >= 0 else -1
        header = source[header_start:header_end if header_end >= 0 else len(source)]
        self.assertNotIn("MusicSourceChooser(", header)
        self.assertIn("Icons.RoundedFilled.Audiotrack", header)
        self.assertIn("Modifier.size(24.dp)", header)
        self.assertIn("MaterialTheme.colorScheme.onSurface", header)
        self.assertNotIn("Icons.RoundedFilled.AudiobookWave", header)


    def test_radio_m3u_policy_is_applied_before_playlist_player_launch(self):
        policy = (JAVA / "utils/media/M3URoutingPolicy.kt").read_text()
        network = (JAVA / "ui/browser/networkstreaming/NetworkBrowserViewModel.kt").read_text()
        repository = (JAVA / "database/repository/PlaylistRepository.kt").read_text()
        detail = (JAVA / "ui/browser/playlist/PlaylistDetailScreen.kt").read_text()
        self.assertIn("M3URoutingPolicy.route(parsed)", network)
        self.assertIn("isAudio = openInMusic", network)
        self.assertIn("M3URoutingPolicy.isMusicPlaylist", repository)
        self.assertIn("playlist?.isAudio == true", detail)
        self.assertIn('"media_library_audio"', detail)
        self.assertIn('"music_m3u_playlist"', detail)
        self.assertIn("videoExtensions", policy)
        self.assertIn("return false", policy)

    def test_radio_setting_is_default_on_and_first_frame_wins_over_stale_video_queue(self):
        preference = (JAVA / "preferences/PlayerPreferences.kt").read_text()
        self.assertIn('getBoolean("open_radio_m3u_in_music", true)', preference)
        controls = (JAVA / "ui/player/controls/PlayerControls.kt").read_text()
        audio_priority = controls.index("if (activity?.hasPreclassifiedMusicLaunch() == true)")
        stale_queue_branch = controls.index("when (currentPlaybackItem?.declaredMediaKind())", audio_priority)
        self.assertLess(audio_priority, stale_queue_branch)
        detail = (JAVA / "ui/browser/playlist/PlaylistDetailScreen.kt").read_text()
        self.assertIn("savedEntries.map", detail)
        self.assertIn("declaredAudio = declaredAudio", detail)
        self.assertLess(detail.index("val musicRoute"), detail.index("context.startActivity(intent)"))

    def test_raw_m3u_preflight_precedes_player_ui_and_is_reused(self):
        source = (JAVA / "ui/player/PlayerActivity.kt").read_text()
        on_create = source[source.index("override fun onCreate("):source.index("private fun shouldPreflightExternalM3u")]
        self.assertIn("preflightExternalM3u(launchIntent)", on_create)
        self.assertNotIn("setContentView", on_create)
        self.assertIn("withTimeoutOrNull(4_000L)", source)
        self.assertIn('sourceIntent.putExtra("mime_type", "audio/x-mpegurl")', source)
        self.assertIn("preflightM3uParse = playableUri to result", source)
        self.assertIn("?.takeIf { it.first == uriString }", source)
        self.assertIn("startInitializedPlayer()", source)
        self.assertIn("resumeInitializedPlayer()", source)
        new_intent = source[source.index("override fun onNewIntent("):source.index("private fun handleNewIntent(")]
        self.assertIn("m3uPreflightJob?.cancel()", new_intent)
        self.assertIn("shouldPreflightExternalM3u(intent)", new_intent)
        self.assertNotIn('extras?.getStringArrayExtra(', source)

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
