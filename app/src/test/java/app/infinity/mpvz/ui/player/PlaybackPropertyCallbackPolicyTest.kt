/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPropertyCallbackPolicyTest {
  private val itemA = PlaybackItem(originalUri = "https://example.test/a", title = "Same title", stableId = "a")
  private val itemB = PlaybackItem(originalUri = "https://example.test/b", title = "Same title", stableId = "b")

  @Test
  fun acceptsCallbackForCurrentItemAndGeneration() {
    val expected = PlaybackPropertyCallbackIdentity(4L, MediaSessionItemIdentity(itemA.stableId, itemA.originalUri))

    assertTrue(PlaybackPropertyCallbackPolicy.isCurrent(expected, 4L, itemA))
  }

  @Test
  fun rejectsCallbackAfterQueueMovesToAnotherItemEvenWhenTitlesMatch() {
    val expected = PlaybackPropertyCallbackIdentity(4L, MediaSessionItemIdentity(itemA.stableId, itemA.originalUri))

    assertFalse(PlaybackPropertyCallbackPolicy.isCurrent(expected, 5L, itemB))
  }

  @Test
  fun rejectsCallbackFromOlderReloadOfTheSameItem() {
    val expected = PlaybackPropertyCallbackIdentity(4L, MediaSessionItemIdentity(itemA.stableId, itemA.originalUri))

    assertFalse(PlaybackPropertyCallbackPolicy.isCurrent(expected, 5L, itemA))
  }
}
