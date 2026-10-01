package app.infinity.mpvz.ui.player

import app.infinity.mpvz.database.entities.AudiobookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookPlaybackTest {
  @Test
  fun restoresSavedTrackPositionAndHonorsExplicitStartOver() {
    val nowMs = 100_000L
    val info = AudiobookPlaybackInfo(bookId = 42L, trackId = 99L)
    val book =
      AudiobookEntity(
        id = info.bookId,
        sourceKey = "local:book",
        title = "Book",
        lastPlayedAt = nowMs - 4_000L,
        currentTrackId = info.trackId,
        positionMs = 42_000L,
        rewindSeconds = 10,
      )

    val resumed =
      AudiobookPlayback.resolvePositionForLoad(
        info = info,
        book = book,
        trackDurationMs = 120_000L,
        explicitBookId = -1L,
        explicitTrackId = -1L,
        explicitPositionMs = -1L,
        nowMs = nowMs,
      )
    assertEquals(42.0, resumed.positionSeconds!!, 0.0)

    val startOver =
      AudiobookPlayback.resolvePositionForLoad(
        info = info,
        book = book,
        trackDurationMs = 120_000L,
        explicitBookId = info.bookId,
        explicitTrackId = info.trackId,
        explicitPositionMs = 0L,
        nowMs = nowMs,
      )
    assertEquals(0.0, startOver.positionSeconds!!, 0.0)
  }
}
