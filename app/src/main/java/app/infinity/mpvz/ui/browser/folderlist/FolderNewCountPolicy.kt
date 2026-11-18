/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.folderlist

import app.infinity.mpvz.database.entities.PlaybackStateEntity
import app.infinity.mpvz.ui.player.isPlaybackStateWatched

/**
 * Applies the same explicit-mark, file-age and watched-threshold rules used by the video list.
 * A reset playback row is not itself a manual NEW mark; only the explicit override can force NEW.
 */
internal fun isVideoCountedAsNew(
  playbackState: PlaybackStateEntity?,
  durationSeconds: Long,
  videoDateModifiedSeconds: Long,
  currentTimeMillis: Long,
  newLabelDays: Int,
  watchedThreshold: Int,
  folderWatchedOverride: Boolean?,
  explicitlyMarkedUnwatched: Boolean,
  explicitlyMarkedWatched: Boolean,
): Boolean {
  val isWatched =
    explicitlyMarkedWatched ||
      isPlaybackStateWatched(playbackState, durationSeconds, watchedThreshold)
  val isRecent =
    newLabelDays == 0 ||
      currentTimeMillis - videoDateModifiedSeconds * 1000L <= newLabelDays.toLong() * 24L * 60L * 60L * 1000L

  return when (folderWatchedOverride) {
    true -> explicitlyMarkedUnwatched && !isWatched
    false -> !explicitlyMarkedWatched && !isWatched
    null -> !isWatched && (explicitlyMarkedUnwatched || isRecent)
  }
}
