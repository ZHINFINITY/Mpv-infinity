/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.utils.media

enum class M3URoute { PLAYER, MUSIC }

/** M3U is a container, not a media type. Unknown TV/live streams stay on the video path. */
object M3URoutingPolicy {
  private val audioExtensions = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "aiff", "alac", "weba")
  private val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mpeg", "mpg", "3gp", "m3u8", "m3u")
  // Generic "live", "stream", "station" and "AM" are not evidence of audio.
  private val radioWords = Regex("\\b(radio|icecast|shoutcast)\\b", RegexOption.IGNORE_CASE)

  fun route(result: M3UParseResult.Success): M3URoute =
    if (isMusicPlaylist(result)) M3URoute.MUSIC else M3URoute.PLAYER

  fun isMusicPlaylist(result: M3UParseResult.Success, declaredAudio: Boolean = false): Boolean {
    if (result.items.isEmpty()) return false
    var allKnownAudio = true
    var radioMetadata = radioWords.containsMatchIn(result.playlistName)
    var liveEntries = false
    for (item in result.items) {
      val ext = extension(item.url.substringBefore('|').substringBefore('?').substringBefore('#'))
      if (ext in videoExtensions) return false
      if (ext !in audioExtensions) allKnownAudio = false
      if (item.duration < 0) liveEntries = true
      if (!radioMetadata) {
        radioMetadata = sequenceOf(item.title, item.groupTitle, item.tvgName, item.url)
          .filterNotNull().any { radioWords.containsMatchIn(it) }
      }
    }
    return allKnownAudio || (radioMetadata && liveEntries) || declaredAudio
  }

  private fun extension(value: String): String =
    value.substringAfterLast('/').substringAfterLast('.').lowercase()
      .takeIf { it.length in 2..5 }.orEmpty()
}
