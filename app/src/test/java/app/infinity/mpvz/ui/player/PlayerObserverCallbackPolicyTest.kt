/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerObserverCallbackPolicyTest {
  @Test
  fun callbackFromRetiredCoreIsRejectedAfterBindingEpochChanges() {
    val queued = PlayerObserverCallbackToken(bindingEpoch = 7L, generation = 12L)

    assertFalse(PlayerObserverCallbackPolicy.isCurrent(queued, currentBindingEpoch = 8L, currentGeneration = 12L))
    assertTrue(PlayerObserverCallbackPolicy.isCurrent(queued, currentBindingEpoch = 7L, currentGeneration = 12L))
  }

  @Test
  fun callbackFromPreviousMediaGenerationIsRejectedWithinSameBinding() {
    val queued = PlayerObserverCallbackToken(bindingEpoch = 3L, generation = 21L)

    assertFalse(PlayerObserverCallbackPolicy.isCurrent(queued, currentBindingEpoch = 3L, currentGeneration = 22L))
  }

  @Test
  fun generationZeroIsNotAWildcardForARealMediaGeneration() {
    val idleCallback = PlayerObserverCallbackToken(bindingEpoch = 1L, generation = 0L)

    assertTrue(PlayerObserverCallbackPolicy.isCurrent(idleCallback, currentBindingEpoch = 1L, currentGeneration = 0L))
    assertFalse(PlayerObserverCallbackPolicy.isCurrent(idleCallback, currentBindingEpoch = 1L, currentGeneration = 1L))
  }

  @Test
  fun queuedNativeErrorFromAnOldMediaItemIsRejectedAfterTheEpochAdvances() {
    val oldItem = NativePlaybackErrorToken(mediaItemEpoch = 12L, playbackGeneration = 4L)
    val currentItem = NativePlaybackErrorToken(mediaItemEpoch = 13L, playbackGeneration = 4L)

    assertFalse(PlaybackItemEpochPolicy.isCurrent(oldItem, currentEpoch = 13L, currentPlaybackGeneration = 4L))
    assertTrue(PlaybackItemEpochPolicy.isCurrent(currentItem, currentEpoch = 13L, currentPlaybackGeneration = 4L))
  }

  @Test
  fun queuedNativeErrorFromAnOldPlaybackSessionIsRejectedEvenWhenItemEpochMatches() {
    val queued = NativePlaybackErrorToken(mediaItemEpoch = 13L, playbackGeneration = 4L)

    assertFalse(PlaybackItemEpochPolicy.isCurrent(queued, currentEpoch = 13L, currentPlaybackGeneration = 5L))
  }

  @Test
  fun eventItemTagPreservesTheOriginalTokenAndRejectsUnrelatedTags() {
    val token = NativePlaybackErrorToken(mediaItemEpoch = 13L, playbackGeneration = 4L)

    assertTrue(PlaybackItemEpochPolicy.tokenFromMediaItemTag(token) === token)
    assertFalse(PlaybackItemEpochPolicy.tokenFromMediaItemTag("unrelated") != null)
  }
}
