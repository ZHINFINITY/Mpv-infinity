package app.infinity.mpvz.ui.player.ytdlp

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubCloudLinkExtractorTest {
  private val mediaUrl =
    "https://video-downloads.googleusercontent.com/videoplayback?id=sample&token=a%2Bb"

  private fun wrapper(url: String = mediaUrl): String =
    "https://cryptonewz.online/dl.php?link=" + URLEncoder.encode(url, StandardCharsets.UTF_8.name())

  @Test
  fun unwrapsOnlyTheObservedCryptonewzMediaHost() {
    assertEquals(mediaUrl, HubCloudLinkExtractor.unwrapCryptonewzDownloadUrl(wrapper()))
  }

  @Test
  fun rejectsWrongWrapperHostPathOrScheme() {
    val encoded = URLEncoder.encode(mediaUrl, StandardCharsets.UTF_8.name())
    assertNull(HubCloudLinkExtractor.unwrapCryptonewzDownloadUrl("https://evil.example/dl.php?link=$encoded"))
    assertNull(HubCloudLinkExtractor.unwrapCryptonewzDownloadUrl("https://cryptonewz.online/other?link=$encoded"))
    assertNull(HubCloudLinkExtractor.unwrapCryptonewzDownloadUrl("http://cryptonewz.online/dl.php?link=$encoded"))
  }

  @Test
  fun rejectsUntrustedNestedHostAndPlainHttpMediaUrl() {
    assertNull(
      HubCloudLinkExtractor.unwrapCryptonewzDownloadUrl(
        wrapper("https://evil.example/video.mkv"),
      ),
    )
    assertNull(
      HubCloudLinkExtractor.unwrapCryptonewzDownloadUrl(
        wrapper("http://video-downloads.googleusercontent.com/video.mkv"),
      ),
    )
  }

  @Test
  fun allowsOnlyTheObservedHubCloudRedirectHosts() {
    assertTrue(HubCloudLinkExtractor.isAllowedResolverHost("gpdl.hubcloud.ist"))
    assertTrue(HubCloudLinkExtractor.isAllowedResolverHost("gpdl.rohitkiskk.workers.dev"))
    assertTrue(HubCloudLinkExtractor.isAllowedResolverHost("cryptonewz.online"))
    assertTrue(HubCloudLinkExtractor.isAllowedResolverHost("video-downloads.googleusercontent.com"))
    assertFalse(HubCloudLinkExtractor.isAllowedResolverHost("attacker.example"))
    assertTrue(HubCloudLinkExtractor.isAllowedResolverUrl("https://gpdl.hubcloud.ist/?id=sample"))
    assertFalse(HubCloudLinkExtractor.isAllowedResolverUrl("http://gpdl.hubcloud.ist/?id=sample"))
    assertFalse(HubCloudLinkExtractor.isAllowedResolverUrl("https://attacker.example/redirect"))
  }

  @Test
  fun usesCryptonewzRefererOnlyForTheObservedResolvedMediaPair() {
    assertTrue(
      HubCloudLinkExtractor.shouldUseCryptonewzReferer(
        "https://gpdl.hubcloud.ist/?id=sample",
        mediaUrl,
      ),
    )
    assertFalse(
      HubCloudLinkExtractor.shouldUseCryptonewzReferer(
        "https://other.example/?id=sample",
        mediaUrl,
      ),
    )
    assertFalse(
      HubCloudLinkExtractor.shouldUseCryptonewzReferer(
        "https://gpdl.hubcloud.ist/?id=sample",
        "https://evil.example/video.mkv",
      ),
    )
  }
}
