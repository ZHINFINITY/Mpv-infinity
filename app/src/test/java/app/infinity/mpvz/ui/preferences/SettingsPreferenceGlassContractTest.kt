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
  private val wrappers = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsPreferenceWrappers.kt").readText()
  private val card = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/CardPreferences.kt").readText()
  private val switch = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SwitchPreference.kt").readText()
  private val customRows = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsComponents.kt").readText()

  @Test
  fun rowsHaveBalancedInsetAndSingleSurfaceOwner() {
    assertEquals(12f, Regex("RowHorizontalInsetDp = ([0-9]+)f").find(glass)!!.groupValues[1].toFloat(), 0f)
    assertEquals(4f, Regex("RowVerticalSpacingDp = ([0-9]+)f").find(glass)!!.groupValues[1].toFloat(), 0f)
    assertTrue(wrappers.contains("modifier.settingsGlassPreferenceRow()"))
    assertTrue(switch.contains(".settingsGlassPreferenceRow()"))
    assertTrue(customRows.contains(".settingsGlassPreferenceRow()"))
    assertTrue(customRows.contains("if (glassEnabled) RoundedCornerShape(18.dp) else"))
    assertTrue(customRows.contains("SettingsClickableItem("))
    assertFalse(card.contains("CompositionLocalProvider(LocalSettingsGlassOwner"))
    assertFalse(card.contains(".settingsGlassSurface("))
  }

  @Test
  fun disabledMasterKeepsOriginalModifierAndMaterialFallback() {
    assertTrue(glass.contains("if (!settingsGlassEnabled()) return this"))
    assertTrue(glass.contains("return liquidGlassPane("))
    assertTrue(glass.contains("if (!enabled) return this"))
    assertTrue(card.contains("else MaterialTheme.colorScheme.surfaceContainerLow"))
  }
}
