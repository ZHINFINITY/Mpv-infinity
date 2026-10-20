/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.domain.navidrome

import kotlinx.serialization.Serializable

enum class NavidromeAuthMode {
  CREDENTIALS,
  TOKEN,
}

@Serializable
data class NavidromeServer(
  val id: Long = 0,
  val name: String,
  val serverUrl: String,
  val username: String,
  val password: String = "",
  val token: String = "",
  val authMode: NavidromeAuthMode = NavidromeAuthMode.CREDENTIALS,
  val lastConnected: Long = 0,
)

@Serializable
data class NavidromeSong(
  val id: String,
  val title: String,
  val album: String = "",
  val albumId: String = "",
  val artist: String = "",
  val artistId: String = "",
  val trackNumber: Int? = null,
  val discNumber: Int? = null,
  val year: Int? = null,
  val genre: String? = null,
  val durationSeconds: Int = 0,
  val bitRate: Int? = null,
  val coverArtId: String? = null,
  val suffix: String? = null,
  val isFavorite: Boolean = false,
)

@Serializable
data class NavidromeAlbum(
  val id: String,
  val title: String,
  val artist: String = "",
  val artistId: String = "",
  val year: Int? = null,
  val genre: String? = null,
  val songCount: Int = 0,
  val durationSeconds: Int = 0,
  val coverArtId: String? = null,
  val isFavorite: Boolean = false,
  val songs: List<NavidromeSong> = emptyList(),
)

@Serializable
data class NavidromeArtist(
  val id: String,
  val name: String,
  val albumCount: Int = 0,
  val artistImageUrl: String? = null,
  val coverArtId: String? = null,
  val isFavorite: Boolean = false,
  val albums: List<NavidromeAlbum> = emptyList(),
)

@Serializable
data class NavidromePlaylist(
  val id: String,
  val name: String,
  val songCount: Int = 0,
  val durationSeconds: Int = 0,
  val coverArtId: String? = null,
  val songs: List<NavidromeSong> = emptyList(),
)

enum class NavidromeMusicTab(val title: String) {
  HOME("Home"),
  TRACKS("Tracks"),
  ALBUMS("Albums"),
  ARTISTS("Artists"),
  PLAYLISTS("Playlists"),
}
