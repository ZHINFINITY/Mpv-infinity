/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeInterpolationRuntimePolicyTest {
  @Test fun flowCanStartBeforeInitializationOnlyForEligibleFormatsAndCanAlwaysBeDisabled() {
    fun canApply(
      flowResourcesAttempted: Boolean,
      flowAvailable: Boolean,
      currentEnabled: Boolean,
      requestedEnabled: Boolean,
      formatEligible: Boolean,
      disposed: Boolean = false,
      preflightAvailable: Boolean = true,
      allowRetryAfterResourceFailure: Boolean = false,
    ) = NativeInterpolationRuntimePolicy.canApplyRuntimeConfiguration(
      disposed = disposed,
      preflightAvailable = preflightAvailable,
      flowResourcesAttempted = flowResourcesAttempted,
      flowAvailable = flowAvailable,
      currentInterpolationEnabled = currentEnabled,
      requestedInterpolationEnabled = requestedEnabled,
      requestedFormatEligible = formatEligible,
      allowRetryAfterResourceFailure = allowRetryAfterResourceFailure,
    )

    assertTrue(canApply(false, false, false, true, true))
    assertFalse(canApply(false, false, false, true, false))
    assertFalse(canApply(true, false, false, true, true))
    assertTrue(canApply(true, false, false, true, true, allowRetryAfterResourceFailure = true))
    assertFalse(canApply(true, false, false, true, false, allowRetryAfterResourceFailure = true))
    assertTrue(canApply(true, false, true, false, false))
    assertTrue(canApply(true, true, false, true, true))
    assertFalse(canApply(false, false, false, true, true, disposed = true))
    assertFalse(canApply(false, false, false, true, true, preflightAvailable = false))
    assertTrue(canApply(true, false, true, false, false, preflightAvailable = false))
    assertFalse(canApply(true, false, false, true, true, preflightAvailable = false, allowRetryAfterResourceFailure = true))
  }

  @Test fun flowEglSurfaceIsReleasedOnlyForFlowToNativePassthroughTransition() {
    fun shouldRelease(previous: Boolean, current: Boolean, nativePassthrough: Boolean) =
      NativeInterpolationRuntimePolicy.shouldReleaseFlowOutputSurfaceBeforeNativeRendererReselect(
        previousInterpolationEnabled = previous,
        interpolationEnabled = current,
        requiresNativePassthrough = nativePassthrough,
      )

    assertTrue(shouldRelease(previous = true, current = false, nativePassthrough = true))
    assertFalse(shouldRelease(previous = true, current = false, nativePassthrough = false))
    assertFalse(shouldRelease(previous = false, current = false, nativePassthrough = true))
    assertFalse(shouldRelease(previous = true, current = true, nativePassthrough = true))
    assertFalse(shouldRelease(previous = false, current = true, nativePassthrough = true))
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
    assertTrue(sink.contains("NativeInterpolationRuntimePolicy.canApplyRuntimeConfiguration"))
    assertTrue(sink.contains("flowResourcesAttempted = flowResourcesAttempted"))
    assertTrue(sink.contains("flowResourcesAttempted = true"))
    assertTrue(sink.contains("requestedFormatEligible = requestedFormatEligible"))
    assertTrue(sink.contains("allowRetryAfterResourceFailure = allowRetryAfterResourceFailure"))
    assertTrue(engine.contains("Media3FlowVideoSink.supportsFormat(it, enabled)"))
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
    assertTrue(engine.contains("NativeInterpolationRuntimePolicy.shouldReleaseFlowOutputSurfaceBeforeNativeRendererReselect("))
    val apply = engine.substringAfter("suspend fun applyInterpolationRuntime").substringBefore("\n  /**")
    val releaseIndex = apply.indexOf("flowSink.clearOutputSurfaceInfo()")
    val reselectIndex = apply.indexOf("nativeTrackSelector.invalidateRendererEligibility()")
    assertTrue(releaseIndex >= 0 && reselectIndex >= 0 && releaseIndex < reselectIndex)
    val clearSurface = sink.substringAfter("override fun clearOutputSurfaceInfo()").substringBefore("\n  override fun")
    assertTrue(clearSurface.contains("destroyWindowSurface { releaseFence.countDown() }"))
    assertTrue(clearSurface.contains("releaseFence.await(GL_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)"))
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
