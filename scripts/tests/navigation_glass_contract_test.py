#!/usr/bin/env python3
"""Integration guards; geometry is JVM-tested and appearance still needs a phone."""
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

    def test_finite_spring_is_separate_from_pager_and_drag_follows_finger(self):
        source = self.section()
        for token in ("rememberBrowserGlassMotionAllowed()", "collectIsDraggedAsState()", "Animatable(position)",
                      "lensPosition.snapTo(position)", "lensPosition.animateTo(targetIndex, spring(",
                      "if (fingerDragging) position else 0f", "lensSquash", "NavigationGlassMotionPolicy"):
            self.assertIn(token, source)
        self.assertNotIn("isScrollInProgress", source)  # Programmatic pager animation is not a finger drag.
        for forbidden in ("while (", "rememberInfiniteTransition", "withFrameNanos", "PixelCopy"):
            self.assertNotIn(forbidden, source)

    def test_only_browser_content_is_recorded_not_the_navigation(self):
        source = MAIN.read_text()
        self.assertIn("BrowserGlassSceneHost(", source)
        self.assertIn("enabled = liquidGlassSurfacesForBrowser,", source)
        self.assertNotIn("liquidGlassSurfacesForBrowser && !hideNavigationBar", source)
        self.assertEqual(1, source.count("BrowserGlassRouteContentHost("))
        self.assertLess(source.index("routeContent = {"), source.index("Browser source ends here"))
        self.assertLess(source.index("Browser source ends here"), source.index("navigationContent = {"))
        self.assertLess(source.index("Browser source ends here"), source.index("// Animated bottom navigation bar"))
        renderer = RENDERER.read_text()
        self.assertIn("fun Modifier.browserGlassSceneSource(): Modifier = this", renderer)
        self.assertIn("if (background != null) background()", renderer)
        producer = renderer.split("if (ownsScene && enabled)", 1)[1].split("// A single call site", 1)[0]
        self.assertIn("LocalBrowserScene provides null", producer)
        self.assertIn("this@drawWithContent.drawContent()", producer)
        self.assertNotIn("\n      content()", producer)
        theme = (JAVA / "ui/theme/Theme.kt").read_text()
        self.assertIn("val glassEnabled by preferences.liquidGlassSurfaces.collectAsState()", theme)
        self.assertIn("BrowserGlassSceneHost(", theme)
        self.assertIn("if (showCustomThemeBackdrop) {\n              BrowserGlassSceneHost(", theme)
        activity = (JAVA / "ui/player/PlayerActivity.kt").read_text()
        self.assertIn("MpvInfinityTheme(showCustomThemeBackdrop = false)", activity)
        self.assertIn("scene.layer.record(size = IntSize", renderer)
        for forbidden in ("PixelCopy.request", "Bitmap.createBitmap", "toImageBitmap", "delay(", "while ("):
            self.assertNotIn(forbidden, renderer)

    def test_lens_only_affects_background_and_capability_fallbacks_are_explicit(self):
        source = RENDERER.read_text().split("fun BrowserGlassSurface(", 1)[1]
        self.assertLess(source.index("val shader = remember(enabled, capabilities.refraction)"), source.index(".drawWithCache {"))
        self.assertIn("RuntimeShader(BROWSER_LENS)", source)
        self.assertIn('createRuntimeShaderEffect(shader, "scene")', source)
        self.assertIn("lens.renderEffect = effect", source)
        self.assertIn("drawLayer(scene!!.layer)", source)
        self.assertIn("Box(modifier.browserGlassPane(", source)
        self.assertIn("lens.alpha = if (scene?.contentLens == true) 1f else GlassSurfacePolicy.browserSampleAlpha(film)", source)
        self.assertIn("GlassSurfacePolicy.browserFallbackAlpha(film)", source)
        source += RENDERER.read_text().split("fun BrowserGlassSurface(", 1)[0]
        for token in ("!lowRam && !powerSave && thermal < 3", "ANIMATOR_DURATION_SCALE", "areAnimatorsEnabled()",
                      "Build.VERSION.SDK_INT >= 33", "Build.VERSION.SDK_INT >= 31", "if (!enabled) Modifier.background"):
            self.assertIn(token, source)

    def test_policy_is_pure_finite_and_bounded(self):
        source = POLICY.read_text()
        self.assertIn("const val MaxStretch = 0.18f", source)
        self.assertIn("coerceIn(-MaxDeformation, MaxDeformation)", source)
        self.assertIn("isFinite()", source)
        for token in ("LaunchedEffect", "Animatable", "android.", "kotlinx.coroutines"):
            self.assertNotIn(token, source)

if __name__ == "__main__":
    unittest.main()
