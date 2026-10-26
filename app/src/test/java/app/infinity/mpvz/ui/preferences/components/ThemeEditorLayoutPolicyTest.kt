/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.preferences.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeEditorLayoutPolicyTest {
  @Test fun fittingLabelKeepsItsSize() {
    assertNull(ThemeEditorLayoutPolicy.nextLabelSp(14f, overflowing = false))
  }

  @Test fun overflowingLabelStepsDownButNeverBelowTheFloor() {
    assertEquals(13.5f, ThemeEditorLayoutPolicy.nextLabelSp(14f, overflowing = true)!!, 0.001f)
    assertEquals(ThemeEditorLayoutPolicy.MinLabelSp, ThemeEditorLayoutPolicy.nextLabelSp(11.2f, true)!!, 0.001f)
    // At the floor the ellipsis is the last resort; the fit loop must terminate.
    assertNull(ThemeEditorLayoutPolicy.nextLabelSp(ThemeEditorLayoutPolicy.MinLabelSp, true))
  }

  @Test fun fitLoopTerminatesFromAnyStartingSize() {
    var sp = 40f
    var steps = 0
    while (true) {
      sp = ThemeEditorLayoutPolicy.nextLabelSp(sp, overflowing = true) ?: break
      steps++
      assertTrue(steps < 100)
    }
    assertEquals(ThemeEditorLayoutPolicy.MinLabelSp, sp, 0.001f)
  }

  @Test fun bottomSpacingIncludesNavigationBarAndImeBreathingRoom() {
    assertTrue(ThemeEditorLayoutPolicy.bottomSpacingDp(0f, false) >= 12f)
    assertTrue(ThemeEditorLayoutPolicy.bottomSpacingDp(24f, false) >= 24f)
    assertTrue(ThemeEditorLayoutPolicy.bottomSpacingDp(24f, true) >= 32f)
  }
}
