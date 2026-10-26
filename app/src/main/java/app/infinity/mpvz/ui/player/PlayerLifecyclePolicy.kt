/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal object PlaybackActivityOwner {
  private val sequence = AtomicLong()
  private val ownershipLock = ReentrantReadWriteLock(true)
  private var activeToken = 0L

  fun claim(): Long =
    ownershipLock.write {
      sequence.incrementAndGet().also { token -> activeToken = token }
    }

  fun owns(token: Long): Boolean = ownershipLock.read { token > 0L && activeToken == token }

  fun beginRequest(
    token: Long,
    action: () -> Unit,
  ): Boolean =
    ownershipLock.write {
      if (token <= 0L || activeToken != token) return@write false
      action()
      true
    }

  fun <T> runIfOwner(
    token: Long,
    staleValue: T,
    action: () -> T,
  ): T =
    ownershipLock.read {
      if (token <= 0L || activeToken != token) staleValue else action()
    }
}

internal enum class PlaybackLaunchPreparation {
  INITIALIZE_CORE,
  REUSE_CORE,
  ATTACH_CURRENT_MEDIA,
  REPLACE_CURRENT_MEDIA,
  WAIT_FOR_STOP,
}

internal object PlayerLifecyclePolicy {
  fun launchPreparation(
    coreInitialized: Boolean,
    phase: PlaybackPhase,
    attachCurrentMedia: Boolean,
  ): PlaybackLaunchPreparation {
    if (!coreInitialized || phase == PlaybackPhase.UNINITIALIZED) {
      return PlaybackLaunchPreparation.INITIALIZE_CORE
    }
    if (attachCurrentMedia && phase in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) {
      return PlaybackLaunchPreparation.ATTACH_CURRENT_MEDIA
    }
    return when (phase) {
      PlaybackPhase.LOADING,
      PlaybackPhase.READY,
      PlaybackPhase.BACKGROUND,
      -> PlaybackLaunchPreparation.REPLACE_CURRENT_MEDIA
      PlaybackPhase.STOPPING -> PlaybackLaunchPreparation.WAIT_FOR_STOP
      PlaybackPhase.INITIALIZING,
      PlaybackPhase.IDLE,
      PlaybackPhase.ERROR,
      -> PlaybackLaunchPreparation.REUSE_CORE
      PlaybackPhase.UNINITIALIZED -> PlaybackLaunchPreparation.INITIALIZE_CORE
    }
  }

  /** Auto-PiP owns Home/Back navigation whenever a playable video can enter it. */
  fun shouldEnterPipOnNavigation(
    autoPipEnabled: Boolean,
    mediaReady: Boolean,
    isAudioMedia: Boolean,
    isActivityUnavailable: Boolean,
    isAlreadyInPip: Boolean,
  ): Boolean =
    autoPipEnabled &&
      mediaReady &&
      !isAudioMedia &&
      !isActivityUnavailable &&
      !isAlreadyInPip

  fun shouldPauseOnPause(
    backgroundPlaybackEnabled: Boolean,
    backgroundPlaybackSessionActive: Boolean,
    isUserFinishing: Boolean,
    isInPictureInPictureMode: Boolean,
    isScreenOffOrLocked: Boolean,
  ): Boolean {
    if (isUserFinishing && !backgroundPlaybackSessionActive) return true
    if (isInPictureInPictureMode && !isScreenOffOrLocked) return false

    return !backgroundPlaybackEnabled
  }

  fun shouldStartBackgroundPlaybackOnBack(
    backgroundPlaybackEnabled: Boolean,
    mediaReady: Boolean,
  ): Boolean = backgroundPlaybackEnabled && mediaReady

  /**
   * Whether onDestroy hands the live session to the service instead of stopping it. Either the
   * background-playback preference allows it, or the user explicitly minimized into the Mini
   * Player ([miniPlayerHandoff]). The Mini Player does not depend on that preference (audio
   * background playback defaults to off), so without the second case the first minimize after a
   * fresh install stopped the song, cleared the queue and left only the resume card behind.
   */
  fun shouldKeepBackgroundPlaybackAliveOnDestroy(
    backgroundPlaybackEnabled: Boolean,
    backgroundPlaybackSessionActive: Boolean,
    miniPlayerHandoff: Boolean = false,
  ): Boolean = backgroundPlaybackSessionActive && (backgroundPlaybackEnabled || miniPlayerHandoff)

  /** Pause state belongs to the active renderer; the inactive MPV core may stay paused. */
  fun activeEnginePausedState(
    activeEngine: PlaybackEngineMode,
    nativePlayWhenReady: Boolean?,
    mpvPaused: Boolean?,
  ): Boolean? =
    if (activeEngine == PlaybackEngineMode.NATIVE) nativePlayWhenReady?.not() else mpvPaused

