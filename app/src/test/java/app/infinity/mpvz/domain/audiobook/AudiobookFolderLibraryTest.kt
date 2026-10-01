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
  fun directAudioParentAndUnlabelledNestedAudioFolderAreSeparateBooksLikeTheReferenceImporter() {
    val root = "content://root"
    val book = "$root/book"
    val nestedBook = "$book/nested-book"
    val tree = AudiobookFolderTree(
      rootUri = root,
      listings = linkedMapOf(
        root to listing(root, "Library", listOf(book)),
        book to listing(book, "Book", listOf(nestedBook), tracks = listOf(track("$book/intro.m4b", 4_000L)), parentUri = root),
        nestedBook to listing(nestedBook, "Nested Book", tracks = listOf(track("$nestedBook/chapter.m4b", 6_000L)), parentUri = book),
      ),
    )

    assertEquals(listOf(book, nestedBook), tree.bookListings().map { it.folder.uri })
    assertEquals(listOf("$book/intro.m4b"), tree.tracksForBook(book).map { it.uri })
    assertEquals(listOf("$nestedBook/chapter.m4b"), tree.tracksForBook(nestedBook).map { it.uri })
  }

  @Test
  fun flatRootSplitsThreeLooseAudioFilesAndKeepsNestedBookSeparateWithStableIdentities() {
    val root = "content://root/audio-books"
    val nestedBook = "$root/nested-book"
    val alpha = track(
      uri = "$root/unrelated-alpha-name.mp3",
      durationMs = 10_000L,
      album = "Album Alpha",
      title = "Chapter Alpha",
    )
    val beta = track(
      uri = "$root/unrelated-beta-name.mp3",
      durationMs = 12_000L,
      album = "Album Beta",
      title = "Chapter Beta",
    )
    val fallback = track(
      uri = "$root/Fallback_Book-Part-01.m4b",
      durationMs = 14_000L,
      album = "unknown",
      title = "Track Title Is Not The Book Grouping Key",
    )
    val nestedTracks = listOf(
      track("$nestedBook/01.m4b", 16_000L, album = "Nested Album", title = "Chapter 1"),
      track("$nestedBook/02.m4b", 18_000L, album = "Nested Album", title = "Chapter 2"),
    )
    val tree = AudiobookFolderTree(
      rootUri = root,
      listings = linkedMapOf(
        root to listing(root, "Audio Books", listOf(nestedBook), tracks = listOf(alpha, beta, fallback)),
        nestedBook to listing(nestedBook, "Nested Book", tracks = nestedTracks, hasMetadata = true, parentUri = root),
      ),
    )

    val books = tree.virtualBookListings()
    assertEquals(listOf(root, nestedBook), tree.bookListings().map { it.folder.uri })
    assertEquals(listOf("Album Alpha", "Album Beta", "Fallback Book", "Nested Book"), books.map { it.title })
    assertEquals(
      listOf(
        listOf(alpha.uri),
        listOf(beta.uri),
        listOf(fallback.uri),
        nestedTracks.map { it.uri },
      ),
      books.map { it.tracks.map(AudiobookFolderTrack::uri) },
    )
    assertEquals(listOf(alpha.uri, beta.uri, fallback.uri), tree.tracksForBook(root).map { it.uri })

    val sourceIdentities = books.map { book ->
      directAudiobookFolderIdentity(book.folder.uri) + (book.groupIdentity?.let { "#audiobook-$it" } ?: "")
    }
    val queueIdentities = books.map { book ->
      book.groupIdentity ?: directAudiobookFolderIdentity(book.folder.uri)
    }
    assertEquals(4, sourceIdentities.distinct().size)
    assertEquals(4, queueIdentities.distinct().size)
    assertEquals(4, sourceIdentities.map { stableVirtualAudiobookId("book", it) }.distinct().size)
    assertEquals(directAudiobookSelectionIdentity(listOf(beta.uri)), books[1].groupIdentity)
    assertEquals(directAudiobookSelectionIdentity(listOf(fallback.uri)), books[2].groupIdentity)
    assertTrue(books[0].groupIdentity == null)
    assertTrue(books[3].groupIdentity == null)
  }

  @Test
  fun sameUsableAlbumTagGroupsTracksTogether() {
    val first = track("content://root/first-file.mp3", 1_000L, album = "Shared Album")
    val second = track("content://root/second-file.mp3", 1_000L, album = "shared album")

    val groups = groupAudiobookItemsByAlbumOrFilename(
      items = listOf(first, second),
      fileName = AudiobookFolderTrack::name,
      album = AudiobookFolderTrack::album,
      identity = AudiobookFolderTrack::uri,
    )

    assertEquals(1, groups.size)
    assertEquals("Shared Album", groups.single().title)
    assertEquals(listOf(first.uri, second.uri), groups.single().items.map { it.uri })
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

  private fun track(
    uri: String,
    durationMs: Long,
    name: String = uri.substringAfterLast('/'),
    album: String? = null,
    title: String? = null,
  ) = AudiobookFolderTrack(
    uri = uri,
    name = name,
    mimeType = "audio/mpeg",
    sizeBytes = 1_024L,
    durationMs = durationMs,
    title = title,
    album = album,
  )
}
