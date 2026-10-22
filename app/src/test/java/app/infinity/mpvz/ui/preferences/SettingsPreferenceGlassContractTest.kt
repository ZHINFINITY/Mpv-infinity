/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.preferences

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the actual preference wrappers against regressing to one giant group pane. */
class SettingsPreferenceGlassContractTest {
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private val glass = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsGlass.kt").readText()
  private val policy = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsGlassPolicy.kt").readText()
  private val wrappers = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsPreferenceWrappers.kt").readText()
  private val card = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/CardPreferences.kt").readText()
  private val switch = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SwitchPreference.kt").readText()
  private val customRows = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsComponents.kt").readText()

  @Test
  fun rowsHaveBalancedInsetAndSingleSurfaceOwner() {
    assertEquals(12f, Regex("RowHorizontalInsetDp = ([0-9]+)f").find(policy)!!.groupValues[1].toFloat(), 0f)
    assertEquals(4f, Regex("RowVerticalSpacingDp = ([0-9]+)f").find(policy)!!.groupValues[1].toFloat(), 0f)
    assertEquals(64f, Regex("MinimumHeightDp = ([0-9]+)f").find(policy)!!.groupValues[1].toFloat(), 0f)
    assertTrue(wrappers.contains("modifier.settingsGlassPreferenceRow()"))
    assertTrue(switch.contains(".settingsGlassPreferenceRow()"))
    assertTrue(customRows.contains(".settingsGlassPreferenceRow()"))
    assertTrue(glass.contains("SettingsGlassPolicy.geometry(enabled)"))
    assertTrue(glass.contains("geometry.cornerRadiusDp.dp"))
    assertTrue(customRows.contains("SettingsClickableItem("))
    assertFalse(card.contains("CompositionLocalProvider(LocalSettingsGlassOwner"))
    assertFalse(card.contains(".settingsGlassSurface("))
  }

  @Test
  fun bothMaterialModesShareGeometryAndOnlyMaterialBranches() {
    assertTrue(policy.contains("fun geometry(glassEnabled: Boolean): SettingsGlassGeometry"))
    assertTrue(policy.contains("data class SettingsGlassGeometry"))
    assertTrue(glass.contains("fun Modifier.settingsGlassPreferenceRow(): Modifier ="))
    assertTrue(glass.contains("return this"))
    assertTrue(glass.contains("surfaceContainerLow"))
    assertTrue(glass.contains("return liquidGlassPane("))
    assertTrue(wrappers.contains("fun <T> ListPreference("))
    assertTrue(wrappers.contains("fun SliderPreference("))
    assertTrue(wrappers.contains("fun <T> TextFieldPreference("))
    assertFalse(wrappers.contains("if (!glassOptionsEnabled())"))
    assertFalse(glass.contains("if (!settingsGlassEnabled()) return this"))
    assertFalse(card.contains("import androidx.compose.material3.Card"))
    assertTrue(card.contains("Individual preference wrappers own their surfaces"))
  }
}
