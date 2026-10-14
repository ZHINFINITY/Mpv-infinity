/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.infinity.mpvz.database.entities.DownloadItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadItemDao {
  @Query("SELECT * FROM download_items ORDER BY timeQueued DESC")
  fun observeAll(): Flow<List<DownloadItemEntity>>

  @Query("SELECT * FROM download_items")
  suspend fun getAll(): List<DownloadItemEntity>

  @Query("SELECT * FROM download_items WHERE id = :id LIMIT 1")
  suspend fun findById(id: Long): DownloadItemEntity?

  @Query("SELECT * FROM download_items WHERE systemDownloadId = :systemDownloadId LIMIT 1")
  suspend fun findBySystemDownloadId(systemDownloadId: Long): DownloadItemEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(entity: DownloadItemEntity): Long

  @Update
  suspend fun update(entity: DownloadItemEntity)

  @Query("DELETE FROM download_items WHERE id = :id")
  suspend fun delete(id: Long)
}
