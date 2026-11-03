/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertFalse
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
  fun timeNetworkButtonRemainsSelectableAndRendersInItsOrientationRegion() {
    val buttonEnum = read("app/src/main/java/app/infinity/mpvz/preferences/PlayerButton.kt")
    val editor = read("app/src/main/java/app/infinity/mpvz/ui/preferences/ControlLayoutEditorScreen.kt")
    val preferences = read("app/src/main/java/app/infinity/mpvz/preferences/AppearancePreferences.kt")
    val controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
    val landscape = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsLandscape.kt")
    val portrait = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsPortrait.kt")
    val renderer = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsShared.kt")

    assertTrue(buttonEnum.contains("TIME_NETWORK"))
    assertTrue(buttonEnum.contains("val allPlayerButtons ="))
    assertTrue(buttonEnum.contains("PlayerButton.values().filter"))
    assertTrue(editor.contains("allPlayerButtons"))
    assertTrue(editor.contains("selectedButtons = selectedButtons + button"))
    assertTrue(editor.contains("prefToEdit.set(selectedButtons.joinToString(\",\"))"))
    assertTrue(preferences.contains("PlayerButton.valueOf(name)"))
    assertTrue(controls.contains("parseButtons(topRightControlsPref, usedButtons)"))
    assertTrue(controls.contains("parseButtons(portraitBottomControlsPref, mutableSetOf())"))
    assertTrue(controls.contains("buttons = topRightButtons"))
    assertTrue(controls.contains("buttons = portraitBottomButtons"))
    assertTrue(controls.contains("visible = controlsShown && !areControlsLocked && !isPortrait"))
    assertTrue(landscape.contains("buttons.forEach { button ->") && landscape.contains("RenderPlayerButton("))
    assertTrue(portrait.contains("buttons.forEach { button ->") && portrait.contains("RenderPlayerButton("))
    assertTrue(renderer.contains("PlayerButton.TIME_NETWORK ->"))
  }

  @Test
  fun mpvSubtitleTapRunsOnIoAndSerializesNativePropertyMutations() {
    val playerViewModel = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerViewModel.kt")
    val toggle = playerViewModel.substringAfter("fun toggleSubtitle(id: Int) {")
      .substringBefore("fun isSubtitleSelected(id: Int): Boolean {")
    val launchIndex = toggle.indexOf("viewModelScope.launch(Dispatchers.IO)")
    val nativeReadIndex = toggle.indexOf("getTrackSelectionId(\"sid\")")

    assertTrue(launchIndex >= 0)
    assertTrue(toggle.contains("mpvSubtitleSelectionMutex.withLock"))
    assertTrue(nativeReadIndex > launchIndex)
    assertTrue(toggle.contains("PlaybackSession.isCurrentGeneration(generation)"))
  }

  @Test
  fun playerLayoutMovesTransportAndControlRowsTogetherAroundTheSeekbar() {
    val preferences = read("app/src/main/java/app/infinity/mpvz/preferences/AppearancePreferences.kt")
    val settings = read("app/src/main/java/app/infinity/mpvz/ui/preferences/PlayerControlsPreferencesScreen.kt")
    val search = read("app/src/main/java/app/infinity/mpvz/ui/preferences/SearchablePreference.kt")
    val fallbackSearch = read("app/src/main/java/app/infinity/mpvz/ui/preferences/SettingsSearchNavigation.kt")
    val controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
    val landscape = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsLandscape.kt")
    val settingsCard = read("app/src/main/java/app/infinity/mpvz/ui/preferences/CardPreferences.kt")
    val customButtons =
      controls.substringAfter("constrainAs(customButtonsPortraitRef) {")
        .substringBefore("width = Dimension.fillToConstraints")
    val landscapeRight =
      controls.substringAfter("constrainAs(bottomRightControls) {")
        .substringBefore("}.onGloballyPositioned")
    val landscapeRightBelow =
      landscapeRight.substringAfter("} else if (playbackControlsPosition == PlaybackControlsPosition.BelowSeekbar) {")
        .substringBefore("} else {")
    val landscapeRightAbove =
      landscapeRight.substringAfter("} else if (playbackControlsPosition == PlaybackControlsPosition.BelowSeekbar) {")
        .substringAfter("} else {")

    assertTrue(preferences.contains("portrait_playback_controls_position\", PlaybackControlsPosition.AboveSeekbar"))
    assertTrue(preferences.contains("Keep the legacy key so saved placement choices from older versions remain intact."))
    assertTrue(settings.contains("values = PlaybackControlsPosition.entries"))
    assertTrue(settings.contains("ui_playback_controls_position_summary"))
    assertTrue(settings.contains("PreferenceCard(contentVerticalPadding = 0.dp)"))
    assertTrue(settingsCard.contains("contentVerticalPadding: Dp = 8.dp"))
    assertTrue(settings.indexOf("pref_section_player_controls_appearance") < settings.lastIndexOf("ui_playback_controls_position"))
    assertTrue(settings.indexOf("ui_time_network_clock") < settings.lastIndexOf("ui_playback_controls_position"))
    assertTrue(!settings.contains("ui_portrait_playback_buttons"))
    assertTrue(search.contains("anchorItemIndex = 8,\n        SearchEntrySpec(\n          R.string.ui_playback_controls_position"))
    assertTrue(search.contains("anchorItemIndex = 5,\n        SearchEntrySpec(R.string.pref_section_seekbar_style"))
    assertTrue(search.contains("anchorItemIndex = 7,\n        SearchEntrySpec(R.string.ui_time_network_clock"))
    assertTrue(fallbackSearch.contains("titleRes = R.string.ui_playback_controls_position, itemIndex = 8"))
    assertTrue(fallbackSearch.contains("titleRes = R.string.pref_layout_top_right_controls, itemIndex = 1"))
    assertTrue(fallbackSearch.contains("titleRes = R.string.pref_layout_portrait_bottom_controls, itemIndex = 3"))
    assertTrue(fallbackSearch.contains("titleRes = R.string.pref_player_display_hide_player_control_time, itemIndex = 7"))
    assertTrue(preferences.contains("BelowSeekbar(\"Below seekbar\")"))
    assertTrue(customButtons.contains("if (showBufferingIndicator)"))
    assertTrue(customButtons.contains("bottom.linkTo(bufferingIndicator.top, spacing.medium)"))
    assertTrue(customButtons.contains("bottom.linkTo(playerPauseButton.top, spacing.medium)"))
    assertTrue(controls.contains("bottom.linkTo(bottomRightControls.top"))
    assertTrue(controls.contains("val portraitControlsAboveGroupTop ="))
    assertTrue(controls.contains("bottom.linkTo(seekbar.top"))
    assertTrue(controls.contains("createTopBarrier(*landscapeSideControlBandRefs.toTypedArray())"))
    assertTrue(controls.contains("bottom.linkTo(landscapeSideControlBandTop, spacing.medium)"))
    assertTrue(controls.contains("createTopBarrier(*landscapeControlBandRefs.toTypedArray())"))
    assertTrue(controls.contains("bottom.linkTo(landscapeControlBandTop, spacing.medium)"))
    assertTrue(controls.contains("end.linkTo(playerPauseButton.start, spacing.small)"))
    assertTrue(controls.contains("start.linkTo(playerPauseButton.end, spacing.small)"))
    assertTrue(controls.contains("bottom.linkTo(seekbar.top, spacing.medium)"))
    assertTrue(landscapeRightBelow.contains("start.linkTo(playerPauseButton.end, spacing.small)"))
    assertTrue(landscapeRightBelow.contains("width = Dimension.fillToConstraints"))
    assertTrue(landscapeRightAbove.contains("start.linkTo(playerPauseButton.end, spacing.small)"))
    assertTrue(landscapeRightAbove.contains("end.linkTo(parent.end, spacing.large)"))
    assertTrue(landscapeRightAbove.contains("width = Dimension.preferredWrapContent"))
    assertTrue(controls.contains("horizontalBias = 0f\n                    width = Dimension.fillToConstraints"))
    assertTrue(controls.contains("horizontalBias = 1f\n                    width = Dimension.fillToConstraints"))
    assertTrue(controls.contains("if (playbackControlsPosition == PlaybackControlsPosition.BelowSeekbar) {\n                    Dimension.fillToConstraints\n                  } else {\n                    Dimension.preferredWrapContent"))
    assertTrue(landscape.contains("buttons.forEach { button ->"))
    assertTrue(landscape.contains("RenderPlayerButton("))
    assertTrue(landscape.contains("Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())"))
    assertTrue(landscape.contains("Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), reverseScrolling = true)"))
    assertTrue(controls.contains(".padding(vertical = customButtonsRowVerticalPadding)\n                  .fillMaxWidth()\n                  .horizontalScroll(rememberScrollState())"))
    assertTrue(controls.contains(".padding(vertical = customButtonsRowVerticalPadding)\n                  .fillMaxWidth()\n                  .horizontalScroll(rememberScrollState(), reverseScrolling = true)"))
    assertTrue(landscape.contains("buttonSize = 45.dp"))
  }

  @Test
  fun portraitControlsFallBackWhenCustomButtonRowIsAbsent() {
    val controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")

    assertTrue(controls.contains("if (showPortraitCustomButtons) customButtonsPortraitRef.top else bottomRightControls.top"))
    assertTrue(controls.contains("showPortraitCustomButtons -> bottom.linkTo(customButtonsPortraitRef.top, spacing.small)"))
    assertTrue(controls.contains("showBufferingIndicator -> bottom.linkTo(bufferingIndicator.top, spacing.small)"))
    assertTrue(controls.contains("areButtonsVisible -> bottom.linkTo(playerPauseButton.top, spacing.small)"))
    assertTrue(controls.contains("else -> bottom.linkTo(parent.bottom, spacing.medium)"))
    assertFalse(controls.contains("bottom.linkTo(customButtonsPortraitRef.top, spacing.small)\n                    } else {"))
  }

  @Test
  fun transportStaysCenteredForAboveAndPreservesBelowPlacement() {
    val preferences = read("app/src/main/java/app/infinity/mpvz/preferences/AppearancePreferences.kt")
    val controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
    val transport = controls.substringAfter("Modifier.constrainAs(playerPauseButton) {")
      .substringBefore("\n              },")
    val aboveBranch = transport.substringAfterLast("} else {")

    assertTrue(preferences.contains("preferenceStore.getEnum(\"portrait_playback_controls_position\", PlaybackControlsPosition.AboveSeekbar)"))
    assertTrue(aboveBranch.contains("top.linkTo(parent.top)"))
    assertTrue(aboveBranch.contains("bottom.linkTo(parent.bottom)"))
    assertTrue(!transport.contains("portraitControlsAboveGroupTop"))
    assertTrue(!transport.contains("landscapeSideControlBandTop"))
    assertTrue(transport.contains("bottom.linkTo(bottomRightControls.top, spacing.small)"))
    assertTrue(transport.contains("bottom.linkTo(parent.bottom, spacing.large)"))
  }

  @Test
  fun secondaryAssStylingAvoidsUnsupportedSecondaryFontProperties() {
    val mpvView = read("app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt")
    val playerActivity = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val typographyCard = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/panels/SubtitleSettingsTypographyCard.kt")
    val miscellaneousCard = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/panels/SubtitleSettingsMiscellaneousCard.kt")
    val colorsCard = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/panels/SubtitleSettingsColorsCard.kt")
    val preferencesScreen = read("app/src/main/java/app/infinity/mpvz/ui/preferences/SubtitlesPreferencesScreen.kt")
    val configOverride = read("app/src/main/java/app/infinity/mpvz/preferences/MpvConfigOverride.kt")
    val subtitlePositioning = read("app/src/main/java/app/infinity/mpvz/ui/player/SubtitlePositioning.kt")
    val unsupportedSecondaryOptions = listOf(
      "secondary-sub-font",
      "secondary-sub-font-size",
      "secondary-sub-bold",
      "secondary-sub-italic",
      "secondary-sub-justify",
      "secondary-sub-color",
      "secondary-sub-back-color",
      "secondary-sub-border-color",
      "secondary-sub-shadow-color",
      "secondary-sub-border-size",
      "secondary-sub-outline-size",
      "secondary-sub-border-style",
      "secondary-sub-shadow-offset",
      "secondary-sub-scale-by-window",
      "secondary-sub-use-margins",
    )
    unsupportedSecondaryOptions.forEach { option ->
      assertTrue(!mpvView.contains("\"$option\""))
      assertTrue(!playerActivity.contains("\"$option\""))
      assertTrue(!typographyCard.contains("\"$option\""))
      assertTrue(!miscellaneousCard.contains("\"$option\""))
      assertTrue(!colorsCard.contains("\"$option\""))
      assertTrue(!preferencesScreen.contains("\"$option\""))
      assertTrue(!configOverride.contains("\"$option\""))
    }
    assertTrue(colorsCard.contains("PlaybackSession.setPropertyString(currentColorType.property, hexColor)"))
    assertFalse(colorsCard.contains("property.replace(\"sub-\", \"secondary-sub-\")"))
    assertTrue(subtitlePositioning.contains("PlaybackSession.setPropertyString(\"secondary-sub-ass-override\""))
    assertTrue(playerActivity.contains("forceAssOverride = subtitlesPreferences.overrideAssSubs.get()"))
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
