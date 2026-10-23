package app.infinity.mpvz.utils.media

import org.junit.Assert.assertEquals
import org.junit.Test

class M3USourceUrlTest {
  @Test
  fun convertsReportedGitlabFileViewToRawPlaylist() {
    assertEquals(
      "https://gitlab.com/marmota/poweramp-radio-cli/-/raw/main/flacradio.m3u",
      M3USourceUrl.normalize(" https://gitlab.com/marmota/poweramp-radio-cli/-/blob/main/flacradio.m3u "),
    )
  }

  @Test
  fun keepsEscapedPathsQueriesAndFragments() {
    assertEquals(
      "https://gitlab.com/group/sub/project/-/raw/feature/radio%20list.M3U8?inline=false#top",
      M3USourceUrl.normalize("https://gitlab.com/group/sub/project/-/blob/feature/radio%20list.M3U8?inline=false#top"),
    )
  }

  @Test
  fun doesNotRewriteOtherHostsFilesOrExistingRawUrls() {
    listOf(
      "https://gitlab.com/g/p/-/raw/main/radio.m3u",
      "https://gitlab.com/g/p/-/blob/main/readme.md",
      "https://gitlab.com.evil.example/g/p/-/blob/main/radio.m3u",
      "https://radio.example/-/blob/main/radio.m3u",
      "file:///tmp/radio.m3u",
      "not a URL",
    ).forEach { assertEquals(it, M3USourceUrl.normalize(it)) }
  }
}
