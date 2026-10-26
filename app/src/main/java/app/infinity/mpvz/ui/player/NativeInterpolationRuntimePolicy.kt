/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

/** Runtime setting changes may start Flow before resource initialization, but not retry a failed setup. */
internal object NativeInterpolationRuntimePolicy {
  fun canApplyRuntimeConfiguration(
    disposed: Boolean,
    preflightAvailable: Boolean,
    flowResourcesAttempted: Boolean,
    flowAvailable: Boolean,
    currentInterpolationEnabled: Boolean,
    requestedInterpolationEnabled: Boolean,
    requestedFormatEligible: Boolean,
  ): Boolean =
    !disposed &&
      ((preflightAvailable &&
        (flowAvailable || (!flowResourcesAttempted && requestedFormatEligible))) ||
        (currentInterpolationEnabled && !requestedInterpolationEnabled))
}
