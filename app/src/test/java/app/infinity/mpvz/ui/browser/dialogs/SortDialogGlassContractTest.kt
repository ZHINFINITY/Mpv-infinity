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
    // Build 1901: inner controls are fills inside the one popup pane, never nested glass panes.
    assertFalse(dialog.contains("liquidGlassPane("))
    assertTrue(dialog.contains("private fun Modifier.popupInnerFill(enabled: Boolean"))
    assertTrue(dialog.contains(".clip(RoundedCornerShape(cornerRadius))"))
  }

  @Test fun actualDialogUsesOneSharedGlassPaneForSegmentedOptions() {
    assertTrue(dialog.contains("liquidGlassSurfaces.collectAsState()"))
    assertTrue(dialog.contains("containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh)"))
    assertTrue(dialog.contains(".settingsGlassSurface(cornerRadius = 28.dp, independent = true)"))
    val calls = Regex("SegmentedButton\\(\\s*modifier = Modifier\\.liquidGlassPane\\(enabled = glassEnabled").findAll(dialog).count()
    assertTrue("segmented options must not add nested glass panes", calls == 0)
    val order = dialog.substringAfter("private fun SortOrderSelector(").substringBefore("private fun DialogSectionTitle")
    assertTrue(order.contains("glassEnabled: Boolean"))
    assertFalse(order.contains("Modifier.liquidGlassPane(enabled = glassEnabled"))
    val type = dialog.substringAfter("private fun SortTypeSelector(").substringBefore("private fun SortOrderSelector")
    // Build 1901: the Sort-by tiles stay visible with glass on (selected = accent tint).
    assertTrue(type.contains("if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)"))
    assertFalse(type.contains("Color.Transparent"))
    assertTrue(type.contains("role = Role.RadioButton"))
  }

  @Test fun sortSectionsRemainScrollableAndFieldsUseBalancedPlainLayout() {
    assertTrue(dialog.contains(".verticalScroll(rememberScrollState())"))
    assertTrue(dialog.contains("DialogSectionTitle(text = \"Sort by\")"))
    val fields = dialog.substringAfter("if (visibilityToggles.isNotEmpty())").substringBefore("confirmButton =")
    val header = fields.substringBefore("if (isFieldsExpanded)")
    assertFalse(header.contains("liquidGlassPane"))
    assertTrue(fields.contains("horizontalArrangement = Arrangement.spacedBy(8.dp)"))
    assertTrue(fields.contains("verticalArrangement = Arrangement.spacedBy(8.dp)"))
    assertTrue(fields.contains("widthIn(min = 76.dp)"))
  }

  @Test fun segmentedHighlightsAreInsetRoundedPills() {
    assertTrue(dialog.contains("modifier = Modifier.padding(horizontal = 3.dp)"))
    assertTrue(dialog.contains("shape = RoundedCornerShape(50)"))
    assertFalse(dialog.contains("SegmentedButtonDefaults.itemShape"))
  }

  @Test fun gridSlidersFieldsAndActionsCannotBypassTheSharedMaterial() {
    assertTrue(dialog.contains("GlassSlider as Slider"))
    assertFalse(dialog.contains("import androidx.compose.material3.Slider"))
    for (name in listOf("folderGridColumnSelector", "videoGridColumnSelector", "selector")) {
      assertTrue("grid slider must retain label and real input seam", dialog.contains("contentDescription = $name.label"))
    }
    val fields = dialog.substringAfter("if (visibilityToggles.isNotEmpty())").substringBefore("confirmButton =")
    assertTrue(fields.contains("FilterChip(\n                      modifier = Modifier\n                        .widthIn(min = 76.dp)"))
    // Build 1901: chips draw their own accent fill/border; no 48dp touch-target glass backplate.
    assertTrue(fields.contains("if (glassEnabled) FilterChipDefaults.filterChipBorder("))
    assertTrue(fields.contains("selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)"))
    assertFalse(fields.contains("liquidGlassPane"))
    val action = dialog.substringAfter("confirmButton =").substringBefore("tonalElevation =")
    assertTrue(action.contains("modifier = Modifier.popupInnerFill(glassEnabled, 18.dp)"))
  }

  @Test fun settingsMasterKeepsIndependentPopupGlassAndReferenceSearchGeometry() {
    val settings = source("ui/preferences/components/SettingsGlass")
    assertTrue(settings.contains("if (!enabled) return this"))
    assertTrue(settings.contains("val tuning = LocalGlassCustomization.current"))
    assertTrue(settings.contains("return liquidGlassPane("))
    val search = source("ui/preferences/PreferencesScreen").substringAfter("private fun SettingsSearchEntry(").substringBefore("private fun")
    assertFalse(search.contains(".settingsGlassPreferenceRow()"))
    assertTrue(search.contains("color = MaterialTheme.colorScheme.secondaryContainer"))
    assertTrue(search.contains("Surface("))
  }
}
