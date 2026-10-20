/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player


internal fun getTrackSelectionId(property: String): Int =
  runCatching { PlaybackSession.getPropertyString(property)?.toIntOrNull() ?: 0 }
    .getOrDefault(0)

internal fun setTrackSelectionId(
  property: String,
  id: Int?,
) {
  val value = id?.takeIf { it > 0 }?.toString() ?: "no"
  PlaybackSession.setPropertyString(property, value)
}
