/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

internal data class IntentSubtitleLoadEntry<T>(
  val value: T,
  val metadataIndex: Int,
  val select: Boolean,
)

internal object IntentSubtitleLoadPolicy {
  fun <T> entriesToLoad(
    subtitles: List<T>,
    enabledSubtitles: List<T>,
    hasEnabledSubtitleExtra: Boolean,
  ): List<IntentSubtitleLoadEntry<T>> {
    val enabledSet = enabledSubtitles.toSet()
    val loaded = mutableListOf<IntentSubtitleLoadEntry<T>>()
    val seen = mutableSetOf<T>()

    for ((index, subtitle) in subtitles.withIndex()) {
      if (seen.add(subtitle)) {
        val isSelected = hasEnabledSubtitleExtra && enabledSet.contains(subtitle)
        loaded.add(
          IntentSubtitleLoadEntry(
            value = subtitle,
            metadataIndex = index,
            select = isSelected,
          ),
        )
      }
    }

    if (hasEnabledSubtitleExtra) {
      for (enabledSubtitle in enabledSubtitles) {
        if (seen.add(enabledSubtitle)) {
          loaded.add(
            IntentSubtitleLoadEntry(
              value = enabledSubtitle,
              metadataIndex = subtitles.indexOf(enabledSubtitle).takeIf { it >= 0 } ?: -1,
              select = true,
            ),
          )
        }
      }
    }

    return loaded
  }
}
