/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientRendererPolicyTest {
  private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  private fun source(path: String) = File(root, "app/src/main/java/app/infinity/mpvz/$path").readText()

  @Test
  fun onlyAnExplicitLegacyGpuOutputUsesGlowShaderPath() {
    assertTrue(AmbientRendererPolicy.usesFrameCapturedAmbient(nativeEngineActive = false, videoOutput = null))
    assertTrue(AmbientRendererPolicy.usesFrameCapturedAmbient(nativeEngineActive = false, videoOutput = "gpu-next"))
    assertTrue(AmbientRendererPolicy.usesFrameCapturedAmbient(nativeEngineActive = false, videoOutput = "GPU-NEXT"))
    assertFalse(AmbientRendererPolicy.usesFrameCapturedAmbient(nativeEngineActive = false, videoOutput = "gpu"))
    assertTrue(AmbientRendererPolicy.usesFrameCapturedAmbient(nativeEngineActive = true, videoOutput = "gpu"))
  }

  @Test
  fun frameCaptureDoesNotOverwriteTheSelectedStyle() {
    val selectedStyle = AmbientStyle.Glow
    val legacyGpuUsesFrameCapture =
      AmbientRendererPolicy.usesFrameCapturedAmbient(nativeEngineActive = false, videoOutput = "gpu")

    assertEquals(AmbientStyle.YouTube, AmbientRendererPolicy.effectiveStyle(selectedStyle, usesFrameCapture = true))
    assertEquals(AmbientStyle.Glow, selectedStyle)
    assertEquals(
      AmbientStyle.Glow,
      AmbientRendererPolicy.effectiveStyle(AmbientStyle.Glow, usesFrameCapture = false),
    )
    assertEquals(
      AmbientStyle.YouTube,
      AmbientRendererPolicy.effectiveStyle(AmbientStyle.YouTube, usesFrameCapture = false),
    )
    assertFalse(legacyGpuUsesFrameCapture)
    assertEquals(AmbientStyle.YouTube, AmbientRendererPolicy.effectiveStyle(AmbientStyle.YouTube, legacyGpuUsesFrameCapture))
  }

  @Test
  fun disablingFrameCaptureFallbackResumesOnlyAnActiveGlowSession() {
    assertTrue(AmbientRendererPolicy.shouldResumeGlowAfterFrameCaptureFallback(fallbackEnabled = false, glowRuntimeActive = true))
    assertFalse(AmbientRendererPolicy.shouldResumeGlowAfterFrameCaptureFallback(fallbackEnabled = false, glowRuntimeActive = false))
    assertFalse(AmbientRendererPolicy.shouldResumeGlowAfterFrameCaptureFallback(fallbackEnabled = true, glowRuntimeActive = true))
  }

  @Test
  fun fallbackUsesTheActiveEngineAndObservedMpvOutput() {
    val activity = source("ui/player/PlayerActivity.kt")
    val viewModel = source("ui/player/PlayerViewModel.kt")
    val mpvView = source("ui/player/MPVView.kt")
    val fallbackSetter = viewModel.substringAfter("fun setFrameCapturedAmbientFallback(")
      .substringBefore("private fun isAmbientRuntimeActive()")

    assertTrue(activity.contains("val nativeActive = glassEngineMode == PlaybackEngineMode.NATIVE"))
    assertTrue(activity.contains("PlaybackSession.propString[\"vo\"]"))
    assertTrue(activity.contains("viewModel.setFrameCapturedAmbientFallback(usesFrameCapturedAmbient)"))
    assertTrue(mpvView.contains("PlaybackSession.observeProperty(\"vo\", MPVLib.MpvFormat.MPV_FORMAT_STRING)"))
    assertTrue(viewModel.contains("&& !frameCapturedAmbientFallback"))
    assertTrue(viewModel.contains("@Volatile private var frameCapturedAmbientFallback = true"))
    assertTrue(fallbackSetter.contains("AmbientRendererPolicy.shouldResumeGlowAfterFrameCaptureFallback(enabled, isAmbientGlowRuntimeActive())"))
    assertTrue(fallbackSetter.contains("scheduleAmbientUpdate(0)"))
    assertTrue(viewModel.contains("if (enabled) {\n      disableAmbientShader()"))
  }
}
