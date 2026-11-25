package app.infinity.mpvz.ui.player.controls

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlsPositionSubscriptionTest {
  private fun source(name: String): String {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    return File(root, name).readText()
  }

  @Test fun videoControlsCollectPositionOnlyInsideSeekbarLeaf() {
    val text = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
    val body = text.substringAfter("fun PlayerControls(").substringBefore("fun AudioPlayerControls(")
    val seekbarBoundary = body.indexOf(").constrainAs(seekbar)")
    assertTrue(seekbarBoundary >= 0)
    val beforeSeekbar = body.substring(0, seekbarBoundary)
    val seekbarLeaf = body.substring(seekbarBoundary)
    assertFalse(beforeSeekbar.contains("val precisePosition by viewModel.precisePosition.collectAsState"))
    assertTrue(seekbarLeaf.contains("val precisePosition by viewModel.precisePosition.collectAsStateWithLifecycle()"))
  }

  @Test fun aNewGestureCancelsThePreviousReleaseWatchdog() {
    // Wholesale mpvrx port: the carousel drives a single Animatable offset; snapTo() on a
    // new gesture cancels any in-flight release animation, so no separate watchdog is needed.
    val text = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt")
    assertTrue(text.contains("val animatableOffsetX = remember { Animatable(0f) }"))
    assertTrue(text.contains("animatableOffsetX.snapTo(animatableOffsetX.value + dragAmount)"))
    assertTrue(text.contains("animatableOffsetX.animateTo("))
  }
}
