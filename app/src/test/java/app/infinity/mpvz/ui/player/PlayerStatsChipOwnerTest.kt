package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    assertTrue(text.contains("StatsLuaOverlay.show(page)"))
    assertEquals(3, Regex("StatsLuaOverlay\\.hide\\(\\)").findAll(text).count())
    assertFalse(text.contains("stats/display-stats-toggle"))
    assertTrue(text.contains("if (activeEngine == PlaybackEngineMode.NATIVE) {"))
    assertTrue(text.contains("advancedPreferences.enabledStatisticsPage.set(page)"))
    assertFalse(text.contains("PlayerStatsLuaCommandPolicy"))
    val statsBranch = text.substringAfter("val isConsoleOpen = PlaybackSession.getPropertyBoolean(\"user-data/mpv/console/open\") == true")
    val pageSaved = statsBranch.indexOf("advancedPreferences.enabledStatisticsPage.set(page)")
    val pageCommands = statsBranch.indexOf("when (page)")
    assertTrue(pageCommands >= 0 && pageSaved > pageCommands)
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
    assertFalse(text.contains("applyStartupStatistics"))
    assertFalse(text.contains("scheduleInterpolationStatsRestore"))
    assertFalse(text.contains("PlayerStatsLuaRestorePolicy"))
    assertFalse(text.contains("restoreLuaAfterVideoSurfaceShown"))
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
    assertTrue(postInit.contains("StatsLuaOverlay.onCoreStarted()"))
    // Stats must show only for positively video-classified items: gating on
    // "not definitely audio" flashed the stats page over audio items whose metadata
    // was not loaded yet at core start (user-reported music player flash).
    assertTrue(postInit.contains("declaredMediaKind() == DeclaredPlaybackMediaKind.VIDEO"))
    assertTrue(postInit.contains("StatsLuaOverlay.show(advancedPreferences.enabledStatisticsPage.get())"))
    assertTrue(postInit.indexOf("onCoreStarted()") < postInit.indexOf("StatsLuaOverlay.show("))
    assertFalse(text.contains("applyStartupStatistics"))
    assertFalse(text.contains("PlayerStatsLuaRestorePolicy"))
    assertFalse(text.contains("stats/display-page-\$it"))
  }

  @Test
  fun musicLoadHidesStatsPageForAnythingNotPositivelyVideo() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/PlaybackSession.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/PlaybackSession.kt")
    val text = file.readText()
    // Streaming music URLs (Navidrome stream.view, Jellyfin /Audio/... proxies) often carry no
    // extension or mime type, so gating the hide on "definitely audio" left the previous video's
    // stats page up for the first second of every music item. Hide unless positively video.
    assertTrue(
      text.contains(
        "if (resolvedItem.declaredMediaKind() != DeclaredPlaybackMediaKind.VIDEO) StatsLuaOverlay.hide()",
      ),
    )
    assertFalse(text.contains("if (resolvedItem.isDefinitelyAudioOnly()) StatsLuaOverlay.hide()"))
  }

  @Test
  fun mpvNetworkCachePauseWaitIsAppliedOnce() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/MPVView.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt")
    val text = file.readText()
    // A later duplicate assignment silently overrode the intended resume wait, so every network
    // dip froze playback for the longer value instead of resuming after the short one.
    assertEquals(
      1,
      Regex("setOptionString\\(\"cache-pause-wait\",").findAll(text).count(),
    )
    assertEquals(
      1,
      Regex("setOptionString\\(\"demuxer-readahead-secs\",").findAll(text).count(),
    )
  }

  @Test
  fun runtimeMpvFlowControlsUseSettingsPolicyOutsideDebugBuilds() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/MPVView.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt")
    val text = file.readText()
    assertTrue(text.contains("Media3FlowDebugSettingsPolicy.mpvFlowFeatureEnabled("))
    assertTrue(text.contains("requested = decoderPreferences.mpvFlowVisibilityResolverDebug.get()"))
    assertTrue(text.contains("requested = decoderPreferences.mpvFlowSoftBlendDebug.get()"))
    assertFalse(text.contains("BuildConfig.DEBUG &&\n        decoderPreferences.mpvFlowVisibilityResolverDebug.get()"))
    assertFalse(text.contains("BuildConfig.DEBUG &&\n        decoderPreferences.mpvFlowSoftBlendDebug.get()"))
  }

  @Test
  fun statsOverlayMatchesPublicV223InMemoryVisibilityState() {
    val file = File("src/main/java/app/infinity/mpvz/ui/player/StatsLuaOverlay.kt")
      .takeIf { it.exists() }
      ?: File("app/src/main/java/app/infinity/mpvz/ui/player/StatsLuaOverlay.kt")
    val text = file.readText()
    assertTrue(text.contains("@Volatile"))
    assertTrue(text.contains("var shown: Boolean = false"))
    assertTrue(text.contains("fun hide()"))
    assertTrue(text.contains("PlaybackSession.command(\"script-binding\", \"stats/display-stats-toggle\")"))
    assertFalse(text.contains("VISIBLE_PROPERTY"))
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
    // The cue-level translation was restored as a fallback for formats whole-track
    // extraction can't handle (e.g. PGS bitmaps), routed through the ViewModel.
    assertTrue(text.contains("viewModel.translateEmbeddedSubtitleCue(cue, native = true)"))
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
