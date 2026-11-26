package app.infinity.mpvz.domain.archive

import java.io.File

/** Helpers for presenting media discovered inside archive files (zip/cbz). */
object ZipArchiveMedia {
  private val ARCHIVE_EXTENSIONS = setOf("zip", "cbz")

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
