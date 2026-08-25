/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player

import android.content.ContentResolver
import android.content.Context
import android.media.AudioManager
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowInsetsControllerCompat
import app.infinity.mpvz.preferences.AudioChannels

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

  /** Seek by one frame-duration using exact seek parameters for frame navigation. */
  fun media3SeekFrameBy(offsetMs: Long): Boolean = false

  fun media3SeekTo(positionMs: Long, fast: Boolean = false): Boolean = false

  fun media3SetPlaybackSpeed(speed: Float): Boolean = false

  /** Returns the active Media3 playback speed for gesture state restoration. */
  fun media3PlaybackSpeed(): Float = 1f

  fun media3SetAudioPitchCorrection(enabled: Boolean): Boolean = false

  fun media3SetRepeatMode(mode: RepeatMode): Boolean = false

  fun media3SetAudioChannels(channels: AudioChannels): Boolean = false

  fun media3SetAudioProcessing(volumeNormalization: Boolean, drcEnabled: Boolean): Boolean = false

  fun media3SelectAudioTrack(trackId: Int): Boolean = false

  fun media3SelectSubtitleTrack(trackId: Int): Boolean = false

  fun media3UnselectSubtitleTrack(trackId: Int): Boolean = false

  fun media3DisableSubtitles(): Boolean = false

  fun media3IsSubtitleSelected(trackId: Int): Boolean = false

  /** Applies the shared subtitle scale to the active Native/Media3 SubtitleView. */
  fun media3SetSubtitleScale(scale: Float): Boolean = false

  /** Applies the shared subtitle position to the active Native/Media3 SubtitleView. */
  fun media3SetSubtitlePosition(position: Int): Boolean = false

  fun media3ApplySubtitleStyle(
    textColor: Int,
    backgroundColor: Int,
    edgeType: Int,
    edgeColor: Int,
    shadowColor: Int = android.graphics.Color.BLACK,
    applyEmbeddedStyles: Boolean = true,
    fontFamily: String? = null,
    bold: Boolean = false,
    italic: Boolean = false,
  ): Boolean = false

  /** Returns whether Native/Media3 currently has any subtitle track selected. */
  fun media3HasSelectedSubtitle(): Boolean = false

  fun media3CurrentPositionMs(): Long = 0L

  fun media3DurationMs(): Long = 0L

  fun media3FrameDurationMs(): Long? = null

  fun media3LoopA(): Long? = null

  fun media3LoopB(): Long? = null

  fun media3SetLoopA(positionMs: Long): Boolean = false

  fun media3SetLoopB(positionMs: Long): Boolean = false

  fun media3ClearABLoop(): Boolean = false

  fun currentMediaLookupHint(): String? = null

  fun currentPlayerLookupHints(): PlayerLookupHints = PlayerLookupHints()

  fun currentThumbnailSource(): String? = null

  fun isCurrentMediaKnownAudio(): Boolean = false

  /**
   * Re-run local folder discovery for a standalone file when the playlist sheet is opened.
   * Hosts without Activity-scoped folder discovery may keep the default no-op behavior.
   */
  fun refreshCurrentFolderQueue() {}

  fun playQueueItem(index: Int)

  fun reorderQueueItem(
    from: Int,
    to: Int,
  )

  fun removeQueueItem(index: Int) {}

  fun hasNextQueueItem(): Boolean

  fun hasPreviousQueueItem(): Boolean

  fun playNextQueueItem()

  fun playPreviousQueueItem()

  fun onQueueShuffleChanged(enabled: Boolean)
}
