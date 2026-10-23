#!/usr/bin/env python3
"""Reference browser geometry and bounded, independent navigation capture contracts."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"
SCENE = (JAVA / "ui/theme/BrowserGlassScene.kt").read_text()
MAIN = (JAVA / "ui/browser/MainScreen.kt").read_text()
ACTIVITY = (JAVA / "MainActivity.kt").read_text()
FOLDERS = (JAVA / "ui/browser/folderlist/FolderListScreen.kt").read_text()
CARD = (JAVA / "ui/browser/cards/FolderCard.kt").read_text()

class BrowserGlassRouteContentContracts(unittest.TestCase):
    def test_route_producer_is_separate_from_navigation_consumer(self):
        host = SCENE.split("fun BrowserGlassRouteContentHost", 1)[1].split("/**\n * Compatibility marker", 1)[0]
        self.assertIn("val navContentScene = remember(navLayer) { BrowserScene(navLayer, contentLens = true) }", host)
        self.assertIn("navLayer.record(size = IntSize(size.width.toInt(), stripHeight))", host)
        self.assertIn("coerceAtMost(BrowserGlassStripHeight)", host)
        self.assertIn("navigationContent: @Composable BoxScope.() -> Unit", host)
        self.assertIn("LocalBrowserScene provides navContentScene", host)
        self.assertIn("LocalBrowserScene provides inheritedRootScene", host)
        self.assertIn("drawContent()", host)
        self.assertIn("GlassScenePolicy.validCaptureSize", host)
        self.assertIn("navContentScene.invalidate()", host)
        self.assertIn("finally", host)
        self.assertIn("navContentScene.isRecording = false", host)
        for forbidden in ("PixelCopy", "screenshot", "delay("):
            self.assertNotIn(forbidden, host)

    def test_main_screen_places_nav_outside_captured_route_without_global_host(self):
        self.assertIn("NavDisplay(", ACTIVITY)
        self.assertNotIn("BrowserGlassSceneHost(", ACTIVITY)
        self.assertNotIn(".browserGlassSceneSource()", ACTIVITY)
        self.assertIn("BrowserGlassRouteContentHost(", MAIN)
        self.assertLess(MAIN.index("routeContent = {"), MAIN.index("navigationContent = {"))
        self.assertLess(MAIN.index("Browser source ends here"), MAIN.index("navigationContent = {"))

    def test_reference_home_grid_list_and_card_dimensions_are_not_glass_dependent(self):
        grid = FOLDERS.split("private fun GridContent(", 1)[1].split("private fun ListContent(", 1)[0]
        rows = FOLDERS.split("private fun ListContent(", 1)[1].split("private fun SearchResultsContent(", 1)[0]
        self.assertIn("val itemSpacing = 2.dp", grid)
        self.assertEqual(2, grid.count("Arrangement.spacedBy(2.dp)"))
        for section in (grid, rows):
            self.assertIn("start = 8.dp", section)
            self.assertIn("end = 8.dp", section)
            self.assertIn("bottom = navigationBarHeight", section)
            self.assertNotIn("BrowserLayoutPolicy", section)
            self.assertNotIn("else 12.dp", section)
        self.assertIn("val horizontalPadding = 32.dp", CARD)
        self.assertIn("val spacing = 8.dp", CARD)
        self.assertIn("val cardShape = AppShapeScale.large", CARD)
        self.assertIn(".padding(12.dp)", CARD)
        self.assertNotIn("BrowserGlassSurface(", CARD)
        self.assertIn("getFolderThumbnailFromMemory(folder.bucketId)", CARD)

if __name__ == "__main__":
    unittest.main()
