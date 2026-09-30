package app.infinity.mpvz.ui.browser.audiobooks

/** Filters the complete local or remote book list without mixing in folder-tree contents. */
internal fun <T> filterAudiobooksByProgress(
  books: List<T>,
  selectedFilter: Int,
  progressMs: (T) -> Long,
  isFinished: (T) -> Boolean,
): List<T> = books.filter { book ->
  val progress = progressMs(book)
  val finished = isFinished(book)
  when (selectedFilter) {
    1 -> progress > 0 && !finished
    2 -> finished
    3 -> progress == 0L && !finished
    else -> true
  }
}

/** Progress chips belong to the All books library pane, never either folder browser. */
internal fun shouldShowAudiobookProgressFilters(
  isFoldersTab: Boolean,
  isFolderTreeBrowser: Boolean,
): Boolean = !isFoldersTab && !isFolderTreeBrowser
