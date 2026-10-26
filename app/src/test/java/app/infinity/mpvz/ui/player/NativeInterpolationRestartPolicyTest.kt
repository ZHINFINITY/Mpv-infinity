/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeInterpolationRestartPolicyTest {
  @Test fun snapshotKeepsExactItemTimestampAndPriorPlayState() {
    val playing = NativeInterpolationRestartPolicy.capture(
      stableMediaId = "episode-17",
      mediaUri = "https://media.example/episode-17.mkv",
      positionMs = 123_456L,
      playWhenReady = true,
      mediaRequestGeneration = 9L,
    )
    val paused = NativeInterpolationRestartPolicy.capture(
      stableMediaId = "episode-17",
      mediaUri = "https://media.example/episode-17.mkv",
      positionMs = 123_456L,
      playWhenReady = false,
      mediaRequestGeneration = 9L,
    )

    assertNotNull(playing)
    assertNotNull(paused)
    assertEquals("episode-17", playing?.stableMediaId)
    assertEquals("https://media.example/episode-17.mkv", playing?.mediaUri)
    assertEquals(123_456L, playing?.positionMs)
    assertEquals(9L, playing?.mediaRequestGeneration)
    assertTrue(playing?.playWhenReady == true)
    assertFalse(paused?.playWhenReady == true)
  }

  @Test fun snapshotRequiresARealItemAndNonnegativePosition() {
    assertEquals(
      null,
      NativeInterpolationRestartPolicy.capture("episode-17", null, 123_456L, true, 1L),
    )
    assertEquals(
      null,
      NativeInterpolationRestartPolicy.capture("episode-17", "file:///episode.mkv", -1L, true, 1L),
    )
    assertEquals(
      "file:///episode.mkv",
      NativeInterpolationRestartPolicy.capture(null, "file:///episode.mkv", 1L, false, 1L)?.stableMediaId,
    )
  }

  @Test fun staleMediaGenerationOrOwnershipCannotRestoreTheOldSnapshot() {
    val state = requireNotNull(
      NativeInterpolationRestartPolicy.capture("episode-17", "file:///episode.mkv", 123_456L, true, 9L),
    )

    fun owns(
      mediaId: String = "episode-17",
      uri: String = "file:///episode.mkv",
      generation: Long = 9L,
      nativeActive: Boolean = true,
      ownsSession: Boolean = true,
    ) = NativeInterpolationRestartPolicy.stillOwns(
      state = state,
      currentStableMediaId = mediaId,
      currentMediaUri = uri,
      currentMediaRequestGeneration = generation,
      nativeActive = nativeActive,
      ownsPlaybackSession = ownsSession,
    )

    assertTrue(owns())
    assertFalse(owns(mediaId = "episode-18"))
    assertFalse(owns(uri = "file:///different.mkv"))
    assertFalse(owns(generation = 10L))
    assertFalse(owns(nativeActive = false))
    assertFalse(owns(ownsSession = false))
  }

  @Test fun nativeShortcutReloadUsesSameMediaItemAndRestoresOnlyAfterReady() {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val base = File(root, "app/src/main/java/app/infinity/mpvz/ui/player")
    val engine = File(base, "NativeMedia3Engine.kt").readText()
    val reload = engine.substringAfter("suspend fun restartCurrentItemForInterpolation")
      .substringBefore("fun setVideoAspect")

    assertTrue(reload.contains("player.setMediaItem(item, state.positionMs)"))
    assertTrue(reload.contains("player.trackSelectionParameters = trackSelectionParameters"))
    assertTrue(reload.contains("player.playbackParameters = playbackParameters"))
    assertTrue(reload.contains("player.volume = volume"))
    assertTrue(reload.contains("player.playWhenReady = false"))
    assertTrue(reload.contains("var reloadFailed = false"))
    assertTrue(reload.contains("reloadFailed = true"))
    assertTrue(reload.contains("!playStateRestored && !reloadFailed && isCurrent()"))
    assertTrue(reload.contains("player.addListener(listener)"))
    assertTrue(reload.contains("hasLeftReadyState && it.isReady"))
    assertTrue(reload.contains("readinessListener?.let { player.removeListener(it) }"))
    assertTrue(reload.contains("snapshot.first"))
    assertTrue(reload.contains("hasRenderedFirstFrame.first"))
    assertTrue(reload.indexOf("readySnapshot?.isReady") < reload.indexOf("player.playWhenReady = state.playWhenReady"))
    assertFalse(reload.contains("MediaItem.Builder"))
  }

  @Test fun decoderFallbackConsumesTheOriginalSnapshotWithoutChangingGlobalEnginePreference() {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val activity = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt").readText()
    val restart = activity.substringAfter("private fun requestNativeInterpolationRestart()")
      .substringBefore("private fun requestMpvInterpolationRestart()")
    val handoff = activity.substringAfter("val outgoingEngine = activeEngineMode")
      .substringBefore("// Freeze the outgoing renderer")

    assertTrue(restart.contains("pendingNativeInterpolationFallbackState = state"))
    assertTrue(restart.contains("if (stillCurrent()) {"))
    assertTrue(restart.contains("selectEngineForCurrentVideo(PlaybackEngineMode.MPV)"))
    assertFalse(restart.contains("playerError == null"))
    assertFalse(restart.contains("decoderPreferences.playbackEngine.set"))
    assertTrue(handoff.contains("nativeInterpolationRestartJob?.join()"))
    assertTrue(handoff.contains("pendingNativeRestart?.positionMs"))
    assertTrue(handoff.contains("pendingNativeRestart?.playWhenReady"))
  }
}
