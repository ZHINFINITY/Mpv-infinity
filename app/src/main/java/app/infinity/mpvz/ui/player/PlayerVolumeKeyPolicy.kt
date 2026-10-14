/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

internal object PlayerVolumeKeyPolicy {
  fun shouldUseSystemVolumeUi(
    isAudioOnly: Boolean,
    isKnownAudio: Boolean,
    isAudioLaunch: Boolean,
  ): Boolean =
    isAudioOnly || isKnownAudio || isAudioLaunch
}
