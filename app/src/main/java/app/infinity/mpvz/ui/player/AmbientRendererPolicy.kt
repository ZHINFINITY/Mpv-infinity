/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

/** Chooses the ambient presentation path supported by the active video renderer. */
internal object AmbientRendererPolicy {
  fun usesFrameCapturedAmbient(
    nativeEngineActive: Boolean,
    videoOutput: String?,
  ): Boolean = nativeEngineActive || !videoOutput.isShaderAmbientOutput()

  fun effectiveStyle(
    selectedStyle: AmbientStyle,
    usesFrameCapture: Boolean,
  ): AmbientStyle =
    if (usesFrameCapture) AmbientStyle.YouTube else selectedStyle

  fun shouldResumeGlowAfterFrameCaptureFallback(
    fallbackEnabled: Boolean,
    glowRuntimeActive: Boolean,
  ): Boolean = !fallbackEnabled && glowRuntimeActive

  /**
   * Both mpv renderers accept `//!HOOK OUTPUT` user shaders, so the styled
   * ambient shaders run on gpu-next too. Restricting this to `gpu` made every
   * ambience style collapse into the frame-captured YouTube backdrop, because
   * gpu-next is the default output.
   */
  private fun String?.isShaderAmbientOutput(): Boolean {
    val output = this?.trim()?.lowercase() ?: return false
    return output == "gpu" || output == "gpu-next"
  }
}
