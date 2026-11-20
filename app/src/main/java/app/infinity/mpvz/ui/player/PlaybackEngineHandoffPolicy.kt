/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

internal object PlaybackEngineHandoffPolicy {
  fun canRestoreMpvAfterNativeFailure(currentRequestIdentity: Boolean, nativeEngineOwnsRenderer: Boolean): Boolean =
    currentRequestIdentity && nativeEngineOwnsRenderer

  fun isCurrent(
    expectedGeneration: Long,
    currentGeneration: Long,
    expectedMediaIdentifier: String,
    currentMediaIdentifier: String,
    expectedPlayableUri: String?,
    currentPlayableUri: String?,
    expectedQueueStableId: String?,
    currentQueueStableId: String?,
    expectedQueueOriginalUri: String?,
    currentQueueOriginalUri: String?,
  ): Boolean =
    expectedGeneration == currentGeneration &&
      expectedMediaIdentifier == currentMediaIdentifier &&
      expectedPlayableUri == currentPlayableUri &&
      expectedQueueStableId == currentQueueStableId &&
      expectedQueueOriginalUri == currentQueueOriginalUri

  fun resolveQueueIndex(
    currentIndex: Int,
    expectedStableId: String?,
    currentStableId: String?,
    expectedOriginalUri: String?,
    currentOriginalUri: String?,
    expectedPlaylistUri: String?,
    currentPlaylistUri: String?,
  ): Int? =
    currentIndex.takeIf {
      it >= 0 &&
        expectedStableId != null &&
        expectedOriginalUri != null &&
        expectedPlaylistUri != null &&
        expectedStableId == currentStableId &&
        expectedOriginalUri == currentOriginalUri &&
        expectedPlaylistUri == currentPlaylistUri
    }
}
