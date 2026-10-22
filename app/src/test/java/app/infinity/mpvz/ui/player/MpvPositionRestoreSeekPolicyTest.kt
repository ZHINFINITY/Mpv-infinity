/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device log 2.1.5-1896: the interpolation reload restored position with a time-pos write, which
 * obeys hr-seek=no and landed on the previous keyframe; the 0.25 s confirmation then timed out
 * while playback was held paused and the shortcut reported failure.
 */
class MpvPositionRestoreSeekPolicyTest {
  private fun source(name: String): String {
    val path = "src/main/java/app/infinity/mpvz/ui/player/$name.kt"
    return listOf(File(path), File("app/$path")).first { it.isFile }.readText()
  }

  @Test fun restoreUsesExactAbsoluteSeekIndependentOfHrSeekPreference() {
    assertArrayEquals(arrayOf("seek", "754.32", "absolute+exact"), MpvPositionRestoreSeekPolicy.command(754.32))
    assertArrayEquals(arrayOf("seek", "0.0", "absolute+exact"), MpvPositionRestoreSeekPolicy.command(-3.0))
  }

  @Test fun confirmationAcceptsExactLandingAndRejectsKeyframeSnapOrActiveSeek() {
    assertTrue(MpvPositionRestoreSeekPolicy.isRestored(754.30, 754.32, seeking = false))
    assertTrue(MpvPositionRestoreSeekPolicy.isRestored(754.70, 754.32, seeking = null))
    // Keyframe snap seconds before the saved position is not a restore.
    assertFalse(MpvPositionRestoreSeekPolicy.isRestored(750.08, 754.32, seeking = false))
    assertFalse(MpvPositionRestoreSeekPolicy.isRestored(754.32, 754.32, seeking = true))
    assertFalse(MpvPositionRestoreSeekPolicy.isRestored(null, 754.32, seeking = false))
    assertFalse(MpvPositionRestoreSeekPolicy.isRestored(Double.NaN, 754.32, seeking = false))
  }

  @Test fun fileLoadedAndRestartTransactionUseThePolicy() {
    val activity = source("PlayerActivity")
    val fileLoaded = activity.substringAfter("private fun handleFileLoaded(loadGeneration: Long)")
      .substringBefore("if (fileName.isBlank())")
    assertTrue(fileLoaded.contains("MpvPositionRestoreSeekPolicy.command(seconds)"))
    assertTrue(fileLoaded.contains("PlaybackSession.commandForGeneration(loadGeneration"))
    assertFalse(fileLoaded.contains("setPropertyDouble(\"time-pos\""))
    assertTrue(activity.contains("MpvPositionRestoreSeekPolicy.isRestored(actual, state.positionSeconds, seeking)"))
    assertFalse(activity.contains("kotlin.math.abs(actual - state.positionSeconds) <= 0.25"))
    // Success is the accepted renderer options plus handed-back pause state, not a timing race.
    assertTrue(activity.contains("return optionsApplied && pauseRestored"))
  }

  @Test fun ambientIsRestartedOnEveryRestartExitPath() {
    val activity = source("PlayerActivity")
    val tail = activity.substringAfter("// Ambient was stopped before teardown; restart it on every exit path")
      .substringBefore("private fun ")
    assertTrue(tail.contains("viewModel.restartAmbientIfActive()"))
    assertTrue(tail.contains("composedVideoAmbientPresentationActive"))
    assertTrue(activity.contains("composedVideoAmbientPresentationActive = presentationActive"))
  }
}
