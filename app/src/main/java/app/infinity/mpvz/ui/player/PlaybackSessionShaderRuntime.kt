/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import app.infinity.mpvz.domain.hdr.MpvShaderRuntime

/** Bridges the domain-layer shader pipeline contract onto the process-wide playback session. */
object PlaybackSessionShaderRuntime : MpvShaderRuntime {
  override fun command(vararg args: String) {
    PlaybackSession.command(*args)
  }

  override fun getPropertyString(property: String): String? = PlaybackSession.getPropertyString(property)

  override fun setPropertyString(
    property: String,
    value: String,
  ) {
    PlaybackSession.setPropertyString(property, value)
  }
}
