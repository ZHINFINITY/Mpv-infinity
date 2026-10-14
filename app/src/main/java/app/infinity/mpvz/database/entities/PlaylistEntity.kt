/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class PlaylistEntity(
  @PrimaryKey(autoGenerate = true) val id: Int = 0,
  val name: String,
  val createdAt: Long,
  val updatedAt: Long,
  val m3uSourceUrl: String? = null, // URL of the M3U/M3U8 source, null for manual playlists
  val isM3uPlaylist: Boolean = false, // True if this playlist was created from an M3U source
  val userAgent: String? = null, // Custom User-Agent for fetching this M3U
  val isAudio: Boolean = false, // True if this playlist was created in music mode
)
