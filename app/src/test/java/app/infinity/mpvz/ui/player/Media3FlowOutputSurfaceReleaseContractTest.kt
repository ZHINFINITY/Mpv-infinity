/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowOutputSurfaceReleaseContractTest {
  private fun sinkSource(): String {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    return File(root, "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt").readText()
  }

  @Test fun clearWaitsForPresentationThreadSurfaceReleaseFence() {
    val source = sinkSource()
    val clear = source.substringAfter("override fun clearOutputSurfaceInfo()")
      .substringBefore("override fun setChangeFrameRateStrategy")

    assertTrue(clear.contains("val releaseFence = CountDownLatch(1)"))
    assertTrue(clear.contains("destroyWindowSurface { releaseFence.countDown() }"))
    assertTrue(clear.contains("releaseFence.await(GL_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)"))
    assertTrue(clear.indexOf("releaseFence.await") > clear.indexOf("val posted = if"))
    assertTrue(clear.contains("event=output_surface_release state=complete"))
    assertTrue(clear.contains("event=output_surface_release state=timeout"))
  }

  @Test fun releaseFenceCompletesOnlyAfterPresentationDrainAndEglDestroy() {
    val destroy = sinkSource()
      .substringAfter("private fun destroyWindowSurface(onDestroyed")
      .substringBefore("private fun drawPresentationFullscreenTriangle()")
    val eglDestroy = destroy.indexOf("EGL14.eglDestroySurface(eglDisplay, windowSurface)")
    val completed = destroy.indexOf("onDestroyed?.invoke()", eglDestroy)

    assertTrue(destroy.contains("presentationQueue.hasPresenting()"))
    assertTrue(destroy.contains("destroyWindowSurfaceOnPresentationThread(onDestroyed)"))
    assertTrue(eglDestroy >= 0)
    assertTrue(completed > eglDestroy)
  }
}
