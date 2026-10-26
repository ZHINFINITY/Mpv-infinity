/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls

/** Pure sizing policy for the full music player after safe insets are consumed. */
object AudioLandscapeLayoutPolicy {
  const val minTouchTargetDp = 40f
  private const val minArtworkDp = 96f

  data class Metrics(
    val artworkDp: Float,
    val headerDp: Float,
    val metadataDp: Float,
    val seekbarDp: Float,
    val mainRowDp: Float,
    val extrasRowDp: Float,
    val gapDp: Float,
  ) {
    val totalHeightDp: Float
      get() = headerDp + metadataDp + seekbarDp + mainRowDp + extrasRowDp + gapDp * 4
  }

  fun measure(availableHeightDp: Float, availableWidthDp: Float): Metrics {
    val height = availableHeightDp.coerceAtLeast(0f)
    val header = 44f
    val seekbar = 40f
    val gap = 4f
    val main = (height * 0.17f).coerceIn(48f, 72f)
    // Extras give up height first on short displays, but never below a usable target.
    val extras = (height * 0.12f).coerceIn(minTouchTargetDp, 48f)
    val metadata = (height - header - seekbar - main - extras - gap * 4)
      .coerceAtLeast(64f)
      .coerceAtMost(112f)
    val fixed = header + metadata + seekbar + main + extras + gap * 4
    val overflow = (fixed - height).coerceAtLeast(0f)
    val reducedMetadata = (metadata - overflow).coerceAtLeast(56f)
    val finalFixed = header + reducedMetadata + seekbar + main + extras + gap * 4
    val finalExtras = if (finalFixed > height) {
      (extras - (finalFixed - height)).coerceAtLeast(minTouchTargetDp)
    } else extras
    val artwork = (height - 8f).coerceAtLeast(minArtworkDp)
      .coerceAtMost(availableWidthDp * 0.40f)

    return Metrics(
      artworkDp = artwork,
      headerDp = header,
      metadataDp = reducedMetadata,
      seekbarDp = seekbar,
      mainRowDp = main,
      extrasRowDp = finalExtras,
      gapDp = gap,
    )
  }
}
