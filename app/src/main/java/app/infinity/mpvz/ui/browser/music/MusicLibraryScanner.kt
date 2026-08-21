/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

private data class WavMetadata(
  val title: String? = null,
  val artist: String? = null,
  val album: String? = null,
  val year: Int? = null,
  val track: Int? = null,
)

object MusicLibraryScanner {

  private const val MAX_WAV_METADATA_SCAN_BYTES = 16L * 1024L * 1024L

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
          val path = cursor.getString(dataCol) ?: continue
          val file = File(path)
          if (!file.exists()) continue

          val wavMetadata = if (file.extension.equals("wav", ignoreCase = true)) {
            readWavMetadata(file)
          } else {
            null
          }
          val mediaStoreTitle = cursor.getString(titleCol)?.trim()
          val mediaStoreArtist = cursor.getString(artistCol)?.trim()
          val mediaStoreAlbum = cursor.getString(albumCol)?.trim()
          val title = wavMetadata?.title
            ?: mediaStoreTitle?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
            ?: file.nameWithoutExtension
          val artist = wavMetadata?.artist
            ?: mediaStoreArtist?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
            ?: "Unknown Artist"
          val album = wavMetadata?.album
            ?: mediaStoreAlbum?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
            ?: "Unknown Album"
          val albumId = cursor.getLong(albumIdCol)
          val duration = cursor.getLong(durationCol)
          val dateAdded = cursor.getLong(dateAddedCol)
          val track = wavMetadata?.track ?: cursor.getInt(trackCol)
          val year = wavMetadata?.year ?: cursor.getInt(yearCol)
          val size = cursor.getLong(sizeCol)

          val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
          val albumArtUri = if (albumId > 0) ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId) else null

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

  /**
   * Reads the standard RIFF INFO tags used by many WAV encoders. Android's
   * MediaStore indexing is inconsistent for these tags, so this is only used
   * for WAV files and never replaces valid metadata from other formats.
   */
  private fun readWavMetadata(file: File): WavMetadata? {
    return runCatching {
      RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < 12L) return@use null
        val riff = ByteArray(4)
        raf.readFully(riff)
        if (!riff.contentEquals(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))) {
          return@use null
        }
        raf.skipBytes(4)
        val wave = ByteArray(4)
        raf.readFully(wave)
        if (!wave.contentEquals(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))) {
          return@use null
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var year: Int? = null
        var track: Int? = null
        val scanLimit = minOf(raf.length(), MAX_WAV_METADATA_SCAN_BYTES)
        while (raf.filePointer + 8L <= scanLimit) {
          val chunkId = ByteArray(4)
          raf.readFully(chunkId)
          val chunkSize = readLittleEndianInt(raf).toLong()
          if (chunkSize < 0L) break
          val chunkStart = raf.filePointer
          val nextChunk = chunkStart + chunkSize + (chunkSize and 1L)
          if (nextChunk > raf.length() || nextChunk > scanLimit + 1L) break

          if (chunkId.contentEquals(byteArrayOf('L'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte())) && chunkSize >= 4L) {
            val listType = ByteArray(4)
            raf.readFully(listType)
            if (listType.contentEquals(byteArrayOf('I'.code.toByte(), 'N'.code.toByte(), 'F'.code.toByte(), 'O'.code.toByte()))) {
              val listEnd = chunkStart + chunkSize
              while (raf.filePointer + 8L <= listEnd) {
                val infoId = ByteArray(4)
                raf.readFully(infoId)
                val infoSize = readLittleEndianInt(raf).toLong()
                val infoStart = raf.filePointer
                if (infoSize < 0L || infoStart + infoSize > listEnd) break
                val value = ByteArray(infoSize.coerceAtMost(4096L).toInt())
                raf.readFully(value)
                val decoded = value.toString(Charsets.UTF_8).trimEnd('\u0000', ' ', '\t', '\r', '\n').trim()
                when (String(infoId, Charsets.US_ASCII)) {
                  "INAM" -> title = decoded.takeIf { it.isNotBlank() }
                  "IART" -> artist = decoded.takeIf { it.isNotBlank() }
                  "IPRD" -> album = decoded.takeIf { it.isNotBlank() }
                  "ICRD" -> year = decoded.filter(Char::isDigit).take(4).toIntOrNull()
                  "ITRK" -> track = decoded.filter(Char::isDigit).toIntOrNull()
                }
                raf.seek(infoStart + infoSize + (infoSize and 1L))
              }
            }
          }
          raf.seek(nextChunk)
        }
        if (title == null && artist == null && album == null && year == null && track == null) null
        else WavMetadata(title, artist, album, year, track)
      }
    }.onFailure { error ->
      Log.d(TAG, "Unable to read WAV INFO metadata from ${file.name}: ${error.message}")
    }.getOrNull()
  }

  private fun readLittleEndianInt(raf: RandomAccessFile): Int {
    val b0 = raf.read()
    val b1 = raf.read()
    val b2 = raf.read()
    val b3 = raf.read()
    if ((b0 or b1 or b2 or b3) < 0) return -1
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
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
