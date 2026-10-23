/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

/** The GL-thread sink must be alive and ready before a shortcut may mutate live processing. */
internal object NativeInterpolationRuntimePolicy {
  fun canMutateLiveSink(disposed: Boolean, preflightAvailable: Boolean, flowAvailable: Boolean): Boolean =
    !disposed && preflightAvailable && flowAvailable
}
