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
    val search = read("app/src/main/java/app/infinity/mpvz/ui/preferences/SearchablePreference.kt")
    val fallbackSearch = read("app/src/main/java/app/infinity/mpvz/ui/preferences/SettingsSearchNavigation.kt")
    val controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
    val landscape = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsLandscape.kt")
    val customButtons =
      controls.substringAfter("constrainAs(customButtonsPortraitRef) {")
        .substringBefore("width = Dimension.fillToConstraints")

    assertTrue(preferences.contains("portrait_playback_controls_position\", PlaybackControlsPosition.AboveSeekbar"))
    assertTrue(preferences.contains("Keep the legacy key so saved placement choices from older versions remain intact."))
    assertTrue(settings.contains("values = PlaybackControlsPosition.entries"))
    assertTrue(settings.contains("ui_playback_controls_position_summary"))
    assertTrue(settings.indexOf("ui_playback_controls_position") < settings.indexOf("pref_section_landscape_controls"))
    assertTrue(!settings.contains("ui_portrait_playback_buttons"))
    assertTrue(search.contains("anchorItemIndex = 0,\n        SearchEntrySpec(\n          R.string.ui_playback_controls_position"))
    assertTrue(search.contains("anchorItemIndex = 6,\n        SearchEntrySpec(R.string.pref_section_seekbar_style"))
    assertTrue(search.contains("anchorItemIndex = 8,\n        SearchEntrySpec(R.string.ui_time_network_clock"))
    assertTrue(fallbackSearch.contains("titleRes = R.string.ui_playback_controls_position, itemIndex = 0"))
    assertTrue(fallbackSearch.contains("titleRes = R.string.pref_layout_top_right_controls, itemIndex = 2"))
    assertTrue(fallbackSearch.contains("titleRes = R.string.pref_layout_portrait_bottom_controls, itemIndex = 4"))
    assertTrue(fallbackSearch.contains("titleRes = R.string.pref_player_display_hide_player_control_time, itemIndex = 8"))
    assertTrue(preferences.contains("BelowSeekbar(\"Below seekbar\")"))
    assertTrue(customButtons.contains("if (showBufferingIndicator)"))
    assertTrue(customButtons.contains("bottom.linkTo(bufferingIndicator.top, spacing.medium)"))
    assertTrue(customButtons.contains("bottom.linkTo(playerPauseButton.top, spacing.medium)"))
    assertTrue(controls.contains("bottom.linkTo(bottomRightControls.top"))
    assertTrue(controls.contains("bottom.linkTo(customButtonsPortraitRef.top"))
    assertTrue(controls.contains("bottom.linkTo(seekbar.top"))
    assertTrue(controls.contains("createTopBarrier(*landscapeSideControlBandRefs.toTypedArray())"))
    assertTrue(controls.contains("bottom.linkTo(landscapeSideControlBandTop, spacing.medium)"))
    assertTrue(controls.contains("createTopBarrier(*landscapeControlBandRefs.toTypedArray())"))
    assertTrue(controls.contains("bottom.linkTo(landscapeControlBandTop, spacing.medium)"))
    assertTrue(controls.contains("bottom.linkTo(playerPauseButton.start, spacing.small)"))
    assertTrue(controls.contains("start.linkTo(playerPauseButton.end, spacing.small)"))
    assertTrue(controls.contains("top.linkTo(seekbar.bottom, spacing.medium)"))
    assertTrue(landscape.contains("buttons.forEach { button ->"))
    assertTrue(landscape.contains("RenderPlayerButton("))
  }

  @Test
  fun hwPlusMapperLogsFrameAndVulkanFailureStagesWithoutChangingDecoderPolicy() {
    val mapper = read("app/src/main/cpp/mpvflow/mediacodec-vulkan/hwdec_aimagereader.c")
    val converter = read("app/src/main/cpp/mpvflow/mediacodec-vulkan/hwdec_aimagereader_vk_convert.c")

    assertTrue(mapper.contains("AIMAGE_TIMESTAMP"))
    assertTrue(mapper.contains("AIMAGE_MAP_INPUT"))
    assertTrue(mapper.contains("AIMAGE_MAP_RESULT"))
    assertTrue(converter.contains("HWPLUS_VK_MAP_FAIL stage=source-config"))
    assertTrue(converter.contains("HWPLUS_VK_MAP_FAIL stage=output-slot"))
    assertTrue(converter.contains("HWPLUS_VK_MAP_FAIL stage=frame-reference"))
    assertTrue(converter.contains("HWPLUS_VK_MAP_FAIL stage=record-conversion"))
    assertTrue(converter.contains("HWPLUS_VK_MAP_FAIL stage=submit-conversion"))
    assertTrue(converter.contains("acquire_sync_fd_enabled=%d"))
    assertTrue(converter.contains("release_sync_fd_enabled=%d"))
    assertTrue(converter.contains("HWPLUS_VK_MAP_SUCCESS"))
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
