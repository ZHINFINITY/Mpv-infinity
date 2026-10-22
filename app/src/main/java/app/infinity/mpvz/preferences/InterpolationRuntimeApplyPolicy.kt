/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.preferences

/**
 * Runtime contract for interpolation preference changes.
 *
 * Native applies through its acknowledged live API. Only the MPV interpolation shortcuts
 * have explicit permission to reopen the current video with a complete state snapshot.
 */
internal object InterpolationRuntimeApplyPolicy {
  enum class Decision { APPLY_LIVE, RESTART_PRESERVING_STATE, PENDING_UNTIL_NEXT_PLAYBACK }

  fun decision(
    hasLiveReconfigureApi: Boolean,
    playbackActive: Boolean,
    restartAuthorized: Boolean = false,
    isMpv: Boolean = false,
  ): Decision = when {
    hasLiveReconfigureApi && playbackActive -> Decision.APPLY_LIVE
    playbackActive && restartAuthorized && isMpv -> Decision.RESTART_PRESERVING_STATE
    else -> Decision.PENDING_UNTIL_NEXT_PLAYBACK
  }
}
