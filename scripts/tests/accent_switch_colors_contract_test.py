#!/usr/bin/env python3
"""Contract checks for theme-accent switch colors and shared routing."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java"
GLASS = JAVA / "app/infinity/mpvz/ui/player/controls/components/GlassControls.kt"
EXPRESSIVE = JAVA / "app/infinity/mpvz/ui/player/components/expressive/ExpressiveSwitch.kt"
ALLOW_RAW = {GLASS}

class AccentSwitchColorsContract(unittest.TestCase):
    def test_shared_switch_has_accent_checked_and_visible_unchecked_colors(self):
        source = GLASS.read_text()
        self.assertIn("checkedTrackColor = contrast.checkedTrack", source)
        self.assertIn("checkedThumbColor = contrast.checkedThumb", source)
        self.assertIn("uncheckedTrackColor = contrast.uncheckedTrack", source)
        self.assertIn("uncheckedThumbColor = contrast.uncheckedThumb", source)
        # Build 1910: GlassSwitch now routes classic colors through tuned contrast colors.
        self.assertIn("colors = if (glass) transparent else tuned", source)
        # Build 1910: capsule rendering uses the shared contrast policy instead of legacy tint/body tokens.
        self.assertIn("contrast.checkedTrack", source)
        self.assertIn("contrast.uncheckedTrack", source)
        self.assertIn("checked = checked", source)

    def test_expressive_switch_uses_primary_and_contrasting_thumb(self):
        source = EXPRESSIVE.read_text()
        self.assertIn("SwitchContrastPolicy", source)
        self.assertIn("contrast.uncheckedTrack", source)
        self.assertIn("contrast.checkedThumb", source)
        self.assertIn("contrast.uncheckedThumb", source)

    def test_no_raw_material_switch_outside_shared_component(self):
        for path in JAVA.rglob("*.kt"):
            source = path.read_text()
            if path in ALLOW_RAW:
                continue
            self.assertNotRegex(source, r"androidx\.compose\.material3\.Switch\s*\(", str(path))
            self.assertNotRegex(source, r"(?<![A-Za-z])Switch\s*\(", str(path))

if __name__ == "__main__":
    unittest.main()
