/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.preferences.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeEditorLayoutPolicyTest {
  @Test fun narrowPortraitStacksActionsBeforeLabelsCanClip() {
    assertTrue(ThemeEditorLayoutPolicy.stackActions(359f))
    assertFalse(ThemeEditorLayoutPolicy.stackActions(360f))
  }

  @Test fun landscapeAndWidePortraitShareTheSameActionGeometry() {
    assertFalse(ThemeEditorLayoutPolicy.stackActions(640f))
    assertFalse(ThemeEditorLayoutPolicy.stackActions(420f))
  }

  @Test fun bottomSpacingIncludesNavigationBarAndImeBreathingRoom() {
    assertTrue(ThemeEditorLayoutPolicy.bottomSpacingDp(0f, false) >= 12f)
    assertTrue(ThemeEditorLayoutPolicy.bottomSpacingDp(24f, false) >= 24f)
    assertTrue(ThemeEditorLayoutPolicy.bottomSpacingDp(24f, true) >= 32f)
  }
}
