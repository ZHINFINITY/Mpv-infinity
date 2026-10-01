package app.infinity.mpvz.ui.browser.audiobooks

/**
 * The selected SAF root feeds the All Books library; its navigable folder tree is a separate Folders pane.
 */
internal enum class AudiobookLibraryContentMode {
  ALL_BOOKS,
  FOLDERS,
  SAF_FOLDER_TREE,
}

internal fun resolveAudiobookLibraryContentMode(
  selectedTabIndex: Int,
  isFolderBrowser: Boolean,
  isAudiobookshelfSource: Boolean,
): AudiobookLibraryContentMode = when {
  selectedTabIndex == 0 -> AudiobookLibraryContentMode.ALL_BOOKS
  selectedTabIndex == 1 && isFolderBrowser && !isAudiobookshelfSource -> AudiobookLibraryContentMode.SAF_FOLDER_TREE
  else -> AudiobookLibraryContentMode.FOLDERS
}

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

/** Progress filters are shown in All Books and never inside either folder pane. */
internal fun shouldShowAudiobookProgressFilters(
  contentMode: AudiobookLibraryContentMode,
): Boolean = contentMode == AudiobookLibraryContentMode.ALL_BOOKS
