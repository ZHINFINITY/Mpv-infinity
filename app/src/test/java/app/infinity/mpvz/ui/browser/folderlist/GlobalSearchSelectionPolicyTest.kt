/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.folderlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchSelectionPolicyTest {
  @Test
  fun leavingSearchAlwaysClearsTheSearchSelection() {
    assertTrue(GlobalSearchSelectionPolicy.shouldClear(false, listOf(1L), listOf(1L, 2L)))
    assertTrue(GlobalSearchSelectionPolicy.shouldClear(false, emptyList(), emptyList()))
  }

  @Test
  fun selectionSurvivesWhileEverySelectedVideoIsStillVisible() {
    assertFalse(GlobalSearchSelectionPolicy.shouldClear(true, listOf(2L, 3L), listOf(1L, 2L, 3L)))
    assertFalse(GlobalSearchSelectionPolicy.shouldClear(true, emptyList(), listOf(1L)))
  }

  @Test
  fun refiningTheQueryAwayFromASelectedVideoClearsTheSelection() {
    assertTrue(GlobalSearchSelectionPolicy.shouldClear(true, listOf(2L, 9L), listOf(1L, 2L, 3L)))
    assertTrue(GlobalSearchSelectionPolicy.shouldClear(true, listOf(2L), emptyList()))
  }
}
