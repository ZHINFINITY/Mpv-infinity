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
import app.infinity.mpvz.database.entities.AudiobookshelfServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudiobookshelfServerDao {
  @Query("SELECT * FROM audiobookshelf_servers ORDER BY lastConnected DESC")
  fun getAllServers(): Flow<List<AudiobookshelfServerEntity>>

  @Query("SELECT * FROM audiobookshelf_servers WHERE id = :id LIMIT 1")
  suspend fun getServerById(id: Long): AudiobookshelfServerEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(server: AudiobookshelfServerEntity): Long

  @Update
  suspend fun update(server: AudiobookshelfServerEntity)

  @Delete
  suspend fun delete(server: AudiobookshelfServerEntity)

  @Query("DELETE FROM audiobookshelf_servers WHERE id = :id")
  suspend fun deleteById(id: Long)
}
