package app.infinity.mpvz.domain.audiobook

import java.util.Locale

internal data class AudiobookFileGroup<T>(
  val key: String,
  val title: String,
  val items: List<T>,
)

/** Mirrors the approved folder-import rule: usable album tag first, sanitized filename otherwise. */
internal fun <T> groupAudiobookItemsByAlbumOrFilename(
  items: List<T>,
  fileName: (T) -> String,
  album: (T) -> String?,
  identity: (T) -> String,
): List<AudiobookFileGroup<T>> {
  val genericAlbumNames = setOf("unknown", "audiobook", "audio book", "track", "untitled")
  val tagged = items.map { item ->
    val name = fileName(item)
    val albumTitle = album(item).orEmpty().trim()
    val usableAlbum = albumTitle.takeIf {
      it.isNotBlank() && normalizeBookTitle(it) !in genericAlbumNames
    }
    val title = cleanBookTitle(usableAlbum ?: name).ifBlank { name }
    val key = normalizeBookTitle(title).ifBlank { identity(item) }
    Triple(key, title, item)
  }
  return tagged.groupBy { it.first }.map { (key, group) ->
    AudiobookFileGroup(key, group.first().second, group.map { it.third })
  }.sortedBy { it.key }
}

private fun cleanBookTitle(value: String): String = value.substringBeforeLast('.', value)
  .replace(Regex("(?i)[\\s._-]*\\d+(?:\\.\\d+)?\\s*(?:kb(?:ps?)?|kbit(?:/s)?|khz|mb(?:ps?)?)\\b.*$"), "")
  .replace(Regex("(?i)[\\s._-]*(?:part|pt|disc|disk|cd|track|chapter)[\\s._-]*\\d+$"), "")
  .replace(Regex("([a-z])([A-Z])"), "$1 $2")
  .replace('_', ' ')
  .replace('-', ' ')
  .replace(Regex("\\s+"), " ")
  .trim()

private fun normalizeBookTitle(value: String): String = cleanBookTitle(value)
  .lowercase(Locale.ROOT)
  .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
  .trim()
