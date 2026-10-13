/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

internal object MpvFlowLogConfig {
  const val FLOW_VERBOSE_DIAGNOSTICS = "all=info,vo/gpu-next/libplacebo=v"

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
