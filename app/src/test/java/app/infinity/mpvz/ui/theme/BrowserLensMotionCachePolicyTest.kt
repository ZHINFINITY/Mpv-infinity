/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrowserLensMotionCachePolicyTest {
  private fun source(path: String): String =
    File(path).takeIf { it.exists() }?.readText() ?: File("app/$path").readText()

  @Test
  fun browserPaneUpdatesMotionWithoutRebuildingEffectFromDeformation() {
    val browser = source("src/main/java/app/infinity/mpvz/ui/theme/BrowserGlassScene.kt")
    val pane = browser.substringAfter("internal fun Modifier.browserGlassPane(")
    assertTrue(pane.contains("browserLensHandleOrNull("))
    assertTrue(pane.contains("lensHandle?.updateMotion(deformation.coerceIn(0f, 1f))"))
    assertFalse(pane.contains("browserLensEffectOrNull(\n          paneWidth"))
  }

  @Test
  fun runtimeHandleKeepsStaticGeometryAndMotionSeparate() {
    val support = source("src/main/java/app/infinity/mpvz/ui/theme/RuntimeShaderSupport.kt")
    val shaders = source("src/main/java/app/infinity/mpvz/ui/theme/AgslRuntimeShaders.kt")
    assertTrue(support.contains("browserLensHandleOrNull"))
    assertTrue(shaders.contains("browserLensHandle("))
    assertTrue(shaders.contains("setFloatUniform(\"motion\", motion.coerceIn(0f, 1f))"))
  }
}
