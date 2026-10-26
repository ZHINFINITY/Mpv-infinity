package app.infinity.mpvz.ui.player.controls.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekbarGlassContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  private fun source(path: String) = File(root, "app/src/main/java/app/infinity/mpvz/$path").readText()

  @Test
  fun timelineUsesGlassOnlyForTheRoundedVisualTrack() {
    val seekbar = source("ui/player/controls/components/Seekbar.kt")
    val track = seekbar.substringAfter("// Visual seekbar (smaller, centered) - always drawn on top of the wave")
      .substringBefore("val activeClip")
    assertTrue(track.contains(".height(32.dp)"))
    assertTrue(track.contains(".playerGlassPane(enabled = liquidGlassSurfaces && glassTrackEnabled, cornerRadius = 999.dp)"))
    assertTrue(seekbar.contains("val liquidGlassSurfaces by appearance.liquidGlassSurfaces.collectAsState()"))
    assertTrue(seekbar.contains("glassEnabled = glassTrackEnabled"))
    assertTrue(seekbar.contains("val glass = liquidGlassEnabled && glassEnabled"))

    val seeker = seekbar.substringAfter("Seeker(").substringBefore("\n    )")
    assertTrue("keep the existing enlarged touch target", seeker.contains(".height(touchAreaHeight)"))
    assertTrue("keep the visual Seeker transparent so only the rendered track changes", seeker.contains(".graphicsLayer(alpha = 0f)"))
    assertTrue(seeker.contains("onValueChange = { newPosition ->"))
    assertTrue(seeker.contains("onValueChangeFinished = {"))
  }

  @Test
  fun musicKeepsLegacySeekbarAndQueueItemsKeepTheirOwnDurations() {
    val audio = source("ui/player/controls/AudioPlayerControls.kt")
    val musicSeekbar = audio.substringAfter("val seekbarView = @Composable {")
      .substringBefore("val playbackControlsRow = @Composable {")
    assertTrue(musicSeekbar.contains("glassTrackEnabled = false"))

    val musicLibrary = source("ui/browser/music/MusicLibraryViewModel.kt")
    val playSong = musicLibrary.substringAfter("fun playSong(").substringBefore("fun playAllSongs(")
    val playAll = musicLibrary.substringAfter("fun playAllSongs(").substringBefore("fun createPlaylist(")
    val duration = "durationSeconds = item.durationMs.takeIf { it > 0L }?.div(1000L)?.toInt()"
    assertTrue(playSong.contains(duration))
    assertTrue(playAll.contains(duration))

    val appearance = source("preferences/AppearancePreferences.kt")
    assertTrue(appearance.contains("getBoolean(\"show_seekbar_outer_container\", false)"))
    val playerControls = source("ui/player/controls/PlayerControls.kt")
    assertTrue(playerControls.contains("val seekbarContainerEnabled = showSeekbarOuterContainer"))
    assertTrue(playerControls.contains("if (seekbarContainerEnabled)"))
    assertTrue(playerControls.contains("val useLiquidGlassSeekbar = liquidGlassSurfaces && seekbarContainerEnabled"))
  }

  @Test
  fun scrubTargetsStayFiniteAndClampedAndFinishNotifiesPlayback() {
    assertEquals(0f, clampSeekbarPosition(-4f, 120f), 0f)
    assertEquals(37f, clampSeekbarPosition(37f, 120f), 0f)
    assertEquals(120f, clampSeekbarPosition(140f, 120f), 0f)
    assertEquals(0f, clampSeekbarPosition(Float.NaN, 120f), 0f)
    assertEquals(0f, clampSeekbarPosition(37f, Float.POSITIVE_INFINITY), 0f)

    val seekbar = source("ui/player/controls/components/Seekbar.kt")
    val change = seekbar.substringAfter("onValueChange = { newPosition ->").substringBefore("},\n      onValueChangeFinished")
    val finish = seekbar.substringAfter("onValueChangeFinished = {").substringBefore("},\n      modifier")
    assertTrue(change.contains("clampSeekbarPosition(newPosition, safeDuration)"))
    assertTrue(change.contains("onUserInteractionChange(true)"))
    assertTrue(change.contains("onValueChange(targetPosition)"))
    assertTrue(finish.contains("clampSeekbarPosition(latestInteractionPosition, safeDuration)"))
    assertTrue(finish.contains("onValueChangeFinished(targetPosition)"))
    assertTrue(finish.contains("onUserInteractionChange(false)"))
  }

  @Test
  fun volumeAndBrightnessTracksUseRoundedGlobalGlassAndRaisedAccentFills() {
    val tracks = source("ui/player/controls/components/VerticalSliders.kt")
    assertEquals(2, Regex("\\.playerGlassPane\\(enabled = useGlass, cornerRadius = 999.dp\\)").findAll(tracks).count())
    assertEquals(2, Regex("\\.glassRimLight\\(CircleShape, ambient = colorEnd, highContrast = true\\)").findAll(tracks).count())
    assertTrue(tracks.contains("val useGlass = glassEnabled && preferenceEnabled"))
    assertTrue(tracks.contains("preferenceEnabled by appearance.liquidGlassSurfaces.collectAsState()"))
    assertEquals(2, Regex("glassEnabled = liquidGlassSurfaces").findAll(tracks).count())
  }
}
