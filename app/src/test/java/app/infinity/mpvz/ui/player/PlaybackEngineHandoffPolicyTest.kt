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

  @Test
  fun nativeTimeoutRestoresMpvOnlyForTheSameLiveRequestAndRendererOwner() {
    assertTrue(PlaybackEngineHandoffPolicy.canRestoreMpvAfterNativeFailure(true, true))
    assertFalse(PlaybackEngineHandoffPolicy.canRestoreMpvAfterNativeFailure(false, true))
    assertFalse(PlaybackEngineHandoffPolicy.canRestoreMpvAfterNativeFailure(true, false))
  }

  @Test
  fun resolvesTheCurrentQueueIndexOnlyWhenEveryIdentityStillMatches() {
    assertTrue(
      PlaybackEngineHandoffPolicy.resolveQueueIndex(
        currentIndex = 1,
        expectedStableId = "queue-a",
        currentStableId = "queue-a",
        expectedOriginalUri = "content://original/a",
        currentOriginalUri = "content://original/a",
        expectedPlaylistUri = "content://original/a",
        currentPlaylistUri = "content://original/a",
      ) == 1,
    )
    assertFalse(
      PlaybackEngineHandoffPolicy.resolveQueueIndex(
        currentIndex = 0,
        expectedStableId = "queue-a",
        currentStableId = "queue-b",
        expectedOriginalUri = "content://original/a",
        currentOriginalUri = "content://original/b",
        expectedPlaylistUri = "content://original/a",
        currentPlaylistUri = "content://original/b",
      ) != null,
    )
    assertFalse(
      PlaybackEngineHandoffPolicy.resolveQueueIndex(
        currentIndex = 1,
        expectedStableId = "queue-a",
        currentStableId = "queue-a",
        expectedOriginalUri = "content://original/a",
        currentOriginalUri = "content://original/a",
        expectedPlaylistUri = "content://original/a",
        currentPlaylistUri = "content://original/b",
      ) != null,
    )
  }
}
