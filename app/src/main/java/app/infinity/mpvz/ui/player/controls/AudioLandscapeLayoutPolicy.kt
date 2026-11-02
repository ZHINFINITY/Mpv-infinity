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
    val main = (height * 0.17f).coerceIn(48f, 72f)
    // Extras give up height first on short displays, but never below a usable target.
    val extras = (height * 0.12f).coerceIn(minTouchTargetDp, 48f)
    val fixedRows = header + seekbar + main + extras
    val gap = minOf(4f, ((height - fixedRows) / 4f).coerceAtLeast(0f))
    // Metadata is the flexible row: it may collapse to zero instead of overflowing the safe
    // viewport, while the seekbar and both essential control rows keep their minimum sizes.
    val metadata = (height - fixedRows - gap * 4).coerceIn(0f, 112f)
    val artwork = (height - 8f).coerceAtLeast(minArtworkDp)
      .coerceAtMost(availableWidthDp * 0.40f)

    return Metrics(
      artworkDp = artwork,
      headerDp = header,
      metadataDp = metadata,
      seekbarDp = seekbar,
      mainRowDp = main,
      extrasRowDp = extras,
      gapDp = gap,
    )
  }
}
