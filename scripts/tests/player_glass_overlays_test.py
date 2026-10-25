#!/usr/bin/env python3
"""Host-level coverage guards, not screenshot or device-rendering assertions."""
from pathlib import Path
import re
import unittest
ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"

class PlayerGlassOverlayContracts(unittest.TestCase):
    def source(self, relative):
        return (JAVA / relative).read_text()

    def test_shared_outer_surface_uses_settings_and_off_fallback(self):
        source = self.source("presentation/components/PlayerGlassOverlays.kt")
        for token in ("liquidGlassSurfaces.collectAsState()", "glassSurfaceOpacity.collectAsState()",
                      "glassSurfaceHighlight.collectAsState()", ".liquidGlassPane(enabled, opacity, highlight, cornerRadius,",
                      "materialRole = GlassSurfacePolicy.MaterialRole.Regular",
                      "if (enabled) Modifier else Modifier.background", "surfaceContainerHigh"):
            self.assertIn(token, source)

    def test_alerts_style_the_whole_container_not_only_slot_content(self):
        for relative in ("ui/player/controls/components/sheets/SubtitleTracksSheet.kt",
                         "ui/player/controls/components/sheets/LyricsTranslateDialog.kt",
                         "ui/browser/dialogs/AddToPlaylistDialog.kt"):
            source = self.source(relative)
            start = source.index("AlertDialog(")
            host = source[start:source.index("title =", start)]
            receiver = "modifier" if relative.endswith("AddToPlaylistDialog.kt") else "Modifier"
            self.assertIn(f"modifier = {receiver}.playerGlassOverlay()", host, relative)
            self.assertIn("containerColor = androidx.compose.ui.graphics.Color.Transparent", host, relative)
            self.assertNotIn("PlayerGlassOverlay(", source, relative)

    def test_playlist_dialog_has_one_outer_argument_per_parameter(self):
        source = self.source("ui/browser/dialogs/AddToPlaylistDialog.kt")
        start = source.index("AlertDialog(")
        call = source[start:source.index("\n  )", start)]
        arguments = re.findall(r"^    (\w+)\s*=", call, re.MULTILINE)
        self.assertEqual(len(arguments), len(set(arguments)), arguments)
        self.assertIn("modifier = modifier.playerGlassOverlay()", call)
        self.assertIn("tonalElevation = 0.dp", call)

    def test_bottom_sheet_surfaces_are_not_opaque_over_glass(self):
        for relative in ("ui/player/controls/components/LyricsView.kt",):
            source = self.source(relative)
            host = source[source.index("ModalBottomSheet("):].split(") {", 1)[0]
            self.assertIn(".playerGlassOverlay()", host, relative)
            self.assertIn("containerColor =", host, relative)
            self.assertIn("Color.Transparent", host, relative)

    def test_owned_player_popups_use_one_glass_aware_root(self):
        for relative in ("ui/player/controls/components/sheets/AudioPropertiesSheet.kt",
                         "ui/player/controls/components/sheets/EqualizerSheet.kt"):
            source = self.source(relative)
            self.assertEqual(1, source.count("PlayerSheet("), relative)
            self.assertNotIn("ModalBottomSheet(", source, relative)
            self.assertIn("modifier = modifier", source, relative)
        lyrics = self.source("ui/player/controls/components/sheets/LyricsSheet.kt")
        self.assertIn("modifier.fillMaxWidth().playerGlassOverlay(cornerRadius = 28.dp)", lyrics)
        self.assertIn("color = androidx.compose.ui.graphics.Color.Transparent", lyrics)
        more = self.source("ui/player/controls/components/sheets/MoreSheet.kt")
        timer = more[more.index("fun TimePickerDialog("):]
        self.assertIn(".playerGlassOverlay()", timer)
        self.assertIn("DialogProperties(usePlatformDefaultWidth = false)", timer)

    def test_translation_child_surface_has_real_glass_call(self):
        source = self.source("ui/player/controls/components/sheets/LyricsTranslateDialog.kt")
        child = source[source.index("Surface("):source.index("Text(", source.index("Surface("))]
        self.assertIn(".playerGlassOverlay(12.dp)", child)
        self.assertIn("Color.Transparent", child)

    def test_all_subtitle_dropdowns_have_one_outer_pane(self):
        source = self.source("ui/player/controls/components/sheets/OnlineSubtitleSearchSheet.kt")
        self.assertEqual(3, source.count("GlassDropdownMenu("))
        self.assertEqual(3, source.count(".playerGlassOverlay(12.dp)"))
        self.assertNotIn("PlayerGlassOverlay(", source)
        shared = self.source("presentation/components/ExposedTextDropDownMenu.kt")
        self.assertIn(".playerGlassOverlay(16.dp)", shared)
        self.assertIn("containerColor = androidx.compose.ui.graphics.Color.Transparent", shared)
        self.assertNotIn("PlayerGlassOverlay(", shared)

    def test_independent_dialog_windows_and_huds_are_covered(self):
        for relative in ("presentation/components/ConfirmDialog.kt",
                         "ui/player/ytdlp/YtdlpInstallPromptDialog.kt",
                         "ui/player/ytdlp/YtdlpInstallProgressDialog.kt",
                         "ui/browser/dialogs/FilePickerDialog.kt",
                         "ui/player/controls/components/sheets/MoreSheet.kt",
                         "ui/player/controls/components/CurrentChapter.kt",
                         "ui/player/controls/components/PlayerUpdates.kt"):
            self.assertRegex(self.source(relative), r"(?:PlayerGlassOverlay\(|\.playerGlassOverlay\()", relative)
        for relative in ("presentation/components/PlayerSheet.kt",
                         "ui/player/controls/components/panels/DraggablePanel.kt",
                         "ui/player/controls/PlayerControlDrawer.kt",
                         "ui/player/controls/components/VerticalSliders.kt"):
            self.assertRegex(self.source(relative), r"(?:liquidGlassPane|playerGlassPane)\(", relative)

    def test_cross_window_sampling_uses_a_common_screen_origin(self):
        source = self.source("ui/theme/LiquidGlassBackdrop.kt")
        self.assertIn("source.getLocationOnScreen(location)", source)
        self.assertIn("hostView.getLocationOnScreen(screenLocation)", source)
        self.assertIn("screenLocation[0] - windowLocation[0]", source)

    def test_equalizer_and_audio_property_children_do_not_restore_opaque_backplates(self):
        equalizer = self.source("ui/player/controls/components/sheets/EqualizerSheet.kt")
        chip = equalizer.split("private fun PresetChip(", 1)[1]
        self.assertIn(".settingsGlassSurface(cornerRadius = 999.dp)", chip)
        self.assertIn("settingsGlassContainerColor(MaterialTheme.colorScheme.primaryContainer)", chip)
        self.assertIn("Modifier.clickable(onClick = onClick)", chip)
        properties = self.source("ui/player/controls/components/sheets/AudioPropertiesSheet.kt")
        self.assertIn("modifier = Modifier.settingsGlassSurface(cornerRadius = 8.dp)", properties)
        rows = properties.split("properties.forEachIndexed", 1)[1]
        self.assertIn(".settingsGlassSurface(cornerRadius = 12.dp)", rows)
        self.assertIn(".background(settingsGlassContainerColor(", rows)

    def test_audio_popup_siblings_share_the_actual_extracted_palette_provider(self):
        controls = self.source("ui/player/controls/PlayerControls.kt")
        # The music branch delegates to AudioPlayerScreen, which the browser sheet also hosts.
        branch = controls.split("if (useAudioPlayer) {", 1)[1].split("    return", 1)[0]
        self.assertIn("AudioPlayerScreen(", branch)
        audio_branch = controls.split("internal fun AudioPlayerScreen(", 1)[1].split("\n}\n", 1)[0]
        self.assertLess(audio_branch.index("AudioPaletteGlassScene("), audio_branch.index("AudioPlayerControls("))
        self.assertLess(audio_branch.index("AudioPlayerControls("), audio_branch.index("PlayerSheets("))
        self.assertIn("onGlassPaletteChanged = { top, bottom -> audioGlassPalette = top to bottom }", audio_branch)
        audio = self.source("ui/player/controls/AudioPlayerControls.kt")
        self.assertIn("LaunchedEffect(visualizerPalette.primary, visualizerPalette.secondary)", audio)
        self.assertIn("onGlassPaletteChanged(visualizerPalette.primary, visualizerPalette.secondary)", audio)

if __name__ == "__main__":
    unittest.main()
