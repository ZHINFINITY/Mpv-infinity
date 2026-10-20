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
import app.infinity.mpvz.domain.network.NetworkConnection
import kotlinx.coroutines.flow.Flow

/**
 * DAO for managing network connections in the database
 */
@Dao
interface NetworkConnectionDao {
  @Query("SELECT * FROM network_connections ORDER BY id ASC")
  fun getAllConnections(): Flow<List<NetworkConnection>>

  @Query("SELECT * FROM network_connections WHERE id = :id")
  suspend fun getConnectionById(id: Long): NetworkConnection?

  @Query("SELECT * FROM network_connections WHERE autoConnect = 1 ORDER BY id ASC")
  suspend fun getAutoConnectConnections(): List<NetworkConnection>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(connection: NetworkConnection): Long

  @Update
  suspend fun update(connection: NetworkConnection)

  @Delete
  suspend fun delete(connection: NetworkConnection)

  @Query("UPDATE network_connections SET lastConnected = :timestamp WHERE id = :id")
  suspend fun updateLastConnected(
    id: Long,
    timestamp: Long,
  )

  @Query("UPDATE network_connections SET password = :encryptedPassword WHERE id = :id")
  suspend fun updateEncryptedPassword(
    id: Long,
    encryptedPassword: String,
  )

  @Query("DELETE FROM network_connections WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("SELECT * FROM network_connections ORDER BY id ASC")
  suspend fun getAllConnectionsList(): List<NetworkConnection>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(connections: List<NetworkConnection>)
}
