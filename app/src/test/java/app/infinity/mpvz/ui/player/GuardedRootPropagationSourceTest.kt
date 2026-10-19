package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level contract checks for the experimental opt-in (usable without an Android SDK). */
class GuardedRootPropagationSourceTest {
  private fun source(path: String): String {
    val candidates = listOf(File("src/main/$path"), File("app/src/main/$path"))
    return candidates.firstOrNull(File::isFile)?.readText()
      ?: error("Unable to locate source file $path from ${File("").absolutePath}")
  }

  @Test
  fun preferenceIsPersistentAndDisabledByDefault() {
    val preferences = source("java/app/infinity/mpvz/preferences/DecoderPreferences.kt")
    assertTrue(preferences.contains("getBoolean(\"mpvflow_experimental_guarded_roots\", false)"))
    val ui = source("java/app/infinity/mpvz/ui/preferences/DecoderPreferencesScreen.kt")
    assertTrue(ui.contains("preferences.mpvFlowExperimentalGuardedRoots::set"))
    assertTrue(ui.contains("value = guardedRootsEnabled"))
  }

  @Test
  fun mpvAndMedia3ForwardTheSameOptIn() {
    val mpv = source("java/app/infinity/mpvz/ui/player/MPVView.kt")
    assertTrue(mpv.contains("mpvflow-experimental-guarded-roots"))
    assertTrue(mpv.contains("requestedMpvFlowExperimentalGuardedRoots"))
    val sink = source("java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt")
    val callCount = Regex("nativeCreateContext\\(").findAll(sink).count()
    assertTrue("Both Media3 context creation paths must be four-argument calls", callCount >= 2)
    assertTrue(sink.contains("experimentalGuardedRoots,"))
    assertFalse(sink.contains("nativeCreateContext(maxDimension, requestedComputeApi.nativeValue, searchRadius)"))
  }
}
