package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerStatsChipOwnerTest {
  @Test
  fun moreSheetUsesKnownGoodStatsChipCommands() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt")
    val text = file.readText()
    // Same 380a778b sequence (toggle on once, then display-page-N; toggle off for Off/6/7),
    // sent through StatsLuaOverlay so the app knows the real overlay state and music can
    // never flip a blind toggle that turns stats ON (owner report: stats over a FLAC).
    assertTrue(text.contains("StatsLuaOverlay.show(page)"))
    assertEquals(3, Regex("StatsLuaOverlay\\.hide\\(\\)").findAll(text).count())
    assertFalse(text.contains("stats/display-stats-toggle"))
    assertTrue(text.contains("if (activeEngine == PlaybackEngineMode.NATIVE) {"))
    assertTrue(text.contains("advancedPreferences.enabledStatisticsPage.set(page)"))
    assertFalse(text.contains("PlayerStatsLuaCommandPolicy"))
  }

  @Test
  fun activityDoesNotSendCompetingStatsLuaCommands() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val text = file.readText()
    assertFalse(text.contains("applyStartupStatistics"))
    assertFalse(text.contains("scheduleInterpolationStatsRestore"))
    assertFalse(text.contains("PlayerStatsLuaCommandPolicy"))
    assertFalse(text.contains("stats/display-stats-toggle"))
    assertFalse(text.contains("stats/display-page-"))
    assertTrue(text.contains("configureNativeEngineIfNeeded"))
    assertTrue(text.contains("shouldInitializeNativeMedia3Engine"))
    assertTrue(text.contains("private val nativeEngine by lazy"))
    assertTrue(text.contains("hideMpvSurfaceAndOverlayForAudioOrMinimize()"))
    assertTrue(text.contains("binding.player.visibility = View.GONE"))
    assertTrue(text.contains("binding.media3SubtitleOverlay.visibility = View.GONE"))
    assertFalse(text.contains("View.INVISIBLE"))
  }

  @Test
  fun mpvViewRestoresStatsOncePerCoreLikeKnownGoodBuild() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/MPVView.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt")
    val text = file.readText()
    val postInit = text.substringAfter("override fun postInitOptions()").substringBefore("\n  }\n")
    // A new core has no overlay; video restores the saved page once, audio never does.
    assertTrue(postInit.contains("StatsLuaOverlay.onCoreStarted()"))
    assertTrue(postInit.contains("isDefinitelyAudioOnly() != true"))
    assertTrue(postInit.contains("StatsLuaOverlay.show(advancedPreferences.enabledStatisticsPage.get())"))
    assertTrue(postInit.indexOf("onCoreStarted()") < postInit.indexOf("StatsLuaOverlay.show("))
    assertFalse(text.contains("applyStartupStatistics"))
    assertFalse(text.contains("PlayerStatsLuaCommandPolicy"))
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

  @Test
  fun onlyTheTrackerSendsTheRawStatsToggle() {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val offenders = root.walkTopDown()
      .filter { it.isFile && it.extension == "kt" && it.name != "StatsLuaOverlay.kt" }
      .filter { it.readText().contains("stats/display-stats-toggle") }
      .map { it.name }
      .toList()
    assertEquals(emptyList<String>(), offenders)
    val tracker = File(root, "app/infinity/mpvz/ui/player/StatsLuaOverlay.kt").readText()
    // hide() never turns stats on; show() toggles only when the overlay is not already up.
    assertTrue(tracker.contains("fun hide() {\n    if (!shown) return"))
    assertTrue(tracker.contains("if (page !in 1..5) return"))
    assertTrue(tracker.contains("if (!shown) {"))
  }

  @Test
  fun musicPathsOnlyHideStats() {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    for (name in listOf("controls/AudioPlayerControls.kt", "controls/PlayerControls.kt")) {
      val text = File(root, "app/infinity/mpvz/ui/player/$name").readText()
      assertTrue(name, text.contains("StatsLuaOverlay.hide()"))
      assertFalse(name, text.contains("StatsLuaOverlay.show("))
    }
  }
}
