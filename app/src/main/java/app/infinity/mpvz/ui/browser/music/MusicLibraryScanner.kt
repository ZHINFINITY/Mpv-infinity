/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.music

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object MusicLibraryScanner {

  private const val TAG = "MusicLibraryScanner"
  private val ALBUM_ART_BASE_URI = Uri.parse("content://media/external/audio/albumart")

  suspend fun scanSongs(context: Context): List<MusicSong> = withContext(Dispatchers.IO) {
    val songs = mutableListOf<MusicSong>()
    val projection = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.TITLE,
      MediaStore.Audio.Media.ARTIST,
      MediaStore.Audio.Media.ALBUM,
      MediaStore.Audio.Media.ALBUM_ID,
      MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.DATA,
      MediaStore.Audio.Media.DATE_ADDED,
      MediaStore.Audio.Media.TRACK,
      MediaStore.Audio.Media.YEAR,
      MediaStore.Audio.Media.SIZE
    )

    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 1000"
    val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

    try {
      context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        null,
        sortOrder
      )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

        while (cursor.moveToNext()) {
          val id = cursor.getLong(idCol)
          val path = cursor.getString(dataCol).orEmpty()
          val size = cursor.getLong(sizeCol)
          val duration = cursor.getLong(durationCol)
          val file = path.takeIf { it.isNotBlank() }?.let(::File)
          val fileExists = try { file?.exists() == true } catch (_: Exception) { false }
          if (!fileExists && size <= 0L && duration <= 0L) continue

          val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
            ?: file?.nameWithoutExtension
            ?: "Unknown Track"
          val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
          val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Album"
          val albumId = cursor.getLong(albumIdCol)
          val dateAdded = cursor.getLong(dateAddedCol)
          val track = cursor.getInt(trackCol)
          val year = cursor.getInt(yearCol)

          val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
          // Keep the initial scan MediaStore-only. Extracting embedded artwork with
          // MediaMetadataRetriever for every track blocks the first library render;
          // the album-art content URI is resolved lazily by the image layer.
          val albumArtUri = albumId.takeIf { it > 0 }?.let {
            ContentUris.withAppendedId(ALBUM_ART_BASE_URI, it)
          }

          songs.add(
            MusicSong(
              id = id,
              title = title,
              artist = artist,
              album = album,
              albumId = albumId,
              durationMs = duration,
              path = path,
              uri = contentUri,
              dateAdded = dateAdded,
              trackNumber = track,
              year = year,
              albumArtUri = albumArtUri,
              size = size
            )
          )
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error scanning songs from MediaStore", e)
    }

    songs
  }

  /** Resolve just one recently-played audio path without rescanning the whole music library. */
  suspend fun resolveSongByPath(
    context: Context,
    path: String,
    fallbackTitle: String? = null,
  ): MusicSong? = withContext(Dispatchers.IO) {
    val parsedUri = runCatching { Uri.parse(path) }.getOrNull()
    val filePath = when {
      parsedUri?.scheme.equals("file", ignoreCase = true) -> parsedUri?.path.orEmpty()
      parsedUri?.scheme == null -> path
      else -> ""
    }
    val file = filePath.takeIf { it.isNotBlank() }?.let(::File)
    val queryUri = parsedUri?.takeIf { it.scheme.equals("content", ignoreCase = true) }
      ?: MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val selection = if (queryUri == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) {
      "${MediaStore.Audio.Media.DATA} = ?"
    } else {
      null
    }
    val selectionArgs = if (selection == null) null else arrayOf(file?.absolutePath ?: path)
    val projection = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.TITLE,
      MediaStore.Audio.Media.ARTIST,
      MediaStore.Audio.Media.ALBUM,
      MediaStore.Audio.Media.ALBUM_ID,
      MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.DATA,
      MediaStore.Audio.Media.DATE_ADDED,
      MediaStore.Audio.Media.TRACK,
      MediaStore.Audio.Media.YEAR,
      MediaStore.Audio.Media.SIZE,
    )

    val indexedSong = runCatching {
      context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
        val storedPath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)).orEmpty()
        val albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID))
        val title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE))
          ?.takeIf { it.isNotBlank() } ?: file?.nameWithoutExtension ?: fallbackTitle ?: "Unknown Track"
        val artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
          ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
        val album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM))
          ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Album"
        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        MusicSong(
          id = id,
          title = title,
          artist = artist,
          album = album,
          albumId = albumId,
          durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)),
          path = storedPath.ifBlank { file?.absolutePath ?: path },
          uri = contentUri,
          dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)),
          trackNumber = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)),
          year = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)),
          albumArtUri = albumId.takeIf { it > 0 }?.let { ContentUris.withAppendedId(ALBUM_ART_BASE_URI, it) },
          size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)),
        )
      }
    }.getOrNull()
    if (indexedSong != null) {
      if (indexedSong.albumArtUri != null) return@withContext indexedSong
      val artworkUri = findArtworkUri(
        context = context,
        contentUri = indexedSong.uri,
        path = indexedSong.path,
        albumId = indexedSong.albumId,
      )
      return@withContext indexedSong.copy(albumArtUri = artworkUri)
    }

    if (file?.isFile == true && file.canRead()) {
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(file.absolutePath)
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
          ?.takeIf { it.isNotBlank() } ?: fallbackTitle?.takeIf { it.isNotBlank() }
          ?: file.nameWithoutExtension
        val artworkUri = findArtworkUri(context, Uri.fromFile(file), file.absolutePath, albumId = 0L)
        MusicSong(
          id = file.absolutePath.hashCode().toLong(),
          title = title,
          artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist",
          album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Album",
          albumId = 0L,
          durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
          path = file.absolutePath,
          uri = Uri.fromFile(file),
          dateAdded = file.lastModified() / 1000L,
          trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.toIntOrNull() ?: 0,
          year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull() ?: 0,
          albumArtUri = artworkUri,
          size = file.length(),
        )
      } catch (error: Exception) {
        Log.w(TAG, "Unable to read embedded audio metadata for $path", error)
        null
      } finally {
        runCatching { retriever.release() }
      }
    } else if (parsedUri?.scheme in setOf("http", "https", "rtmp", "rtsp")) {
      val streamUri = parsedUri ?: Uri.parse(path)
      MusicSong(
        id = path.hashCode().toLong(),
        title = fallbackTitle?.takeIf { it.isNotBlank() } ?: streamUri.lastPathSegment ?: "Audio Track",
        artist = "Unknown Artist",
        album = "Unknown Album",
        albumId = 0L,
        durationMs = 0L,
        path = path,
        uri = streamUri,
        dateAdded = 0L,
      )
    } else {
      null
    }
  }

  /** Prefer embedded/sidecar art because the legacy album-art provider is absent on many devices. */
  private fun findArtworkUri(
    context: Context,
    contentUri: Uri,
    path: String,
    albumId: Long,
  ): Uri? {
    val embeddedBytes = runCatching {
      MediaMetadataRetriever().use { retriever ->
        if (path.isNotBlank() && File(path).canRead()) retriever.setDataSource(path)
        else retriever.setDataSource(context, contentUri)
        retriever.embeddedPicture
      }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
    if (embeddedBytes != null) saveArtwork(context, embeddedBytes, "embedded:$path")?.let { return it }

    app.infinity.mpvz.domain.thumbnail.EmbeddedArtworkResolver.decodeSidecar(path)?.let { bitmap ->
      return try {
        saveArtwork(context, bitmap, "sidecar:$path:${File(path).lastModified()}")
      } finally {
        bitmap.recycle()
      }
    }

    return albumId.takeIf { it > 0 }?.let { ContentUris.withAppendedId(ALBUM_ART_BASE_URI, it) }
  }

  private fun saveArtwork(context: Context, bytes: ByteArray, key: String): Uri? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return try {
      saveArtwork(context, bitmap, key)
    } finally {
      bitmap.recycle()
    }
  }

  private fun saveArtwork(context: Context, bitmap: Bitmap, key: String): Uri? {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(key.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    val file = File(context.filesDir, "music_artwork/$digest.jpg")
    return runCatching {
      file.parentFile?.mkdirs()
      if (!file.isFile || file.length() == 0L) {
        file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) }
      }
      Uri.fromFile(file)
    }.getOrNull()
  }

  suspend fun scanAlbums(context: Context, songs: List<MusicSong>): List<MusicAlbum> = withContext(Dispatchers.IO) {
    if (songs.isNotEmpty()) {
      // Group songs by albumId/album title for exact matching
      songs.groupBy { if (it.albumId > 0) it.albumId else it.album.hashCode().toLong() }
        .map { (albumId, albumSongs) ->
          val firstSong = albumSongs.first()
          MusicAlbum(
            id = albumId,
            title = firstSong.album,
            artist = firstSong.artist,
            songCount = albumSongs.size,
            year = albumSongs.maxOfOrNull { it.year } ?: 0,
            albumArtUri = firstSong.albumArtUri
          )
        }
        .sortedBy { it.title.lowercase() }
    } else {
      emptyList()
    }
  }

  suspend fun scanArtists(context: Context, songs: List<MusicSong>): List<MusicArtist> = withContext(Dispatchers.IO) {
    if (songs.isNotEmpty()) {
      songs.groupBy { it.artist.lowercase().trim() }
        .map { (_, artistSongs) ->
          val firstSong = artistSongs.first()
          val albumCount = artistSongs.map { it.albumId }.distinct().size
          MusicArtist(
            id = firstSong.artist.hashCode().toLong(),
            name = firstSong.artist,
            songCount = artistSongs.size,
            albumCount = albumCount
          )
        }
        .sortedBy { it.name.lowercase() }
    } else {
      emptyList()
    }
  }
}
