/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.browser.music

import android.graphics.Bitmap
import androidx.palette.graphics.Palette

/** Dominant colors extracted from the album art, used for the music player ambience backdrop. */
internal data class AmbienceColors(
  val base: Int,
  val accent: Int,
) {
  companion object {
    fun from(bitmap: Bitmap): AmbienceColors? {
      val palette =
        Palette.from(bitmap).maximumColorCount(12).clearFilters().generate()
      val dominant = palette.dominantSwatch?.rgb
        ?: palette.vibrantSwatch?.rgb
        ?: return null
      val accent =
        palette.vibrantSwatch?.rgb
          ?: palette.mutedSwatch?.rgb
          ?: dominant
      return AmbienceColors(base = dominant, accent = accent)
    }
  }
}
