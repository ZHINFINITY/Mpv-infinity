/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

internal object MpvFlowLogConfig {
  const val FLOW_VERBOSE_DIAGNOSTICS = "all=warn,vo/gpu-next=warn"

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
