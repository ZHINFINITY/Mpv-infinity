/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerActivityRuntimeScriptTest {
  @Test
  fun selectedScriptsAreStagedInOneSerializedGenerationAndLoadedAfterPublication() {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val activity = File(root, "app/infinity/mpvz/ui/player/PlayerActivity.kt").readText()
    val loader = activity.substringAfter("private fun loadScriptsAtRuntime(").substringBefore("// ==================== Helpers")
    val scriptSync =
      activity.substringAfter("private fun syncScripts(")
        .substringBefore("private fun syncScriptSupportDirectories(")
    val enableObserver =
      activity.substringAfter("advancedPreferences.enableLuaScripts.changes().drop(1).collect { enabled ->")
        .substringBefore("lifecycleScope.launch")

    assertTrue(activity.contains("loadScriptsAtRuntime(addedScripts.toList())"))
    assertTrue(activity.contains("loadScriptsAtRuntime(selectedScripts.toList())"))
    assertTrue(scriptSync.contains("CustomButtonScriptFilePolicy.isManaged(file.name, emptySet())"))
    assertTrue(scriptSync.contains("protectedNames = protectedCustomButtonScripts"))
    assertTrue(loader.contains("mpvScriptAssetLoadMutex.withLock"))
    assertTrue(loader.contains("val generation = mpvScriptAssetGeneration.incrementAndGet()"))
    assertTrue(loader.contains("publishPaths = listOf(\"scripts\", \"script-modules\", \"script-opts\")"))
    assertTrue(loader.contains("RuntimeScriptStagePolicy.isUsable(targetFile.isFile, copiedFromProvider, cachedCopyAvailable)"))
    assertTrue(loader.contains("val requestedScripts = requestedScriptNames.filter { it in selectedScripts }"))
    assertTrue(loader.contains("failedNames += scriptName"))
    assertTrue(loader.contains("continue"))
    assertTrue(loader.contains("advancedPreferences.enableLuaScripts.get()"))
    assertTrue(loader.contains("stagedScriptNames.filter { it in currentSelection }"))
    assertTrue(loader.contains("Unable to load selected scripts:"))
    assertTrue(loader.contains("(unavailable:"))
    assertTrue(enableObserver.contains("mpvScriptAssetGeneration.incrementAndGet()"))

    val publishGuard = loader.indexOf("if (stagedScriptNames.isEmpty())")
    val prePublishSelectionCheck = loader.indexOf("val currentlyEnabled = advancedPreferences.enableLuaScripts.get()")
    val preCommandSelectionCheck = loader.indexOf("val scriptsToLoad =")
    val loadCommands = loader.indexOf("scriptsToLoad.forEach")
    assertTrue(publishGuard >= 0 && prePublishSelectionCheck >= 0 && preCommandSelectionCheck > prePublishSelectionCheck)
    assertTrue(loadCommands > preCommandSelectionCheck)
  }

  @Test
  fun cachedAndSuccessfullyCopiedScriptsAreUsableButFailedCopiesWithoutCacheAreRejected() {
    assertTrue(RuntimeScriptStagePolicy.isUsable(targetIsFile = true, copiedFromProvider = false, cachedCopyAvailable = true))
    assertTrue(RuntimeScriptStagePolicy.isUsable(targetIsFile = true, copiedFromProvider = true, cachedCopyAvailable = false))
    assertFalse(RuntimeScriptStagePolicy.isUsable(targetIsFile = true, copiedFromProvider = false, cachedCopyAvailable = false))
    assertFalse(RuntimeScriptStagePolicy.isUsable(targetIsFile = false, copiedFromProvider = true, cachedCopyAvailable = false))
  }
}
