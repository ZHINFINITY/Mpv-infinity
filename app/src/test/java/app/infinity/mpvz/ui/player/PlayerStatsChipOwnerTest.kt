package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerStatsChipOwnerTest {
  @Test
  fun moreSheetIsTheOnlyChipSenderAndReplacesPages() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt")
    val text = file.readText()
    assertTrue(text.contains("PlayerStatsLuaOverlayPolicy.applyChip"))
    assertTrue(text.contains("advancedPreferences.enabledStatisticsPage.set(page)"))
    assertFalse("MoreSheet must not send display-page without hide-first", text.contains("stats/display-page-\$page"))
  }

  @Test
  fun activityDoesNotCompeteWhileMoreIsSender() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val text = file.readText()
    assertTrue(text.contains("senderIsMoreSheet = moreIsSender"))
    assertTrue(text.contains("if (moreIsSender) return"))
    assertTrue(text.contains("pendingStatsLuaReset"))
    assertTrue(text.contains("configureNativeEngineIfNeeded"))
    assertTrue(text.contains("shouldInitializeNativeMedia3Engine"))
    assertTrue(text.contains("private val nativeEngine by lazy"))
    assertTrue(text.contains("View.INVISIBLE"))
  }

  @Test
  fun lyricsDelayUsesInPlaceBarNotEmptyGlassSheet() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/controls/components/LyricsView.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/LyricsView.kt")
    val text = file.readText()
    assertTrue(text.contains("isSyncExpanded"))
    assertTrue(text.contains("Lyrics delay"))
    assertFalse(text.contains("ModalBottomSheet"))
    assertFalse(text.contains("playerGlassOverlay"))
  }
}
