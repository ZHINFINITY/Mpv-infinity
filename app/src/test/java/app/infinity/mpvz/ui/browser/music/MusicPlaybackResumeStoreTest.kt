package app.infinity.mpvz.ui.browser.music

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MusicPlaybackResumeStoreTest {
  private val application: Application
    get() = RuntimeEnvironment.getApplication()
  private lateinit var preferences: SharedPreferences

  @Before
  fun setUp() {
    preferences = application.getSharedPreferences("music_resume_store_test", Context.MODE_PRIVATE)
    MusicPlaybackResumeStore.setPreferencesForTests(preferences)
    preferences.edit().clear().commit()
  }

  @After
  fun tearDown() {
    preferences.edit().clear().commit()
    MusicPlaybackResumeStore.setPreferencesForTests(null)
  }

  @Test
  fun encryptedStoreRoundTripsQueueIndexPositionAndMetadata() {
    val tracks =
      listOf(
        track(id = 11L, uri = "file:///music/one.flac", title = "One"),
        track(id = 22L, uri = "file:///music/two.flac", title = "Two", durationMs = 90_000L),
      )
    val snapshot =
      MusicPlaybackSnapshot(
        launchToken = "launch-token",
        launchSource = "recently_played",
        queue = tracks,
        currentIndex = 1,
        positionMs = 17_500L,
      )

    assertTrue(MusicPlaybackResumeStore.save(application, snapshot))
    val loaded = requireNotNull(MusicPlaybackResumeStore.load(application))

    assertEquals("launch-token", loaded.launchToken)
    assertEquals("recently_played", loaded.launchSource)
    assertEquals(tracks, loaded.queue)
    assertEquals(1, loaded.currentIndex)
    assertEquals(17_500L, loaded.positionMs)
  }

  @Test
  fun restoreDropsInaccessibleNeighborsAndClampsPositionForSelectedTrack() {
    val snapshot =
      MusicPlaybackResumeSnapshot(
        launchToken = "recover-me",
        launchSource = "music_library",
        queue =
          listOf(
            track(id = 1L, uri = "file:///music/one.flac", title = "One"),
            track(id = 2L, uri = "file:///music/two.flac", title = "Two"),
            track(id = 3L, uri = "file:///music/three.flac", title = "Three", durationMs = 30_000L),
          ),
        currentIndex = 2,
        positionMs = 80_000L,
      )

    val restored =
      restoreMusicPlaybackLaunch(
        snapshot = snapshot,
        requestedToken = "recover-me",
        fallback = null,
        isUriAccessible = { uri -> uri.path != "/music/two.flac" },
      )

    assertEquals(listOf(1L, 3L), restored?.songs?.map { it.id })
    assertEquals(1, restored?.currentIndex)
    assertEquals(30_000L, restored?.positionMs)
    assertEquals("music_library", restored?.launchSource)
  }

  @Test
  fun mismatchedTokenAndUnavailableCurrentTrackReturnFallback() {
    val fallback =
      MusicQueueLaunch(
        token = "fresh-token",
        songs = listOf(song(99L, "file:///music/fallback.flac")),
        currentIndex = 0,
      )
    val snapshot =
      MusicPlaybackResumeSnapshot(
        launchToken = "old-token",
        launchSource = "music_library",
        queue = listOf(track(id = 1L, uri = "file:///music/old.flac", title = "Old")),
        currentIndex = 0,
        positionMs = 1L,
      )

    val tokenMismatch =
      restoreMusicPlaybackLaunch(snapshot, "fresh-token", fallback) { true }
    val inaccessibleCurrent =
      restoreMusicPlaybackLaunch(snapshot, "old-token", fallback) { false }

    assertSame(fallback, tokenMismatch)
    assertSame(fallback, inaccessibleCurrent)
  }

  @Test
  fun invalidSnapshotIsDiscardedAndQueueWindowKeepsSelectedTrack() {
    preferences.edit().putString(MusicPlaybackResumeStore.SNAPSHOT_KEY, "not-json").commit()

    assertNull(MusicPlaybackResumeStore.load(application))
    assertFalse(preferences.contains(MusicPlaybackResumeStore.SNAPSHOT_KEY))
    assertEquals(375 to 875, MusicPlaybackResumeStore.queueWindow(1_000, 500, 500))
  }

  private fun track(
    id: Long,
    uri: String,
    title: String,
    durationMs: Long = 60_000L,
  ): MusicQueueTrackSnapshot =
    MusicQueueTrackSnapshot(
      mediaId = "music:$id:$uri",
      uri = uri,
      title = title,
      artist = "Artist",
      artworkUri = null,
      durationMs = durationMs,
      songId = id,
      album = "Album",
      albumId = 8L,
      path = Uri.parse(uri).path,
      dateAdded = 10L,
      trackNumber = id.toInt(),
      year = 2026,
      size = 100L,
    )

  private fun song(id: Long, uri: String): MusicSong =
    track(id, uri, "Fallback").toMusicSongOrNull()!!
}
