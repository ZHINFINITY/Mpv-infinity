/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.utils.media

/** Formats the conventional vertical-resolution label independent of encoded orientation. */
internal object VideoResolutionFormatter {
  fun format(
    width: Int,
    height: Int,
  ): String {
    if (width <= 0 || height <= 0) return "--"

    // Resolution tags follow the conventional 16:9 tier even when a movie is cropped wider
    // (for example, 3840x1600 is still UHD/2160p). Compare the short side to p-heights and
    // the long side to their 16:9 widths so swapped portrait dimensions classify identically.
    val shortSide = minOf(width, height)
    val longSide = maxOf(width, height)
    return when {
      shortSide >= 4320 || longSide >= 7680 -> "4320p"
      shortSide >= 2160 || longSide >= 3840 -> "2160p"
      shortSide >= 1440 || longSide >= 2560 -> "1440p"
      shortSide >= 1080 || longSide >= 1920 -> "1080p"
      shortSide >= 720 || longSide >= 1280 -> "720p"
      shortSide >= 576 || longSide >= 1024 -> "576p"
      shortSide >= 480 || longSide >= 854 -> "480p"
      shortSide >= 360 || longSide >= 640 -> "360p"
      shortSide >= 240 || longSide >= 426 -> "240p"
      else -> "${shortSide}p"
    }
  }
}
