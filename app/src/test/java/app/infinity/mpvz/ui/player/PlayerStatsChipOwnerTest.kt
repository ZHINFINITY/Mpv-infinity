package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerStatsChipOwnerTest {
  @Test
  fun moreSheetIssuesMpvRxStatsCommandsImmediately() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt")
    val text = file.readText()
    assertTrue(text.contains("stats/display-stats-toggle"))
    assertTrue(text.contains("stats/display-page-\$page"))
    assertTrue(text.contains("advancedPreferences.enabledStatisticsPage.set(page)"))
    assertFalse(text.contains("page.takeIf { it in 1..6 }"))
  }

  @Test
  fun activityOwnsStatsLuaAndSkipsNativeOnAudio() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val text = file.readText()
    assertTrue(text.contains("PlayerStatsLuaOverlayPolicy.decide"))
    assertTrue(text.contains("configureNativeEngineIfNeeded"))
    assertTrue(text.contains("shouldInitializeNativeMedia3Engine"))
    assertTrue("lazy NativeMedia3Engine still exists so video can construct it", text.contains("private val nativeEngine by lazy"))
    assertTrue(text.contains("View.INVISIBLE"))
    assertTrue(text.contains("Sheets.More"))
  }
}
