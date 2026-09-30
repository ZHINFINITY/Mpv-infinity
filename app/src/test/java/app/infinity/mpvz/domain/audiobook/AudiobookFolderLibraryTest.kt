package app.infinity.mpvz.domain.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookFolderLibraryTest {
  @Test
  fun logicalBookListingsIncludeNestedDiscsAndSeparateSiblingBooks() {
    val root = "content://root"
    val bookOne = "$root/book-one"
    val discOne = "$bookOne/disc-one"
    val discTwo = "$bookOne/disc-two"
    val bookTwo = "$root/book-two"
    val tree = AudiobookFolderTree(
      rootUri = root,
      listings = linkedMapOf(
        root to listing(root, "Library", listOf(bookOne, bookTwo)),
        bookOne to listing(bookOne, "Book One", listOf(discOne, discTwo), hasMetadata = true, parentUri = root),
        discOne to listing(discOne, "Disc One", tracks = listOf(track("$discOne/01.m4b", 10_000L)), parentUri = bookOne),
        discTwo to listing(discTwo, "Disc Two", tracks = listOf(track("$discTwo/02.m4b", 20_000L)), parentUri = bookOne),
        bookTwo to listing(bookTwo, "Book Two", tracks = listOf(track("$bookTwo/book.mp3", 30_000L)), parentUri = root),
      ),
    )

    assertEquals(listOf(bookOne, bookTwo), tree.bookListings().map { it.folder.uri })
    assertEquals(listOf("$discOne/01.m4b", "$discTwo/02.m4b"), tree.tracksForBook(bookOne).map { it.uri })
  }

  @Test
  fun directAudioParentOwnsNestedTracksWithoutDuplicatingDiscFolders() {
    val root = "content://root"
    val book = "$root/book"
    val disc = "$book/disc"
    val tree = AudiobookFolderTree(
      rootUri = root,
      listings = linkedMapOf(
        root to listing(root, "Library", listOf(book)),
        book to listing(book, "Book", listOf(disc), tracks = listOf(track("$book/intro.m4b", 4_000L)), parentUri = root),
        disc to listing(disc, "Disc", tracks = listOf(track("$disc/chapter.m4b", 6_000L)), parentUri = book),
      ),
    )

    assertEquals(listOf(book), tree.bookListings().map { it.folder.uri })
    assertEquals(listOf("$book/intro.m4b", "$disc/chapter.m4b"), tree.tracksForBook(book).map { it.uri })
  }

  @Test
  fun nestedMetadataBooksStaySeparateAndDoNotDuplicateMembership() {
    val root = "content://root"
    val outer = "$root/outer"
    val nested = "$outer/nested"
    val tree = AudiobookFolderTree(
      rootUri = root,
      listings = linkedMapOf(
        root to listing(root, "Library", listOf(outer)),
        outer to listing(outer, "Outer", listOf(nested), tracks = listOf(track("$outer/outer.m4b", 8_000L)), hasMetadata = true, parentUri = root),
        nested to listing(nested, "Nested", tracks = listOf(track("$nested/nested.m4b", 5_000L)), hasMetadata = true, parentUri = outer),
      ),
    )

    assertEquals(listOf(outer, nested), tree.bookListings().map { it.folder.uri })
    assertEquals(listOf("$outer/outer.m4b"), tree.tracksForBook(outer).map { it.uri })
    assertEquals(listOf("$nested/nested.m4b"), tree.tracksForBook(nested).map { it.uri })
  }

  @Test
  fun playbackHistoryBuildsWholeBookProgressAndFinishedStateWithoutRoomRows() {
    val tracks = listOf(track("content://book/one.m4b", 10_000L), track("content://book/two.m4b", 20_000L))
    val progress = calculateDirectAudiobookProgress(
      tracks = tracks,
      savedPositionMs = mapOf(tracks[0].uri to 10_000L, tracks[1].uri to 5_000L),
    )

    assertEquals(30_000L, progress.durationMs)
    assertEquals(15_000L, progress.progressMs)
    assertEquals(1, progress.currentTrackIndex)
    assertEquals(5_000L, progress.currentTrackPositionMs)
    assertFalse(progress.finished)

    val finished = calculateDirectAudiobookProgress(
      tracks = tracks,
      savedPositionMs = mapOf(tracks[0].uri to 10_000L, tracks[1].uri to 20_000L),
    )
    assertTrue(finished.finished)
  }

  @Test
  fun virtualRowIdsAreStableAndNeverOverlapPositiveRoomIds() {
    val first = stableVirtualAudiobookId("book", "stable-key")
    assertEquals(first, stableVirtualAudiobookId("book", "stable-key"))
    assertTrue(first < 0L)
  }

  private fun listing(
    uri: String,
    name: String,
    childUris: List<String> = emptyList(),
    tracks: List<AudiobookFolderTrack> = emptyList(),
    hasMetadata: Boolean = false,
    parentUri: String? = null,
  ) = AudiobookFolderListing(
    folder = AudiobookFolderEntry(uri, name),
    parentUri = parentUri,
    folders = childUris.map { AudiobookFolderEntry(it, it.substringAfterLast('/')) },
    tracks = tracks,
    hasBookMetadata = hasMetadata,
  )

  private fun track(uri: String, durationMs: Long) =
    AudiobookFolderTrack(uri, uri.substringAfterLast('/'), "audio/mpeg", 1_024L, durationMs)
}
