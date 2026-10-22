/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source integration checks complement the actual rendered FolderCard runtime suite. */
class BrowserMaterialParityContractTest {
  private fun source(name: String): String {
    val relative = "src/main/java/app/infinity/mpvz/ui/theme/$name.kt"
    return listOf(File(relative), File("app/$relative")).first { it.exists() }.readText()
  }

  @Test fun browserNavigationAndSettingsUseTheSameDepthProfileAsPlayerPanes() {
    val scene = source("BrowserGlassScene")
    val pane = scene.substring(scene.indexOf("internal fun Modifier.browserGlassPane("), scene.indexOf("private fun windowScreenOffset"))
    val player = source("LiquidGlassBackdrop")
    assertTrue(pane.contains("GlassSurfacePolicy.depthOptics(tuning.depth, glow)"))
    for (field in listOf("bodyHighlight", "bodyShade", "rimHighlight", "rimShade", "rimWidthDp", "shadowAlpha")) {
      assertTrue("Browser must consume shared $field", pane.contains("optics.$field"))
      assertTrue("Player must consume shared $field", player.contains("optics.$field"))
    }
    assertFalse(pane.contains("0.06f + glow * 0.10f"))
    assertTrue(pane.contains("RenderEffect.createRuntimeShaderEffect(shader, \"scene\")"))
    assertTrue(pane.contains("GlassSurfacePolicy.browserFallbackAlpha(film)"))
  }

  @Test fun actualBrowserPaneHonorsGlobalMaterialOverrideAndBorderlessCaller() {
    val scene = source("BrowserGlassScene")
    val pane = scene.substring(scene.indexOf("internal fun Modifier.browserGlassPane("), scene.indexOf("private fun windowScreenOffset"))
    assertTrue(pane.contains("if (tuning.overrideMaterial) tuning.opacity else opacity"))
    assertTrue(pane.contains("if (tuning.overrideMaterial) tuning.highlight else highlight"))
    assertTrue(pane.contains("if (drawRim) drawRoundRect(edge"))
    assertTrue(pane.contains("enabled && drawRim && optics.shadowAlpha"))
    val dispatch = source("LiquidGlassBackdrop").substringAfter("if (backdrop == null && hasBrowserGlassScene()) {").substringBefore("val tint =")
    assertTrue("Do not add a second frame to borderless controls", dispatch.contains("drawRim = drawRim"))
  }

  @Test fun visibleStaticDepthDoesNotDependOnSuccessfulGpuRefraction() {
    for (depth in listOf(0f, 0.5f, 1f, 2f)) {
      val profile = GlassSurfacePolicy.depthOptics(depth, 0.18f)
      assertTrue(profile.bodyHighlight in 0f..0.30f)
      assertTrue(profile.bodyShade in 0f..0.20f)
      assertTrue(GlassSurfacePolicy.browserFallbackAlpha(1f) < 0.3f)
    }
    assertTrue(GlassSurfacePolicy.depthOptics(1f, 0.18f).bodyHighlight >= 0.15f)
    assertTrue(GlassSurfacePolicy.depthOptics(2f, 0.18f).bodyHighlight >
      GlassSurfacePolicy.depthOptics(0f, 0.18f).bodyHighlight)
  }
}
