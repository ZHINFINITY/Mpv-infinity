/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.domain.audiobookshelf

import kotlinx.serialization.Serializable

@Serializable
data class AudiobookshelfServer(
  val id: Long = 0,
  val name: String,
  val serverUrl: String,
  val username: String,
  val token: String = "",
  val userId: String = "",
  val activeLibraryId: String? = null,
  val lastConnected: Long = 0,
)

@Serializable
data class AudiobookshelfLibrary(
  val id: String,
  val name: String,
  val mediaType: String = "book", // "book" or "podcast"
  val icon: String? = null,
  val displayOrder: Int = 0,
)

@Serializable
data class AudiobookshelfAuthor(
  val id: String? = null,
  val name: String = "",
)

@Serializable
data class AudiobookshelfSeries(
  val id: String? = null,
  val name: String = "",
  val sequence: String = "",
)

@Serializable
data class AudiobookshelfChapter(
  val id: Long = 0,
  val startMs: Long = 0,
  val endMs: Long = 0,
  val title: String = "",
)

@Serializable
data class AudiobookshelfTrack(
  val id: String,
  val index: Int = 0,
  val ino: String = "",
  val title: String = "",
  val durationMs: Long = 0,
  val size: Long = 0,
  val mimeType: String = "",
  val contentUrl: String = "",
)

@Serializable
data class AudiobookshelfBook(
  val id: String,
  val libraryId: String,
  val title: String,
  val subtitle: String = "",
  val author: String = "",
  val narrator: String = "",
  val series: String = "",
  val seriesPart: String = "",
  val description: String = "",
  val genres: List<String> = emptyList(),
  val publisher: String = "",
  val publishedYear: String = "",
  val language: String = "",
  val isbn: String = "",
  val asin: String = "",
  val durationMs: Long = 0,
  val progressMs: Long = 0,
  val progressPercent: Float = 0f,
  val isFinished: Boolean = false,
  val coverUrl: String? = null,
  val tracks: List<AudiobookshelfTrack> = emptyList(),
  val chapters: List<AudiobookshelfChapter> = emptyList(),
  val addedAt: Long = 0,
  val updatedAt: Long = 0,
) {
  val remainingMs: Long
    get() = (durationMs - progressMs).coerceAtLeast(0)
}
