package app.infinity.mpvz.ui.browser.audiobooks

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
  fun progressFiltersBelongOnlyToAllBooksAndDoNotBleedIntoEitherFolderView() {
    assertTrue(shouldShowAudiobookProgressFilters(isFoldersTab = false, isFolderTreeBrowser = false))
    assertFalse(shouldShowAudiobookProgressFilters(isFoldersTab = true, isFolderTreeBrowser = false))
    assertFalse(shouldShowAudiobookProgressFilters(isFoldersTab = false, isFolderTreeBrowser = true))
  }
}
