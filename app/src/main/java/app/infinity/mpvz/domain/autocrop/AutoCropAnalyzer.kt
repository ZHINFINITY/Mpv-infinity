/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.domain.autocrop

import android.graphics.Bitmap
import kotlin.math.max

/** Edge widths expressed as fractions of the decoded frame size. */
data class AutoCropEdges(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
)

/**
 * Detects encoded black bars without depending on the active playback core.
 *
 * The detector intentionally favours false negatives over false positives: a dark scene may leave
 * a bar behind, but it must never be allowed to crop real picture content.
 */
object AutoCropAnalyzer {
  private const val DARK_LUMA = 28
  private const val CONTENT_LUMA = 42
  private const val DARK_PIXEL_RATIO = 0.965f
  private const val MIN_CONTENT_PIXEL_RATIO = 0.035f
  private const val MAX_EDGE_FRACTION = 0.32f
  private const val MIN_EDGE_FRACTION = 0.008f
  private const val BOUNDARY_OVERLAP_PIXELS = 1

  fun analyzeFrame(bitmap: Bitmap): AutoCropEdges? {
    val width = bitmap.width
    val height = bitmap.height
    if (width < 32 || height < 32) return null

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    if (!hasVisibleContent(pixels, width, height)) return null

    val horizontalInset = max(1, (width * 0.05f).toInt())
    val verticalInset = max(1, (height * 0.05f).toInt())
    val top = scanHorizontalEdge(pixels, width, height, horizontalInset, fromStart = true)
    val bottom = scanHorizontalEdge(pixels, width, height, horizontalInset, fromStart = false)
    val left = scanVerticalEdge(pixels, width, height, verticalInset, fromStart = true)
    val right = scanVerticalEdge(pixels, width, height, verticalInset, fromStart = false)

    return AutoCropEdges(
      left = normalizeEdge(left, width),
      top = normalizeEdge(top, height),
      right = normalizeEdge(right, width),
      bottom = normalizeEdge(bottom, height),
    )
  }

  /**
   * Combines several samples conservatively.
   *
   * A zero edge in any sample wins for that edge. This intentionally treats an expanded IMAX or
   * other variable-aspect-ratio frame as real picture instead of an outlier, so a static crop does
   * not override an expanded frame present in the sample set.
   */
  fun combine(samples: List<AutoCropEdges>): AutoCropEdges? {
    if (samples.size < 3) return null

    fun conservative(values: List<Float>): Float {
      val candidate = values.minOrNull() ?: 0f
      return candidate.takeIf { it >= MIN_EDGE_FRACTION } ?: 0f
    }

    val result =
      AutoCropEdges(
        left = conservative(samples.map(AutoCropEdges::left)),
        top = conservative(samples.map(AutoCropEdges::top)),
        right = conservative(samples.map(AutoCropEdges::right)),
        bottom = conservative(samples.map(AutoCropEdges::bottom)),
      )

    if (result.left + result.right > MAX_EDGE_FRACTION) return null
    if (result.top + result.bottom > MAX_EDGE_FRACTION) return null
    return result.takeIf { it.left > 0f || it.top > 0f || it.right > 0f || it.bottom > 0f }
  }

  private fun hasVisibleContent(
    pixels: IntArray,
    width: Int,
    height: Int,
  ): Boolean {
    val left = width / 4
    val right = width - left
    val top = height / 4
    val bottom = height - top
    val step = 2
    var sampled = 0
    var visible = 0
    for (y in top until bottom step step) {
      val row = y * width
      for (x in left until right step step) {
        sampled++
        if (luma(pixels[row + x]) > CONTENT_LUMA) visible++
      }
    }
    return sampled > 0 && visible.toFloat() / sampled >= MIN_CONTENT_PIXEL_RATIO
  }

  private fun scanHorizontalEdge(
    pixels: IntArray,
    width: Int,
    height: Int,
    inset: Int,
    fromStart: Boolean,
  ): Int {
    val limit = (height * MAX_EDGE_FRACTION).toInt().coerceAtLeast(1)
    var accepted = 0
    var misses = 0
    for (distance in 0 until limit) {
      val y = if (fromStart) distance else height - 1 - distance
      if (isDarkRow(pixels, width, y, inset)) {
        accepted = distance + 1
        misses = 0
      } else if (++misses >= 2) {
        break
      }
    }
    return accepted
  }

  private fun scanVerticalEdge(
    pixels: IntArray,
    width: Int,
    height: Int,
    inset: Int,
    fromStart: Boolean,
  ): Int {
    val limit = (width * MAX_EDGE_FRACTION).toInt().coerceAtLeast(1)
    var accepted = 0
    var misses = 0
    for (distance in 0 until limit) {
      val x = if (fromStart) distance else width - 1 - distance
      if (isDarkColumn(pixels, width, height, x, inset)) {
        accepted = distance + 1
        misses = 0
      } else if (++misses >= 2) {
        break
      }
    }
    return accepted
  }

  private fun isDarkRow(
    pixels: IntArray,
    width: Int,
    y: Int,
    inset: Int,
  ): Boolean {
    val start = inset
    val end = width - inset
    var dark = 0
    for (x in start until end) {
      if (luma(pixels[y * width + x]) <= DARK_LUMA) dark++
    }
    return dark.toFloat() / (end - start).coerceAtLeast(1) >= DARK_PIXEL_RATIO
  }

  private fun isDarkColumn(
    pixels: IntArray,
    width: Int,
    height: Int,
    x: Int,
    inset: Int,
  ): Boolean {
    val start = inset
    val end = height - inset
    var dark = 0
    for (y in start until end) {
      if (luma(pixels[y * width + x]) <= DARK_LUMA) dark++
    }
    return dark.toFloat() / (end - start).coerceAtLeast(1) >= DARK_PIXEL_RATIO
  }

  private fun normalizeEdge(
    pixels: Int,
    dimension: Int,
  ): Float {
    if (pixels <= 0) return 0f
    // Scaling blends the last black row with the first picture row. Include one analysis pixel so
    // the reconstructed source crop does not leave a thin black seam behind.
    val fraction = (pixels + BOUNDARY_OVERLAP_PIXELS).toFloat() / dimension
    return fraction.takeIf { it >= MIN_EDGE_FRACTION } ?: 0f
  }

  private fun luma(color: Int): Int {
    val red = color shr 16 and 0xff
    val green = color shr 8 and 0xff
    val blue = color and 0xff
    return (red * 54 + green * 183 + blue * 19) shr 8
  }
}
