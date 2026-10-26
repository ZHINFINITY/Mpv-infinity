/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class InterpolationShortcutFeedbackContractTest {
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private fun source(path: String) = File(root, "app/src/main/java/app/infinity/mpvz/$path").readText()
  private val activity get() = source("ui/player/PlayerActivity.kt")

  @Test fun nativeShortcutUsesControlledReloadWithoutRoutinePopup() {
    val callback = activity.substringAfter("fun applyInterpolationPreferencesFromControls()")
      .substringBefore("private fun requestNativeInterpolationRestart")
    assertTrue(callback.contains("requestNativeInterpolationRestart()"))
    assertTrue(callback.contains("requestMpvInterpolationRestart()"))
    assertFalse(callback.contains("Toast.makeText"))
    assertFalse(callback.contains("showSnackbar"))

    val restart = activity.substringAfter("private fun requestNativeInterpolationRestart")
      .substringBefore("private fun requestMpvInterpolationRestart")
    assertTrue(restart.contains("NativeInterpolationRestartPolicy.capture"))
    assertTrue(restart.contains("backend.restartCurrentItemForInterpolation"))
    assertTrue(restart.contains("pendingNativeInterpolationFallbackState = state"))
    assertFalse(restart.contains("Toast.makeText"))
    assertFalse(restart.contains("showSnackbar"))
  }
  @Test fun mpvShortcutStillReopensAndRestoresWithoutProgressOrSuccessPopup() {
    val restart = activity.substringAfter("private fun requestMpvInterpolationRestart")
      .substringBefore("private fun handleNativePlaybackEnded")
    assertTrue(restart.contains("MpvInterpolationRestartTransaction.run(snapshot"))
    assertTrue(restart.contains("PlaybackSession.load("))
    assertTrue(restart.contains("positionRestoreOverride = PlaybackPositionRestoreOverride(state.positionSeconds, paused = true)"))
    for (resource in listOf("toast_interpolation_restarting_mpv", "toast_interpolation_restarted_mpv",
      "toast_interpolation_configured_mpv", "toast_interpolation_applied_live")) {
      assertFalse("Routine popup returned: $resource", restart.contains(resource))
    }
    assertTrue(restart.contains("if (!success || !stillCurrent())"))
    assertTrue(restart.contains("toast_interpolation_restart_failed"))
  }
  @Test fun toggleAndFpsChooserStillCallActualActivityWithoutIndependentPopup() {
    for (path in listOf("ui/player/controls/components/FrameInterpolationControls.kt",
      "ui/player/controls/components/sheets/FrameInterpolationFpsSheet.kt")) {
      val caller = source(path)
      assertTrue(path, caller.contains("activity.applyInterpolationPreferencesFromControls()"))
      assertFalse(path, caller.contains("Toast.makeText"))
      assertFalse(path, caller.contains("showSnackbar"))
    }
  }
}
