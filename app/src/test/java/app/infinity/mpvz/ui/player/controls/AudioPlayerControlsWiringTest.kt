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
    // Wholesale mpvrx port: cover-card carousel with horizontal drag + threshold, and a
    // back handler scoped to the lyrics views instead of a competing sheet swipe.
    assertTrue(controls.contains("detectHorizontalDragGestures"))
    assertTrue(controls.contains("BackHandler(enabled = isLyricsFullscreen || showInPlaceLyrics)"))
    assertTrue(controls.contains("MaterialTheme.colorScheme.surface"))
    assertFalse(controls.contains("mpvRx"))
  }

  @Test
  fun paletteSceneDoesNotPaintAnOpaqueSurface() {
    val scene = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPaletteGlassScene.kt")
    assertTrue(scene.contains("backdrop provider, not an opaque player surface"))
    assertTrue(scene.contains("LocalLiquidGlassBackdrop provides frame"))
  }
}
