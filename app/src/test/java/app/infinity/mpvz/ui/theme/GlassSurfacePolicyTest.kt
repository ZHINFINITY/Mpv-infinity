package app.infinity.mpvz.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassSurfacePolicyTest {
  @Test fun browserPaneAndNoSampleFallbackStayTranslucentAtAllSavedOpacities() {
    for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 0.72f, 0.88f, 1f, 2f)) {
      val sample = GlassSurfacePolicy.browserSampleAlpha(value)
      val fallback = GlassSurfacePolicy.browserFallbackAlpha(value)
      assertTrue(sample.isFinite() && sample in 0f..0.40f)
      assertTrue(fallback.isFinite() && fallback in 0f..0.28f)
      // Even the sampled theme plus film cannot fully replace what's behind it.
      assertTrue(1f - (1f - sample) * (1f - fallback) < 0.6f)
    }
  }

  @Test fun opacityIsBounded() {
    assertEquals(GlassSurfacePolicy.MinOpacity, GlassSurfacePolicy.opacity(0f), 0f)
    assertEquals(GlassSurfacePolicy.MaxOpacity, GlassSurfacePolicy.opacity(2f), 0f)
    assertEquals(1f, GlassSurfacePolicy.opacity(1f), 0f)
  }
  @Test fun highlightIsBounded() {
    assertEquals(GlassSurfacePolicy.MinHighlight, GlassSurfacePolicy.highlight(-1f), 0f)
    assertEquals(GlassSurfacePolicy.MaxHighlight, GlassSurfacePolicy.highlight(1f), 0f)
  }
  @Test fun invalidImportedSettingsUseReadableDefaults() {
    assertEquals(0.88f, GlassSurfacePolicy.opacity(Float.NaN), 0f)
    assertEquals(0.88f, GlassSurfacePolicy.opacity(Float.POSITIVE_INFINITY), 0f)
    assertEquals(0.18f, GlassSurfacePolicy.highlight(Float.NEGATIVE_INFINITY), 0f)
  }
  @Test fun circularPaneRejectsSquareCorners() {
    val circle = GlassSurfacePolicy.geometry(76f, 76f, 999f)
    assertEquals(38f, circle.safeRadius, 0f)
    assertTrue(circle.contains(38f, 38f))
    assertTrue(circle.contains(38f, 1f))
    assertFalse(circle.contains(1f, 1f))
    assertFalse(circle.contains(75f, 75f))
    assertFalse(circle.contains(Float.NaN, 38f))
  }
  @Test fun widePillsUseTheirHeightNotTheirWidthForRadius() {
    val pill = GlassSurfacePolicy.geometry(300f, 120f, 999f)
    assertEquals(60f, pill.safeRadius, 0f)
    assertTrue(pill.contains(150f, 1f))
    assertFalse(pill.contains(1f, 1f))
    assertFalse(pill.contains(301f, 60f))
  }
  @Test fun invalidGeometryCannotReachShaderOrPathAsNaN() {
    val bad = GlassSurfacePolicy.geometry(Float.NaN, -1f, Float.POSITIVE_INFINITY)
    assertEquals(0f, bad.width, 0f)
    assertEquals(0f, bad.height, 0f)
    assertEquals(0f, bad.safeRadius, 0f)
  }
  @Test fun depthIncreasesOneCoherentBodyRimAndContactShadow() {
    val flat = GlassSurfacePolicy.depthOptics(0f, 0.18f)
    val normal = GlassSurfacePolicy.depthOptics(1f, 0.18f)
    val deep = GlassSurfacePolicy.depthOptics(2f, 0.18f)
    assertEquals(0f, flat.bodyShade, 0f)
    assertEquals(0f, flat.shadowAlpha, 0f)
    assertTrue(normal.bodyHighlight > flat.bodyHighlight)
    assertTrue(deep.bodyShade > normal.bodyShade)
    assertTrue(deep.rimWidthDp > normal.rimWidthDp)
    assertTrue(deep.shadowSpreadDp > normal.shadowSpreadDp)
    assertEquals(deep, GlassSurfacePolicy.depthOptics(100f, 0.18f))
    assertEquals(normal, GlassSurfacePolicy.depthOptics(Float.NaN, 0.18f))
    assertTrue(GlassSurfacePolicy.TopLeftLight.x < 0f)
    assertTrue(GlassSurfacePolicy.TopLeftLight.y < 0f)
  }
  @Test fun reducedMotionSuppressesLensingNotStaticMaterialDepth() {
    assertEquals(0f, GlassSurfacePolicy.refractionStrength(2f, true, false), 0f)
    assertEquals(2f, GlassSurfacePolicy.refractionStrength(2f, false, false), 0f)
    assertEquals(0f, GlassSurfacePolicy.refractionStrength(2f, false, true), 0f)
    assertTrue(GlassSurfacePolicy.depthOptics(1f, 0.18f).shadowAlpha > 0f)
  }
  @Test fun highContrastUsesOpaqueFilmAndNoDecorativeShadow() {
    assertEquals(1f, GlassSurfacePolicy.filmAlpha(0.35f, true), 0f)
    assertEquals(0.35f, GlassSurfacePolicy.filmAlpha(0.35f, false), 0f)
    val accessible = GlassSurfacePolicy.depthOptics(2f, 0.36f, true)
    assertEquals(0f, accessible.shadowAlpha, 0f)
    assertEquals(0f, accessible.bodyHighlight, 0f)
    assertTrue(accessible.rimWidthDp >= 1f)
  }
}
