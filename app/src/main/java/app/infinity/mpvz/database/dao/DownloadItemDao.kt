/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
