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

    assertTrue(activity.contains("loadScriptsAtRuntime(addedScripts.toList())"))
    assertTrue(activity.contains("loadScriptsAtRuntime(selectedScripts.toList())"))
    assertTrue(loader.contains("mpvScriptAssetLoadMutex.withLock"))
    assertTrue(loader.contains("val generation = mpvScriptAssetGeneration.incrementAndGet()"))
    assertTrue(loader.contains("publishPaths = listOf(\"scripts\", \"script-modules\", \"script-opts\")"))
    assertTrue(loader.contains("RuntimeScriptStagePolicy.isUsable(targetFile.isFile, copiedFromProvider, cachedCopyAvailable)"))

    val publishGuard = loader.indexOf("if (!published || stagedScriptNames.isEmpty()) return@withLock")
    val loadCommands = loader.indexOf("stagedScriptNames.forEach")
    assertTrue(publishGuard >= 0 && loadCommands > publishGuard)
  }

  @Test
  fun cachedAndSuccessfullyCopiedScriptsAreUsableButFailedCopiesWithoutCacheAreRejected() {
    assertTrue(RuntimeScriptStagePolicy.isUsable(targetIsFile = true, copiedFromProvider = false, cachedCopyAvailable = true))
    assertTrue(RuntimeScriptStagePolicy.isUsable(targetIsFile = true, copiedFromProvider = true, cachedCopyAvailable = false))
    assertFalse(RuntimeScriptStagePolicy.isUsable(targetIsFile = true, copiedFromProvider = false, cachedCopyAvailable = false))
    assertFalse(RuntimeScriptStagePolicy.isUsable(targetIsFile = false, copiedFromProvider = true, cachedCopyAvailable = false))
  }
}
