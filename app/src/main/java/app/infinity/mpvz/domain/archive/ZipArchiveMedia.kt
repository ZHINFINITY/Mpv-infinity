package app.infinity.mpvz.domain.archive

import java.io.File

/** Helpers for presenting media discovered inside archive files (zip/cbz). */
object ZipArchiveMedia {
  private val ARCHIVE_EXTENSIONS = setOf("zip", "cbz")

  /**
   * Resolves a picked archive Uri to a playable file path. SAF uris are copied into the
   * cache (mpv needs a real path to open archives through libarchive); local file uris are
   * used as-is.
   */
  fun openForPlayback(context: android.content.Context, uri: android.net.Uri): File? {
    if (uri.scheme == "file") {
      uri.path?.let { path -> File(path).takeIf { it.isFile }?.let { return it } }
    }
    return runCatching {
      val resolver = context.contentResolver
      val name =
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
          ?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst().takeIf { idx >= 0 }?.let { cursor.getString(idx) }
          }
          ?.takeIf { it.isNotBlank() }
          ?: "archive.zip"
      val safeName = name.substringAfterLast('/').ifBlank { "archive.zip" }
      val dir = File(context.cacheDir, "zip-viewer").apply { mkdirs() }
      val target = File(dir, safeName)
      resolver.openInputStream(uri)?.use { input ->
        val tmp = File(dir, "$safeName.part")
        tmp.outputStream().use { output -> input.copyTo(output) }
        if (!tmp.renameTo(target)) {
          target.delete()
          tmp.renameTo(target)
        }
      } ?: return null
      target.takeIf { it.length() > 0 }
    }.getOrNull()
  }

  fun isArchive(path: String?): Boolean =
    path?.substringAfterLast('.', "")?.lowercase() in ARCHIVE_EXTENSIONS

  /**
   * A readable location for an archive entry: the archive name plus the entry's
   * folder path inside it, e.g. `Manga vol 1 › chapter 03`. Returns null when
   * [source] is not an archive entry.
   */
  fun displayPath(source: String?): String? {
    if (source == null) return null
    val separator = "!/"
    val idx = source.indexOf(separator)
    if (idx < 0) return null
    val archive = File(source.take(idx)).nameWithoutExtension
    val entry = source.substring(idx + separator.length).trim('/')
    val entryFolder = entry.substringBeforeLast('/', "")
    val fileName = entry.substringAfterLast('/')
    return buildList {
      add(archive)
      if (entryFolder.isNotBlank()) addAll(entryFolder.split('/').filter { it.isNotBlank() })
      add(File(fileName).nameWithoutExtension)
    }.joinToString(" › ")
  }
}
