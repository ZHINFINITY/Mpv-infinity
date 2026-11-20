/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

/**
 * Owns resources prepared for a playback load until native commit transfers the active resource.
 * A cancelled dispatch may still have committed synchronously, so failure cleanup must distinguish
 * an unsubmitted load from one whose committed ownership still needs finalization.
 */
internal class ResolvedLoadResourceOwner<Resource>(
  private val preparedRegistration: Resource?,
  private val closeUnconsumedFd: () -> Unit,
  private val releaseRegistration: (Resource) -> Unit,
) {
  private var nativeCommitInvoked = false
  private var committedGeneration: Long? = null
  private var displacedRegistration: Resource? = null
  private var displacedAuxiliary = emptyList<Resource>()
  private var finalized = false

  fun markNativeCommitInvoked() {
    nativeCommitInvoked = true
  }

  fun recordSuccessfulCommit(
    generation: Long,
    displaced: Resource?,
    displacedAuxiliary: List<Resource>,
  ) {
    if (generation < 0L) return
    committedGeneration = generation
    displacedRegistration = displaced
    this.displacedAuxiliary = displacedAuxiliary
  }

  fun finish(generation: Long): Long {
    if (!finalized) {
      if (generation < 0L) {
        closeUnconsumedFd()
        preparedRegistration?.let(releaseRegistration)
      } else {
        displacedRegistration
          ?.takeIf { it != preparedRegistration }
          ?.let(releaseRegistration)
        displacedAuxiliary.forEach(releaseRegistration)
      }
      finalized = true
    }
    return generation
  }

  fun cleanupOnFailure() {
    if (finalized) return
    val successfulGeneration = committedGeneration
    if (successfulGeneration != null) {
      finish(successfulGeneration)
    } else {
      if (!nativeCommitInvoked) closeUnconsumedFd()
      preparedRegistration?.let(releaseRegistration)
      finalized = true
    }
  }
}
