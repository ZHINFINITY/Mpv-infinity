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
              write("HTTP/1.1 200 OK\r\nContent-Type: application/ogg\r\nConnection: close\r\n\r\n".toByteArray())
              flush()
            }
            closed.complete(request.any { it.equals("Range: bytes=0-0", true) } && reader.read() == -1)
          }
        } catch (error: Throwable) {
          closed.completeExceptionally(error)
        }
      }
      assertEquals("audio/ogg", RadioStreamProbe.detectAudioMimeType("http://127.0.0.1:${server.localPort}/flacm", emptyMap()))
      assertTrue(closed.get(3, TimeUnit.SECONDS))
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
