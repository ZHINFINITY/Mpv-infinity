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
    assertTrue(button.contains(".glassPressFeedback(interactionSource, enabled)"))
    assertTrue(GlassWaterRipplePolicy.useRefraction(true, true, 33))
    assertTrue(!GlassWaterRipplePolicy.useRefraction(true, false, 33))
    assertTrue(!GlassWaterRipplePolicy.useRefraction(true, true, 32))
    assertTrue(GlassWaterRipplePolicy.amplitude(0.8f) < GlassWaterRipplePolicy.amplitude(0.2f))
  }
}
