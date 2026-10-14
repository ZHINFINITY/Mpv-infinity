/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.infinity.mpvz.domain.jellyfin.JellyfinServer

@Entity(tableName = "jellyfin_servers")
data class JellyfinServerEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val name: String,
  val serverUrl: String,
  val userId: String,
  val username: String,
  val accessToken: String,
  val lastConnected: Long = 0,
) {
  fun toDomain(): JellyfinServer =
    JellyfinServer(
      id = id,
      name = name,
      serverUrl = serverUrl,
      userId = userId,
      username = username,
      accessToken = accessToken,
      lastConnected = lastConnected,
    )

  companion object {
    fun fromDomain(server: JellyfinServer): JellyfinServerEntity =
      JellyfinServerEntity(
        id = server.id,
        name = server.name,
        serverUrl = server.serverUrl,
        userId = server.userId,
        username = server.username,
        accessToken = server.accessToken,
        lastConnected = server.lastConnected,
      )
  }
}
