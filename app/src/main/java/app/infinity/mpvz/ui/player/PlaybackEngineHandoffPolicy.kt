/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

internal object PlaybackEngineHandoffPolicy {
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
}
