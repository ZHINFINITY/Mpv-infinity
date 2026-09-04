/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.infinity.mpvz.database.converters.NetworkProtocolConverter
import app.infinity.mpvz.database.converters.NetworkStreamEntryTypeConverter
import app.infinity.mpvz.database.dao.DirectoryScanDao
import app.infinity.mpvz.database.dao.DownloadItemDao
import app.infinity.mpvz.database.dao.NetworkConnectionDao
import app.infinity.mpvz.database.dao.NetworkStreamEntryDao
import app.infinity.mpvz.database.dao.PlaybackStateDao
import app.infinity.mpvz.database.dao.PlaylistDao
import app.infinity.mpvz.database.dao.RecentlyPlayedDao
import app.infinity.mpvz.database.dao.SecureMediaDao
import app.infinity.mpvz.database.dao.VideoMetadataDao
import app.infinity.mpvz.database.dao.JellyfinServerDao
import app.infinity.mpvz.database.entities.DirectoryScanEntity
import app.infinity.mpvz.database.entities.DownloadItemEntity
import app.infinity.mpvz.database.entities.JellyfinServerEntity
import app.infinity.mpvz.database.entities.NetworkStreamEntryEntity
import app.infinity.mpvz.database.entities.PlaybackStateEntity
import app.infinity.mpvz.database.entities.PlaylistEntity
import app.infinity.mpvz.database.entities.PlaylistItemEntity
import app.infinity.mpvz.database.entities.RecentlyPlayedEntity
import app.infinity.mpvz.database.entities.SecureMediaEntity
import app.infinity.mpvz.database.entities.VideoMetadataEntity
import app.infinity.mpvz.domain.network.NetworkConnection

@Database(
  entities = [
    PlaybackStateEntity::class,
    RecentlyPlayedEntity::class,
    VideoMetadataEntity::class,
    NetworkConnection::class,
    PlaylistEntity::class,
    PlaylistItemEntity::class,
    DirectoryScanEntity::class,
    SecureMediaEntity::class,
    NetworkStreamEntryEntity::class,
    JellyfinServerEntity::class,
    DownloadItemEntity::class,
  ],
  version = 18,
  exportSchema = true,
)
@TypeConverters(NetworkProtocolConverter::class, NetworkStreamEntryTypeConverter::class)
abstract class MpvRxDatabase : RoomDatabase() {
  abstract fun videoDataDao(): PlaybackStateDao

  abstract fun recentlyPlayedDao(): RecentlyPlayedDao

  abstract fun videoMetadataDao(): VideoMetadataDao

  abstract fun networkConnectionDao(): NetworkConnectionDao

  abstract fun networkStreamEntryDao(): NetworkStreamEntryDao

  abstract fun playlistDao(): PlaylistDao

  abstract fun directoryScanDao(): DirectoryScanDao

  abstract fun secureMediaDao(): SecureMediaDao

  abstract fun jellyfinServerDao(): JellyfinServerDao

  abstract fun downloadItemDao(): DownloadItemDao
}
