package app.infinity.mpvz.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import app.infinity.mpvz.ui.player.controls.components.GlassWaterRipplePolicy

class GlassInteractionContractTest {
  private fun source(path: String): String {
    val direct = File(path)
    return (if (direct.exists()) direct else File("app/$path")).readText()
  }

  @Test
  fun glassChipClipsBeforeInstallingItsPressIndication() {
    val chip = source("src/main/java/app/infinity/mpvz/ui/components/GlassChip.kt")
    val clip = chip.indexOf(".clip(chipShape)")
    val feedback = chip.indexOf(".glassPressFeedback", clip)
    val clickable = chip.indexOf(".clickable(", feedback)
    assertTrue("GlassChip must clip before press feedback", clip >= 0 && clip < feedback)
    assertTrue("GlassChip must keep its clickable indication inside that clip", feedback >= 0 && feedback < clickable)
  }

  @Test
  fun castChooserUsesGlassFactoryWhenLiquidGlassIsEnabled() {
    val cast = source("src/main/java/app/infinity/mpvz/ui/cast/CastPlayerButton.kt")
    assertTrue(cast.contains("setDialogFactory("))
    assertTrue(cast.contains("glassMediaRouteDialogFactory"))
    assertTrue(cast.contains("GlassSurfacePolicy.opacity"))
    assertTrue(cast.contains("GlassSurfacePolicy.highlight"))
    assertTrue(cast.contains("setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))"))
  }

  @Test
  fun sharedGlassControlsUseBoundedWaterWaveFeedback() {
    val controls = source("src/main/java/app/infinity/mpvz/ui/player/controls/components/GlassControls.kt")
    val button = source("src/main/java/app/infinity/mpvz/ui/player/controls/components/ControlsButton.kt")
    assertTrue(controls.contains("const val DurationMs = 560"))
    assertTrue(controls.contains("glassWaterRipple(interactionSource"))
    assertTrue(button.contains(".clip(CircleShape)"))
    assertTrue(button.contains(".glassPressFeedback(interactionSource, enabled, glass = glassEnabled)"))
    assertTrue(
      "the wave must wrap the glass pane so it refracts the glass",
      button.indexOf(".glassPressFeedback(") in 0 until button.indexOf(".playerGlassPane("),
    )
    assertTrue(GlassWaterRipplePolicy.useRefraction(true, true, 33))
    assertTrue(!GlassWaterRipplePolicy.useRefraction(true, false, 33))
    assertTrue(!GlassWaterRipplePolicy.useRefraction(true, true, 32))
    assertTrue(GlassWaterRipplePolicy.amplitude(0.8f) < GlassWaterRipplePolicy.amplitude(0.2f))
  }

  @Test
  fun waterWaveIsAGpuRefractionOnlyWhileActiveWithASafeFallback() {
    val controls = source("src/main/java/app/infinity/mpvz/ui/player/controls/components/GlassControls.kt")
    val shader = source("src/main/java/app/infinity/mpvz/ui/theme/GlassWaterRippleShader.kt")
    // GPU path: AGSL displacement installed as a layer RenderEffect, null when idle.
    assertTrue(shader.contains("@RequiresApi(33)"))
    assertTrue(shader.contains("RenderEffect.createRuntimeShaderEffect(shader, \"content\")"))
    assertTrue(shader.contains("GlassShaderCache.shared(WATER_RIPPLE)"))
    assertTrue("driver failures are remembered and fall back", shader.contains("failed = true"))
    assertTrue(controls.contains("renderEffect ="))
    assertTrue(controls.contains("GlassWaterRipplePolicy.isActive(p)"))
    assertTrue(controls.contains("GlassDeviceSupport.allowsGlass(context)"))
    // Regression: the fallback branch once returned the bare Modifier companion, silently
    // dropping every caller modifier (test tags, clip, glass surface) on non-refraction paths.
    val ripple = controls.substringAfter("fun Modifier.glassWaterRipple(").substringBefore("private object GlassWaterRippleEffects")
    assertTrue(!Regex("else \\{\\s*(//[^\\n]*\\s*)*Modifier\\s*\\}").containsMatchIn(ripple))
    assertTrue(ripple.contains("return refractive.drawWithCache"))
    // No frame polling loops; one Animatable drives the wave and a new press restarts it.
    assertTrue(!controls.contains("withFrameNanos"))
    assertTrue(controls.contains("progress.animateTo(1f, tween(GlassWaterRipplePolicy.DurationMs"))
    assertTrue(controls.contains("launch {"))
    // The shader class is only referenced behind SDK checks.
    val refs = Regex("GlassWaterRippleShader\\.").findAll(controls).count()
    assertTrue(refs == 2)
    // Every shared glass press surface passes its real glass state.
    val chip = source("src/main/java/app/infinity/mpvz/ui/components/GlassChip.kt")
    val theme = source("src/main/java/app/infinity/mpvz/ui/preferences/components/ThemeGlassActions.kt")
    assertTrue(chip.contains(".glassPressFeedback(source, enabled, glass = true)"))
    assertTrue(theme.split(".glassPressFeedback(interactionSource, enabled, glass = glass)").size == 3)
  }

  @Test
  fun waterWavePolicyDecaysFollowsTuningAndStaysSubtleOnAmoled() {
    assertTrue(GlassWaterRipplePolicy.isActive(0f))
    assertTrue(!GlassWaterRipplePolicy.isActive(1f))
    assertTrue(GlassWaterRipplePolicy.amplitude(1f) == 0f)
    assertTrue(GlassWaterRipplePolicy.radiusFraction(1f) > 1f)
    assertTrue(GlassWaterRipplePolicy.ringWidthPx(200f, 1f) > GlassWaterRipplePolicy.ringWidthPx(200f, 0f))
    assertTrue(GlassWaterRipplePolicy.ringWidthPx(10f, 0f) >= 6f)
    assertTrue(GlassWaterRipplePolicy.displacementPx(3f, refraction = 0f, amplitude = 1f) == 0f)
    assertTrue(GlassWaterRipplePolicy.displacementPx(3f, 2f, 1f) > GlassWaterRipplePolicy.displacementPx(3f, 1f, 1f))
    assertTrue(GlassWaterRipplePolicy.displacementPx(3f, 9f, 1f) == GlassWaterRipplePolicy.displacementPx(3f, 2f, 1f))
    assertTrue(GlassWaterRipplePolicy.crestLight(1f, 1f, amoled = true) < GlassWaterRipplePolicy.crestLight(1f, 1f, amoled = false))
    assertTrue(GlassWaterRipplePolicy.crestLight(0f, 1f, amoled = false) == 0f)
    assertTrue(!GlassWaterRipplePolicy.useRefraction(false, true, 36))
  }
}
