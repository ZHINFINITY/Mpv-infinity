/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.cast

data class CastSubtitleTrack(
  val id: Long,
  val name: String,
  val language: String?,
)

data class CastAudioTrack(
  val id: Long,
  val name: String,
  val language: String?,
)

data class CastSessionState(
  val isConnected: Boolean = false,
  val deviceName: String? = null,
  val isPlaying: Boolean = false,
  val isPaused: Boolean = false,
  val isBuffering: Boolean = false,
  val currentPosition: Long = 0L,
  val duration: Long = 0L,
  val volume: Double = 1.0,
  val isMuted: Boolean = false,
  val playbackSpeed: Float = 1.0f,
  val title: String = "",
  val subtitleTracks: List<CastSubtitleTrack> = emptyList(),
  val activeSubtitleTrackId: Long? = null,
  val audioTracks: List<CastAudioTrack> = emptyList(),
  val activeAudioTrackId: Long? = null,
)
