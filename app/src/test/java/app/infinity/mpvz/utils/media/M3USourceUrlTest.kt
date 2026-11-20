package app.infinity.mpvz.utils.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

  @Test
  fun canonicalizesEquivalentAbsolutePathAndFileUri() {
    val file = File("/tmp/radio list.m3u")
    assertEquals(
      M3UParser.sourceCacheKey(file.absolutePath),
      M3UParser.sourceCacheKey(file.toURI().toASCIIString()),
    )
  }

  @Test
  fun normalizesUppercaseFileSchemeAndPreservesEncodedQueryAndFragment() {
    assertEquals(
      "file:/tmp/radio%20list.m3u?token=a%2Fb#part%20one",
      M3UParser.sourceCacheKey("FILE:/tmp/radio%20list.m3u?token=a%2Fb#part%20one"),
    )
  }

  @Test
  fun leavesRelativePlaylistPathsUnchanged() {
    assertEquals("lists/radio.m3u", M3UParser.sourceCacheKey("lists/radio.m3u"))
  }

  @Test
  fun normalizesSchemesAtProviderM3uAndDisplayNameBoundaries() {
    val mainRoot = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val playerUtils = File(mainRoot, "app/infinity/mpvz/ui/player/PlayerUtils.kt").readText()
    val playerActivity = File(mainRoot, "app/infinity/mpvz/ui/player/PlayerActivity.kt").readText()
    val parser = File(mainRoot, "app/infinity/mpvz/utils/media/M3UParser.kt").readText()
    val descriptorPath = playerUtils.substringAfter("private fun Uri.tryFileDescriptorPath(").substringBefore("/**")

    assertTrue(playerUtils.contains("val normalizedUri = normalizeScheme()"))
    assertTrue(playerUtils.contains("when (normalizedScheme)"))
    assertTrue(playerUtils.contains("resolved.closeDetachedFileDescriptorIfNeeded()"))
    assertTrue(playerUtils.contains("fallback?.closeDetachedFileDescriptorIfNeeded()"))
    assertTrue(descriptorPath.contains("finally {"))
    assertTrue(descriptorPath.contains("if (!detached) pfd.close()"))
    assertTrue(playerActivity.contains("uri.normalizeScheme()"))
    assertTrue(playerActivity.contains("Uri.parse(uriString).normalizeScheme()"))
    assertTrue(parser.contains("val normalizedUri = uri.normalizeScheme()"))
  }

  @Test
  fun providerCancellationIsRecordedBeforeLateStreamPublicationAndParsing() {
    val mainRoot = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val parser = File(mainRoot, "app/infinity/mpvz/utils/media/M3UParser.kt").readText()
    val providerIo = File(mainRoot, "app/infinity/mpvz/utils/media/BoundedProviderIo.kt").readText()
    val providerRead =
      parser.substringAfter("suspend fun parseFromUri(")
        .substringBefore("suspend fun parseFromStream(")
    val streamPublished = providerRead.indexOf("streamRef.set(stream)")
    val cancellationCheck = providerRead.indexOf("if (providerReadCancelled.get())", streamPublished)
    val parseCall = providerRead.indexOf("parseFromStreamBlocking(stream, sourceUrl, playlistName)")

    assertTrue(providerRead.contains("onCancellationRequested = { providerReadCancelled.set(true) }"))
    assertTrue(streamPublished >= 0 && cancellationCheck > streamPublished && parseCall > cancellationCheck)
    assertTrue(providerRead.contains("streamRef.getAndSet(null)?.let(::closeProviderStreamAsync)"))
    assertTrue(providerIo.contains("runCatching { onCancellationRequested() }"))
  }
}
