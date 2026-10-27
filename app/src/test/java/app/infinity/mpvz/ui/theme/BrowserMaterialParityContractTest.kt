/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import java.io.File
import app.infinity.mpvz.ui.theme.lens.LensGlassPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source integration checks complement the actual rendered FolderCard runtime suite. */
class BrowserMaterialParityContractTest {
  private fun source(name: String): String {
    val relative = "src/main/java/app/infinity/mpvz/ui/theme/$name.kt"
    return listOf(File(relative), File("app/$relative")).first { it.exists() }.readText()
  }

  @Test fun browserPanesUseTheNavigationPillMaterial() {
    val scene = source("BrowserGlassScene")
    val pane = scene.substring(scene.indexOf("internal fun Modifier.browserGlassPane("), scene.indexOf("private fun windowScreenOffset"))
    val nav = scene.substring(scene.indexOf("fun BrowserGlassSurface("), scene.indexOf("/** The shared pane path"))
    // Same lens shader, sheen, rim and film as the navigation pill (owner's reference look).
    for (token in listOf("RuntimeShaderSupport.browserLensEffectOrNull(", "film * (if (selected) 0.24f else 0.20f)",
      "Color.White.copy(alpha = 0.20f + glow * 0.65f)", "Color.White.copy(alpha = 0.10f + glow * 0.35f)",
      "0.06f + glow * 0.10f", "0.16f + glow * 0.24f", "(if (selected) 7.dp else 12.dp) * tuning.depth")) {
      assertTrue("browser pane must match nav pill: $token", pane.contains(token))
      assertTrue("nav pill reference changed: $token", nav.contains(token))
    }
    assertFalse("the older depth profile produced the glowing band/rim", pane.contains("GlassSurfacePolicy.depthOptics("))
    assertFalse(pane.contains("BROWSER_LATEST_LENS"))
    // The refracted sample replaces what is behind the pane, so text below never reads through.
    assertTrue(pane.contains("lens.alpha = 1f"))
    assertFalse(pane.contains("browserSampleAlpha(film)"))
    assertTrue(pane.contains("GlassSurfacePolicy.NavigationNoSceneFilmAlpha"))
  }

  @Test fun actualBrowserPaneHonorsGlobalMaterialOverrideAndBorderlessCaller() {
    val scene = source("BrowserGlassScene")
    val pane = scene.substring(scene.indexOf("internal fun Modifier.browserGlassPane("), scene.indexOf("private fun windowScreenOffset"))
    assertTrue(pane.contains("if (tuning.overrideMaterial) tuning.opacity else opacity"))
    assertTrue(pane.contains("if (tuning.overrideMaterial) tuning.highlight else highlight"))
    assertTrue(pane.contains("val floating = enabled && drawRim && !edgeBar"))
    assertTrue(pane.contains("if (floating) drawRoundRect(edge"))
    assertTrue(pane.contains("tuning.refraction"))
    assertTrue(pane.contains("GlassSurfacePolicy.browserPopupFrostDp(tuning.frost)"))
    assertTrue(pane.contains("GlassSurfacePolicy.navigationFrostDp(tuning.frost"))
    assertTrue(pane.contains("val blurFallback = if (Build.VERSION.SDK_INT >= 31 && scene != null && blurDp > 0f)"))
    assertTrue(pane.contains("lens.renderEffect = lensHandle?.effect ?: blurFallback"))
    val dispatch = source("LiquidGlassBackdrop").substringAfter("if (backdrop == null && hasBrowserGlassScene()) {").substringBefore("val tint =")
    assertTrue("Do not add a second frame to borderless controls", dispatch.contains("drawRim = drawRim"))
  }

  @Test fun edgeToEdgeBarsHaveNoGlowAndAmoledBackdropIsPureBlack() {
    val scene = source("BrowserGlassScene")
    val pane = scene.substring(scene.indexOf("internal fun Modifier.browserGlassPane("), scene.indexOf("private fun windowScreenOffset"))
    assertTrue(GlassSurfacePolicy.isEdgeToEdgeChrome(0f))
    assertFalse(GlassSurfacePolicy.isEdgeToEdgeChrome(20f))
    assertTrue(pane.contains("edgeBar -> MaterialTheme.colorScheme.background"))
    assertTrue("edge bars skip the white body sheen", pane.contains("if (!edgeBar) {\n            drawRoundRect(Brush.verticalGradient("))
    // No lens on edge bars (its edge term draws a bright line) and an opaque-enough no-scene film.
    assertTrue(pane.contains("enabled && !edgeBar && capabilities.refraction && RuntimeShaderSupport.available()"))
    assertTrue(pane.contains("edgeBar -> GlassSurfacePolicy.EdgeChromeNoSceneFilmAlpha"))
    // Settings and other screens without a browser scene take the same glow-free edge path.
    val fallback = source("LiquidGlassBackdrop").substringAfter("fun Modifier.liquidGlassPane(").substringBefore("val tint = MaterialTheme.colorScheme.surfaceContainerHigh")
    assertTrue(fallback.contains("if (backdrop == null && GlassSurfacePolicy.isEdgeToEdgeChrome(cornerRadius.value))"))
    assertTrue(fallback.contains("MaterialTheme.colorScheme.background.copy(alpha = GlassSurfacePolicy.EdgeChromeNoSceneFilmAlpha)"))
    assertFalse(fallback.substringAfter("isEdgeToEdgeChrome(cornerRadius.value)").contains("Stroke("))
    assertTrue(GlassSurfacePolicy.EdgeChromeNoSceneFilmAlpha >= 0.85f)
    val producer = scene.substring(scene.indexOf("val amoledBackdrop"), scene.indexOf("val producerBrush"))
    assertTrue(producer.contains("MaterialTheme.colorScheme.background == Color.Black"))
    assertTrue(producer.contains("if (amoledBackdrop) Color.Transparent else MaterialTheme.colorScheme.primary"))
    assertTrue(producer.contains("if (amoledBackdrop) Color.Transparent else MaterialTheme.colorScheme.secondary"))
  }

