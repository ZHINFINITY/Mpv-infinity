package app.infinity.mpvz.preferences

import app.infinity.mpvz.preferences.preference.PreferenceStore
import app.infinity.mpvz.preferences.preference.getEnum

enum class MusicSourceProvider(val id: String) {
  LOCAL("local"),
  JELLYFIN("jellyfin"),
  NAVIDROME("navidrome"),
  ;
  companion object {
    fun fromId(id: String): MusicSourceProvider = entries.firstOrNull { it.id == id } ?: LOCAL
  }
}

enum class AudiobookSourceProvider(val id: String) {
  LOCAL("local"),
  AUDIOBOOKSHELF("audiobookshelf"),
  ;
  companion object {
    fun fromId(id: String): AudiobookSourceProvider = entries.firstOrNull { it.id == id } ?: LOCAL
  }
}

class MediaServerPreferences(preferenceStore: PreferenceStore) {
  val musicSourceProvider = preferenceStore.getEnum("media_server_music_source_provider", MusicSourceProvider.LOCAL)
  val audiobookSourceProvider = preferenceStore.getEnum("media_server_audiobook_source_provider", AudiobookSourceProvider.LOCAL)
}
