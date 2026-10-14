/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.playlist

import app.infinity.mpvz.database.entities.PlaylistEntity

const val ALL_VIDEOS_PLAYLIST_ID = -2
const val ALL_VIDEOS_PLAYLIST_NAME = "All Videos"

fun isAllVideosPlaylist(playlistId: Int): Boolean = playlistId == ALL_VIDEOS_PLAYLIST_ID

fun buildAllVideosPlaylistEntity(updatedAt: Long = System.currentTimeMillis()): PlaylistEntity =
  PlaylistEntity(
    id = ALL_VIDEOS_PLAYLIST_ID,
    name = ALL_VIDEOS_PLAYLIST_NAME,
    createdAt = 0L,
    updatedAt = updatedAt,
  )
