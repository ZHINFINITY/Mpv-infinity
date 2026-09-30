package app.infinity.mpvz.domain.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookFolderTreeTest {
  @Test
  fun tracksUnderKeepsEachNestedFolderTogetherWhenFilenamesRepeat() {
    val root = "content://root/book"
    val discOne = "$root/Disc 1"
    val discTwo = "$root/Disc 2"
    val tree = AudiobookFolderTree(
      rootUri = root,
      listings = mapOf(
        root to AudiobookFolderListing(
          folder = folder(root, "Book"),
          parentUri = null,
          folders = listOf(folder(discOne, "Disc 1"), folder(discTwo, "Disc 2")),
          tracks = listOf(track("$root/00-intro.mp3", "00-intro.mp3")),
        ),
        discOne to AudiobookFolderListing(
          folder = folder(discOne, "Disc 1"),
          parentUri = root,
          folders = emptyList(),
          tracks = listOf(
            track("$discOne/01.mp3", "01.mp3"),
            track("$discOne/02.mp3", "02.mp3"),
          ),
        ),
        discTwo to AudiobookFolderListing(
          folder = folder(discTwo, "Disc 2"),
          parentUri = root,
          folders = emptyList(),
          tracks = listOf(
            track("$discTwo/01.mp3", "01.mp3"),
            track("$discTwo/02.mp3", "02.mp3"),
          ),
        ),
      ),
    )

    assertEquals(
      listOf(
        "$root/00-intro.mp3",
        "$discOne/01.mp3",
        "$discOne/02.mp3",
        "$discTwo/01.mp3",
        "$discTwo/02.mp3",
      ),
      tree.tracksUnder(root).map { it.uri },
    )
  }

  private fun folder(uri: String, name: String) = AudiobookFolderEntry(uri, name)

  private fun track(uri: String, name: String) = AudiobookFolderTrack(uri, name, "audio/mpeg", 1024L)
}
