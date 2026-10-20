/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

/**
 * Decides whether Native's position is authoritative for persistence.
 *
 * A player can report position 0 while preparing, after a media item has been
 * cleared, or while a renderer is being torn down. Once READY has been reached,
 * however, a later buffering/IDLE transition must not make the last position
 * unknown. The engine-owned marker should remain set until the owner has saved
 * the position for the old item.
 */
internal object NativePositionPersistencePolicy {
  fun isPositionKnown(
    hasCurrentMediaItem: Boolean,
    isReady: Boolean,
    hasEstablishedPlaybackPosition: Boolean,
  ): Boolean =
    hasCurrentMediaItem && (isReady || hasEstablishedPlaybackPosition)
}
