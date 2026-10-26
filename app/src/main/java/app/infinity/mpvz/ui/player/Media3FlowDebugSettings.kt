/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

/** Effective opt-in synthesis policies passed into the Native Media3 Vulkan path. */
internal data class Media3FlowDebugSettings(
  val visibilityResolverEnabled: Boolean = false,
  val softBlendEnabled: Boolean = false,
)

internal object Media3FlowDebugSettingsPolicy {
  /** These user-facing synthesis controls are available in stable builds too. */
  @Suppress("UNUSED_PARAMETER")
  fun controlsVisible(
    debugBuild: Boolean,
    mpvFlowRuntimeAvailable: Boolean,
    nativeMedia3FlowAvailable: Boolean,
  ): Boolean = mpvFlowRuntimeAvailable || nativeMedia3FlowAvailable

  fun controlEnabled(
    mpvFlowAvailable: Boolean,
    nativeMedia3FlowAvailable: Boolean,
    currentValue: Boolean,
  ): Boolean = mpvFlowAvailable || nativeMedia3FlowAvailable || currentValue

  /** Debug-only instrumentation remains gated separately; these settings are runtime features. */
  @Suppress("UNUSED_PARAMETER")
  fun effective(
    debugBuild: Boolean,
    requestedVisibilityResolver: Boolean,
    requestedSoftBlend: Boolean,
  ): Media3FlowDebugSettings {
    val softBlendEnabled = requestedSoftBlend
    // The shared synthesis pipeline only runs the resolver post-pass when soft blend is off.
    // Prefer the requested soft blend when both saved preferences happen to be enabled.
    val visibilityResolverEnabled = requestedVisibilityResolver && !softBlendEnabled
    return Media3FlowDebugSettings(
      visibilityResolverEnabled = visibilityResolverEnabled,
      softBlendEnabled = softBlendEnabled,
    )
  }

  /** MPVFlow runtime options are user-facing features, not diagnostics restricted to debug builds. */
  @Suppress("UNUSED_PARAMETER")
  fun mpvFlowFeatureEnabled(
    debugBuild: Boolean,
    requested: Boolean,
    interpolationActive: Boolean,
    vulkanBackend: Boolean,
    mpvConfOwnsOption: Boolean,
  ): Boolean = requested && interpolationActive && vulkanBackend && !mpvConfOwnsOption
}
