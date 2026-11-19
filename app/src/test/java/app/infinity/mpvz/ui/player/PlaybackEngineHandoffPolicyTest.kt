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

class PlaybackEngineHandoffPolicyTest {
  @Test
  fun acceptsOnlyTheCapturedRequestAndQueueItem() {
    val current = PlaybackEngineHandoffPolicy.isCurrent(
      expectedGeneration = 7L,
      currentGeneration = 7L,
      expectedMediaIdentifier = "media-7",
      currentMediaIdentifier = "media-7",
      expectedPlayableUri = "content://media/7",
      currentPlayableUri = "content://media/7",
      expectedQueueStableId = "queue-7",
      currentQueueStableId = "queue-7",
      expectedQueueOriginalUri = "content://original/7",
      currentQueueOriginalUri = "content://original/7",
    )
    assertTrue(current)
    assertFalse(
      PlaybackEngineHandoffPolicy.isCurrent(
        expectedGeneration = 7L,
        currentGeneration = 8L,
        expectedMediaIdentifier = "media-7",
        currentMediaIdentifier = "media-8",
        expectedPlayableUri = "content://media/7",
        currentPlayableUri = "content://media/8",
        expectedQueueStableId = "queue-7",
        currentQueueStableId = "queue-8",
        expectedQueueOriginalUri = "content://original/7",
        currentQueueOriginalUri = "content://original/8",
      ),
    )
  }
}
