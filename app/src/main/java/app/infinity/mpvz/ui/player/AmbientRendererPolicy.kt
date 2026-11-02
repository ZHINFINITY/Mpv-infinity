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
  ): Boolean = nativeEngineActive || !videoOutput.isLegacyGpuOutput()

  fun effectiveStyle(
    selectedStyle: AmbientStyle,
    usesFrameCapture: Boolean,
  ): AmbientStyle =
    if (usesFrameCapture) AmbientStyle.YouTube else selectedStyle

  private fun String?.isLegacyGpuOutput(): Boolean =
    this?.trim()?.equals("gpu", ignoreCase = true) == true
}
