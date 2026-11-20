/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.filesystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPlaybackRefreshOwnerTest {
  @Test
  fun staleCalculationCannotPublishAfterNewerRefreshForUnchangedInput() {
    val owner = SearchPlaybackRefreshOwner()
    val published = mutableListOf<String>()

    val staleRefresh = owner.begin()
    val currentRefresh = owner.begin()

    assertFalse(owner.publishIfCurrent(staleRefresh) { published += "stale" })
    assertTrue(owner.publishIfCurrent(currentRefresh) { published += "current" })
    assertEquals(listOf("current"), published)
  }
}
