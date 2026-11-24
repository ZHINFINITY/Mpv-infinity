/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import androidx.media3.common.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stream-format metadata published by the native (Media3) engine. mpv publishes its equivalents
 * through [PlaybackSession.propString]/[propInt], which the native engine never touches — without
 * this holder the media badges row has no source and shows nothing after an engine switch.
 */
object NativeMediaMetadata {
  data class Snapshot(
    val videoHeight: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val channelCount: Int? = null,
  )

  private val _snapshot = MutableStateFlow(Snapshot())
  val snapshot: StateFlow<Snapshot> = _snapshot

  fun publish(videoFormat: Format?, audioFormat: Format?) {
    _snapshot.value =
      Snapshot(
        videoHeight = videoFormat?.height?.takeIf { it != Format.NO_VALUE },
        videoCodec = videoFormat?.codecs?.takeIf { it.isNotBlank() },
        audioCodec = audioFormat?.codecs?.takeIf { it.isNotBlank() },
        channelCount = audioFormat?.channelCount?.takeIf { it != Format.NO_VALUE },
      )
  }

  fun clear() {
    _snapshot.value = Snapshot()
  }
}
