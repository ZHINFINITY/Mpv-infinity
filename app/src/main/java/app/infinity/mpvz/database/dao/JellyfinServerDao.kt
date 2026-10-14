/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.infinity.mpvz.database.entities.JellyfinServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JellyfinServerDao {
  @Query("SELECT * FROM jellyfin_servers ORDER BY lastConnected DESC")
  fun getAllServers(): Flow<List<JellyfinServerEntity>>

  @Query("SELECT * FROM jellyfin_servers WHERE id = :id LIMIT 1")
  suspend fun getServerById(id: Long): JellyfinServerEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(server: JellyfinServerEntity): Long

  @Update
  suspend fun update(server: JellyfinServerEntity)

  @Delete
  suspend fun delete(server: JellyfinServerEntity)

  @Query("DELETE FROM jellyfin_servers WHERE id = :id")
  suspend fun deleteById(id: Long)
}
