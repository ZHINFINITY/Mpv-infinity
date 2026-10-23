/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.preferences

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the reference settings hierarchy: grouped cards own glass, rows do not become cards. */
class SettingsPreferenceGlassContractTest {
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private val glass = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsGlass.kt").readText()
  private val card = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/CardPreferences.kt").readText()
  private val wrappers = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsPreferenceWrappers.kt").readText()
  private val switch = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SwitchPreference.kt").readText()
  private val topPreferences = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/PreferencesScreen.kt").readText()
  private val customRows = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsComponents.kt").readText()

  @Test fun groupedCardsOwnGlassAndRowsKeepReferenceGeometry() {
    assertTrue(card.contains("androidx.compose.material3.Card"))
    assertTrue(card.contains("CompositionLocalProvider(LocalSettingsGlassOwner provides true)"))
    assertTrue(card.contains(".settingsGlassSurface(cornerRadius = 28.dp)"))
    assertTrue(glass.contains("LocalSettingsGlassOwner.current && !independent"))
    assertFalse(wrappers.contains("settingsGlassPreferenceRow"))
    assertFalse(switch.contains("settingsGlassPreferenceRow"))
    assertFalse(customRows.contains("settingsGlassPreferenceRow"))
  }

  @Test fun destinationIndexUsesReferenceSurfaceEntriesNotStandaloneCards() {
    assertFalse(topPreferences.contains("settingsGlassPreferenceRow"))
    assertFalse(topPreferences.contains("settings-destination-card:"))
    assertFalse(topPreferences.contains("SettingsDestinationCard("))
    assertTrue(topPreferences.contains("SettingsDestinationGroup("))
    assertTrue(topPreferences.contains("SettingsDestinationRow("))
    assertTrue(topPreferences.contains("HorizontalDivider("))
  }

  @Test fun independentPopupGlassRemainsAvailable() {
    assertTrue(glass.contains("independent: Boolean = false"))
    assertTrue(glass.contains("GlassSurfacePolicy.MaterialRole.Regular"))
  }
}
