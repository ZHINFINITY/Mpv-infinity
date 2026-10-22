/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser.dialogs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direct caller checks complement actual dialog callbacks and scrolling in Compose. */
class SortDialogGlassContractTest {
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private fun source(path: String) = File(root, "app/src/main/java/app/infinity/mpvz/$path.kt").readText()
  private val dialog = source("ui/browser/dialogs/SortDialog")

  @Test fun innerGlassControlsImportTheirExtensionAfterOuterPopupMaterialChanges() {
    assertTrue(dialog.contains("Modifier.liquidGlassPane("))
    assertTrue(dialog.contains("import app.infinity.mpvz.ui.theme.liquidGlassPane"))
  }

  @Test fun actualDialogAndEachSegmentedOptionUseSharedGlassWithMasterFallback() {
    assertTrue(dialog.contains("liquidGlassSurfaces.collectAsState()"))
    assertTrue(dialog.contains("containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh)"))
    assertTrue(dialog.contains(".settingsGlassSurface(cornerRadius = 28.dp, independent = true)"))
    val calls = Regex("SegmentedButton\\(\\s*modifier = Modifier\\.liquidGlassPane\\(enabled = glassEnabled").findAll(dialog).count()
    assertTrue("view, both layout choices and sort direction all own glass", calls == 4)
    val order = dialog.substringAfter("private fun SortOrderSelector(").substringBefore("private fun DialogSectionTitle")
    assertTrue(order.contains("glassEnabled: Boolean"))
    assertTrue(order.contains("Modifier.liquidGlassPane(enabled = glassEnabled"))
    val type = dialog.substringAfter("private fun SortTypeSelector(").substringBefore("private fun SortOrderSelector")
    assertTrue(type.contains("if (glassEnabled) {\n                    Color.Transparent"))
    assertTrue(type.contains("role = Role.RadioButton"))
  }

  @Test fun gridSlidersFieldsAndActionsCannotBypassTheSharedMaterial() {
    assertTrue(dialog.contains("GlassSlider as Slider"))
    assertFalse(dialog.contains("import androidx.compose.material3.Slider"))
    for (name in listOf("folderGridColumnSelector", "videoGridColumnSelector", "selector")) {
      assertTrue("grid slider must retain label and real input seam", dialog.contains("contentDescription = $name.label"))
    }
    val fields = dialog.substringAfter("if (visibilityToggles.isNotEmpty())").substringBefore("confirmButton =")
    assertTrue(fields.contains("FilterChip(\n                      modifier = Modifier.liquidGlassPane"))
    assertTrue(fields.contains("if (glassEnabled) null else FilterChipDefaults.filterChipBorder"))
    val action = dialog.substringAfter("confirmButton =").substringBefore("tonalElevation =")
    assertTrue(action.contains("modifier = Modifier.liquidGlassPane(enabled = glassEnabled"))
  }

  @Test fun settingsMasterAndSearchEntryShareTheIndividualRowMaterial() {
    val settings = source("ui/preferences/components/SettingsGlass")
    assertTrue(settings.contains("if (!enabled) return this"))
    assertTrue(settings.contains("val tuning = LocalGlassCustomization.current"))
    assertTrue(settings.contains("return liquidGlassPane("))
    val search = source("ui/preferences/PreferencesScreen").substringAfter("private fun SettingsSearchEntry(").substringBefore("private fun")
    assertTrue(search.contains(".settingsGlassPreferenceRow()"))
    assertFalse(search.contains("color = MaterialTheme.colorScheme.secondaryContainer"))
  }
}
