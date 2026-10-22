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
    assertTrue(GlassSurfacePolicy.regularCenterFilmAlpha(1f) < 1f)
  }

  @Test fun independentSettingsAndPlayerPopupsRouteToRegularMaterial() {
    val settings = source("ui/preferences/components/SettingsGlass.kt")
    assertTrue(settings.contains("if (independent) GlassSurfacePolicy.MaterialRole.Regular"))
    assertTrue(settings.contains("tuning.opacity") && settings.contains("tuning.highlight"))
    assertTrue(source("presentation/components/PlayerSheet.kt").contains("materialRole = GlassSurfacePolicy.MaterialRole.Regular"))
    assertTrue(source("presentation/components/PlayerGlassOverlays.kt").contains("materialRole = GlassSurfacePolicy.MaterialRole.Regular"))
  }

  @Test fun normalTextContrastSurvivesBothWorstCaseSrgbUnderlaysAndPeakBodyLighting() {
    val darkForeground = Color(0xFFE3E2E6)
    val lightForeground = Color(0xFF1B1B1F)
    val darkTint = GlassSurfacePolicy.regularTintColor(Color(0xFF36343B), darkForeground)
    val lightTint = GlassSurfacePolicy.regularTintColor(Color(0xFFECE6F0), lightForeground)
    for (opacity in listOf(0.72f, 0.88f, 1f, Float.NaN)) {
      val alpha = GlassSurfacePolicy.regularCenterFilmAlpha(opacity)
      for (underlay in listOf(Color.White, Color.Black)) {
        val dark = blend(underlay, darkTint, alpha)
        val light = blend(underlay, lightTint, alpha)
        val maxOptics = GlassSurfacePolicy.depthOptics(depth = 2f, highlight = 0.36f)
        // Include the strongest top white sheen and bottom shade, not just the quiet center.
        for (background in listOf(dark, blend(dark, Color.White, maxOptics.bodyHighlight), blend(dark, Color.Black, maxOptics.bodyShade))) {
          assertTrue("dark theme text over $underlay at opacity $opacity", GlassSurfacePolicy.contrastRatio(luminance(darkForeground), luminance(background)) >= 4.5f)
        }
        for (background in listOf(light, blend(light, Color.White, maxOptics.bodyHighlight), blend(light, Color.Black, maxOptics.bodyShade))) {
          assertTrue("light theme text over $underlay at opacity $opacity", GlassSurfacePolicy.contrastRatio(luminance(lightForeground), luminance(background)) >= 4.5f)
        }
      }
    }
  }

  @Test fun regularFrostAndFilmSurviveNoSceneAndZeroFrostFallbackBranches() {
    assertTrue(GlassSurfacePolicy.RegularMinimumFrostDp >= 12f)
    val browser = source("ui/theme/BrowserGlassScene.kt")
    val player = source("ui/theme/LiquidGlassBackdrop.kt")
    for (renderer in listOf(browser, player)) {
      assertTrue(renderer.contains("maxOf(GlassSurfacePolicy.RegularMinimumFrostDp"))
    }
    assertTrue(browser.contains("val fallbackFilm = if (materialRole == GlassSurfacePolicy.MaterialRole.Regular) centerFilm"))
    assertTrue(player.contains("val filmAlpha = if (materialRole == GlassSurfacePolicy.MaterialRole.Regular) GlassSurfacePolicy.filmAlpha(centerFilm, highContrast)"))
    assertTrue(browser.contains("materialRole == GlassSurfacePolicy.MaterialRole.Regular || tuning.frost > 0f"))
    assertTrue(player.contains("if (materialRole == GlassSurfacePolicy.MaterialRole.Regular) size.maxDimension <= 1024.dp.toPx()"))
    assertTrue(player.contains("else size.minDimension <= 128.dp.toPx() && size.maxDimension <= 512.dp.toPx()"))
  }

  @Test fun numericInputsRemainFiniteAndBounded() {
    for (opacity in listOf(Float.NaN, Float.POSITIVE_INFINITY, -100f, 100f)) {
      val alpha = GlassSurfacePolicy.regularCenterFilmAlpha(opacity)
      assertTrue(alpha.isFinite() && alpha >= GlassSurfacePolicy.RegularMinimumFilmAlpha && alpha < 1f)
    }
    assertTrue(GlassSurfacePolicy.contrastRatio(Float.NaN, 0.5f).isFinite())
    assertTrue(GlassSurfacePolicy.contrastRatio(Float.POSITIVE_INFINITY, 0f).isFinite())
  }
}
