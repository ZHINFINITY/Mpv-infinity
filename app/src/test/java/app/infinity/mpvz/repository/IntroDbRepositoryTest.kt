package app.infinity.mpvz.repository

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroDbRepositoryTest {
  private val repository = IntroDbRepository(OkHttpClient(), Json)

  @Test
  fun theIntroDbMediaUrlUsesV4AndPreservesTvdbCompatibleLookupCoordinates() {
    val url = repository.buildTheIntroDbMediaUrl(
      tmdbId = 123,
      mediaType = "tv",
      season = 2,
      episode = 7,
      mapping = "dvd",
    )

    assertEquals("https", url.scheme)
    assertEquals("api.theintrodb.org", url.host)
    assertEquals("/v4/media", url.encodedPath)
    assertEquals("123", url.queryParameter("tmdb_id"))
    assertEquals("2", url.queryParameter("season"))
    assertEquals("7", url.queryParameter("episode"))
    assertEquals("dvd", url.queryParameter("mapping"))
  }

  @Test
  fun v4SegmentShapeUsesSegmentTypeAndMillisecondBounds() {
    val segment = IntroDbSegment(
      segmentType = "intro",
      start = 12.5,
      end = 45.0,
    )

    assertEquals("intro", segment.segmentType)
    assertTrue(segment.hasTimingBounds)
    assertEquals(12.5, segment.normalizedStart, 0.0)
    assertEquals(45.0, segment.normalizedEnd, 0.0)
  }
}
