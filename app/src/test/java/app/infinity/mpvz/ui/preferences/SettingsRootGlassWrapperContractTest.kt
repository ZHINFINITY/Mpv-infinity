/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.preferences

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRootGlassWrapperContractTest {
  private val sourceRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
    .map { it.resolve("app/src/main/java/app/infinity/mpvz/ui/preferences") }
    .first { Files.isDirectory(it) }
  private val components = sourceRoot.resolve("components/SettingsScaffold.kt")

  @Test
  fun sharedWrappersKeepNativeRootsWhenGlassIsDisabled() {
    val source = components.readText()
    assertTrue(source.contains("fun SettingsScaffold("))
    assertTrue(source.contains("containerColor = if (glassEnabled) Color.Transparent else containerColor"))
    assertTrue(source.contains("fun SettingsTopAppBar("))
    assertTrue(source.contains("modifier.settingsGlassSurface(cornerRadius = 28.dp)"))
    assertTrue(source.contains("colors.copy(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)"))
    assertTrue(Regex("else\\s*\\{\\s*colors\\s*}").containsMatchIn(source))
    assertTrue(source.contains("MaterialScaffold(") && source.contains("MaterialTopAppBar("))
  }

  @Test
  fun everyPreferenceRootUsesTheSharedAliases() {
    Files.list(sourceRoot).use { files ->
      files.filter { it.toString().endsWith(".kt") }.forEach { file ->
        val source = file.readText()
        assertFalse("raw Scaffold import in $file", source.contains("import androidx.compose.material3.Scaffold"))
        assertFalse("raw TopAppBar import in $file", source.contains("import androidx.compose.material3.TopAppBar\n"))
        if (source.contains("Scaffold(") && source.contains("import ")) {
          assertTrue("missing SettingsScaffold alias in $file", source.contains("SettingsScaffold as Scaffold"))
        }
        if (source.contains("TopAppBar(") && source.contains("import ")) {
          assertTrue("missing SettingsTopAppBar alias in $file", source.contains("SettingsTopAppBar as TopAppBar"))
        }
      }
    }
  }
}
