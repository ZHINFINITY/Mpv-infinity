#!/usr/bin/env python3
"""Static contract checks for inset translucent standalone player-sheet rows."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"


def read(relative: str) -> str:
    return (JAVA / relative).read_text()


class PlayerSheetSettingRowsContractTest(unittest.TestCase):
    def test_shared_helper_defines_rounded_translucent_material(self):
        helper = read("ui/player/controls/components/sheets/PlayerSheetSettingRow.kt")
        self.assertIn("RoundedCornerShape(16.dp)", helper)
        self.assertIn("width = 1.dp", helper)
        self.assertIn("onSurface.copy(alpha = 0.07f)", helper)
        self.assertIn("onSurface.copy(alpha = 0.14f)", helper)
        self.assertIn("accent.copy(alpha = 0.16f)", helper)
        self.assertIn("accent.copy(alpha = 0.5f)", helper)
        self.assertIn("padding(horizontal = MaterialTheme.spacing.medium)", helper)

    def test_auto_crop_and_pitch_use_shared_row(self):
        aspect = read("ui/player/controls/components/sheets/AspectRatioSheet.kt")
        speed = read("ui/player/controls/components/sheets/PlaybackSpeedSheet.kt")
        self.assertIn("ui_auto_crop_black_bars", aspect)
        self.assertIn(".playerSheetSettingRow(active = autoCropEnabled)", aspect)
        self.assertIn(".playerSheetSettingRow(active = pitchCorrection)", speed)
        self.assertNotIn("ListItem(", aspect)
        self.assertNotIn("Color.White", aspect)
        self.assertNotIn("surfaceContainer", aspect)
        self.assertNotIn("Color.White", speed)

    def test_similar_standalone_toggle_rows_use_shared_row(self):
        subtitle = read("ui/player/controls/components/sheets/SubtitleTracksSheet.kt")
        zoom = read("ui/player/controls/components/sheets/VideoZoomSheet.kt")
        self.assertIn(".playerSheetSettingRow(active = translationEnabled)", subtitle)
        self.assertIn(".playerSheetSettingRow(active = panAndZoomEnabled)", zoom)


if __name__ == "__main__":
    unittest.main()
