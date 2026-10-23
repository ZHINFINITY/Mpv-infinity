/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import org.junit.Assert.*
import org.junit.Test

class GlassCustomizationPolicyTest {
  @Test fun importedNonFiniteStrengthUsesTheSafeDefault() {
    listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
      assertEquals(1f, GlassCustomizationPolicy.strength(it), 0f)
      assertEquals(1f, GlassCustomizationPolicy.motion(it), 0f)
    }
  }
  @Test fun strengthAndMotionStayInSeparateBoundedRanges() {
    assertEquals(0f, GlassCustomizationPolicy.strength(-5f), 0f)
    assertEquals(2f, GlassCustomizationPolicy.strength(500f), 0f)
    assertEquals(0f, GlassCustomizationPolicy.motion(-5f), 0f)
    assertEquals(1f, GlassCustomizationPolicy.motion(500f), 0f)
  }
  @Test fun frostHasNoUnboundedWorkSetting() {
    assertEquals(0, GlassCustomizationPolicy.frostPasses(0f))
    assertEquals(GlassFrostPolicy.Passes, GlassCustomizationPolicy.frostPasses(1f))
    assertEquals(GlassCustomizationPolicy.MaximumFrostPasses, GlassCustomizationPolicy.frostPasses(Float.MAX_VALUE))
    assertEquals(GlassFrostPolicy.Passes, GlassCustomizationPolicy.frostPasses(Float.NaN))
  }
  @Test fun zeroFrostCopiesWithoutChangingSourcePixels() {
    val source = intArrayOf(0xFF102030.toInt(), 0xFF8090A0.toInt())
    val result = GlassFrostPolicy.frostArgb(source, 2, 1, 0f)
    assertArrayEquals(source, result)
    assertNotSame(source, result)
  }
  @Test fun malformedFrostMatchesDefaultAndMaximumFrostSmoothsTheImpulse() {
    val source = IntArray(17 * 17) { 0xFF000000.toInt() }.also { it[8 * 17 + 8] = 0xFFFFFFFF.toInt() }
    val original = source.copyOf()
    val normal = GlassFrostPolicy.frostArgb(source, 17, 17, 1f)
    assertArrayEquals(normal, GlassFrostPolicy.frostArgb(source, 17, 17, Float.NaN))
    val strong = GlassFrostPolicy.frostArgb(source, 17, 17, 2f)
    assertTrue(strong.maxOf { it and 255 } <= normal.maxOf { it and 255 })
    assertArrayEquals(original, source)
  }
}
