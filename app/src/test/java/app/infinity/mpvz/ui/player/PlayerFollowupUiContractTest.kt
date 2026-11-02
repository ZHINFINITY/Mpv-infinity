/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerFollowupUiContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  @Test
  fun timeNetworkPillStartsCompactAndPersistsUntilTheNextTap() {
    val preferences = read("app/src/main/java/app/infinity/mpvz/preferences/AppearancePreferences.kt")
    val controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsShared.kt")
    val branch = controls.substringAfter("PlayerButton.TIME_NETWORK ->").substringBefore("PlayerButton.NONE ->")

    assertTrue(preferences.contains("getBoolean(\"time_network_pill_expanded\", false)"))
    assertTrue(branch.contains("targetState = timeNetworkPillExpanded && !compact"))
    assertTrue(branch.contains("timeNetworkPillExpanded.set(!appearancePreferences.timeNetworkPillExpanded.get())"))
    assertTrue(branch.contains("onClick = {"))
    assertTrue(branch.contains("toggleTimeNetworkPill()"))
    assertTrue(branch.contains("using(SizeTransform(clip = false))"))
    assertTrue(branch.contains("onLongClick = toggleTimeAndNetwork"))
    assertTrue(!branch.contains("resetFrameNavigationTimer"))
  }

  @Test
  fun playerLayoutMovesTransportAndControlRowsTogetherAroundTheSeekbar() {
    val preferences = read("app/src/main/java/app/infinity/mpvz/preferences/AppearancePreferences.kt")
    val settings = read("app/src/main/java/app/infinity/mpvz/ui/preferences/PlayerControlsPreferencesScreen.kt")
    val controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
    val customButtons =
      controls.substringAfter("constrainAs(customButtonsPortraitRef) {")
        .substringBefore("width = Dimension.fillToConstraints")

    assertTrue(preferences.contains("portrait_playback_controls_position\", PortraitPlaybackControlsPosition.AboveSeekbar"))
    assertTrue(preferences.contains("PortraitPlaybackControlsPosition.AboveSeekbar"))
    assertTrue(preferences.contains("BelowSeekbar(\"Below seekbar\")"))
    assertTrue(settings.contains("values = PortraitPlaybackControlsPosition.entries"))
    assertTrue(customButtons.contains("if (showBufferingIndicator)"))
    assertTrue(customButtons.contains("bottom.linkTo(bufferingIndicator.top, spacing.medium)"))
    assertTrue(customButtons.contains("bottom.linkTo(playerPauseButton.top, spacing.medium)"))
    assertTrue(controls.contains("bottom.linkTo(bottomRightControls.top"))
    assertTrue(controls.contains("bottom.linkTo(customButtonsPortraitRef.top"))
    assertTrue(controls.contains("bottom.linkTo(seekbar.top"))
  }

  @Test
  fun moreSheetQualityChooserUsesTheExistingAnime4kQualityPreference() {
    val more = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt")
    val sheets = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerSheets.kt")
    val viewModel = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerViewModel.kt")
    val state = read("app/src/main/java/app/infinity/mpvz/ui/player/anime4k/Anime4KUiState.kt")
    val decoder = read("app/src/main/java/app/infinity/mpvz/ui/preferences/DecoderPreferencesScreen.kt")

    assertTrue(more.indexOf("items(Anime4KManager.Mode.entries") < more.indexOf("pref_anime4k_quality_title"))
    assertTrue(more.contains("items(Anime4KManager.Quality.entries"))
    assertTrue(more.contains("selectedQuality == quality"))
    assertTrue(more.contains("onAnime4KQualitySelected(quality)"))
    assertTrue(sheets.contains("onAnime4KQualitySelected = viewModel::selectAnime4KQuality"))
    assertTrue(viewModel.contains("decoderPreferences.anime4kQuality.changes()"))
    assertTrue(viewModel.contains("decoderPreferences.anime4kQuality.set(quality)"))
    val qualityHandler = viewModel.substringAfter("fun selectAnime4KQuality(").substringBefore("/**")
    assertTrue(qualityHandler.contains("selectAnime4KMode(currentMode)"))
    assertTrue(state.contains("selectedQuality: Anime4KManager.Quality"))
    assertTrue(decoder.contains("preferences.anime4kQuality.set(it)"))
  }

  private fun read(path: String): String = File(root, path).readText()
}
