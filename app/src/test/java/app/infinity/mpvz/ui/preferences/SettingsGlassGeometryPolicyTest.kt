/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.preferences

import app.infinity.mpvz.ui.preferences.components.SettingsGlassPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsGlassGeometryPolicyTest {
  @Test
  fun geometryIsIdenticalForGlassAndClassicMaterial() {
    assertEquals(SettingsGlassPolicy.geometry(true), SettingsGlassPolicy.geometry(false))
  }

  @Test
  fun geometryHasOneInsetAndOneShapePolicy() {
    val geometry = SettingsGlassPolicy.geometry(false)
    assertEquals(16f, geometry.horizontalInsetDp, 0f)
    assertEquals(8f, geometry.verticalSpacingDp, 0f)
    assertEquals(18f, geometry.cornerRadiusDp, 0f)
    assertEquals(72f, geometry.minimumHeightDp, 0f)
  }
}
