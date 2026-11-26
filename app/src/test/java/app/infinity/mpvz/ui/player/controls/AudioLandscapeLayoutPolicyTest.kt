/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLandscapeLayoutPolicyTest {
  @Test
  fun representativeHeightsFitWithinTheSafeHeight() {
    for (height in listOf(180f, 240f, 320f, 360f, 411f, 480f)) {
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
    assertEquals(192f, narrow.artworkDp, 0.001f)
    assertTrue(wide.artworkDp > narrow.artworkDp)
    assertEquals(256f, AudioLandscapeLayoutPolicy.measure(320f, 640f).artworkDp, 0.001f)
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
  fun metadataYieldsBeforeEssentialRowsOnCompactLandscapeHeights() {
    val compact = AudioLandscapeLayoutPolicy.measure(180f, 640f)
    assertTrue(compact.totalHeightDp <= 180.01f)
    assertEquals(0f, compact.metadataDp, 0.001f)
    assertTrue(compact.seekbarDp >= AudioLandscapeLayoutPolicy.minTouchTargetDp)
    assertTrue(compact.mainRowDp >= AudioLandscapeLayoutPolicy.minTouchTargetDp)
    assertTrue(compact.extrasRowDp >= AudioLandscapeLayoutPolicy.minTouchTargetDp)
  }

  @Test
  fun landscapeBranchUsesPolicyAndHasExactlyFiveTransportButtons() {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val source = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt").readText()
    val branch = source.substringAfter("val landscapePlaybackControlsRow").substringBefore("val audiobookModeButtons")
    assertTrue(source.contains("AudioLandscapeLayoutPolicy.measure"))
    assertEquals(2, Regex("ExpressiveTransportSideButton").findAll(branch).count())
    assertTrue(branch.contains("SkipPrevious"))
    assertTrue(branch.contains("AudioSeekButton"))
    assertTrue(branch.contains("FastRewind") || branch.contains("forward = false"))
    assertTrue(branch.contains("FastForward") || branch.contains("forward = true"))
    assertTrue(branch.contains("SkipNext"))
    assertTrue(branch.contains("ExpressivePlayPauseButton"))
  }

  @Test
  fun landscapeTransportIsLabelledAndPlayFitsTheMeasuredRow() {
    val src = listOf(
      File("src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt"),
      File("app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt"),
    ).first { it.isFile }.readText()
    val start = src.indexOf("val landscapePlaybackControlsRow = @Composable {")
    val row = src.substring(start, src.indexOf("val audiobookModeButtons = @Composable {", start))
    assertTrue("every transport icon has a TalkBack label", !row.contains("contentDescription = null"))
    for (key in listOf("pref_gesture_media_previous", "player_seek_backward", "player_seek_forward", "pref_gesture_media_next", "ui_play", "audiobook_pause")) {
      assertTrue(key, row.contains("R.string.$key") || src.contains("R.string.$key"))
    }
    // The expressive play button takes its height from the row (56dp landscape); it never hardcodes 64dp.
    assertTrue(!row.contains("Modifier.size(64.dp)"))
    assertTrue(row.contains("ExpressivePlayPauseButton"))
    assertTrue(AudioLandscapeLayoutPolicy.measure(320f, 640f).mainRowDp >= 48f)
  }

  @Test
  fun landscapeLyricsUseTheLeftPaneWithoutHidingTransportControls() {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val source = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt").readText()
    val layout = source.substringAfter("val landscapeMetrics = AudioLandscapeLayoutPolicy.measure(")
      .substringBefore("if (addToPlaylistDialogOpen")
    assertTrue(layout.contains("if (showInPlaceLyrics) {\n              lyricsPanel(Modifier.fillMaxSize())"))
    assertTrue(layout.indexOf("if (!showInPlaceLyrics)") < layout.indexOf("landscapePlaybackControlsRow()"))
    assertTrue(layout.contains("landscapePlaybackControlsRow()"))
    assertTrue(layout.contains("bottomActionRow()"))
  }

  @Test
  fun flyingCoverUsesTheCurrentCoverOverride() {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val source = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt").readText()
    assertTrue(source.contains("CoverArtCardImage(bitmap = activeCoverOverride ?: albumArtBitmap, artworkUrl = currentArtworkUri)"))
  }
}
