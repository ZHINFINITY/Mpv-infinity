#!/usr/bin/env python3
"""Source contract for the single shared Material3 dropdown menu wrapper."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java"
WRAPPER = JAVA / "app/infinity/mpvz/ui/theme/GlassDropdownMenu.kt"


class GlassDropdownMenuContractTest(unittest.TestCase):
    def test_all_application_menus_use_the_wrapper(self):
        direct = re.compile(r"\b(?:DropdownMenu|ExposedDropdownMenu)\s*\(")
        offenders = []
        for path in JAVA.rglob("*.kt"):
            if path == WRAPPER:
                continue
            if direct.search(path.read_text()):
                offenders.append(str(path.relative_to(ROOT)))
        self.assertEqual([], offenders, f"raw Material3 menu calls: {offenders}")

    def test_wrapper_has_glass_and_classic_branches(self):
        source = WRAPPER.read_text()
        self.assertIn("containerColor = Color.Transparent", source)
        self.assertIn("tonalElevation = 0.dp", source)
        self.assertIn("shadowElevation = 0.dp", source)
        self.assertIn("RoundedCornerShape(20.dp)", source)
        self.assertIn("materialRole = GlassSurfacePolicy.MaterialRole.Regular", source)
        self.assertIn("liquidGlassPane(", source)
        self.assertIn("else {", source)
        self.assertIn("MaterialDropdownMenu(", source)
        self.assertIn("MaterialExposedDropdownMenu(", source)


if __name__ == "__main__":
    unittest.main()
