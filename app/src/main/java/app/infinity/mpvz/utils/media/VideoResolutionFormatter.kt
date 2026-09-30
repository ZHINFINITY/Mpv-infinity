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

    // MediaStore and container metadata can report portrait dimensions with the axes swapped.
    // The p-label denotes vertical pixel count, so use the shorter side for either orientation.
    val verticalPixelCount = minOf(width, height)
    return when {
      verticalPixelCount >= 4320 -> "4320p"
      verticalPixelCount >= 2160 -> "2160p"
      verticalPixelCount >= 1440 -> "1440p"
      verticalPixelCount >= 1080 -> "1080p"
      verticalPixelCount >= 720 -> "720p"
      verticalPixelCount >= 576 -> "576p"
      verticalPixelCount >= 480 -> "480p"
      verticalPixelCount >= 360 -> "360p"
      verticalPixelCount >= 240 -> "240p"
      else -> "${verticalPixelCount}p"
    }
  }
}
