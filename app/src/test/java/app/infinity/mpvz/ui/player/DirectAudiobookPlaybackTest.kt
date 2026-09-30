package app.infinity.mpvz.ui.player

import app.infinity.mpvz.domain.audiobook.AudiobookFolderTrack
import app.infinity.mpvz.domain.audiobook.directAudiobookFolderIdentity
import app.infinity.mpvz.domain.audiobook.directAudiobookSelectionIdentity
import app.infinity.mpvz.domain.audiobook.directAudiobookTrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectAudiobookPlaybackTest {
  @Test
  fun folderQueueItemKeepsTheSafUriAndExistingPlaybackResumeIdentity() {
    val folderUri = "content://storage/tree/books/document/book"
    val queueIdentity = directAudiobookFolderIdentity(folderUri)
    val trackUri = "content://storage/tree/books/document/book%2Fchapter-01.m4b"
    val track = AudiobookFolderTrack(trackUri, "Chapter 01.m4b", null, 1024L)

    val item = createDirectAudiobookTrackPlaybackItem(queueIdentity, track)

    assertEquals(trackUri, item.originalUri)
    assertEquals(trackUri, item.playableUri)
    assertEquals("Chapter 01", item.title)
    assertEquals("audio/*", item.mimeType)
    assertEquals(PlaybackIdentity.forUri(trackUri), item.stableId)
    assertEquals(queueIdentity, item.directAudiobook?.queueIdentity)
    assertEquals(directAudiobookTrackIdentity(trackUri), item.directAudiobook?.trackIdentity)
    assertNotNull(item.directAudiobook)
  }

  @Test
  fun directFolderQueueReferencesSourceUrisWithoutImportingOrCopyingAudio() {
    val tracks = listOf(
      AudiobookFolderTrack("content://storage/chapter-01.m4b", "chapter-01.m4b", "audio/mpeg", 10L),
      AudiobookFolderTrack("content://storage/chapter-02.m4b", "chapter-02.m4b", "audio/mpeg", 20L),
    )
    val queue = buildDirectAudiobookQueue(directAudiobookFolderIdentity("content://storage/tree/book"), tracks)

    assertEquals(tracks.map { it.uri }, queue.map { it.originalUri })
    assertEquals(tracks.map { it.uri }, queue.map { it.playableUri })
  }

  @Test
  fun selectedFilesUseAStableUriQueueAndDoNotCreateLibraryRowsOrAudioCopies() {
    val tracks = listOf(
      AudiobookFolderTrack("content://storage/selected-1.m4b", "selected-1.m4b", "audio/mpeg", 10L),
      AudiobookFolderTrack("content://storage/selected-2.m4b", "selected-2.m4b", "audio/mpeg", 20L),
    )
    val queueIdentity = directAudiobookSelectionIdentity(tracks.map { it.uri })
    val queue = buildDirectAudiobookQueue(queueIdentity, tracks)

    assertEquals(queueIdentity, directAudiobookSelectionIdentity(tracks.reversed().map { it.uri }))
    assertTrue(queueIdentity.none { it == '/' })
    assertEquals(tracks.map { it.uri }, queue.map { it.originalUri })
    assertEquals(tracks.map { it.uri }, queue.map { it.playableUri })
    assertTrue(queue.all { it.stableId == PlaybackIdentity.forUri(it.originalUri) })
  }

  @Test
  fun folderResumeUsesTheSavedTrackAndFallsBackToTheFirstTrackWhenMissing() {
    val queueIdentity = directAudiobookFolderIdentity("content://storage/tree/book")
    val tracks = listOf("one.m4b", "two.m4b").mapIndexed { index, name ->
      createDirectAudiobookTrackPlaybackItem(
        queueIdentity,
        AudiobookFolderTrack("content://storage/$name", name, "audio/mpeg", index.toLong()),
      )
    }

    assertEquals(1, directAudiobookResumeIndex(tracks, tracks[1].directAudiobook?.trackIdentity))
    assertEquals(0, directAudiobookResumeIndex(tracks, "no-longer-present"))
    assertEquals(0, directAudiobookResumeIndex(tracks, null))
  }
}
