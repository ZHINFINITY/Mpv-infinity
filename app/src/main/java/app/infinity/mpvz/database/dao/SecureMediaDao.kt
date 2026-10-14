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
import app.infinity.mpvz.database.entities.SecureMediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SecureMediaDao {
  @Query("SELECT * FROM secure_media ORDER BY dateHidden DESC")
  fun observeAll(): Flow<List<SecureMediaEntity>>

  @Query("SELECT * FROM secure_media ORDER BY dateHidden DESC")
  suspend fun getAll(): List<SecureMediaEntity>

  @Query("SELECT * FROM secure_media WHERE id = :id LIMIT 1")
  suspend fun getById(id: Long): SecureMediaEntity?

  @Query("SELECT * FROM secure_media WHERE id IN (:ids)")
  suspend fun getByIds(ids: List<Long>): List<SecureMediaEntity>

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(entity: SecureMediaEntity): Long

  @Query("DELETE FROM secure_media WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM secure_media WHERE id IN (:ids)")
  suspend fun deleteByIds(ids: List<Long>)

  @Query("SELECT COUNT(*) FROM secure_media")
  suspend fun getCount(): Int

  @Query("SELECT COUNT(*) FROM secure_media")
  fun observeCount(): Flow<Int>
}
