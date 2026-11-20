/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowDisplayVsyncContractTest {
  private fun sinkSource(): String {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    return File(root, "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt").readText()
  }

  @Test
  fun displayVsyncRegistrationUsesItsOwnLooperInsteadOfThePlayerUiLooper() {
    val source = sinkSource()
    val scheduler = source.substringAfter("private fun scheduleDisplayFrame()")
      .substringBefore("private fun cancelDisplayFrame()")

    assertTrue(source.contains("HandlerThread(\"Media3Flow-Vsync\")"))
    assertTrue(source.contains("Handler(vsyncThread.looper)"))
    assertTrue(scheduler.contains("vsyncHandler.post(registerDisplayFrameRunnable)"))
    assertFalse(scheduler.contains("mainHandler"))
  }

  @Test
  fun cancellationRemovesPendingVsyncAndReleaseQuitsItsLooper() {
    val source = sinkSource()
    val cancellation = source.substringAfter("private fun cancelDisplayFrame()")
      .substringBefore("private fun renderAtDisplayFrame(")
    val release = source.substringAfter("private fun finishGlThreadRelease()")
      .substringBefore("private fun releaseNextDecoderFrame(")

    assertTrue(cancellation.contains("vsyncHandler.removeCallbacks(registerDisplayFrameRunnable)"))
    assertTrue(cancellation.contains("vsyncHandler.post(removeDisplayFrameRunnable)"))
    assertTrue(release.contains("vsyncThread.quitSafely()"))
  }
}
