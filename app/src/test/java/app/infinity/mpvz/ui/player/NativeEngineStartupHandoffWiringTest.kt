package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeEngineStartupHandoffWiringTest {
  private val activity: String by lazy {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    File(root, "app/infinity/mpvz/ui/player/PlayerActivity.kt").readText()
  }

  private val engine: String by lazy {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    File(root, "app/infinity/mpvz/ui/player/NativeMedia3Engine.kt").readText()
  }

  private fun assertInOrder(text: String, vararg needles: String) {
    var from = 0
    for (needle in needles) {
      val at = text.indexOf(needle, from)
      assertTrue("expected `$needle` after index $from", at >= 0)
      from = at + needle.length
    }
  }

  private fun nativeHandoff(): String {
    val engineChoice = activity.indexOf("val useNative = effectiveEngine ==")
    assertTrue("missing engine selection", engineChoice >= 0)
    val start = activity.indexOf("if (useNative) {", engineChoice)
    val end = activity.indexOf("} else if (mpvInitialized)", start)
    assertTrue("missing Native handoff block", start >= 0 && end > start)
    return activity.substring(start, end)
  }

  @Test
  fun mpvFrameRemainsVisibleUntilNativeFirstFrame() {
    val handoff = nativeHandoff()
    val firstFrameWait = handoff.indexOf("nativeFirstFrame.first { it }")
    assertTrue("Native first-frame wait missing", firstFrameWait >= 0)
    val beforeFirstFrame = handoff.substring(0, firstFrameWait)

    assertTrue(beforeFirstFrame.contains("prepareNativeVideoSurfaceForHandoff()"))
    assertFalse(beforeFirstFrame.contains("binding.player.visibility = View.GONE"))
    assertInOrder(
      handoff,
      "prepareNativeVideoSurfaceForHandoff()",
      "nativeEngine.play(",
      "nativeFirstFrame.first { it }",
      "setNativeVideoSurfaceVisible(true)",
      "binding.player.visibility = View.GONE",
    )

    val surfacePreparation = activity.substringAfter("private fun prepareNativeVideoSurfaceForHandoff()")
      .substringBefore("\n  }\n")
    assertTrue(surfacePreparation.contains("alpha = 0f"))
    assertTrue(surfacePreparation.contains("visibility = View.VISIBLE"))
    assertTrue(surfacePreparation.contains("showMpvSurfaceUnlessMusic()"))
  }

  @Test
  fun oldSubtitleTracksAreClearedOffUiBeforeNativePlaybackStarts() {
    val clearStart = engine.indexOf("suspend fun clearPreviousSubtitleTracks()")
    val clearEnd = engine.indexOf("\n  }\n", clearStart)
    assertTrue("missing suspendable subtitle reset", clearStart >= 0 && clearEnd > clearStart)
    val clearMethod = engine.substring(clearStart, clearEnd)
    assertTrue(clearMethod.contains("withContext(Dispatchers.IO)"))
    assertTrue(clearMethod.contains("renderer.getTrackIds()"))

    val playStart = engine.indexOf("fun play(")
    val playEnd = engine.indexOf("\n  }\n", playStart)
    assertTrue(playStart >= 0 && playEnd > playStart)
    assertFalse("play() must not query the blocking renderer lock", engine.substring(playStart, playEnd).contains("getTrackIds()"))

    val handoff = nativeHandoff()
    assertInOrder(
      handoff,
      "configureNativeEngineIfNeeded(force = true)",
      "nativeEngine.clearPreviousSubtitleTracks()",
      "nativeEngine.play(",
    )
    val initialLoad = activity.substringAfter("if (canUseNative) {").substringBefore("return\n    }")
    assertInOrder(
      initialLoad,
      "configureNativeEngineIfNeeded(force = true)",
      "nativeEngine.clearPreviousSubtitleTracks()",
      "nativeEngine.play(",
    )
  }
}
