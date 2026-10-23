/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the rendered full-player caller wiring; gesture math remains policy-tested separately. */
class AudioPlayerControlsWiringTest {
  private fun source(path: String): String {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    return File(root, path).readText()
  }

  @Test
  fun fullPlayerUsesSharedPaletteWithoutOpaqueEnabledSurfaceOrCompetingBackSwipe() {
    val controls = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt")
    assertTrue(controls.contains("MiniPlayerDragController"))
    assertTrue(controls.contains("nestedGestureController.nestedScrollConnection"))
    assertTrue(controls.contains("if (liquidGlassSurfaces) Color.Transparent"))
    assertFalse(controls.contains("if (totalDrag > 160f) onBackPress()"))
  }

  @Test
  fun paletteSceneDoesNotPaintAnOpaqueSurface() {
    val scene = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPaletteGlassScene.kt")
    assertTrue(scene.contains("backdrop provider, not an opaque player surface"))
    assertTrue(scene.contains("LocalLiquidGlassBackdrop provides frame"))
  }

  @Test
  fun minimizeKeepsTheAudioWindowSeeThroughOverTheLibrary() {
    val controls = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt")
    val activity = source("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val themes = source("app/src/main/res/values/themes.xml")
    assertTrue(activity.contains("Theme_mpvinfinity_Player_Audio"))
    assertTrue(activity.contains("Never call Activity.setTranslucent()"))
    assertTrue(activity.contains("prepareAudioPlayerSeeThroughLayers"))
    assertTrue(activity.contains("setNativeVideoSurfaceVisible(false)"))
    assertTrue(themes.contains("name=\"Theme.mpvinfinity.Player.Audio\""))
    assertTrue(themes.contains("android:windowIsTranslucent"))
    assertTrue(controls.contains("Stay see-through. Converting the window opaque mid-session"))
    assertFalse(controls.contains("playerActivity?.setAudioTransitionTranslucent(false)"))
  }
}
