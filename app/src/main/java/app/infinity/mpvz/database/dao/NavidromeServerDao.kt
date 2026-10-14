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
import app.infinity.mpvz.database.entities.NavidromeServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NavidromeServerDao {
  @Query("SELECT * FROM navidrome_servers ORDER BY lastConnected DESC")
  fun getAllServers(): Flow<List<NavidromeServerEntity>>

  @Query("SELECT * FROM navidrome_servers WHERE id = :id LIMIT 1")
  suspend fun getServerById(id: Long): NavidromeServerEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(server: NavidromeServerEntity): Long

  @Update
  suspend fun update(server: NavidromeServerEntity)

  @Delete
  suspend fun delete(server: NavidromeServerEntity)

  @Query("DELETE FROM navidrome_servers WHERE id = :id")
  suspend fun deleteById(id: Long)
}
