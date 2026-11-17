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

  fun shouldShowPlaybackControls(controlsVisible: Boolean, bufferingIndicatorVisible: Boolean): Boolean =
    controlsVisible && !bufferingIndicatorVisible

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

  fun isNativePlaybackStalled(
    isEnded: Boolean,
    hasError: Boolean,
    isReady: Boolean,
    isLoading: Boolean,
    isBuffering: Boolean,
  ): Boolean = !isEnded && !hasError && (!isReady || isLoading || isBuffering)
}
