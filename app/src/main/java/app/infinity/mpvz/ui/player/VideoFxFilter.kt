/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import app.infinity.mpvz.preferences.DecoderPreferences

/**
 * Layered on-video effect filters, in the spirit of the mpv "Effects" shader pack: each entry
 * is an ffmpeg libavfilter video filter that runs over the decoded picture. Several can be
 * stacked; the enabled set is persisted and re-applied on every video load.
 */
enum class VideoFxFilter(
  val id: String,
  val label: String,
  val filter: String,
) {
  SHARPEN("sharpen", "Sharpen", "unsharp=5:5:0.8:3:3:0.4"),
  CLARITY("clarity", "Clarity", "unsharp=7:7:0.4:7:7:0.0"),
  DENOISE("denoise", "Denoise", "hqdn3d=3:3:6:6"),
  POP("pop", "Pop", "eq=contrast=1.06:saturation=1.10"),
  ;

  companion object {
    fun fromIds(raw: String): Set<VideoFxFilter> =
      raw.split(',').mapNotNull { id -> entries.firstOrNull { it.id == id.trim() } }.toSet()

    fun idsOf(filters: Set<VideoFxFilter>): String = filters.joinToString(",") { it.id }

    fun enabled(preferences: DecoderPreferences): Set<VideoFxFilter> =
      fromIds(preferences.videoFxFilters.get())

    /** Adds or removes this filter from the live chain; safe to call for any playback state. */
    fun setEnabled(filter: VideoFxFilter, enabled: Boolean) {
      if (!PlaybackSession.isInitialized) return
      val label = "mpvfx_${filter.id}"
      PlaybackSession.command("vf", "remove", "@$label")
      if (enabled) PlaybackSession.command("vf", "add", "@$label:${filter.filter}")
    }

    /** Re-applies every persisted filter on a freshly started track. */
    fun reapplyAll(preferences: DecoderPreferences) {
      if (!PlaybackSession.isInitialized) return
      val active = enabled(preferences)
      entries.forEach { filter -> setEnabled(filter, filter in active) }
    }
  }
}
