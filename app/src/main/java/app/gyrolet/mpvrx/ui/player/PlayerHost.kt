/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.ContentResolver
import android.content.Context
import android.media.AudioManager
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowInsetsControllerCompat

data class PlayerLookupHints(
  val canonicalTitle: String? = null,
  val imdbId: String? = null,
  val tmdbId: Int? = null,
  val mediaType: String? = null,
  val season: Int? = null,
  val episode: Int? = null,
)

/**
 * Abstraction over host requirements so the player logic can run in an Activity or a Screen.
 */
interface PlayerHost {
  val context: Context
  val windowInsetsController: WindowInsetsControllerCompat
  val audioManager: AudioManager

  // Host OS primitives with non-conflicting names
  val hostWindow: Window
  val hostWindowManager: WindowManager
  val hostContentResolver: ContentResolver
  var hostRequestedOrientation: Int

  fun requestAudioFocus(): Boolean

  fun abandonAudioFocus()

  /** Returns true when the Media3 backend owns the current video. */
  fun isMedia3Active(): Boolean = false

  fun media3IsPlaying(): Boolean = false

  fun media3SetPlayWhenReady(value: Boolean): Boolean = false

  fun media3SeekBy(offsetMs: Long): Boolean = false

  fun media3SeekTo(positionMs: Long): Boolean = false

  fun media3SetPlaybackSpeed(speed: Float): Boolean = false

  fun currentMediaLookupHint(): String? = null

  fun currentPlayerLookupHints(): PlayerLookupHints = PlayerLookupHints()

  fun currentThumbnailSource(): String? = null

  fun isCurrentMediaKnownAudio(): Boolean = false

  fun playQueueItem(index: Int)

  fun reorderQueueItem(
    from: Int,
    to: Int,
  )

  fun hasNextQueueItem(): Boolean

  fun hasPreviousQueueItem(): Boolean

  fun playNextQueueItem()

  fun playPreviousQueueItem()

  fun onQueueShuffleChanged(enabled: Boolean)
}
