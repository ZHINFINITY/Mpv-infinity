/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

internal object MpvFlowLogConfig {
  // MPV emits measured runtime_cadence at INFO; WARN here would erase FPS statistics.
  const val FLOW_VERBOSE_DIAGNOSTICS = "all=warn,vo/gpu-next=info"

  fun messageLevelOption(
    mpvFlowEnabled: Boolean,
    frameInterpolationEnabled: Boolean,
    verboseLogging: Boolean,
  ): String =
    when {
      mpvFlowEnabled -> FLOW_VERBOSE_DIAGNOSTICS
      frameInterpolationEnabled -> "all=info"
      verboseLogging -> "all=v"
      else -> "all=warn"
    }
}
