/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import app.infinity.mpvz.database.entities.PlaybackStateEntity

internal const val PLAYBACK_STATE_MILLISECONDS_TO_SECONDS = 1000
internal const val DEFAULT_PLAYBACK_STATE_SPEED = 1.0
internal const val DEFAULT_PLAYBACK_STATE_SUB_SPEED = 1.0

internal data class PlaybackStateSnapshot(
  val mediaIdentifier: String,
  val mediaTitle: String,
  val currentPosition: Int,
  val duration: Int,
  val playbackSpeed: Double,
  val videoZoom: Float,
  val videoAspect: String,
  val customAspectRatio: Float,
  val sid: Int,
  val secondarySid: Int,
  val subDelayMs: Int,
  val subSpeed: Double,
  val aid: Int,
  val audioDelayMs: Int,
  val externalSubtitles: String,
  val isPositionRestorePending: Boolean = false,
  /** False when the renderer/property read was unavailable (for example after surface teardown). */
  val positionIsKnown: Boolean = true,
)

internal object PlaybackStatePersistence {
  fun buildEntity(
    oldState: PlaybackStateEntity?,
    snapshot: PlaybackStateSnapshot,
    savePositionOnQuit: Boolean,
    watchedThreshold: Int,
  ): PlaybackStateEntity {
    val lastPosition =
      calculateSavePosition(
        oldState = oldState,
        currentPosition = snapshot.currentPosition,
        duration = snapshot.duration,
        savePositionOnQuit = savePositionOnQuit,
        isPositionRestorePending = snapshot.isPositionRestorePending,
        positionIsKnown = snapshot.positionIsKnown,
      )
    val duration = snapshot.duration
    val timeRemaining = if (duration > lastPosition) duration - lastPosition else 0

    return PlaybackStateEntity(
      mediaTitle = snapshot.mediaIdentifier,
      lastPosition = lastPosition,
      playbackSpeed = snapshot.playbackSpeed,
      videoZoom = snapshot.videoZoom,
      videoAspect = snapshot.videoAspect,
      customAspectRatio = snapshot.customAspectRatio,
      sid = snapshot.sid,
      secondarySid = snapshot.secondarySid,
      subDelay = snapshot.subDelayMs,
      subSpeed = snapshot.subSpeed,
      aid = snapshot.aid,
      audioDelay = snapshot.audioDelayMs,
      timeRemaining = timeRemaining,
      externalSubtitles = snapshot.externalSubtitles,
      hasBeenWatched =
        isWatched(
          oldState = oldState,
          currentPosition = snapshot.currentPosition,
          lastPosition = lastPosition,
          duration = duration,
          watchedThreshold = watchedThreshold,
        ),
    )
  }

  fun calculateSavePosition(
    oldState: PlaybackStateEntity?,
    currentPosition: Int,
    duration: Int,
    savePositionOnQuit: Boolean,
    isPositionRestorePending: Boolean = false,
    positionIsKnown: Boolean = true,
  ): Int {
    if (!savePositionOnQuit) {
      return oldState?.lastPosition ?: 0
    }
    if (isPositionRestorePending || !positionIsKnown) {
      return oldState?.lastPosition ?: currentPosition.coerceAtLeast(0)
    }

    if (duration <= 0) return currentPosition.takeIf { it > 0 } ?: oldState?.lastPosition ?: 0

    return if (currentPosition < duration - 1) currentPosition.coerceAtLeast(0) else 0
  }

  private fun isWatched(
    oldState: PlaybackStateEntity?,
    currentPosition: Int,
    lastPosition: Int,
    duration: Int,
    watchedThreshold: Int,
  ): Boolean {
    // Watched threshold 0 means "Infinitely": the video is never auto-marked as
    // watched based on progress (only an explicit flag keeps it marked).
    if (watchedThreshold == 0) return oldState?.hasBeenWatched == true

    val durationSeconds = duration.toFloat()
    if (durationSeconds <= 0f) return oldState?.hasBeenWatched == true

    val isFinished = currentPosition >= durationSeconds - 1
    val currentProgress = currentPosition.toFloat() / durationSeconds
    val savedProgress = lastPosition.toFloat() / durationSeconds
    val threshold = watchedThreshold / 100f

    return currentProgress >= threshold ||
      isFinished ||
      savedProgress >= threshold ||
      (oldState?.hasBeenWatched == true)
  }
}
