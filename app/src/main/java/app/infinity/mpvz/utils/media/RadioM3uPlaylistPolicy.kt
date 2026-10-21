/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.utils.media

/** Category policy for playlist UI; deliberately conservative so TV/video M3U stays ordinary. */
object RadioM3uPlaylistPolicy {
  const val RADIO_PREFIX = "Radio/M3U "

  fun isRadio(result: M3UParseResult.Success): Boolean {
    if (result.items.isEmpty() || !M3URoutingPolicy.isMusicPlaylist(result)) return false
    val hasLiveEntry = result.items.any { it.duration < 0 }
    val radioWords = Regex("\\b(radio|icecast|shoutcast|station)\\b", RegexOption.IGNORE_CASE)
    return hasLiveEntry && (radioWords.containsMatchIn(result.playlistName) || result.items.any { item ->
      sequenceOf(item.title, item.groupTitle, item.tvgName, item.url).filterNotNull().any(radioWords::containsMatchIn)
    })
  }

  fun displayName(name: String): String = if (name.startsWith(RADIO_PREFIX)) name else RADIO_PREFIX + name
  fun isStoredRadioName(name: String): Boolean = name.startsWith(RADIO_PREFIX)
}