  @Test fun popupFrostFollowsTheGlobalFrostSettingAboveAReadableFloor() {
    assertTrue(GlassSurfacePolicy.browserPopupFrostDp(0f) >= 14f)
    assertTrue(GlassSurfacePolicy.browserPopupFrostDp(2f) > GlassSurfacePolicy.browserPopupFrostDp(1f))
    assertTrue(GlassSurfacePolicy.browserPopupFrostDp(1f) > GlassSurfacePolicy.browserPopupFrostDp(0f))
    assertTrue(GlassSurfacePolicy.browserPopupFrostDp(Float.NaN).isFinite())
    assertTrue(GlassSurfacePolicy.browserPopupFrostDp(99f) == GlassSurfacePolicy.browserPopupFrostDp(2f))
    assertTrue(GlassSurfacePolicy.navigationFrostDp(1f, refraction = true) == 2f)
    assertTrue(GlassSurfacePolicy.navigationFrostDp(1f, refraction = false) == 3f)
    assertTrue(GlassSurfacePolicy.navigationFrostDp(0f, refraction = true) == 0f)
  }

  @Test fun fallbackLensRefractionUsesTheActualPaneCornerRadius() {
    val liquid = source("LiquidGlassBackdrop")
    assertTrue(liquid.contains("cornerRadius.toPx().coerceAtMost(size.minDimension / 2f)"))
    assertTrue(liquid.contains("LensGlassPolicy.refractionHeightPx(24.dp.toPx(), minCornerRadiusPx, tuning.lensDepth)"))
    assertEquals(16f, LensGlassPolicy.refractionHeightPx(24f, 16f, 1f), 0.001f)
  }

  @Test fun visibleStaticDepthDoesNotDependOnSuccessfulGpuRefraction() {
    for (depth in listOf(0f, 0.5f, 1f, 2f)) {
      val profile = GlassSurfacePolicy.depthOptics(depth, 0.18f)
      assertTrue(profile.bodyHighlight in 0f..0.30f)
      assertTrue(profile.bodyShade in 0f..0.20f)
      assertTrue(GlassSurfacePolicy.browserFallbackAlpha(1f) < 0.3f)
    }
    assertTrue(GlassSurfacePolicy.depthOptics(1f, 0.18f).bodyHighlight >= 0.15f)
    assertTrue(GlassSurfacePolicy.depthOptics(2f, 0.18f).bodyHighlight >
      GlassSurfacePolicy.depthOptics(0f, 0.18f).bodyHighlight)
  }

  @Test fun browserSurfaceKeepsReferenceLensAndReferenceFilmSemantics() {
    val scene = source("BrowserGlassScene")
    val browser = scene.substring(scene.indexOf("fun BrowserGlassSurface("), scene.indexOf("/** The shared pane path"))
    assertTrue(browser.contains("RuntimeShaderSupport.browserLensEffectOrNull("))
    assertTrue(browser.contains("val refractionEnabled = enabled && selected && lensEnabled && capabilities.refraction && RuntimeShaderSupport.available()"))
    assertTrue(browser.contains("val browserLensShader = remember(refractionEnabled)"))
    assertTrue(browser.contains("RuntimeShaderSupport.browserLensShaderOrNull()"))
    assertTrue(browser.contains("shader = browserLensShader"))
    val shaders = source("AgslRuntimeShaders")
    val effectBody = shaders.substringAfter("fun browserLensEffect(").substringBefore("private fun browserLensEffectUnchecked(")
    assertTrue(effectBody.contains("val runtimeShader = shader as? RuntimeShader"))
    assertFalse(effectBody.contains("browserLens()"))
    assertTrue(browser.contains("val blurFallback = if (Build.VERSION.SDK_INT >= 31 && scene != null && tuning.frost > 0f)"))
    assertTrue(browser.contains("lens.renderEffect = lensEffect ?: blurFallback"))
    assertTrue(shaders.contains("shader.setFloatUniform(\"bend\""))
    assertTrue(shaders.contains("shader.setFloatUniform(\"motion\""))
    assertTrue(browser.contains("film * (if (selected) 0.24f else 0.20f)"))
    assertTrue(browser.contains("Color.White.copy(alpha = if (selected) 0.16f + glow * 0.24f else 0.06f + glow * 0.10f)"))
    assertTrue(browser.contains("style = Stroke(1.dp.toPx())"))
    assertFalse(browser.contains("browserGlassPane(enabled"))
    assertTrue(scene.contains("private const val BROWSER_LATEST_LENS"))
  }

  @Test fun navigationCaptureUsesIndependentProducerAndConsumerScenes() {
    val scene = source("BrowserGlassScene")
    val route = scene.substring(scene.indexOf("fun BrowserGlassRouteContentHost("), scene.indexOf("/**\n * Compatibility marker"))
    assertTrue(route.contains("navLayer.record"))
    assertTrue(route.contains("LocalBrowserScene provides navContentScene"))
    assertTrue(route.contains("LocalBrowserScene provides inheritedRootScene"))
    assertTrue(route.contains("navContentScene.isRecording = true"))
    assertTrue(route.contains("navContentScene.isRecording = false"))
    assertTrue(scene.contains("canDrawRecordedScene"))
  }
}
