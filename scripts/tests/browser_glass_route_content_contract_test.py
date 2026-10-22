#!/usr/bin/env python3
"""Contracts for the bounded navigation-content glass producer."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"
SCENE = (JAVA / "ui/theme/BrowserGlassScene.kt").read_text()
MAIN = (JAVA / "ui/browser/MainScreen.kt").read_text()
FOLDERS = (JAVA / "ui/browser/folderlist/FolderListScreen.kt").read_text()
CARD = (JAVA / "ui/browser/cards/FolderCard.kt").read_text()


class BrowserGlassRouteContentContracts(unittest.TestCase):
    def test_route_producer_is_separate_from_navigation_consumer(self):
        host = SCENE[SCENE.index("fun BrowserGlassRouteContentHost"):SCENE.index("/**\n * Compatibility marker", SCENE.index("fun BrowserGlassRouteContentHost"))]
        self.assertIn("val navContentScene = remember(navLayer) { BrowserScene(navLayer, contentLens = true) }", host)
        self.assertIn("navLayer.record(size = IntSize", host)
        self.assertIn("drawLayer(navLayer)", host)
        self.assertIn("lens.alpha = if (scene?.contentLens == true) 1f else GlassSurfacePolicy.browserSampleAlpha(film)", SCENE)
        self.assertIn("navigationContent: @Composable BoxScope.() -> Unit", host)
        self.assertIn("LocalBrowserScene provides navContentScene", host)
        self.assertIn("drawContent()", host)
        self.assertIn("GlassScenePolicy.validCaptureSize", host)
        self.assertIn("navContentScene.invalidate()", host)
        self.assertIn("finally", host)
        self.assertIn("navContentScene.isRecording = false", host)
        self.assertNotIn("PixelCopy", host)
        self.assertNotIn("screenshot", host.lower())
        self.assertNotIn("delay(", host)

    def test_main_screen_places_nav_outside_captured_route(self):
        block = MAIN[MAIN.index("BrowserGlassRouteContentHost("):MAIN.index("\n@Composable\nprivate fun ExpressivePillNavigationBar")]
        self.assertLess(block.index("routeContent ="), block.index("navigationContent ="))
        self.assertIn("ExpressivePillNavigationBar", block)
        self.assertIn("routeContent = {", block)
        self.assertIn("navigationContent = {", block)

    def test_home_spacing_keeps_last_rows_reachable(self):
        self.assertIn("bottom = navigationBarHeight + 24.dp", FOLDERS)
        self.assertIn("start = if (glassEnabled) geometry.edgeInset else 16.dp", FOLDERS)
        self.assertIn("end = if (glassEnabled) geometry.edgeInset else 16.dp", FOLDERS)
        self.assertIn("Arrangement.spacedBy(if (glassEnabled) geometry.gridGap else 12.dp)", FOLDERS)
        self.assertIn("Arrangement.spacedBy(if (glassEnabled) geometry.listGap else 12.dp)", FOLDERS)
        self.assertIn("padding(horizontal = 14.dp, vertical = 16.dp)", CARD)


if __name__ == "__main__":
    unittest.main()
