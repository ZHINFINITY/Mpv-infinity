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

  /**
   * Native playback owns its readiness/buffering state. Do not OR it with MPV's process-wide phase:
   * that phase can remain LOADING after a native engine handoff.
   */
  fun shouldShowBufferingIndicator(
    nativeEngineActive: Boolean,
    nativeReady: Boolean,
    nativeLoading: Boolean,
    nativeBuffering: Boolean,
    nativeEnded: Boolean,
    mpvLoading: Boolean,
    mpvBuffering: Boolean,
    torrentConnecting: Boolean,
    isSeeking: Boolean,
  ): Boolean {
    if (isSeeking) return false
    return if (nativeEngineActive) {
      !nativeEnded && (!nativeReady || nativeLoading || nativeBuffering)
    } else {
      mpvLoading || mpvBuffering || torrentConnecting
    }
  }
}
