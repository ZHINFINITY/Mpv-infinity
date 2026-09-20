/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
