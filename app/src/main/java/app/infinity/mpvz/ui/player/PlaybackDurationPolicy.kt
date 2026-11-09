/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import kotlin.math.abs

/** Reconciles transient MPV duration reads with the active track's known timeline. */
internal object PlaybackDurationPolicy {
  fun initialDurationSeconds(itemDurationSeconds: Int?): Double =
    itemDurationSeconds?.takeIf { it > 0 }?.toDouble() ?: 0.0

  fun resolve(
    previousDurationSeconds: Double,
    observedDurationSeconds: Double?,
    sameItem: Boolean,
    preserveSameItemDuration: Boolean,
  ): Double {
    val previous = previousDurationSeconds.takeIf { it.isFinite() && it > 0.0 }
    val observed = observedDurationSeconds?.takeIf { it.isFinite() && it > 0.0 }
    if (sameItem && preserveSameItemDuration && previous != null && observed != null && abs(previous - observed) > 1.0) {
      return previous
    }
    return observed ?: previous ?: 0.0
  }
}
