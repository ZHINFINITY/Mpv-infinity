package app.infinity.mpvz.ui.player.ytdlp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtdlpDirectMediaUrlCommandTest {
  @Test
  fun directUrlCommandIncludesBundledQuickJsForYouTubeChallengeResolution() {
    val source = "https://youtu.be/jMkOo4Acdps?si=test"
    val command =
      YtdlpManager.buildDirectMediaUrlCommand(
        executablePath = "/app/lib/libytdl.so",
        ytDlpScriptPath = "/data/user/0/app/files/ytdl/yt-dlp",
        source = source,
        cookiesFile = null,
        quickJsFile = File("/app/lib/libqjs.so"),
        isInstagram = false,
      )
    val runtimeIndex = command.indexOf("--js-runtimes")

    assertTrue(runtimeIndex >= 0)
    assertEquals("quickjs:/app/lib/libqjs.so", command[runtimeIndex + 1])
    assertTrue(command.contains("--get-url"))
    assertEquals(source, command.last())
  }

  @Test
  fun directUrlCommandKeepsOptionalCookiesAndInstagramHeaders() {
    val cookies = File("/data/user/0/app/files/cookies.txt")
    val command =
      YtdlpManager.buildDirectMediaUrlCommand(
        executablePath = "/app/lib/libytdl.so",
        ytDlpScriptPath = "/data/user/0/app/files/ytdl/yt-dlp",
        source = "https://instagram.com/reel/example",
        cookiesFile = cookies,
        quickJsFile = null,
        isInstagram = true,
      )

    assertFalse(command.contains("--js-runtimes"))
    assertEquals(YtdlpOptionsBuilder.DEFAULT_USER_AGENT, command[command.indexOf("--user-agent") + 1])
    assertEquals("https://www.instagram.com/", command[command.indexOf("--referer") + 1])
    assertEquals(cookies.absolutePath, command[command.indexOf("--cookies") + 1])
  }
}
