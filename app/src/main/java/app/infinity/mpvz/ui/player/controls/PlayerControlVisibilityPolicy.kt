/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls

/** Keeps player control visibility and buffering decisions consistent across playback engines. */
internal object PlayerControlVisibilityPolicy {
  fun areButtonsVisible(controlsShown: Boolean, controlsLocked: Boolean, slidersShown: Boolean): Boolean =
    controlsShown && !controlsLocked && !slidersShown

  fun shouldShowPlaybackControls(
    controlsVisible: Boolean,
    bufferingIndicatorVisible: Boolean,
    sharedLandscapeRow: Boolean = false,
  ): Boolean = controlsVisible && (sharedLandscapeRow || !bufferingIndicatorVisible)

  fun shouldAutoHideControls(
    isAudioOnly: Boolean,
    controlsShown: Boolean,
    paused: Boolean?,
    bufferingOrWaiting: Boolean,
    isSeeking: Boolean,
    isUnlockSliderDragging: Boolean,
    drawerBlocksAutoHide: Boolean,
  ): Boolean =
    !isAudioOnly &&
      controlsShown &&
      (paused != true || bufferingOrWaiting) &&
      !isSeeking &&
      !isUnlockSliderDragging &&
      !drawerBlocksAutoHide

  /** Selects the active engine's debounced state; stale MPV phases never leak into Native playback. */
  fun shouldShowBufferingIndicator(
    nativeEngineActive: Boolean,
    nativeBufferingVisible: Boolean,
    mpvBufferingVisible: Boolean,
    isSeeking: Boolean,
  ): Boolean {
    if (isSeeking) return false
    return if (nativeEngineActive) nativeBufferingVisible else mpvBufferingVisible
  }

  /** Suppress local prepare until its first frame; playback advancement wins and loading alone after it is not a stall. */
  fun isNativePlaybackStalled(
    isEnded: Boolean,
    isBuffering: Boolean,
    hasError: Boolean = false,
    isReady: Boolean = true,
    isLoading: Boolean = false,
    isLocalSource: Boolean = false,
    hasRenderedFirstFrame: Boolean = true,
    isPlaying: Boolean = false,
  ): Boolean =
    !isEnded &&
      !hasError &&
      (!isLocalSource || hasRenderedFirstFrame) &&
      !isPlaying &&
      (!isReady || isBuffering || (isLoading && !hasRenderedFirstFrame))

  /** A torrent can be waiting for data before MPV has a playable source to report as stalled. */
  fun shouldForceMpvBufferingIndicator(
    nativeEngineActive: Boolean,
    torrentConnecting: Boolean,
  ): Boolean = !nativeEngineActive && torrentConnecting
}
