/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a media file that has been moved into app-private "secure" storage
 * (outside MediaStore) as part of the Secure Folder feature.
 *
 * [originalPath] is retained so the file can be restored back to its original
 * location (or as close to it as possible) via [app.infinity.mpvz.repository.SecureFolderRepository].
 */
@Entity(
  tableName = "secure_media",
  indices = [
    Index(value = ["secureFilePath"], unique = true),
  ],
)
data class SecureMediaEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val originalPath: String,
  val secureFilePath: String,
  val fileName: String,
  val fileSize: Long,
  val mimeType: String,
  val dateHidden: Long,
)
