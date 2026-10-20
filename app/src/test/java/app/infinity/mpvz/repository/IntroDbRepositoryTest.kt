/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.repository

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
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
  fun movieImdbLookupDoesNotAddTvArguments() {
    val url = repository.buildTheIntroDbMediaUrl(
      imdbId = "tt1234567",
      mediaType = "movie",
      season = null,
      episode = null,
      mapping = "dvd",
    )

    assertEquals("tt1234567", url.queryParameter("imdb_id"))
    assertEquals(null, url.queryParameter("season"))
    assertEquals(null, url.queryParameter("episode"))
    assertEquals(null, url.queryParameter("mapping"))
  }

  @Test
  fun specialsRetainSeasonZeroAndMapping() {
    val url = repository.buildTheIntroDbMediaUrl(
      imdbId = "tt1234567",
      mediaType = "tv",
      season = 0,
      episode = 1,
      mapping = "aired",
    )

    assertEquals("0", url.queryParameter("season"))
    assertEquals("1", url.queryParameter("episode"))
    assertEquals("aired", url.queryParameter("mapping"))
  }

  @Test
  fun v4FixtureParsesTvdbSegmentsAndConvertsMillisecondsToSeconds() {
    val body = """
      {
        "tvdb_id": 77889,
        "type": "tv",
        "season": 2,
        "episode": 7,
        "intro": [{"start_ms": 1250, "end_ms": 45250}],
        "recap": [{"start_ms": 0, "end_ms": 8000}],
        "credits": [{"start_ms": 121000, "end_ms": 145500}],
        "preview": [{"start_ms": 145500, "end_ms": 148250}]
      }
    """.trimIndent()

    val segments = repository.parseTheIntroDbMediaBody(body)

    assertEquals(listOf("intro", "recap", "credits", "preview"), segments.map { it.segmentType })
    assertEquals(1.25, segments[0].normalizedStart, 0.0)
    assertEquals(45.25, segments[0].normalizedEnd, 0.0)
    assertEquals(0.0, segments[1].normalizedStart, 0.0)
    assertEquals(8.0, segments[1].normalizedEnd, 0.0)
    assertEquals(121.0, segments[2].normalizedStart, 0.0)
    assertEquals(145.5, segments[2].normalizedEnd, 0.0)
    assertEquals(145.5, segments[3].normalizedStart, 0.0)
    assertEquals(148.25, segments[3].normalizedEnd, 0.0)
  }

  @Test
  fun v4HttpResponseUsesTvdbPayloadAndMillisecondBounds() = runBlocking {
    val body = """
      {
        "tvdb_id": 77889,
        "type": "tv",
        "season": 2,
        "episode": 7,
        "intro": [{"start_ms": 1250, "end_ms": 45250}],
        "credits": [{"start_ms": 121000, "end_ms": 145500}]
      }
    """.trimIndent()
    val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
      Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK")
        .body(body.toResponseBody("application/json".toMediaType())).build()
    }).build()
    val segments = IntroDbRepository(client, Json).getTheIntroDbSegments(
      tmdbId = 123, mediaType = "tv", season = 2, episode = 7, mapping = "official",
    ).getOrThrow()
    assertEquals(listOf("intro", "credits"), segments.map { it.segmentType })
    assertEquals(1.25, segments[0].normalizedStart, 0.0)
    assertEquals(45.25, segments[0].normalizedEnd, 0.0)
    assertEquals(121.0, segments[1].normalizedStart, 0.0)
    assertEquals(145.5, segments[1].normalizedEnd, 0.0)
  }

  @Test
  fun emptyAndNotFoundResponsesProduceNoSegments() = runBlocking {
    assertTrue(repository.parseTheIntroDbMediaBody("{\"tvdb_id\": 77889, \"type\": \"movie\"}").isEmpty())

    val notFoundClient = OkHttpClient.Builder().addInterceptor { chain ->
      Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(404)
        .message("Not Found")
        .body("".toResponseBody("application/json".toMediaType()))
        .build()
    }.build()
    val notFoundRepository = IntroDbRepository(notFoundClient, Json)

    assertTrue(
      notFoundRepository.getTheIntroDbSegments(
        imdbId = "tt1234567",
        mediaType = "movie",
      ).getOrThrow().isEmpty(),
    )
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
