/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.preferences

/**
 * Runtime contract for interpolation preference changes.
 *
 * Renderer/sink construction currently happens at playback load. Until a verified live
 * reconfiguration API exists, changing a preference must not recreate the active session.
 */
internal object InterpolationRuntimeApplyPolicy {
  enum class Decision { APPLY_LIVE, PENDING_UNTIL_NEXT_PLAYBACK }

  fun decision(hasLiveReconfigureApi: Boolean, playbackActive: Boolean): Decision =
    if (hasLiveReconfigureApi && playbackActive) Decision.APPLY_LIVE
    else Decision.PENDING_UNTIL_NEXT_PLAYBACK
}
