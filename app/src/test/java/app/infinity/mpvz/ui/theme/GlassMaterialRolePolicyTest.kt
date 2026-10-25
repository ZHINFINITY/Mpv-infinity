/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassMaterialRolePolicyTest {
  private val sourceRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .map { File(it, "app/src/main/java/app/infinity/mpvz") }.first { it.isDirectory }
  private fun source(path: String) = File(sourceRoot, path).readText()

  private fun blend(underlay: Color, tint: Color, alpha: Float): Color = Color(
    underlay.red * (1f - alpha) + tint.red * alpha,
    underlay.green * (1f - alpha) + tint.green * alpha,
    underlay.blue * (1f - alpha) + tint.blue * alpha,
  )

  private fun luminance(color: Color): Float {
    fun linear(channel: Float): Float = if (channel <= 0.04045f) channel / 12.92f
      else ((channel + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * linear(color.red) + 0.7152f * linear(color.green) + 0.0722f * linear(color.blue)
  }

  @Test fun clearControlsPreserveTheReferencePlayerMaterial() {
    val clear = GlassSurfacePolicy.materialTuning(GlassSurfacePolicy.MaterialRole.Clear)
    assertEquals(1f, clear.frostMultiplier, 0f)
    assertEquals(0.32f, clear.tintAlpha, 0f)
    assertEquals(1f, clear.refractionMultiplier, 0f)
  }

  @Test fun regularRoleHasMoreFrostAndTintButLessRefractionThanClearRole() {
    val clear = GlassSurfacePolicy.materialTuning(GlassSurfacePolicy.MaterialRole.Clear)
    val regular = GlassSurfacePolicy.materialTuning(GlassSurfacePolicy.MaterialRole.Regular)
    assertTrue(regular.frostMultiplier > clear.frostMultiplier)
    assertTrue(regular.tintAlpha > clear.tintAlpha)
    assertTrue(regular.refractionMultiplier < clear.refractionMultiplier)
    assertTrue(GlassSurfacePolicy.RegularMaximumFilmAlpha <= 0.5f)
  }

  @Test fun independentSettingsAndPlayerPopupsRouteToRegularMaterial() {
    val settings = source("ui/preferences/components/SettingsGlass.kt")
    assertTrue(settings.contains("if (independent) GlassSurfacePolicy.MaterialRole.Regular"))
    assertTrue(settings.contains("tuning.opacity") && settings.contains("tuning.highlight"))
    assertTrue(source("presentation/components/PlayerSheet.kt").contains("materialRole = GlassSurfacePolicy.MaterialRole.Regular"))
    assertTrue(source("presentation/components/PlayerGlassOverlays.kt").contains("materialRole = GlassSurfacePolicy.MaterialRole.Regular"))
  }

  private fun gray(v: Float) = Color(v, v, v)

  @Test fun regularFilmIsTheSeeThroughReferenceFilmOverOrdinaryVideo() {
    // 593922 target / bbaf44b8 / 0dd2a9cd: tint * opacity * 0.42 (0.30..0.42), not an 88-92% slab.
    val ambient = Color(0xFF1E2A33)
    for (opacity in listOf(0.72f, 0.86f, 1f)) {
      for (u in listOf(0f, 0.15f, 0.30f)) {
        val a = GlassSurfacePolicy.regularAdaptiveFilmAlpha(opacity, u, ambient, Color.White, 0.08f, 0.12f)
        assertEquals("opacity $opacity underlay $u", GlassSurfacePolicy.opacity(opacity) * 0.42f, a, 1e-4f)
      }
    }
  }

  @Test fun noRegularSheetOrPopupFilmIsOpaqueOrDarkEvenOverWhite() {
    for (opacity in listOf(0.72f, 0.86f, 1f, Float.NaN, 100f, -1f)) {
      for (u in listOf(0f, 0.5f, 1f, Float.NaN, 9f)) {
        for (tint in listOf(Color.Black, Color(0xFF36343B), Color(0xFFECE6F0))) {
          for (text in listOf(Color.White, Color.Black)) {
            val a = GlassSurfacePolicy.regularAdaptiveFilmAlpha(opacity, u, tint, text, 0.30f, 0.30f)
            assertTrue("film $a", a.isFinite() && a >= 0.30f - 1e-4f && a <= 0.50f + 1e-4f)
          }
        }
      }
    }
  }

  @Test fun brighterBackdropsOnlyRaiseTheFilmAndTypicalVideoKeepsWhiteTextReadable() {
    val tint = Color(0xFF202020)
    var last = 0f
    for (step in 0..20) {
      val u = step / 20f
      val a = GlassSurfacePolicy.regularAdaptiveFilmAlpha(0.86f, u, tint, Color.White, 0.10f, 0.10f)
      assertTrue("monotone at $u", a >= last - 1e-4f)
      last = a
      if (u <= 0.55f) {
        val bg = blend(gray(u), tint, a)
        val lit = blend(bg, Color.White, 0.10f)
        for (c in listOf(bg, lit)) assertTrue("white text over $u", GlassSurfacePolicy.contrastRatio(luminance(Color.White), luminance(c)) >= 4.5f)
      }
    }
  }

  @Test fun underlayLumaUsesABrightPercentileOfTheFrostedSample() {
    val dark = IntArray(1000) { 0xFF101010.toInt() }
    assertTrue(GlassSurfacePolicy.underlayLuma(dark) < 0.1f)
    // 20% bright highlights must dominate the estimate (85th percentile), unlike the mean.
    val mixed = IntArray(1000) { if (it % 5 == 0) 0xFFF0F0F0.toInt() else 0xFF101010.toInt() }
    assertTrue(GlassSurfacePolicy.underlayLuma(mixed, step = 1) > 0.9f)
    assertEquals(1f, GlassSurfacePolicy.underlayLuma(IntArray(0)), 0f)
    // Browser popups estimate the page, plus margin for thumbnails behind them.
    assertTrue(GlassSurfacePolicy.browserUnderlayEstimate(Color(0xFF121212)) in 0.25f..0.35f)
  }

  @Test fun regularFrostAndFilmSurviveNoSceneAndZeroFrostFallbackBranches() {
    assertTrue(GlassSurfacePolicy.RegularMinimumFrostDp >= 12f)
    val browser = source("ui/theme/BrowserGlassScene.kt")
    val player = source("ui/theme/LiquidGlassBackdrop.kt")
    // Player sheets keep the 12dp floor; browser popups use the stronger popup floor below.
    assertTrue(player.contains("maxOf(GlassSurfacePolicy.RegularMinimumFrostDp"))
    assertTrue(browser.contains("regular -> GlassSurfacePolicy.RegularNoSceneFilmAlpha"))
    assertTrue(player.contains("val filmAlpha = if (materialRole == GlassSurfacePolicy.MaterialRole.Regular) GlassSurfacePolicy.filmAlpha(centerFilm, highContrast)"))
    assertTrue(browser.contains("val blurDp = if (regular) GlassSurfacePolicy.browserPopupFrostDp(tuning.frost)"))
    // Popups blur the library/settings page enough that its text cannot compete with the popup's.
    assertTrue(GlassSurfacePolicy.BrowserPopupMinimumFrostDp >= 20f)
    // Popup frost follows the global frost setting and never drops below the text floor.
    assertTrue(GlassSurfacePolicy.browserPopupFrostDp(0f) >= GlassSurfacePolicy.BrowserPopupTextFrostFloorDp)
    // API 31 fallback still blurs in the pane; the API 33 chain lives in AgslRuntimeShaders.
    assertEquals(1, Regex("createBlurEffect\\(blurDp \\* density, blurDp \\* density").findAll(browser).count())
    assertTrue(source("ui/theme/AgslRuntimeShaders.kt").contains("createBlurEffect(frostPx, frostPx"))
    // Both renderers solve the regular film instead of using a fixed opaque value.
    for (renderer in listOf(browser, player)) assertTrue(renderer.contains("GlassSurfacePolicy.regularAdaptiveFilmAlpha("))
    assertTrue(player.contains("underlayLuma = sampled.second"))
    assertTrue(player.contains("if (materialRole == GlassSurfacePolicy.MaterialRole.Regular) size.maxDimension <= 1024.dp.toPx()"))
    assertTrue(player.contains("else size.minDimension <= 128.dp.toPx() && size.maxDimension <= 512.dp.toPx()"))
  }

  @Test fun noPlayerSheetAddsAnOpaqueOrDarkFilmOverTheGlass() {
    val sheet = source("presentation/components/PlayerSheet.kt")
    assertTrue(sheet.contains("color = if (liquidGlassSurfaces) Color.Transparent else resolvedSurfaceColor"))
    assertTrue(sheet.contains("materialRole = GlassSurfacePolicy.MaterialRole.Regular"))
    val opaque = Regex("""fillMaxSize\(\)[^\n]*\.background\((Color\.Black|MaterialTheme\.colorScheme\.(surface|background|surfaceContainer\w*))\b""")
    val dirs = listOf("ui/player/controls/components/sheets", "presentation/components", "ui/player/controls")
    val files = dirs.flatMap { File(sourceRoot, it).listFiles { f -> f.extension == "kt" }.orEmpty().toList() }
      .filter { it.readText().contains("PlayerSheet(") }
    assertTrue("sheet sources found", files.size >= 5)
    for (file in files) assertTrue("opaque sheet-wide fill in ${file.name}", !opaque.containsMatchIn(file.readText()))
    // The pane film itself is solved and capped at a light ceiling, never the old 0.88-0.92 slab.
    val player = source("ui/theme/LiquidGlassBackdrop.kt")
    val policy = source("ui/theme/GlassSurfacePolicy.kt")
    assertTrue(!player.contains("regularCenterFilmAlpha") && !policy.contains("RegularMinimumFilmAlpha"))
    assertTrue(GlassSurfacePolicy.RegularMaximumFilmAlpha <= 0.5f)
  }

  @Test fun numericInputsRemainFiniteAndBounded() {
    for (opacity in listOf(Float.NaN, Float.POSITIVE_INFINITY, -100f, 100f)) {
      val alpha = GlassSurfacePolicy.regularAdaptiveFilmAlpha(opacity, Float.NaN, Color.Black, Color.White, Float.NaN, Float.NaN)
      assertTrue(alpha.isFinite() && alpha <= GlassSurfacePolicy.RegularMaximumFilmAlpha)
    }
    assertTrue(GlassSurfacePolicy.contrastRatio(Float.NaN, 0.5f).isFinite())
    assertTrue(GlassSurfacePolicy.contrastRatio(Float.POSITIVE_INFINITY, 0f).isFinite())
  }
}
