/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.music

/** Emits a media ID once playback begins, then once for each actual queue transition. */
internal class MusicPlaybackHistoryGate {
  private var pendingTransitionMediaId: String? = null
  private var lastRecordedMediaId: String? = null
  private var isPlaying = false

  fun onMediaItemTransition(mediaId: String?, isPlaying: Boolean): String? {
    this.isPlaying = isPlaying
    if (mediaId.isNullOrBlank()) {
      pendingTransitionMediaId = null
      return null
    }
    pendingTransitionMediaId = mediaId
    return recordIfPlaying(mediaId, isPlaying)
  }

  fun onIsPlayingChanged(mediaId: String?, isPlaying: Boolean): String? {
    val started = isPlaying && !this.isPlaying
    this.isPlaying = isPlaying
    if (!isPlaying || mediaId.isNullOrBlank()) return null
    if (started || pendingTransitionMediaId == mediaId || lastRecordedMediaId != mediaId) return record(mediaId)
    return null
  }

  private fun recordIfPlaying(mediaId: String, isPlaying: Boolean): String? {
    if (!isPlaying) return null
    return record(mediaId)
  }

  private fun record(mediaId: String): String {
    pendingTransitionMediaId = null
    lastRecordedMediaId = mediaId
    return mediaId
  }
}
