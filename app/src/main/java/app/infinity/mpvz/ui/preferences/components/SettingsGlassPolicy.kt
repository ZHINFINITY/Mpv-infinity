/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.preferences.components

/** Geometry shared by every standalone settings row surface. */
data class SettingsGlassGeometry(
  val horizontalInsetDp: Float = SettingsGlassPolicy.RowHorizontalInsetDp,
  val verticalSpacingDp: Float = SettingsGlassPolicy.RowVerticalSpacingDp,
  val cornerRadiusDp: Float = SettingsGlassPolicy.CornerRadiusDp,
  val minimumHeightDp: Float = SettingsGlassPolicy.MinimumHeightDp,
)

/** Pure, host-JVM-testable row geometry. Glass changes optics, never bounds. */
object SettingsGlassPolicy {
  const val RowHorizontalInsetDp = 16f
  const val RowVerticalSpacingDp = 8f
  const val CornerRadiusDp = 18f
  const val MinimumHeightDp = 72f

  fun geometry(glassEnabled: Boolean): SettingsGlassGeometry = SettingsGlassGeometry()
}
