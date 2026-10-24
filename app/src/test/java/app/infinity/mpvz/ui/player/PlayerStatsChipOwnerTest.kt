package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerStatsChipOwnerTest {
  @Test
  fun moreSheetMatchesMpvRxStatsChipCommands() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt")
    val text = file.readText()
    assertTrue(text.contains("PlayerStatsLuaCommandPolicy.apply("))
    assertTrue(text.contains("PlayerStatsLuaCommandPolicy.forChip("))
    assertTrue(text.contains("luaShown = PlayerStatsLuaCommandPolicy.isLuaShown()"))
    assertTrue(text.contains("previousPage = statisticsPage"))
    assertTrue(text.contains("advancedPreferences.enabledStatisticsPage.set(page)"))
    assertFalse(text.contains("if (statisticsPage in 1..5) PlaybackSession.command(\"script-binding\", \"stats/display-stats-toggle\")"))
    assertFalse(text.contains("PlayerStatsLuaOverlayPolicy"))
    assertFalse(text.contains("applyChip"))
    assertFalse(text.contains("overlayDepth"))
  }

  @Test
  fun activityDoesNotSendCompetingStatsLuaCommands() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val text = file.readText()
    assertTrue(text.contains("player.applyStartupStatistics()"))
    assertTrue(text.contains("MPV reloaded; position, pause, tracks and options confirmed; synthesized frames not inferred"))
    val reloadTail = text.substringAfter("MPV reloaded; position, pause, tracks and options confirmed; synthesized frames not inferred")
    assertTrue(reloadTail.contains("player.applyStartupStatistics()"))
    assertFalse(text.contains("applyStatsOverlayPolicy"))
    assertFalse(text.contains("PlayerStatsLuaOverlayPolicy"))
    assertFalse(text.contains("pendingStatsLuaReset"))
    assertFalse(text.contains("mpvStatsVisible"))
    assertTrue(text.contains("configureNativeEngineIfNeeded"))
    assertTrue(text.contains("shouldInitializeNativeMedia3Engine"))
    assertTrue(text.contains("private val nativeEngine by lazy"))
    assertTrue(text.contains("View.INVISIBLE"))
  }

  @Test
  fun mpvViewAppliesStartupStatisticsOncePerCore() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/MPVView.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt")
    val text = file.readText()
    assertTrue(text.contains("fun applyStartupStatistics()"))
    assertTrue(text.contains("PlayerStatsLuaCommandPolicy.STARTUP_APPLIED_PROPERTY"))
    assertTrue(text.contains("PlayerStatsLuaCommandPolicy.forStartup"))
    assertTrue(text.contains("PlayerStatsLuaCommandPolicy.apply"))
    assertTrue(text.contains("PlayerStatsLuaCommandPolicy.onCoreRecreated()"))
    assertFalse(text.contains("stats/display-page-\$it"))
  }

  @Test
  fun nativeCollectorsMatchWorkingCueWiring() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val text = file.readText()
    assertTrue(text.contains("private var nativeEngineConfigured = false"))
    assertTrue(text.contains("val nativePlaybackSnapshot get() = nativeEngineOrNull()?.snapshot ?: idleNativePlaybackSnapshot"))
    assertTrue(text.contains("private val nativeFirstFrame get() = nativeEngineOrNull()?.hasRenderedFirstFrame ?: idleNativeFirstFrame"))
    assertTrue(text.contains("val engine = nativeEngineOrNull() ?: return@repeatOnLifecycle"))
    assertTrue(text.contains("engine.subtitleCueText.collect { cue ->"))
    assertFalse(text.contains("flatMapLatest"))
    assertFalse(text.contains("emptyFlow()"))
    assertFalse(text.contains("collectAsState(initial = NativePlaybackSnapshot())"))
    assertFalse(text.contains("NativeSubtitleParserFactory(DefaultSubtitleParserFactory"))
  }

  @Test
  fun nativeEngineKeepsAssOnlyParserFactory() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/NativeMedia3Engine.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/NativeMedia3Engine.kt")
    val text = file.readText()
    assertTrue(text.contains("NativeSubtitleParserFactory(AssSubtitleParserFactory(assHandler))"))
    assertFalse(text.contains("DefaultSubtitleParserFactory"))
  }

  @Test
  fun flowInterpolationStatsRowRemains() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
    val text = file.readText()
    assertTrue(text.contains("CustomStatsPageSixOverlay"))
    assertTrue(text.contains("NativeStatsPageOverlay"))
    assertTrue(text.contains("else if (statisticsPage == 6)"))
    assertFalse(text.contains("else if (statisticsPage in 1..6)"))
    assertFalse(text.contains("sheetOrPanelOpen"))
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
