/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

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

  /** Prevent source-geometry callbacks from undoing a rotation explicitly chosen by the user. */
  fun onManualOrientationOverride() {}

  fun requestAudioFocus(): Boolean

  fun abandonAudioFocus()

  fun currentMediaLookupHint(): String? = null

  fun currentPlayerLookupHints(): PlayerLookupHints = PlayerLookupHints()

  fun currentThumbnailSource(): String? = null

  fun isCurrentMediaKnownAudio(): Boolean = false

  fun reloadCurrentYtdlFormat(format: String): Boolean = false

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

  fun isNativeEngineActive(): Boolean = false

  fun isNativePlaying(): Boolean = false

  fun nativePlayWhenReady(): Boolean = isNativePlaying()

  fun nativePlaybackSpeed(): Float = 1f

  fun nativePauseUnpause() {}

  fun nativePause() {}

  fun nativeUnpause() {}

  fun nativeSeekBy(offsetMs: Long) {}

  fun nativeSeekTo(positionMs: Long) {}

  fun nativeSeekPreviewTo(positionMs: Long) = nativeSeekTo(positionMs)

  fun nativeCancelSeekPreview() {}

  fun nativePlaybackPositionSeconds(): Double = 0.0

  fun nativePlaybackDurationSeconds(): Double = 0.0

  fun nativeSetLoopA(positionSeconds: Double?) {}

  fun nativeSetLoopB(positionSeconds: Double?) {}

  fun nativeClearLoop() {}

  fun nativeSetSpeed(speed: Float) {}

  fun nativeSetRepeatMode(repeatMode: RepeatMode) {}

  fun nativeSetVideoAspect(aspect: VideoAspect) {}

  /** Called after a user-facing aspect change, allowing the host to persist the current item's state. */
  fun onVideoAspectChanged() {}

  fun nativeSetZoom(zoom: Float) {}

  fun nativeSetPan(x: Float, y: Float) {}

  fun nativeSetSubtitleScale(scale: Float) {}

  fun nativeSetSubtitlePosition(position: Int) {}

  fun nativeSelectSubtitle(groupIndex: Int, trackIndex: Int) {}

  fun nativeDisableSubtitles() {}

  fun nativeAddSubtitle(uri: android.net.Uri, select: Boolean): Boolean = false
}
