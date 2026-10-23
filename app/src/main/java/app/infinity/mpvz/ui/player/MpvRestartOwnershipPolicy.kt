/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

/** Ownership of only the explicitly authorized interpolation core-recreation transaction. */
internal object MpvRestartOwnershipPolicy {
  fun accepts(
    ownsOwner: Boolean,
    requestMatches: Boolean,
    mediaMatches: Boolean,
    queueMatches: Boolean,
    currentItemMatches: Boolean,
    currentItemAbsent: Boolean,
    phase: PlaybackPhase,
    generation: Long,
    expectedRetiredGeneration: Long,
    committedGeneration: Long?,
    coreReplacementStarted: Boolean,
  ): Boolean {
    if (!ownsOwner || !requestMatches || !mediaMatches || !queueMatches) return false
    if (committedGeneration != null) {
      return currentItemMatches && generation == committedGeneration &&
        phase in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
    }
    // Snapshot and pause must remain current before any native core is retired.
    if (currentItemMatches && generation == expectedRetiredGeneration) {
      return phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND) ||
        (coreReplacementStarted && phase == PlaybackPhase.STOPPING)
    }
    // Only this transaction may cross the single tombstone generation, with no media
    // selected. An unrelated cleared/replaced core or a different media item is not ours.
    return coreReplacementStarted && currentItemAbsent &&
      generation == PlaybackGenerationPolicy.next(expectedRetiredGeneration) &&
      phase in setOf(PlaybackPhase.UNINITIALIZED, PlaybackPhase.INITIALIZING, PlaybackPhase.IDLE)
  }
}
