/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.infinity.mpvz.database.entities.NetworkStreamEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkStreamEntryDao {
  @Query(
    """
    SELECT * FROM network_stream_entries
    WHERE entryType = 'NORMAL'
    ORDER BY updatedAt DESC, stableKey ASC
    LIMIT :limit
    """,
  )
  fun observeRecentNormalEntries(limit: Int): Flow<List<NetworkStreamEntryEntity>>

  @Query(
    """
    SELECT * FROM network_stream_entries
    WHERE entryType = 'TORRENT_FILE'
    ORDER BY updatedAt DESC, infoHash ASC, fileIndex ASC
    """,
  )
  fun observeTorrentFileEntries(): Flow<List<NetworkStreamEntryEntity>>

  @Upsert
  suspend fun upsert(entry: NetworkStreamEntryEntity)

  @Upsert
  suspend fun upsertAll(entries: List<NetworkStreamEntryEntity>)

  @Query(
    """
    DELETE FROM network_stream_entries
    WHERE entryType = 'NORMAL'
      AND stableKey NOT IN (
        SELECT stableKey FROM network_stream_entries
        WHERE entryType = 'NORMAL'
        ORDER BY updatedAt DESC, stableKey ASC
        LIMIT :keepCount
      )
    """,
  )
  suspend fun trimNormalEntries(keepCount: Int)

  @Query("DELETE FROM network_stream_entries WHERE entryType = 'TORRENT_FILE' AND infoHash = :infoHash")
  suspend fun deleteTorrentFiles(infoHash: String)

  @Query("DELETE FROM network_stream_entries WHERE stableKey = :stableKey")
  suspend fun deleteByStableKey(stableKey: String)

  @Query(
    """
    UPDATE network_stream_entries
    SET updatedAt = :updatedAt
    WHERE stableKey = :stableKey AND entryType = 'NORMAL'
    """,
  )
  suspend fun touchNormalEntry(
    stableKey: String,
    updatedAt: Long,
  )

  @Transaction
  suspend fun upsertNormalAndTrim(
    entry: NetworkStreamEntryEntity,
    keepCount: Int,
  ) {
    upsert(entry)
    trimNormalEntries(keepCount)
  }

  @Query(
    """
    UPDATE network_stream_entries
    SET groupTitle = COALESCE(:title, groupTitle),
        posterUrl = COALESCE(:posterUrl, posterUrl),
        backdropUrl = COALESCE(:backdropUrl, backdropUrl),
        overview = COALESCE(:overview, overview),
        releaseYear = COALESCE(:releaseYear, releaseYear),
        mediaType = COALESCE(:mediaType, mediaType)
    WHERE infoHash = :infoHash
    """,
  )
  suspend fun updateTorrentArtwork(
    infoHash: String,
    title: String?,
    posterUrl: String?,
    backdropUrl: String?,
    overview: String?,
    releaseYear: String?,
    mediaType: String?,
  )

  @Transaction
  suspend fun replaceTorrentFiles(
    infoHash: String,
    entries: List<NetworkStreamEntryEntity>,
  ) {
    deleteTorrentFiles(infoHash)
    if (entries.isNotEmpty()) upsertAll(entries)
  }
}

