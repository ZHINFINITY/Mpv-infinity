/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import kotlin.math.abs

/** Value comparisons for live MPV aspect updates. */
internal object MpvAspectPropertyUpdatePolicy {
  /** libmpv reports `video-aspect-override=no` as -2.0 in the pinned runtime. */
  const val NO_ASPECT_OVERRIDE = -2.0
  private const val EPSILON = 0.000001

  fun needsUpdate(current: Double?, target: Double): Boolean =
    current == null || !current.isFinite() || !target.isFinite() || abs(current - target) > EPSILON
}