  /** Preserve an intentional pause while restoring a playing service handoff. */
  fun shouldResumeAfterBackgroundHandoff(
    backgroundPlaybackSessionActive: Boolean,
    wasPlayingBeforeHandoff: Boolean,
    isInPictureInPictureMode: Boolean,
  ): Boolean =
    backgroundPlaybackSessionActive &&
      wasPlayingBeforeHandoff &&
      !isInPictureInPictureMode

  fun shouldTreatStopAsPipDismissal(
    wasInPictureInPictureMode: Boolean,
    isInPictureInPictureMode: Boolean,
    isActivityFinishing: Boolean,
    isChangingConfigurations: Boolean,
    isScreenOffOrLocked: Boolean,
    alreadyHandled: Boolean,
  ): Boolean =
    wasInPictureInPictureMode &&
      (!isInPictureInPictureMode || isActivityFinishing) &&
      !isChangingConfigurations &&
      !isScreenOffOrLocked &&
      !alreadyHandled

  fun shouldStartBackgroundPlaybackOnStop(
    backgroundPlaybackEnabled: Boolean,
    backgroundPlaybackSessionActive: Boolean,
    isUserFinishing: Boolean,
    isFinishing: Boolean,
    isInPictureInPictureMode: Boolean,
    isScreenOffOrLocked: Boolean,
  ): Boolean =
    backgroundPlaybackEnabled &&
      !backgroundPlaybackSessionActive &&
      !isUserFinishing &&
      !isFinishing &&
      (!isInPictureInPictureMode || isScreenOffOrLocked)

  /** Music never needs Media3. Building it on song tap or mini-player expand stalls the UI. */
  fun shouldInitializeNativeMedia3Engine(isAudioLaunch: Boolean): Boolean = !isAudioLaunch

  /** Compose already draws cover art. MPV's embedded audio-display would stack a second image. */
  fun audioDisplayOption(configOwnsAudioDisplay: Boolean): String? =
    if (configOwnsAudioDisplay) null else "no"

  /**
   * After a long screen-off or background interval, OEM decoders (especially ColorOS/Realme)
   * keep the session alive but drop the video Surface. Rebuild instead of showing a black frame.
   */
  fun shouldRebuildRendererAfterBackground(
    stoppedForMs: Long,
    isAudioMedia: Boolean,
    thresholdMs: Long = LONG_BACKGROUND_REBUILD_THRESHOLD_MS,
  ): Boolean = !isAudioMedia && stoppedForMs >= thresholdMs

  /** Recover Native playback if backgrounding dropped its item or left it idle. */
  fun shouldRecoverNativePlayback(
    hasCurrentMediaItem: Boolean,
    playbackIsIdle: Boolean,
    mediaLoadInProgress: Boolean,
  ): Boolean = !mediaLoadInProgress && (!hasCurrentMediaItem || playbackIsIdle)

  /** Reloading media before Android recreates the Surface leaves ColorOS on a black frame. */
  fun shouldWaitForSurfaceBeforeRebuild(surfaceReady: Boolean): Boolean = !surfaceReady

  /** Music keeps its own session. Video must remember time-pos before OEM Surface teardown. */
  fun shouldCaptureForegroundResumePosition(isAudioMedia: Boolean): Boolean = !isAudioMedia

  fun resumePositionSeconds(positionSeconds: Double?): Double? =
    positionSeconds?.takeIf { it.isFinite() && it > 0.0 }

  /**
   * Whether service-owned playback must pause because the whole app just left the foreground.
   *
   * After a minimize, Back or background round trip the playback service owns the session and
   * the full player is the in-app music sheet (or Mini Player) inside the library, not a
   * PlayerActivity. Turning background playback off there only changed the preference: nothing
   * applied it when the app was left, so music kept playing in the background until the app
   * was restarted. Leaving the app now honours the setting for the current media kind, exactly
   * like PlayerActivity does. Inside the app (sheet <-> Mini Player) playback is unaffected.
   */
  fun shouldPauseDetachedPlaybackOnAppBackground(
    serviceOwnsPlayback: Boolean,
    isAudio: Boolean,
    audioBackgroundPlaybackEnabled: Boolean,
    videoBackgroundPlaybackEnabled: Boolean,
    hasActiveItem: Boolean,
    isPaused: Boolean,
  ): Boolean {
    if (!serviceOwnsPlayback || !hasActiveItem || isPaused) return false
    val backgroundPlaybackEnabled = if (isAudio) audioBackgroundPlaybackEnabled else videoBackgroundPlaybackEnabled
    return !backgroundPlaybackEnabled
  }

  const val LONG_BACKGROUND_REBUILD_THRESHOLD_MS = 900_000L
}
