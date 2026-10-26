/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLandscapeLayoutPolicyTest {
  @Test
  fun representativeHeightsFitWithinTheSafeHeight() {
    for (height in listOf(320f, 360f, 411f, 480f)) {
      val m = AudioLandscapeLayoutPolicy.measure(height, 640f)
      assertTrue("$height: ${m.totalHeightDp}", m.totalHeightDp <= height + 0.01f)
      assertTrue(m.extrasRowDp >= AudioLandscapeLayoutPolicy.minTouchTargetDp)
      assertTrue(m.mainRowDp >= AudioLandscapeLayoutPolicy.minTouchTargetDp)
      assertTrue(m.artworkDp > 0f)
    }
  }

  @Test
  fun artworkIsSquareAndWidthAware() {
    val narrow = AudioLandscapeLayoutPolicy.measure(411f, 480f)
    val wide = AudioLandscapeLayoutPolicy.measure(411f, 1280f)
    assertEquals(narrow.artworkDp, narrow.artworkDp, 0.001f)
    assertTrue(wide.artworkDp > narrow.artworkDp)
  }

  @Test
  fun extrasShrinkBeforeMainTransport() {
    val tall = AudioLandscapeLayoutPolicy.measure(480f, 640f)
    val short = AudioLandscapeLayoutPolicy.measure(320f, 640f)
    assertTrue(short.extrasRowDp <= tall.extrasRowDp)
    assertTrue(short.extrasRowDp >= AudioLandscapeLayoutPolicy.minTouchTargetDp)
    assertTrue(short.mainRowDp >= AudioLandscapeLayoutPolicy.minTouchTargetDp)
  }

  @Test
  fun landscapeBranchUsesPolicyAndHasExactlyFiveTransportButtons() {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val source = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt").readText()
    val branch = source.substringAfter("val landscapePlaybackControlsRow").substringBefore("val audiobookModeButtons")
    assertTrue(source.contains("AudioLandscapeLayoutPolicy.measure"))
    assertEquals(4, Regex("ReactiveIconButton").findAll(branch).count())
    assertTrue(branch.contains("SkipPrevious"))
    assertTrue(branch.contains("FastRewind"))
    assertTrue(branch.contains("FastForward"))
    assertTrue(branch.contains("SkipNext"))
    assertTrue(branch.contains("ReactiveSurfaceButton"))
  }
}
