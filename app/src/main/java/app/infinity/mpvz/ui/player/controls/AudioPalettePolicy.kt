/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls

/** Pure routing policy: music glass is a color scene, never an artwork bitmap. */
object AudioPalettePolicy {
  fun useArtworkAsGlassScene(): Boolean = false
  fun cacheIdentity(pathOrUri: String?, artworkUri: String?): String? =
    pathOrUri?.takeIf { it.isNotBlank() }?.let { "$it\u0000${artworkUri.orEmpty()}" }

  /** Only semantic colors enter this sampler; no source image or image dimensions are accepted. */
  fun gradientRows(topArgb: Int, bottomArgb: Int, height: Int = 32): IntArray {
    val rows = height.coerceIn(2, 64)
    return IntArray(rows) { y ->
      val fraction = y.toFloat() / (rows - 1)
      var color = 0
      for (shift in intArrayOf(24, 16, 8, 0)) {
        val top = (topArgb ushr shift) and 255
        val bottom = (bottomArgb ushr shift) and 255
        val channel = (top + (bottom - top) * fraction + 0.5f).toInt().coerceIn(0, 255)
        color = color or (channel shl shift)
      }
      color
    }
  }
}
