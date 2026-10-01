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
  fun selectedSafTreeKeepsEveryBookInAllBooksAndShowsNavigationOnlyOnFoldersTab() {
    val root = "content://root"
    val bookOne = "$root/book-one"
    val discOne = "$bookOne/disc-one"
    val discTwo = "$bookOne/disc-two"
    val bookTwo = "$root/book-two"
    val tree = AudiobookFolderTree(
      rootUri = root,
      listings = linkedMapOf(
        root to listing(root, "Library", childUris = listOf(bookOne, bookTwo)),
        bookOne to listing(bookOne, "Book One", childUris = listOf(discOne, discTwo), hasMetadata = true, parentUri = root),
        discOne to listing(discOne, "Disc One", tracks = listOf(track("$discOne/01.m4b")), parentUri = bookOne),
        discTwo to listing(discTwo, "Disc Two", tracks = listOf(track("$discTwo/02.m4b")), parentUri = bookOne),
        bookTwo to listing(bookTwo, "Book Two", tracks = listOf(track("$bookTwo/book.mp3")), parentUri = root),
      ),
    )
    val discoveredBooks = tree.bookListings()
    val libraryCards = discoveredBooks.map { Book(it.folder.name, progressMs = 0L, finished = false) }

    val allBooksMode = resolveAudiobookLibraryContentMode(
      selectedTabIndex = 0,
      isFolderBrowser = true,
      isAudiobookshelfSource = false,
    )
    assertEquals(AudiobookLibraryContentMode.ALL_BOOKS, allBooksMode)
    assertTrue(shouldShowAudiobookProgressFilters(allBooksMode))
    assertEquals(
      listOf("Book One", "Book Two"),
      filterAudiobooksByProgress(libraryCards, 3, Book::progressMs, Book::finished).map { it.name },
    )
    assertEquals(listOf(2, 1), discoveredBooks.map { tree.tracksForBook(it.folder.uri).size })

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

  private fun track(uri: String) =
    AudiobookFolderTrack(uri, uri.substringAfterLast('/'), "audio/mpeg", 1_024L, 10_000L)
}
