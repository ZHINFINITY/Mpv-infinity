#!/usr/bin/env python3
"""Reference navigation optics and safe capture; appearance still needs phone testing."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/infinity/mpvz"
MAIN = JAVA / "ui/browser/MainScreen.kt"
POLICY = JAVA / "ui/browser/NavigationGlassMotionPolicy.kt"
RENDERER = JAVA / "ui/theme/BrowserGlassScene.kt"

class NavigationGlassContracts(unittest.TestCase):
    def section(self):
        source = MAIN.read_text()
        return source[source.index("private fun ExpressivePillNavigationBar"):source.index("val LocalNavigationBarHeight")]

    def test_outer_and_selected_lenses_replace_opaque_flat_fill(self):
        source = self.section()
        self.assertEqual(2, source.count("BrowserGlassSurface("))
        self.assertIn("selected = true", source)
        self.assertIn("deformation = kotlin.math.abs(lensGeometry.deformation)", source)
        self.assertNotIn("background(MaterialTheme.colorScheme.primaryContainer)", source)
        self.assertIn("enabled = liquidGlassSurfaces", source)

    def test_finite_reference_spring_and_finger_drag_remain(self):
        source = self.section()
        for token in ("rememberBrowserGlassMotionAllowed()", "collectIsDraggedAsState()", "Animatable(position)",
                      "lensPosition.snapTo(position)", "lensPosition.animateTo(targetIndex, spring(",
                      "if (fingerDragging) position else 0f", "lensSquash", "NavigationGlassMotionPolicy"):
            self.assertIn(token, source)
        self.assertNotIn("isScrollInProgress", source)
        for forbidden in ("while (", "rememberInfiniteTransition", "withFrameNanos", "PixelCopy"):
            self.assertNotIn(forbidden, source)

    def test_reference_strip_records_browser_only_and_never_navigation(self):
        source = MAIN.read_text()
        self.assertIn("liquidGlassSurfacesForBrowser && !hideNavigationBar", source)
        self.assertEqual(1, source.count("BrowserGlassRouteContentHost("))
        self.assertLess(source.index("routeContent = {"), source.index("Browser source ends here"))
        self.assertLess(source.index("Browser source ends here"), source.index("navigationContent = {"))
        renderer = RENDERER.read_text()
        host = renderer.split("fun BrowserGlassRouteContentHost(", 1)[1].split("/**\n * Compatibility marker", 1)[0]
        self.assertIn("LocalBrowserScene provides inheritedRootScene", host)
        self.assertIn("LocalBrowserScene provides navContentScene", host)
        self.assertIn("coerceAtMost(BrowserGlassStripHeight)", host)
        self.assertIn("private const val BrowserGlassStripHeight = 384", renderer)
        self.assertIn("navLayer.record(size = IntSize(size.width.toInt(), stripHeight))", host)
        self.assertIn("translate(top = -stripTop)", host)
        self.assertIn("drawContent()", host)
        self.assertIn("finally", host)
        self.assertIn("navContentScene.isRecording = false", host)
        self.assertIn("canDrawRecordedScene", renderer)
        activity = (JAVA / "ui/player/PlayerActivity.kt").read_text()
        self.assertIn("MpvInfinityTheme(showCustomThemeBackdrop = false)", activity)
        for forbidden in ("PixelCopy.request", "Bitmap.createBitmap", "toImageBitmap", "delay(", "while ("):
            self.assertNotIn(forbidden, renderer)

    def test_reference_lens_is_separate_from_latest_controls_and_capability_fallbacks(self):
        renderer = RENDERER.read_text()
        source = renderer.split("fun BrowserGlassSurface(", 1)[1].split("/** The shared pane path", 1)[0]
        self.assertLess(source.index("val shader = remember(enabled, capabilities.refraction)"), source.index(".drawWithCache {"))
        self.assertIn("RuntimeShader(BROWSER_LENS)", source)
        self.assertIn('createRuntimeShaderEffect(shader, "scene")', source)
        self.assertIn("lens.renderEffect = effect", source)
        self.assertIn("drawLayer(scene!!.layer)", source)
        self.assertIn("film * (if (selected) 0.24f else 0.20f)", source)
        self.assertNotIn("browserGlassPane(enabled", source)
        self.assertIn("RuntimeShader(BROWSER_LATEST_LENS)", renderer)
        self.assertIn("GlassSurfacePolicy.depthOptics(tuning.depth, glow)", renderer)
        for token in ("!lowRam && !powerSave && thermal < 3", "ANIMATOR_DURATION_SCALE", "areAnimatorsEnabled()",
                      "Build.VERSION.SDK_INT >= 33", "Build.VERSION.SDK_INT >= 31", "if (!enabled) Modifier.background"):
            self.assertIn(token, renderer)

    def test_policy_is_pure_finite_and_bounded(self):
        source = POLICY.read_text()
        self.assertIn("const val MaxStretch = 0.18f", source)
        self.assertIn("coerceIn(-MaxDeformation, MaxDeformation)", source)
        self.assertIn("isFinite()", source)
        for token in ("LaunchedEffect", "Animatable", "android.", "kotlinx.coroutines"):
            self.assertNotIn(token, source)

if __name__ == "__main__":
    unittest.main()
