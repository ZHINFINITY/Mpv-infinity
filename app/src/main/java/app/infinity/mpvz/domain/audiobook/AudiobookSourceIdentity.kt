package app.infinity.mpvz.domain.audiobook

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/** Stable identity for one local audiobook source across different SAF tree grants. */
internal object AudiobookSourceIdentity {
  private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

  fun key(uri: Uri): String {
    if (uri.scheme.equals("file", ignoreCase = true)) {
      return uri.path?.let(::fileKey) ?: "uri:${uri.normalizeScheme()}"
    }

    // Prefer the document ID: a child opened through its parent tree has a document ID for the
    // child, while a separately selected tree has that same ID as its tree ID.
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
      ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    if (!documentId.isNullOrBlank()) {
      if (uri.authority == EXTERNAL_STORAGE_AUTHORITY) {
        externalStoragePath(documentId)?.let(::fileKey)?.let { return it }
      }
      return "document:${uri.authority.orEmpty()}:$documentId"
    }

    return "uri:${uri.normalizeScheme()}"
  }

  fun key(uriString: String): String = key(Uri.parse(uriString))

  private fun externalStoragePath(documentId: String): String? {
    val separator = documentId.indexOf(':')
    if (separator <= 0) return null
    val volume = documentId.substring(0, separator)
    val relativePath = documentId.substring(separator + 1).trimStart('/')
    val root = if (volume.equals("primary", ignoreCase = true)) {
      Environment.getExternalStorageDirectory().absolutePath
    } else {
      File("/storage", volume).absolutePath
    }
    return if (relativePath.isBlank()) root else "$root/$relativePath"
  }

  private fun fileKey(path: String): String {
    val normalized = File(path).absolutePath
      .replace('\\', '/')
      .replace(Regex("/+"), "/")
      .let { if (it.length > 1) it.trimEnd('/') else it }
    return "file:$normalized"
  }
}
