#!/usr/bin/env python3
"""Host-level coverage guards, not screenshot or device-rendering assertions."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"

class PlayerGlassOverlayContracts(unittest.TestCase):
    def source(self, relative):
        return (JAVA / relative).read_text()

    def test_shared_outer_surface_uses_settings_and_off_fallback(self):
        source = self.source("presentation/components/PlayerGlassOverlays.kt")
        for token in ("liquidGlassSurfaces.collectAsState()", "glassSurfaceOpacity.collectAsState()",
                      "glassSurfaceHighlight.collectAsState()", ".liquidGlassPane(enabled, opacity, highlight, cornerRadius)",
                      "if (enabled) Modifier else Modifier.background", "surfaceContainerHigh"):
            self.assertIn(token, source)

    def test_alerts_style_the_whole_container_not_only_slot_content(self):
        for relative in ("ui/player/controls/components/sheets/SubtitleTracksSheet.kt",
                         "ui/player/controls/components/sheets/LyricsTranslateDialog.kt",
                         "ui/browser/dialogs/AddToPlaylistDialog.kt"):
            source = self.source(relative)
            start = source.index("AlertDialog(")
            host = source[start:source.index("title =", start)]
            self.assertIn("modifier = Modifier.playerGlassOverlay()", host, relative)
            self.assertIn("containerColor = androidx.compose.ui.graphics.Color.Transparent", host, relative)
            self.assertNotIn("PlayerGlassOverlay(", source, relative)

    def test_bottom_sheet_surfaces_are_not_opaque_over_glass(self):
        for relative in ("ui/player/controls/components/sheets/AudioPropertiesSheet.kt",
                         "ui/player/controls/components/sheets/EqualizerSheet.kt",
                         "ui/player/controls/components/LyricsView.kt"):
            source = self.source(relative)
            host = source[source.index("ModalBottomSheet("):].split(") {", 1)[0]
            self.assertIn(".playerGlassOverlay()", host, relative)
            self.assertIn("containerColor =", host, relative)
            self.assertIn("Color.Transparent", host, relative)

    def test_all_subtitle_dropdowns_have_one_outer_pane(self):
        source = self.source("ui/player/controls/components/sheets/OnlineSubtitleSearchSheet.kt")
        self.assertEqual(3, source.count("DropdownMenu("))
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

if __name__ == "__main__":
    unittest.main()
