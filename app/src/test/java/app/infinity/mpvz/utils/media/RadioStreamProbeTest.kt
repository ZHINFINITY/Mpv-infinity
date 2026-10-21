package app.infinity.mpvz.utils.media

import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStreamProbeTest {
  @Test
  fun closesResponseAfterHeadersWithoutWaitingForLiveBody() = runBlocking {
    ServerSocket(0).use { server ->
      val closed = CompletableFuture<Boolean>()
      thread(isDaemon = true) {
        try {
          server.accept().use { socket ->
            socket.soTimeout = 2000
            val reader = socket.getInputStream().bufferedReader()
            val request = buildList {
              while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                add(line)
              }
            }
            socket.getOutputStream().apply {
              write("HTTP/1.1 200 OK\r\nContent-Type: application/ogg\r\nicy-name: Test Station\r\nicy-logo: https://radio.example/logo.jpg\r\nConnection: close\r\n\r\n".toByteArray())
              flush()
            }
            closed.complete(
              request.any { it.equals("Range: bytes=0-0", true) } &&
                request.any { it.equals("Icy-MetaData: 1", true) } &&
                reader.read() == -1,
            )
          }
        } catch (error: Throwable) {
          closed.completeExceptionally(error)
        }
      }
      val url = "http://127.0.0.1:${server.localPort}/flacm"
      assertEquals("audio/ogg", RadioStreamProbe.detectAudioMimeType(url, emptyMap()))
      assertTrue(closed.get(3, TimeUnit.SECONDS))
      assertEquals("audio/ogg", RadioStreamProbe.cachedAudioMimeType(url, mapOf("Referer" to "http://127.0.0.1")))
      assertEquals("Test Station", RadioStreamProbe.cachedMetadata(url, emptyMap())?.icyName)
      assertEquals("https://radio.example/logo.jpg", RadioStreamProbe.cachedMetadata(url, emptyMap())?.icyLogo)
      // The server accepted only one request and is no longer reading: this must be cached.
      assertEquals("audio/ogg", RadioStreamProbe.detectAudioMimeType(url, emptyMap()))
      assertNull(RadioStreamProbe.cachedAudioMimeType(url, mapOf("Authorization" to "different-user")))
    }
  }

  @Test
  fun recognizesIcecastAudioWithoutDependingOnUrlExtensions() {
    assertEquals("audio/ogg", RadioStreamProbe.audioMimeType("application/ogg"))
    assertEquals("audio/flac", RadioStreamProbe.audioMimeType("Audio/FLAC; charset=binary"))
    assertEquals("audio/mpeg", RadioStreamProbe.audioMimeType("audio/mpeg"))
  }

  @Test
  fun doesNotTreatWebPagesVideoOrPlaylistsAsProvenAudio() {
    listOf(null, "text/html", "video/mp4", "application/octet-stream", "audio/x-mpegurl", "application/vnd.apple.mpegurl", "audio/x-scpls")
      .forEach { assertNull(RadioStreamProbe.audioMimeType(it)) }
  }
}
