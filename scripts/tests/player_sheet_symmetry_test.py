#!/usr/bin/env python3
"""Static guards for bounded player-sheet geometry and glass surface routing."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"


def read(relative: str) -> str:
    return (JAVA / relative).read_text()


class PlayerSheetSymmetryTest(unittest.TestCase):
    def test_more_grid_uses_fixed_equal_rows_not_flow_precision(self):
        drawer = read("ui/player/controls/PlayerControlDrawer.kt")
        self.assertNotIn("FlowRow", drawer)
        self.assertIn("buttons.chunked(PlayerControlGridPolicy.columnCount)", drawer)
        self.assertIn("Modifier.weight(1f)", drawer)
        self.assertIn("PlayerControlGridPolicy.columnCount", drawer)
        self.assertNotIn("ExperimentalLayoutApi", drawer)

    def test_sheet_and_scroll_body_are_clipped_and_bounded(self):
        sheet = read("presentation/components/PlayerSheet.kt")
        panel = read("ui/player/controls/components/panels/DraggablePanel.kt")
        self.assertIn(".clip(sheetShape)", sheet)
        self.assertIn(".heightIn(max = panelMaxHeight)", panel)
        self.assertIn(".fillMaxHeight()", panel)
        self.assertIn("import androidx.compose.foundation.layout.fillMaxHeight", panel)
        self.assertIn("Column(modifier = Modifier.fillMaxSize())", panel)
        self.assertIn(".verticalScroll(rememberScrollState())", panel)

    def test_playlist_inner_root_has_theme_fallback_and_shape_clip(self):
        playlist = read("ui/player/controls/components/sheets/PlaylistSheet.kt")
        self.assertIn("liquidGlassSurfaces", playlist)
        self.assertIn("Color.Transparent", playlist)
        self.assertIn(".clip(", playlist)
        self.assertIn("topStart = 16.dp", playlist)
        self.assertIn("MaterialTheme.colorScheme.surfaceContainerLow", playlist)

    def test_decoder_tabs_do_not_reintroduce_an_opaque_header(self):
        decoder = read("ui/player/controls/components/sheets/DecodersSheet.kt")
        self.assertIn("ScrollableTabRow(", decoder)
        self.assertIn("containerColor = Color.Transparent", decoder)
        self.assertIn("contentColor = MaterialTheme.colorScheme.onSurface", decoder)
        self.assertIn("divider = {}", decoder)

    def test_runtime_callers_wire_owned_components(self):
        drawer = read("ui/player/controls/PlayerControlDrawer.kt")
        playlist = read("ui/player/controls/components/sheets/PlaylistSheet.kt")
        self.assertIn("DraggablePanel(", drawer)
        self.assertIn("PlayerSheet(", playlist)
        self.assertIn("LazyVerticalGrid(", playlist)

    def test_actual_portrait_and_landscape_header_pills_use_glass(self):
        for filename in ("PlayerControlsPortrait.kt", "PlayerControlsLandscape.kt"):
            header = read("ui/player/controls/" + filename)
            self.assertIn("liquidGlassSurfaces by appearance.liquidGlassSurfaces.collectAsState()", header)
            self.assertIn(".playerGlassPane(enabled = liquidGlassSurfaces, cornerRadius = 999.dp)", header)
            self.assertGreaterEqual(header.count("if (liquidGlassSurfaces || hideBackground)"), 2)
            self.assertIn("shadowElevation = 0.dp", header)


if __name__ == "__main__":
    unittest.main()
