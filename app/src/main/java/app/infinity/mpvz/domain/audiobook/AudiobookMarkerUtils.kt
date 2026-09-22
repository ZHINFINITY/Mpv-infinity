/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.domain.audiobook

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.infinity.mpvz.database.dao.AudiobookDao
import app.infinity.mpvz.ui.player.resolveUri
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object AudiobookMarkerUtils {
  const val AUDIOBOOK_MARKER = ".audiobook"
  private const val TAG = "AudiobookMarkerUtils"

  private val directoryCache = ConcurrentHashMap<String, Boolean>()
  private val knownAudiobookPaths = ConcurrentHashMap.newKeySet<String>()
  private val knownAudiobookDirectories = ConcurrentHashMap.newKeySet<String>()

  fun clearCache() {
    directoryCache.clear()
  }

  fun registerAudiobookPath(pathOrUri: String?) {
    if (pathOrUri.isNullOrBlank()) return
    knownAudiobookPaths.add(pathOrUri)
    val dirPath = when {
      pathOrUri.startsWith("content://") -> null
      else -> {
        val file = File(pathOrUri)
        if (file.isDirectory) file.absolutePath else file.parent
      }
    }
    if (dirPath != null) {
      knownAudiobookDirectories.add(dirPath.trimEnd('/'))
    }
  }

  fun isAudiobookDirectory(directory: File?): Boolean {
    if (directory == null) return false
    val path = runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath }
    val normPath = path.trimEnd('/')

    if (knownAudiobookDirectories.any { normPath == it || normPath.startsWith("$it/") }) {
      return true
    }

    directoryCache[path]?.let { return it }

    val result = runCatching {
      File(directory, AUDIOBOOK_MARKER).isFile ||
        directory.parentFile?.let(::isAudiobookDirectory) == true
    }.getOrDefault(false)

    directoryCache[path] = result
    return result
  }

  fun isAudiobookPath(path: String?): Boolean {
    if (path.isNullOrBlank()) return false

    if (knownAudiobookPaths.contains(path)) return true

    val cleanPath = path.trimEnd('/')
    if (knownAudiobookDirectories.any { cleanPath == it || cleanPath.startsWith("$it/") }) {
      return true
    }

    val file = File(path)
    val dir = if (file.isDirectory) file else file.parentFile
    return isAudiobookDirectory(dir)
  }

  fun resolveUriToDirectory(context: Context, uri: Uri?): File? {
    if (uri == null) return null
    return runCatching {
      if (DocumentsContract.isTreeUri(uri)) {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        when {
          docId.startsWith("primary:") -> {
            val relPath = docId.substringAfter("primary:")
            File("/storage/emulated/0/$relPath").takeIf { it.exists() }
              ?: File(Environment.getExternalStorageDirectory(), relPath).takeIf { it.exists() }
          }
          docId.contains(":") -> {
            val volume = docId.substringBefore(":")
            val relPath = docId.substringAfter(":")
            File("/storage/$volume/$relPath").takeIf { it.exists() }
          }
          else -> null
        }
      } else {
        val resolved = uri.resolveUri(context, allowFdFallback = false)
        if (resolved != null) {
          val f = File(resolved)
          if (f.isDirectory) f else f.parentFile
        } else {
          val path = uri.path
          if (path != null) {
            val f = File(path)
            if (f.isDirectory) f else f.parentFile
          } else null
        }
      }
    }.getOrNull()
  }

  fun ensureMarker(context: Context, folderUri: Uri? = null, document: DocumentFile? = null) {
    if (folderUri != null) {
      val dir = resolveUriToDirectory(context, folderUri)
      if (dir != null) {
        ensureMarker(dir)
        registerAudiobookPath(dir.absolutePath)
      }
    }

    if (document != null) {
      runCatching {
        if (document.isDirectory) {
          if (document.findFile(AUDIOBOOK_MARKER) == null) {
            document.createFile("application/octet-stream", AUDIOBOOK_MARKER)
          }
          val resolvedDir = resolveUriToDirectory(context, document.uri)
          if (resolvedDir != null) {
            ensureMarker(resolvedDir)
            registerAudiobookPath(resolvedDir.absolutePath)
          }
        } else {
          document.parentFile?.let { parent ->
            if (parent.findFile(AUDIOBOOK_MARKER) == null) {
              parent.createFile("application/octet-stream", AUDIOBOOK_MARKER)
            }
            val resolvedDir = resolveUriToDirectory(context, parent.uri)
            if (resolvedDir != null) {
              ensureMarker(resolvedDir)
              registerAudiobookPath(resolvedDir.absolutePath)
            }
          }
        }
      }.onFailure { e ->
        Log.w(TAG, "Failed creating .audiobook via SAF", e)
      }
    }
  }

  fun ensureMarker(file: File?) {
    if (file == null) return
    runCatching {
      val dir = if (file.isDirectory) file else file.parentFile
      if (dir != null && dir.exists()) {
        val marker = File(dir, AUDIOBOOK_MARKER)
        if (!marker.exists()) {
          marker.createNewFile()
        }
        registerAudiobookPath(dir.absolutePath)
      }
    }.onFailure { e ->
      Log.w(TAG, "Failed creating .audiobook file in $file", e)
    }
  }

  suspend fun syncKnownAudiobooks(context: Context, dao: AudiobookDao) {
    runCatching {
      val tracks = dao.getAllTracks()
      val books = dao.getAllBooks()

      books.forEach { book ->
        val uri = runCatching { Uri.parse(book.sourceKey) }.getOrNull()
        if (uri != null) {
          ensureMarker(context, folderUri = uri)
        }
      }

      tracks.forEach { track ->
        registerAudiobookPath(track.uri)
        val uri = runCatching { Uri.parse(track.uri) }.getOrNull()
        val dir = resolveUriToDirectory(context, uri)
        if (dir != null) {
          ensureMarker(dir)
        }
      }

      clearCache()
    }
  }
}
