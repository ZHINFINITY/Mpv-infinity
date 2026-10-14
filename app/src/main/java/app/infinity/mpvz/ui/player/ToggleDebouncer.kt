/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import android.os.SystemClock

internal class ToggleDebouncer(
  private val minimumIntervalMs: Long = 350L,
  private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
  private var lastAcceptedAtMs: Long = 0L

  fun tryConsume(nowMs: Long = clock()): Boolean {
    if (lastAcceptedAtMs != 0L && (nowMs - lastAcceptedAtMs) < minimumIntervalMs) return false
    lastAcceptedAtMs = nowMs
    return true
  }

  fun reset() {
    lastAcceptedAtMs = 0L
  }
}

