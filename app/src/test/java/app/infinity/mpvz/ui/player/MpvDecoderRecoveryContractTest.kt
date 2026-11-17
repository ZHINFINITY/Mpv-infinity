/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvDecoderRecoveryContractTest {
  private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  @Test
  fun aspectChangeCachesTheActiveTrackAndDecoderRecoveryRetriesItBeforeAuto() {
    val session = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/PlaybackSession.kt").readText()
    val snapshot = session.substringAfter("fun rememberMpvVideoTrackForRecovery() {")
      .substringBefore("\n  /** Retry the last selected stream")
    val recovery = session.substringAfter("fun setDecoderAndRecover(value: String) {")
      .substringBefore("\n  fun grabThumbnail")
    val viewModel = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/PlayerViewModel.kt").readText()
    val standardAspect = viewModel.substringAfter("fun changeVideoAspect(")
      .substringBefore("fun setCustomAspectRatio(")
    val customAspect = viewModel.substringAfter("fun setCustomAspectRatio(")
      .substringBefore("fun restoreSavedVideoAspect(")

    assertTrue(snapshot.contains("MPVLib.getPropertyInt(\"vid\")"))
    assertTrue(snapshot.contains("RecoverableVideoTrack(trackId, current.generation)"))
    assertTrue(recovery.contains("MpvConfigOverridePolicy.isOwnedByMpvConf(\"hwdec\")"))
    assertTrue(recovery.contains("lastSelectedVideoTrackForRecovery?.takeIf { it.generation == current.generation }"))
    assertTrue(recovery.indexOf("setPropertyInt(\"vid\", rememberedTrack.id)") >= 0)
    assertTrue(recovery.indexOf("setPropertyString(\"vid\", \"auto\")") > recovery.indexOf("rememberedTrack"))
    assertTrue(session.contains("suspendedVideoTrack = null\n      lastSelectedVideoTrackForRecovery = null"))

    val standardSnapshot = standardAspect.indexOf("PlaybackSession.rememberMpvVideoTrackForRecovery()")
    val standardWrite = standardAspect.indexOf("setMpvAspectOverrideIfChanged")
    val customSnapshot = customAspect.indexOf("PlaybackSession.rememberMpvVideoTrackForRecovery()")
    val customWrite = customAspect.indexOf("setMpvAspectPanscanIfChanged")
    assertTrue(standardSnapshot >= 0 && standardSnapshot < standardWrite)
    assertTrue(customSnapshot >= 0 && customSnapshot < customWrite)
  }

  @Test
  fun everyPlayerLayoutRoutesDecoderSelectionThroughRecovery() {
    val controls = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt").readText()
    val recoveryCallbacks = Regex("onUpdateDecoder = \\{ PlaybackSession\\.setDecoderAndRecover\\(it\\.value\\) \\}")
      .findAll(controls)
      .count()

    assertEquals(2, recoveryCallbacks)
  }
}
