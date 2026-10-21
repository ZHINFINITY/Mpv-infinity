/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationGlassIntensityTest {
  @Test fun zeroIntensityHasNoStretchOrDeformation() {
    assertEquals(NavigationGlassMotionPolicy.Geometry(20f, 100f, 0f),
      NavigationGlassMotionPolicy.dragGeometry(20f, 100f, 0.5f, 1, 0f))
  }
  @Test fun partialIntensityScalesTheFiniteTailAndStretch() {
    val full = NavigationGlassMotionPolicy.dragGeometry(20f, 100f, 0.5f, 1, 1f)
    val half = NavigationGlassMotionPolicy.dragGeometry(20f, 100f, 0.5f, 1, 0.5f)
    assertEquals((full.width - 100f) / 2f, half.width - 100f, 0.001f)
    assertEquals((20f - full.left) / 2f, 20f - half.left, 0.001f)
    assertEquals(full.deformation / 2f, half.deformation, 0.001f)
  }
  @Test fun malformedAndExtremeIntensitiesCannotEscapeTheWorkBudget() {
    val full = NavigationGlassMotionPolicy.dragGeometry(20f, 100f, 0.5f, -1, 1f)
    assertEquals(full, NavigationGlassMotionPolicy.dragGeometry(20f, 100f, 0.5f, -1, Float.NaN))
    assertEquals(full, NavigationGlassMotionPolicy.dragGeometry(20f, 100f, 0.5f, -1, Float.MAX_VALUE))
    assertEquals(NavigationGlassMotionPolicy.Geometry(20f, 100f, 0f),
      NavigationGlassMotionPolicy.dragGeometry(20f, 100f, 0.5f, -1, -10f))
  }
}
