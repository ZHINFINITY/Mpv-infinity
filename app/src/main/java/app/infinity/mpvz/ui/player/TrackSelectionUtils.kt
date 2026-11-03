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

internal data class MpvSubtitleSelectionIds(
  val primaryId: Int,
  val secondaryId: Int,
)

internal fun toggleMpvSubtitleSelection(
  currentPrimaryId: Int,
  currentSecondaryId: Int,
  tappedId: Int,
): MpvSubtitleSelectionIds {
  val primary = currentPrimaryId.coerceAtLeast(0)
  val secondary = currentSecondaryId.coerceAtLeast(0)
  if (tappedId <= 0) return MpvSubtitleSelectionIds(primary, secondary)
  return when {
    tappedId == primary -> MpvSubtitleSelectionIds(0, secondary)
    tappedId == secondary -> MpvSubtitleSelectionIds(primary, 0)
    primary <= 0 -> MpvSubtitleSelectionIds(tappedId, secondary)
    secondary <= 0 -> MpvSubtitleSelectionIds(primary, tappedId)
    else -> MpvSubtitleSelectionIds(tappedId, secondary)
  }
}
