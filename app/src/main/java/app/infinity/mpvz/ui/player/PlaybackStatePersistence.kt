/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import app.infinity.mpvz.database.entities.PlaybackStateEntity
import kotlinx.serialization.json.Json

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
  /** False when the live subtitle list could not be read and an empty value is only a placeholder. */
  val externalSubtitlesAreKnown: Boolean = true,
  /** Native does not expose MPV track IDs, subtitle timing or audio delay. Keep those intact. */
  val mpvSettingsAreKnown: Boolean = true,
  /** A detached service has no authority over the Activity's aspect selection. */
  val videoPresentationIsKnown: Boolean = true,
  /** Prevent teardown placeholders from clearing a previously saved speed/zoom. */
  val playbackSettingsAreKnown: Boolean = positionIsKnown,
  val audioSelection: String? = null,
  val subtitleSelection: String? = null,
  val captureSequence: Long = PlaybackStateSaveCoordinator.nextSequence(),
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
    val timeRemaining =
      if (!snapshot.positionIsKnown && duration <= 0) oldState?.timeRemaining ?: 0
      else if (duration > lastPosition) duration - lastPosition else 0
    val externalSubtitles =
      if (!snapshot.externalSubtitlesAreKnown && snapshot.externalSubtitles.isBlank()) {
        oldState?.externalSubtitles.orEmpty()
      } else {
        snapshot.externalSubtitles
      }

    return PlaybackStateEntity(
      mediaTitle = snapshot.mediaIdentifier,
      lastPosition = lastPosition,
      playbackSpeed = if (snapshot.playbackSettingsAreKnown) snapshot.playbackSpeed else oldState?.playbackSpeed ?: snapshot.playbackSpeed,
      videoZoom = if (snapshot.playbackSettingsAreKnown) snapshot.videoZoom else oldState?.videoZoom ?: snapshot.videoZoom,
      videoAspect = if (snapshot.videoPresentationIsKnown) snapshot.videoAspect else oldState?.videoAspect ?: snapshot.videoAspect,
      customAspectRatio = if (snapshot.videoPresentationIsKnown) snapshot.customAspectRatio else oldState?.customAspectRatio ?: snapshot.customAspectRatio,
      sid = if (snapshot.mpvSettingsAreKnown) snapshot.sid else oldState?.sid ?: snapshot.sid,
      secondarySid = if (snapshot.mpvSettingsAreKnown) snapshot.secondarySid else oldState?.secondarySid ?: snapshot.secondarySid,
      subDelay = if (snapshot.mpvSettingsAreKnown) snapshot.subDelayMs else oldState?.subDelay ?: snapshot.subDelayMs,
      subSpeed = if (snapshot.mpvSettingsAreKnown) snapshot.subSpeed else oldState?.subSpeed ?: snapshot.subSpeed,
      aid = if (snapshot.mpvSettingsAreKnown) snapshot.aid else oldState?.aid ?: snapshot.aid,
      audioDelay = if (snapshot.mpvSettingsAreKnown) snapshot.audioDelayMs else oldState?.audioDelay ?: snapshot.audioDelayMs,
      timeRemaining = timeRemaining,
      externalSubtitles = externalSubtitles,
      hasBeenWatched =
        isWatched(
          oldState = oldState,
          currentPosition = snapshot.currentPosition,
          lastPosition = lastPosition,
          duration = duration,
          watchedThreshold = watchedThreshold,
        ),
      audioSelection = snapshot.audioSelection ?: oldState?.audioSelection,
      subtitleSelection = snapshot.subtitleSelection ?: oldState?.subtitleSelection,
    )
  }

  private val selectionJson = Json { encodeDefaults = false }

  fun encodeSelection(selection: TrackSelection): String =
    selectionJson.encodeToString(SharedTrackSelection.serializer(), selection)

  fun decodeSelection(encoded: String?): TrackSelection? = encoded?.let {
    runCatching { selectionJson.decodeFromString(SharedTrackSelection.serializer(), it) }.getOrNull()
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

    if (duration <= 0) return currentPosition.coerceAtLeast(0)

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
