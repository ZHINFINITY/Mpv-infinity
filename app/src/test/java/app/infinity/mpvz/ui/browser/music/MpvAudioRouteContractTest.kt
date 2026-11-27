package app.infinity.mpvz.ui.browser.music

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvAudioRouteContractTest {
  private val root: File =
    generateSequence(File(System.getProperty("user.dir").orEmpty())) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  private fun read(path: String): String = File(root, path).readText()

  @Test
  fun audioOutputPickReachesTheMpvSession() {
    val router = read("app/src/main/java/app/infinity/mpvz/ui/browser/music/AudioOutputRouter.kt")

    assertTrue(router.contains("player.setPreferredAudioDevice(device)"))
    assertTrue(router.contains("MpvAudioRoute.select(device)"))
  }

  @Test
  fun musicRouteForcesTheAudioTrackDriverAndReloadsTheOutput() {
    val route = read("app/src/main/java/app/infinity/mpvz/ui/browser/music/MpvAudioRoute.kt")

    assertTrue(route.contains("nativeSetPreferredDevice(device)"))
    assertTrue(route.contains("PlaybackSession.setOptionString(\"ao\", \"audiotrack\")"))
    assertTrue(route.contains("PlaybackSession.command(\"ao-reload\")"))
  }

  @Test
  fun videoSessionsNeverTakeTheMusicOutputRoute() {
    val route = read("app/src/main/java/app/infinity/mpvz/ui/browser/music/MpvAudioRoute.kt")

    assertTrue(route.contains("kind != DeclaredPlaybackMediaKind.VIDEO"))
  }

  @Test
  fun nativeBridgeMatchesTheKotlinDeclaration() {
    val route = read("app/src/main/java/app/infinity/mpvz/ui/browser/music/MpvAudioRoute.kt")
    val bridge = read("app/src/main/cpp/mpv_audio_route_jni.cpp")
    val cmake = read("app/src/main/cpp/CMakeLists.txt")

    assertTrue(route.contains("external fun nativeSetPreferredDevice(device: AudioDeviceInfo?)"))
    assertTrue(route.contains("System.loadLibrary(\"mpvaudio\")"))
    assertTrue(
      bridge.contains(
        "Java_app_infinity_mpvz_ui_browser_music_MpvAudioRoute_nativeSetPreferredDevice"
      )
    )
    assertTrue(bridge.contains("\"mpv_audiotrack_set_preferred_device\""))
    assertTrue(cmake.contains("add_library(mpvaudio SHARED mpv_audio_route_jni.cpp)"))
  }

  @Test
  fun mpvRuntimePatchRoutesAudioTracksToTheChosenDevice() {
    val patch = read("scripts/patch_audiotrack_device.py")

    assertTrue(patch.contains("audio/out/ao_audiotrack.c"))
    assertTrue(patch.contains("mpv_audiotrack_set_preferred_device"))
    assertTrue(patch.contains("AudioTrack.setPreferredDevice"))
    assertTrue(patch.contains("MPV_INFINITY_AUDIOTRACK_ROUTE_V1"))
  }
}
