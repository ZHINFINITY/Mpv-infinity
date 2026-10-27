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
    assertFalse(body.contains("val precisePosition by viewModel.precisePosition.collectAsState()"))
    assertTrue(body.contains("val precisePosition by viewModel.precisePosition.collectAsStateWithLifecycle()"))
  }
}
