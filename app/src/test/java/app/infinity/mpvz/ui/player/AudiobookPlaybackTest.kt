package app.infinity.mpvz.ui.player

import app.infinity.mpvz.database.entities.AudiobookEntity
import app.infinity.mpvz.database.entities.AudiobookTrackEntity
import app.infinity.mpvz.domain.audiobook.AudiobookFolderTrack
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

  @Test
  fun queueItemRetainsTheLibraryTrackContentUriForDirectPlayback() {
    val book = AudiobookEntity(id = 42L, sourceKey = "test-tree", title = "Book", author = "Author")
    val trackUri = "content:tree/library/chapter.m4b"
    val track = AudiobookTrackEntity(
      id = 99L,
      bookId = book.id,
      uri = trackUri,
      fileName = "chapter.m4b",
      title = "Chapter",
      position = 0,
      durationMs = 120_000L,
    )

    val item = createAudiobookTrackPlaybackItem(book.id, book, track)

    assertEquals(trackUri, item.originalUri)
    assertEquals(trackUri, item.playableUri)
    assertEquals(AudiobookPlaybackInfo(book.id, track.id), item.audiobook)
  }

  @Test
  fun directAudiobookRestoresSavedPositionFromItsUriPlaybackState() {
    val item = createDirectAudiobookTrackPlaybackItem(
      queueIdentity = "selection-id",
      track = AudiobookFolderTrack(
        uri = "content://root/chapter.mp3",
        name = "chapter.mp3",
        mimeType = "audio/mpeg",
        sizeBytes = 1024L,
      ),
    )

    val restored = AudiobookPlayback.resolveDirectPositionForLoad(item, savedPositionSeconds = 73)

    assertEquals(73.0, restored!!.positionSeconds!!, 0.0)
    assertEquals(false, restored.paused)
    assertEquals(null, item.audiobook)
  }
}
