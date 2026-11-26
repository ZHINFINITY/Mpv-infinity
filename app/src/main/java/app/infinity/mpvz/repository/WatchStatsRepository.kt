package app.infinity.mpvz.repository

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WatchMediaStats(
  val title: String,
  val isAudio: Boolean,
  val seconds: Long,
  val artworkUri: String? = null,
  val sourceUri: String? = null,
)

@Serializable
data class WatchStatsSnapshot(
  val totalSeconds: Long = 0L,
  val sessions: Long = 0L,
  val videoSeconds: Long = 0L,
  val audioSeconds: Long = 0L,
  val days: Map<String, Long> = emptyMap(),
  val media: Map<String, WatchMediaStats> = emptyMap(),
)

/**
 * File-backed watch-time statistics, shared by the profile watch-stats screen.
 * Playback sessions call [record] when a media item finishes a segment; the
 * screen reads [snapshot] reactively via [revision].
 */
class WatchStatsRepository(
  context: Context,
  private val json: Json,
) {
  private val file = File(context.filesDir, "watch_stats.json")
  private val mutex = Mutex()
  private val state = MutableStateFlow(load())

  val revision: StateFlow<Long> = MutableStateFlow(0L)

  fun snapshot(): WatchStatsSnapshot = state.value

  suspend fun record(
    title: String,
    isAudio: Boolean,
    seconds: Long,
    artworkUri: String? = null,
    sourceUri: String? = null,
  ) {
    if (seconds <= 0L || title.isBlank()) return
    mutex.withLock {
      val current = state.value
      val key = "${if (isAudio) "a" else "v"}:${title.lowercase()}"
      val media = current.media[key]
      val updated =
        current.copy(
          totalSeconds = current.totalSeconds + seconds,
          sessions = current.sessions + 1,
          videoSeconds = if (isAudio) current.videoSeconds else current.videoSeconds + seconds,
          audioSeconds = if (isAudio) current.audioSeconds + seconds else current.audioSeconds,
          days = mergeDay(current.days, seconds),
          media =
            current.media +
              (
                key to
                  WatchMediaStats(
                    title = title,
                    isAudio = isAudio,
                    seconds = (media?.seconds ?: 0L) + seconds,
                    artworkUri = artworkUri ?: media?.artworkUri,
                    sourceUri = sourceUri ?: media?.sourceUri,
                  )
              ),
        )
      state.value = updated
      persist(updated)
    }
  }

  suspend fun clear() {
    mutex.withLock {
      state.value = WatchStatsSnapshot()
      persist(state.value)
    }
  }

  private fun mergeDay(days: Map<String, Long>, seconds: Long): Map<String, Long> {
    val today = java.time.LocalDate.now().toString()
    return days + (today to ((days[today] ?: 0L) + seconds))
  }

  private fun load(): WatchStatsSnapshot =
    runCatching {
      if (file.exists()) json.decodeFromString<WatchStatsSnapshot>(file.readText())
      else WatchStatsSnapshot()
    }.getOrDefault(WatchStatsSnapshot())

  private suspend fun persist(snapshot: WatchStatsSnapshot) {
    withContext(Dispatchers.IO) {
      runCatching {
        file.writeText(json.encodeToString(snapshot))
      }
    }
  }
}
