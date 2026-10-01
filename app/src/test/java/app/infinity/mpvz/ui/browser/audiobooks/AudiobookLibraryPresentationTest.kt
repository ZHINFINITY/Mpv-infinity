package app.infinity.mpvz.ui.browser.audiobooks

import app.infinity.mpvz.domain.audiobook.AudiobookFolderEntry
import app.infinity.mpvz.domain.audiobook.AudiobookFolderListing
import app.infinity.mpvz.domain.audiobook.AudiobookFolderTrack
import app.infinity.mpvz.domain.audiobook.AudiobookFolderTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookLibraryPresentationTest {
  private data class Book(val name: String, val progressMs: Long, val finished: Boolean)

  private val completeLibrary = listOf(
    Book("Not started", 0L, false),
    Book("In progress", 1200L, false),
    Book("Finished", 2400L, true),
  )

  @Test
  fun allBooksFilterReturnsTheCompleteLibraryAndProgressFiltersOnlyNarrowIt() {
    assertEquals(completeLibrary, filterAudiobooksByProgress(completeLibrary, 0, Book::progressMs, Book::finished))
    assertEquals(listOf(completeLibrary[1]), filterAudiobooksByProgress(completeLibrary, 1, Book::progressMs, Book::finished))
    assertEquals(listOf(completeLibrary[2]), filterAudiobooksByProgress(completeLibrary, 2, Book::progressMs, Book::finished))
    assertEquals(listOf(completeLibrary[0]), filterAudiobooksByProgress(completeLibrary, 3, Book::progressMs, Book::finished))
  }

  @Test
  fun flatSelectedRootShowsEveryAlbumGroupedAndNestedBookOnAllBooksOnly() {
    val root = "content://root/audio-books"
    val nested = "$root/nested-book"
    val rootTracks = listOf(
      track("$root/unrelated-alpha-name.mp3", album = "Album Alpha", title = "Chapter Alpha"),
      track("$root/unrelated-beta-name.mp3", album = "Album Beta", title = "Chapter Beta"),
      track("$root/Fallback_Book-Part-01.m4b", album = "unknown", title = "Track Title"),
    )
    val nestedTracks = listOf(
      track("$nested/01.m4b", album = "Nested Album", title = "Chapter 1"),
      track("$nested/02.m4b", album = "Nested Album", title = "Chapter 2"),
    )
    val tree = AudiobookFolderTree(
      rootUri = root,
      listings = linkedMapOf(
        root to listing(root, "Audio Books", childUris = listOf(nested), tracks = rootTracks),
        nested to listing(nested, "Nested Book", tracks = nestedTracks, hasMetadata = true, parentUri = root),
      ),
    )
    val discoveredBooks = tree.virtualBookListings()
    val names = listOf("Album Alpha", "Album Beta", "Fallback Book", "Nested Book")
    assertEquals(names, discoveredBooks.map { it.title })
    assertEquals(
      listOf(listOf(rootTracks[0].uri), listOf(rootTracks[1].uri), listOf(rootTracks[2].uri), nestedTracks.map { it.uri }),
      discoveredBooks.map { it.tracks.map(AudiobookFolderTrack::uri) },
    )
    assertEquals(listOf(nested), tree.listing(root)?.folders?.map { it.uri })

    val cards = discoveredBooks.mapIndexed { index, book ->
      when (index) {
        1 -> Book(book.title, progressMs = 1_200L, finished = false)
        3 -> Book(book.title, progressMs = 2_400L, finished = true)
        else -> Book(book.title, progressMs = 0L, finished = false)
      }
    }
    val allBooksMode = resolveAudiobookLibraryContentMode(
      selectedTabIndex = 0,
      isFolderBrowser = true,
      isAudiobookshelfSource = false,
    )
    assertEquals(AudiobookLibraryContentMode.ALL_BOOKS, allBooksMode)
    assertTrue(shouldShowAudiobookProgressFilters(allBooksMode))
    assertEquals(names, filterAudiobooksByProgress(cards, 0, Book::progressMs, Book::finished).map { it.name })
    assertEquals(
      listOf("Album Alpha", "Fallback Book"),
      filterAudiobooksByProgress(cards, 3, Book::progressMs, Book::finished).map { it.name },
    )

    val foldersMode = resolveAudiobookLibraryContentMode(
      selectedTabIndex = 1,
      isFolderBrowser = true,
      isAudiobookshelfSource = false,
    )
    assertEquals(AudiobookLibraryContentMode.SAF_FOLDER_TREE, foldersMode)
    assertFalse(shouldShowAudiobookProgressFilters(foldersMode))
    assertEquals(
      AudiobookLibraryContentMode.FOLDERS,
      resolveAudiobookLibraryContentMode(
        selectedTabIndex = 1,
        isFolderBrowser = false,
        isAudiobookshelfSource = false,
      ),
    )
  }

  @Test
  fun progressFiltersBelongOnlyToTheAllBooksPane() {
    assertTrue(shouldShowAudiobookProgressFilters(AudiobookLibraryContentMode.ALL_BOOKS))
    assertFalse(shouldShowAudiobookProgressFilters(AudiobookLibraryContentMode.FOLDERS))
    assertFalse(shouldShowAudiobookProgressFilters(AudiobookLibraryContentMode.SAF_FOLDER_TREE))
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

  private fun track(uri: String, album: String? = null, title: String? = null) = AudiobookFolderTrack(
    uri = uri,
    name = uri.substringAfterLast('/'),
    mimeType = "audio/mpeg",
    sizeBytes = 1_024L,
    durationMs = 10_000L,
    title = title,
    album = album,
  )
}
