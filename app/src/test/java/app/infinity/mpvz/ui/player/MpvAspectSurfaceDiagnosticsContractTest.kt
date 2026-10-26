/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvAspectSurfaceDiagnosticsContractTest {
  private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private fun source(path: String) = File(root, "app/src/main/java/app/infinity/mpvz/$path").readText()

  @Test fun mpvAspectModesStayLivePropertyWritesWithoutSurfaceRebindOrFlowMutation() {
    val viewModel = source("ui/player/PlayerViewModel.kt")
    val mpvView = source("ui/player/MPVView.kt")
    val transition = viewModel.substringAfter("fun changeVideoAspect(")
      .substringBefore("fun setCustomAspectRatio(")
    val inactiveFlowOptions = mpvView.substringAfter("if (!MpvConfigOverridePolicy.isOwnedByMpvConf(\"interpolation\"))")
      .substringBefore("if (!MpvConfigOverridePolicy.isOwnedByMpvConf(\"video-sync\"))")

    assertTrue(transition.contains("video-aspect-override"))
    assertTrue(transition.contains("panscan"))
    assertFalse(transition.contains("bindSurface("))
    assertFalse(transition.contains("unbindSurface("))
    assertFalse(transition.contains("initializeSession("))
    assertFalse(transition.contains("mpvFlowFrameInterpolation"))
    assertTrue(inactiveFlowOptions.contains("if (mpvFlowFrameInterpolationEnabled) \"yes\" else \"no\""))
  }

  @Test fun debugTraceCorrelatesAspectPropertiesWithSurfaceLifecycle() {
    val activity = source("ui/player/PlayerActivity.kt")
    val mpvView = source("ui/player/MPVView.kt")
    val aspectTrace = activity.substringAfter("override fun onVideoAspectChanged()")
      .substringBefore("private var interpolationControlRequestGeneration")
    val surfaceChange = mpvView.substringAfter("override fun surfaceChanged(")
      .substringBefore("override fun surfaceCreated(")
    val surfaceCreate = mpvView.substringAfter("override fun surfaceCreated(")
      .substringBefore("override fun surfaceDestroyed(")
    val surfaceDestroy = mpvView.substringAfter("override fun surfaceDestroyed(")
      .substringBefore("override fun onDetachedFromWindow(")

    for (field in listOf("MPV_ASPECT_DIAGNOSTIC", "video-aspect-override", "panscan", "interpolation", "video-sync", "gpuApi", "gpuContext", "ambient")) {
      assertTrue("Missing aspect trace field: $field", aspectTrace.contains(field))
    }
    assertTrue(surfaceChange.contains("event=surface_changed"))
    assertTrue(surfaceChange.contains("PlaybackSession.resizeSurface"))
    assertTrue(surfaceCreate.contains("event=surface_created"))
    assertTrue(surfaceDestroy.contains("event=surface_destroyed"))
  }
}
