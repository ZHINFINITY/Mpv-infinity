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
        self.assertIn("checkedTrackColor = accent", source)
        self.assertIn("checkedThumbColor = scheme.onPrimary", source)
        self.assertIn("uncheckedTrackColor = scheme.onSurface.copy(alpha = 0.20f)", source)
        self.assertIn("uncheckedThumbColor = scheme.onSurfaceVariant", source)
        self.assertIn("colors = if (!glass) themedColors", source)
        self.assertIn("tint = if (checked) scheme.primary else scheme.onSurface", source)
        self.assertIn("body = if (checked) scheme.onPrimary else scheme.onSurfaceVariant", source)
        self.assertIn("checked = checked", source)

    def test_expressive_switch_uses_primary_and_contrasting_thumb(self):
        source = EXPRESSIVE.read_text()
        self.assertIn("MaterialTheme.colorScheme.primary", source)
        self.assertIn("onSurface.copy(alpha = 0.20f)", source)
        self.assertIn("MaterialTheme.colorScheme.onPrimary", source)
        self.assertIn("MaterialTheme.colorScheme.onSurfaceVariant", source)

    def test_no_raw_material_switch_outside_shared_component(self):
        for path in JAVA.rglob("*.kt"):
            source = path.read_text()
            if path in ALLOW_RAW:
                continue
            self.assertNotRegex(source, r"androidx\.compose\.material3\.Switch\s*\(", str(path))
            self.assertNotRegex(source, r"(?<![A-Za-z])Switch\s*\(", str(path))

if __name__ == "__main__":
    unittest.main()
