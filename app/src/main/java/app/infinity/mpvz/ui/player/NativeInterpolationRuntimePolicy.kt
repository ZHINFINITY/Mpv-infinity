/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

/** Runtime setting changes may retry Flow resources only as part of an explicit controlled reload. */
internal object NativeInterpolationRuntimePolicy {
  fun canApplyRuntimeConfiguration(
    disposed: Boolean,
    preflightAvailable: Boolean,
    flowResourcesAttempted: Boolean,
    flowAvailable: Boolean,
    currentInterpolationEnabled: Boolean,
    requestedInterpolationEnabled: Boolean,
    requestedFormatEligible: Boolean,
    allowRetryAfterResourceFailure: Boolean = false,
  ): Boolean =
    !disposed &&
      ((preflightAvailable &&
        (flowAvailable || (!flowResourcesAttempted && requestedFormatEligible) ||
          (allowRetryAfterResourceFailure && requestedFormatEligible))) ||
        (currentInterpolationEnabled && !requestedInterpolationEnabled))
}
