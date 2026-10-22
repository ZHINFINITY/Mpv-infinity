/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvRestartOwnershipPolicyTest {
  private fun accepts(
    ownsOwner: Boolean = true,
    requestMatches: Boolean = true,
    mediaMatches: Boolean = true,
    queueMatches: Boolean = true,
    itemMatches: Boolean = true,
    itemAbsent: Boolean = false,
    phase: PlaybackPhase = PlaybackPhase.READY,
    generation: Long = 7L,
    committed: Long? = null,
    started: Boolean = false,
  ) = MpvRestartOwnershipPolicy.accepts(
    ownsOwner, requestMatches, mediaMatches, queueMatches, itemMatches, itemAbsent,
    phase, generation, 7L, committed, started,
  )

  @Test fun admitsOriginalPlayingOrPausedSessionBeforeAnyCoreIsDestroyed() {
    assertTrue(accepts())
    assertTrue(accepts(phase = PlaybackPhase.BACKGROUND))
    assertFalse(accepts(generation = 6L))
    assertFalse(accepts(phase = PlaybackPhase.STOPPING))
    assertTrue(accepts(phase = PlaybackPhase.STOPPING, started = true))
  }

  @Test fun allowsOnlyThisTransactionsExpectedEmptyCoreGap() {
    for (phase in listOf(PlaybackPhase.UNINITIALIZED, PlaybackPhase.INITIALIZING, PlaybackPhase.IDLE)) {
      assertTrue(accepts(itemMatches = false, itemAbsent = true, phase = phase, generation = 8L, started = true))
      assertFalse(accepts(itemMatches = false, itemAbsent = true, phase = phase, generation = 8L))
      assertFalse(accepts(itemMatches = false, itemAbsent = true, phase = phase, generation = 9L, started = true))
      assertFalse(accepts(itemMatches = false, itemAbsent = false, phase = phase, generation = 8L, started = true))
    }
  }

  @Test fun acceptsPublishedReplacementGenerationFromLoadingThroughReady() {
    for (phase in listOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) {
      assertTrue(accepts(phase = phase, generation = 9L, committed = 9L, started = true))
      assertFalse(accepts(phase = phase, generation = 8L, committed = 9L, started = true))
      assertFalse(accepts(itemMatches = false, phase = phase, generation = 9L, committed = 9L, started = true))
    }
    assertFalse(accepts(phase = PlaybackPhase.STOPPING, generation = 9L, committed = 9L, started = true))
  }

  @Test fun ownerRequestMediaAndQueueChangesRejectEveryStage() {
    for (stage in 0..2) {
      fun permitted(owner: Boolean = true, request: Boolean = true, media: Boolean = true, queue: Boolean = true) = accepts(
        ownsOwner = owner, requestMatches = request, mediaMatches = media, queueMatches = queue,
        itemMatches = stage != 1, itemAbsent = stage == 1,
        phase = if (stage == 1) PlaybackPhase.INITIALIZING else PlaybackPhase.READY,
        generation = 7L + stage, committed = if (stage == 2) 9L else null, started = stage != 0,
      )
      assertTrue(permitted())
      assertFalse(permitted(owner = false))
      assertFalse(permitted(request = false))
      assertFalse(permitted(media = false))
      assertFalse(permitted(queue = false))
    }
  }
}
