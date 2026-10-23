package app.infinity.mpvz.utils.media

import org.junit.Assert.assertEquals
import org.junit.Test

class M3URoutingPolicyTest {
  @Test fun routesNamedLiveRadioToMusic() {
    val result = M3UParseResult.Success(
      "Radio stations",
      listOf(M3UPlaylistItem("https://example.test/live", "Jazz FM", duration = -1)),
    )
    assertEquals(M3URoute.MUSIC, M3URoutingPolicy.route(result))
  }

  @Test fun routesAllKnownAudioToMusicWithoutRadioName() {
    val result = M3UParseResult.Success(
      "My songs",
      listOf(M3UPlaylistItem("https://example.test/one.flac"), M3UPlaylistItem("https://example.test/two.mp3")),
    )
    assertEquals(M3URoute.MUSIC, M3URoutingPolicy.route(result))
  }

  @Test fun keepsOrdinaryVideoPlaylistOnPlayerPath() {
    val result = M3UParseResult.Success(
      "Live videos",
      listOf(M3UPlaylistItem("https://example.test/camera.m3u8", "Live camera", duration = -1)),
    )
    assertEquals(M3URoute.PLAYER, M3URoutingPolicy.route(result))
  }
  @Test fun keepsMixedAudioAndVideoOnPlayerPath() {
    val result = M3UParseResult.Success(
      "Mixed",
      listOf(M3UPlaylistItem("https://example.test/song.mp3"), M3UPlaylistItem("https://example.test/movie.mp4")),
    )
    assertEquals(M3URoute.PLAYER, M3URoutingPolicy.route(result))
  }

  @Test fun doesNotTreatUnknownLiveVideoAsMusicWithoutAudioEvidence() {
    val result = M3UParseResult.Success(
      "Live camera",
      listOf(M3UPlaylistItem("https://example.test/live", "Live camera", duration = -1)),
    )
    assertEquals(M3URoute.PLAYER, M3URoutingPolicy.route(result))
  }

  @Test fun genericStreamingWordsAreNotRadioEvidence() {
    for (name in listOf("Live TV", "Streaming stations", "I AM a camera", "Broadcast channels")) {
      val result = M3UParseResult.Success(name, listOf(M3UPlaylistItem("https://example.test/stream")))
      assertEquals(M3URoute.PLAYER, M3URoutingPolicy.route(result))
    }
  }

  @Test fun videoContainerVetoesRadioNamesAndStoredAudioFlag() {
    val result = M3UParseResult.Success("Radio", listOf(M3UPlaylistItem("https://example.test/movie.mp4")))
    assertEquals(false, M3URoutingPolicy.isMusicPlaylist(result, declaredAudio = true))
  }

  @Test fun storedAudioClassificationSupportsOpaqueNetworkPaths() {
    val result = M3UParseResult.Success("Music", listOf(M3UPlaylistItem("network://saved/opaque")))
    assertEquals(true, M3URoutingPolicy.isMusicPlaylist(result, declaredAudio = true))
  }

  @Test fun emptyPlaylistIsNeverAudioEvenWhenPreviouslyFlagged() {
    val result = M3UParseResult.Success("Radio", emptyList())
    assertEquals(false, M3URoutingPolicy.isMusicPlaylist(result, declaredAudio = true))
  }
}
