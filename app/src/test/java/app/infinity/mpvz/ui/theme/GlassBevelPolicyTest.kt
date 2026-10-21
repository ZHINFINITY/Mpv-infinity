package app.infinity.mpvz.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBevelPolicyTest {
  @Test fun paramsAreFiniteAndBoundedForMalformedInputs() {
    val p = GlassBevelPolicy.params(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN,
      Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, false, false)
    for (value in listOf(p.radius, p.bevel, p.normalX, p.normalY, p.specular,
      p.fresnel, p.refractionX, p.refractionY)) assertTrue(value.isFinite())
    assertTrue(p.radius >= 0f)
    assertTrue(p.specular in 0f..0.72f)
    assertTrue(p.fresnel in 0f..0.42f)
  }

  @Test fun cornerAndStraightEdgeNormalsMatchRoundedPath() {
    val corner = GlassBevelPolicy.edgeNormal(100f, 60f, 20f, 90f, 50f)
    assertTrue(corner.first > 0f && corner.second > 0f)
    val right = GlassBevelPolicy.edgeNormal(100f, 60f, 20f, 100f, 30f)
    assertEquals(1f, right.first, 0f)
    assertEquals(0f, right.second, 0f)
    val top = GlassBevelPolicy.edgeNormal(100f, 60f, 20f, 50f, 0f)
    assertEquals(0f, top.first, 0f)
    assertEquals(-1f, top.second, 0f)
  }

  @Test fun depthAddsBevelSpecularAndFresnelWithoutUnboundedRefraction() {
    val shallow = GlassBevelPolicy.params(300f, 120f, 60f, 0f, 1f, false, false)
    val deep = GlassBevelPolicy.params(300f, 120f, 60f, 2f, 2f, false, false)
    assertTrue(deep.bevel > shallow.bevel)
    assertTrue(deep.specular > shallow.specular)
    assertTrue(deep.fresnel > shallow.fresnel)
    assertTrue(deep.refractionX in -7f..7f)
    assertTrue(deep.refractionY in -7f..7f)
  }

  @Test fun reducedMotionAndHighContrastDisableDecorativeOptics() {
    val reduced = GlassBevelPolicy.params(100f, 100f, 50f, 2f, 2f, true, false)
    val contrast = GlassBevelPolicy.params(100f, 100f, 50f, 2f, 2f, false, true)
    assertEquals(0f, reduced.refractionX, 0f)
    assertEquals(0f, reduced.fresnel, 0f)
    assertEquals(0f, contrast.refractionY, 0f)
    assertEquals(0f, contrast.specular, 0f)
  }
}
