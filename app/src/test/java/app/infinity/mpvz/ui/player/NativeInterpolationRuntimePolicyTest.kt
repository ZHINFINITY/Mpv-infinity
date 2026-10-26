/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeInterpolationRuntimePolicyTest {
  @Test fun onlyAlivePreflightedReadySinkMayMutateLive() {
    for (disposed in listOf(false, true)) {
      for (preflight in listOf(false, true)) {
        for (ready in listOf(false, true)) {
          assertEquals(!disposed && preflight && ready,
            NativeInterpolationRuntimePolicy.canMutateLiveSink(disposed, preflight, ready))
        }
      }
    }
  }

  @Test fun actualSinkConstructionAndAcknowledgementUsePersistedStateAndEligibility() {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val base = File(root, "app/src/main/java/app/infinity/mpvz/ui/player")
    val engine = File(base, "NativeMedia3Engine.kt").readText()
    val sink = File(base, "Media3FlowVideoSink.kt").readText()
    assertTrue(engine.contains("Media3FlowVideoSink.deviceSupportsVulkanFlow(appContext)"))
    assertTrue(!engine.contains("enableMedia3Flow && Media3FlowVideoSink.deviceSupportsVulkanFlow"))
    assertTrue(sink.contains("interpolationEnabled = initialInterpolationEnabled"))
    assertTrue(sink.contains("NativeInterpolationRuntimePolicy.canMutateLiveSink"))
    val apply = sink.substringAfter("suspend fun applyRuntimeInterpolation").substringBefore("private val maxDimension")
    assertTrue(apply.indexOf("glHandler.post") < apply.indexOf("interpolationEnabled = enabled"))
    assertTrue(apply.indexOf("setOutputFrameRate()") < apply.indexOf("continuation.resume(true)"))
    assertTrue(apply.contains("continuation.resume(false)"))
  }

  @Test fun hdrRendererSelectionUsesEffectiveRuntimeFlagAndKeepsTrackParameters() {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val base = File(root, "app/src/main/java/app/infinity/mpvz/ui/player")
    val engine = File(base, "NativeMedia3Engine.kt").readText()
    val sink = File(base, "Media3FlowVideoSink.kt").readText()
    val renderers = File(base, "Media3FlowRenderersFactory.java").readText()
    assertTrue(sink.contains("fun isInterpolationEnabled(): Boolean = interpolationEnabled"))
    assertTrue(renderers.contains("Media3FlowVideoSink.supportsFormat(format, interpolationEnabled)"))
    assertTrue(renderers.contains("flowSink.isInterpolationEnabled()"))
    assertTrue(!renderers.contains("Media3FlowVideoSink.supportsFormat(format)"))
    assertTrue(engine.contains(".setTrackSelector(nativeTrackSelector)"))
    assertTrue(engine.contains("fun invalidateRendererEligibility()"))
    assertTrue(engine.contains("invalidate(null)"))
    assertTrue(engine.contains("nativeTrackSelector.invalidateRendererEligibility()"))
    assertTrue(engine.contains("Media3FlowFormatPolicy.shouldReevaluateNativeRenderer("))
  }

  @Test fun startupUsesPersistedInterpolationStateBeforeRendererSelection() {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val base = File(root, "app/src/main/java/app/infinity/mpvz/ui/player")
    val activity = File(base, "PlayerActivity.kt").readText()
    val engine = File(base, "NativeMedia3Engine.kt").readText()
    val renderers = File(base, "Media3FlowRenderersFactory.java").readText()
    val sinkCreation = engine.indexOf("private val media3FlowSink")
    val playerCreation = engine.indexOf("private val player = ExoPlayer.Builder")
    assertTrue(activity.contains("enableMedia3Flow = decoderPreferences.mpvFlowFrameInterpolation.get()"))
    assertTrue(sinkCreation >= 0 && sinkCreation < playerCreation)
    assertTrue(engine.contains("      enableMedia3Flow,\n      ::publishFlowDiagnostics,"))
    assertTrue(renderers.contains("Media3FlowVideoSink.supportsFormat(format, flowSink.isInterpolationEnabled())"))
  }
}
