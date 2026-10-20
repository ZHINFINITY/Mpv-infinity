/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.infinity.mpvz.domain.audiobookshelf.AudiobookshelfServer

@Entity(tableName = "audiobookshelf_servers")
data class AudiobookshelfServerEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val name: String,
  val serverUrl: String,
  val username: String,
  val token: String = "",
  val userId: String = "",
  val activeLibraryId: String? = null,
  val lastConnected: Long = 0,
) {
  fun toDomain(): AudiobookshelfServer =
    AudiobookshelfServer(
      id = id,
      name = name,
      serverUrl = serverUrl,
      username = username,
      token = token,
      userId = userId,
      activeLibraryId = activeLibraryId,
      lastConnected = lastConnected,
    )

  companion object {
    fun fromDomain(server: AudiobookshelfServer): AudiobookshelfServerEntity =
      AudiobookshelfServerEntity(
        id = server.id,
        name = server.name,
        serverUrl = server.serverUrl,
        username = server.username,
        token = server.token,
        userId = server.userId,
        activeLibraryId = server.activeLibraryId,
        lastConnected = server.lastConnected,
      )
  }
}
