package app.infinity.mpvz.domain.audiobook

import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class DirectAudiobookProgress(
  val durationMs: Long,
  val progressMs: Long,
  val currentTrackIndex: Int,
  val currentTrackPositionMs: Long,
  val finished: Boolean,
)

/** Aggregates the existing per-URI playback resume records without creating audiobook database rows. */
internal fun calculateDirectAudiobookProgress(
  tracks: List<AudiobookFolderTrack>,
  savedPositionMs: Map<String, Long>,
  savedDurationMs: Map<String, Long> = emptyMap(),
  finishedOverride: Boolean? = null,
): DirectAudiobookProgress {
  if (tracks.isEmpty()) return DirectAudiobookProgress(0L, 0L, -1, 0L, finishedOverride == true)
  val durations = tracks.map { track ->
    maxOf(track.durationMs, savedDurationMs[track.uri] ?: 0L).coerceAtLeast(0L)
  }
  val positions = tracks.mapIndexed { index, track ->
    (savedPositionMs[track.uri] ?: 0L).coerceIn(0L, durations[index])
  }
  val duration = durations.sum()
  val progress = positions.sum().coerceAtMost(duration)
  val currentIndex = tracks.indices.firstOrNull { index ->
    durations[index] == 0L || positions[index] < durations[index]
  } ?: tracks.lastIndex
  val finished = finishedOverride ?: (duration > 0L && duration - progress <= FINISHED_TOLERANCE_MS)
  return DirectAudiobookProgress(
    durationMs = duration,
    progressMs = if (finishedOverride == true) duration else progress,
    currentTrackIndex = currentIndex,
    currentTrackPositionMs = positions[currentIndex],
    finished = finished,
  )
}

/** Stable, negative IDs are reserved for virtual rows and never written to Room. */
internal fun stableVirtualAudiobookId(kind: String, identity: String): Long {
  val digest = MessageDigest.getInstance("SHA-256").digest("$kind\u0000$identity".toByteArray(Charsets.UTF_8))
  val positive = (ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long and Long.MAX_VALUE).coerceAtLeast(1L)
  return -positive
}

private const val FINISHED_TOLERANCE_MS = 5_000L
