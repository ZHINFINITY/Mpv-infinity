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
    assertTrue(branch.contains(".widthIn(min = 176.dp)"))
    assertFalse(branch.contains("timeNetworkSlotWidth"))
    assertFalse(branch.contains("Modifier.width(slotWidth)"))
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
    assertTrue(editor.contains("val serialized = selectedButtons.joinToString(\",\")"))
    assertTrue(editor.contains("prefToEdit.set(serialized)"))
    assertTrue(preferences.contains("PlayerButton.valueOf(name)"))
    assertTrue(controls.contains("parseButtons(topRightControlsPref, usedButtons)"))
    assertTrue(controls.contains("parseButtons(portraitBottomControlsPref, mutableSetOf())"))
    assertTrue(controls.contains("buttons = topRightButtons"))
    assertTrue(controls.contains("buttons = portraitBottomButtons"))
    assertTrue(controls.contains("visible = { visibility -> visibility.first && !isPortrait }"))
    assertTrue(controls.contains("controlVisibilityTransition.AnimatedVisibility("))
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
    val topRightPlacement =
      controls.substringAfter("constrainAs(topRightControls) {").substringBefore("},")
    val topRightButtons =
      landscape.substringAfter("fun TopRightPlayerControlsLandscape(").substringBefore("@Composable\nfun BottomRightPlayerControlsLandscape(")
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
    val landscapeLeft =
      controls.substringAfter("constrainAs(bottomLeftControls) {").substringBefore("},")
    val landscapeLeftAbove = landscapeLeft.substringAfter("} else {")

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
    assertTrue(controls.contains("bottom.linkTo(parent.bottom, spacing.large)"))
    assertTrue(controls.contains("createTopBarrier(*landscapeControlBandRefs.toTypedArray())"))
    assertTrue(controls.contains("bottom.linkTo(landscapeControlBandTop, spacing.medium)"))
    assertTrue(controls.contains("end.linkTo(playerPauseButton.start, spacing.small)"))
    assertTrue(controls.contains("start.linkTo(playerPauseButton.end, spacing.small)"))
    assertTrue(controls.contains("bottom.linkTo(seekbar.top, spacing.medium)"))
    assertTrue(landscapeRightBelow.contains("start.linkTo(playerPauseButton.end, spacing.small)"))
    assertTrue(landscapeRightBelow.contains("width = Dimension.fillToConstraints"))
    assertTrue(landscapeRightAbove.contains("start.linkTo(landscapeSideControlSplit, spacing.small)"))
    assertTrue(landscapeRightAbove.contains("end.linkTo(parent.end, spacing.large)"))
    assertTrue(landscapeRightAbove.contains("width = Dimension.fillToConstraints"))
    assertTrue(landscapeLeftAbove.contains("end.linkTo(landscapeSideControlSplit, spacing.small)"))
    assertTrue(landscapeLeftAbove.contains("width = Dimension.fillToConstraints"))
    assertTrue(topRightPlacement.contains("width = Dimension.preferredWrapContent"))
    assertFalse(topRightPlacement.contains("start.linkTo(parent.start"))
    assertTrue(controls.contains("end.linkTo(playerPauseButton.start, spacing.small)"))
    assertTrue(controls.contains("start.linkTo(playerPauseButton.end, spacing.small)"))
    assertTrue(controls.contains("width = Dimension.fillToConstraints"))
    assertTrue(controls.contains("Dimension.preferredWrapContent"))
    assertTrue(landscape.contains("buttons.forEach { button ->"))
    assertTrue(landscape.contains("RenderPlayerButton("))
    assertTrue(landscape.contains("private fun LandscapePlayerControlStrip("))
    assertTrue(landscape.contains("PlayerControlScrollStrip("))
    assertTrue(landscape.contains("showEdgeFades = true"))
    assertTrue(landscape.contains("reverseScrolling = reverseScrolling"))
    assertTrue(landscape.contains("if (scrollable)"))
    assertFalse(topRightButtons.contains("LandscapePlayerControlStrip"))
    assertTrue(controls.contains("scrollable = true"))
    assertTrue(controls.contains("landscapeSideControlSplit = createGuidelineFromStart(0.5f)"))
    assertFalse(landscape.contains("scrollable = playbackControlsPosition == PlaybackControlsPosition.BelowSeekbar"))
    assertFalse(landscape.contains("FlowRow("))
    assertTrue(landscape.contains("alignment = Alignment.End"))
    assertTrue(controls.contains("PlayerControlScrollStrip("))
    assertTrue(controls.contains("alignment = Alignment.Start"))
    assertTrue(controls.contains("alignment = Alignment.End"))
    assertTrue(controls.contains("reverseScrolling = true"))
    assertTrue(controls.contains("showEdgeFades = true"))
    assertFalse(controls.contains("horizontalScroll(customButtonScrollState"))
    assertTrue(landscape.contains("buttonSize = 45.dp"))
  }

  @Test
  fun videoStartupAutoHidesDuringBufferingOrFirstFrameWaitAndPublishesCustomButtonsBeforeScriptWork() {
    val controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
    val viewModel = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerViewModel.kt")
    val activity = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val autoHideEffect = controls.substringAfter("LaunchedEffect(\n    controlsShown,").substringBefore("\n  val videoOpenAnim")
    val setup =
      viewModel.substringAfter("private fun setupCustomButtons()")
        .substringBefore("private fun reloadCustomButtonsScript")
    val buttonPreflight =
      activity.substringAfter("private suspend fun issuePlaybackLoad(")
        .substringBefore("val (savedPlaybackState, radioMimeType, ytdlpReady)")
    val uiPublishIndex = setup.indexOf("_customButtons.value = parseCustomButtonUiStates(jsonString)")
    val backgroundSetupIndex = setup.indexOf("viewModelScope.launch(Dispatchers.IO)")

    assertTrue(autoHideEffect.contains("isWaitingForVideo,"))
    assertTrue(autoHideEffect.contains("showBufferingIndicator,"))
    assertTrue(autoHideEffect.contains("shouldAutoHideControls("))
    assertTrue(autoHideEffect.contains("bufferingOrWaiting = showBufferingIndicator || isWaitingForVideo"))
    assertTrue(controls.contains("controlsShown = controlsShown,"))
    assertFalse(controls.contains("controlsShown = controlsShown || showBufferingIndicator"))
    assertTrue(uiPublishIndex >= 0)
    assertTrue(backgroundSetupIndex > uiPublishIndex)
    assertTrue(buttonPreflight.contains("if (looksLikeWebSource && !item.isDefinitelyAudioOnly())"))
    assertTrue(buttonPreflight.contains("if (!requiresYtdlpForPreflight) ytdlpPreparationJob?.cancel()"))
  }

  @Test
  fun layoutSettingsShowCompactPreviewAndBufferingIndicatorStaysIndependentOfControls() {
    val settings = read("app/src/main/java/app/infinity/mpvz/ui/preferences/PlayerControlsPreferencesScreen.kt")
    val editor = read("app/src/main/java/app/infinity/mpvz/ui/preferences/ControlLayoutEditorScreen.kt")
    val preview = read("app/src/main/java/app/infinity/mpvz/ui/preferences/PlayerLayoutPreview.kt")
    val controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")

    assertTrue(editor.contains("PlayerLayoutPreview("))
    assertTrue(editor.contains("previewTopRightButtons"))
    assertTrue(editor.contains("previewBottomLeftButtons"))
    assertTrue(editor.contains("position = playbackControlsPosition"))
    assertTrue(preview.contains("VIDEO PLAYER"))
    assertTrue(preview.contains("BoxWithConstraints("))
    assertTrue(preview.contains("frameWidth = minOf(maxWidth, if (isPortraitPreview) 190.dp else 540.dp)"))
    assertTrue(preview.contains("val sceneWidth = (if (isPortraitPreview) shortScreenEdge else longScreenEdge).dp"))
    assertTrue(preview.contains("val sceneHeight = (if (isPortraitPreview) longScreenEdge else shortScreenEdge).dp"))
    assertTrue(preview.contains("frameHeight = frameWidth / (sceneWidth.value / sceneHeight.value)"))
    assertTrue(preview.contains("val frameScale = frameWidth.value / sceneWidth.value"))
    assertTrue(preview.contains("if (!isPortraitPreview)"))
    assertTrue(preview.contains("val iconTouchSize = 48.dp"))
    assertTrue(preview.contains("val skipVisualSize = PlayerControlsGeometry.PlaylistSkipButtonSize"))
    assertTrue(preview.contains("val playVisualSize = PlayerControlsGeometry.PlayPauseButtonSize"))
    assertTrue(preview.contains("Constraints.fixed(sceneWidth.roundToPx(), sceneHeight.roundToPx())"))
    assertTrue(preview.contains("transformOrigin = TransformOrigin(0f, 0f)"))
    assertTrue(preview.contains(".previewScrollHints(listState, reverseLayout = reverseScrolling)"))
    assertTrue(preview.contains("state.canScrollBackward"))
    assertTrue(preview.contains("state.canScrollForward"))
    assertTrue(preview.contains("PlaybackControlsPosition.AboveSeekbar"))
    assertTrue(preview.contains("PlaybackControlsPosition.BelowSeekbar"))
    assertFalse(preview.contains("Infinity layout"))
    assertFalse(preview.contains("cubicTo("))
    assertTrue(controls.contains("PlayerControlVisibilityPolicy.shouldShowBufferingIndicator("))
    assertTrue(controls.contains("PlayerControlVisibilityPolicy.areButtonsVisible("))
    assertTrue(controls.contains("PlayerControlVisibilityPolicy.shouldShowPlaybackControls("))
    assertTrue(controls.contains("bufferingIndicatorVisible = showBufferingIndicator"))
    assertTrue(controls.contains("targetState = areButtonsVisible to seekBarControlsVisible"))
    assertTrue(controls.contains("controlVisibilityTransition.AnimatedVisibility("))
    assertTrue(controls.contains("visible = { visibility -> visibility.second }"))
    assertTrue(controls.contains("visible = showBufferingIndicator"))
    assertFalse(controls.contains("controlsShown = controlsShown || showBufferingIndicator"))
    assertFalse(controls.contains("isNativeBuffering"))
  }

  @Test
  fun controlEditorPreviewTracksSectionsAndBelowSeekbarScrollsBothSides() {
    val editor = read("app/src/main/java/app/infinity/mpvz/ui/preferences/ControlLayoutEditorScreen.kt")
    val settings = read("app/src/main/java/app/infinity/mpvz/ui/preferences/PlayerControlsPreferencesScreen.kt")
    val controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
    val landscape = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsLandscape.kt")
    val portrait = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsPortrait.kt")
    val preview = read("app/src/main/java/app/infinity/mpvz/ui/preferences/PlayerLayoutPreview.kt")

    assertTrue(editor.contains("PlayerLayoutPreview("))
    assertTrue(editor.contains("previewTopRightButtons"))
    assertTrue(editor.contains("activeRegion = region"))
    assertTrue(editor.contains("portraitBottomButtons = previewPortraitBottomButtons"))
    assertTrue(editor.contains("position = playbackControlsPosition"))
    assertFalse(settings.contains("PlayerLayoutPreview("))
    assertTrue(controls.contains("top.linkTo(parent.top)\n                  bottom.linkTo(parent.bottom)"))
    assertTrue(controls.contains("bottom.linkTo(portraitControlsAboveGroupTop, spacing.small)"))
    assertTrue(landscape.contains("PlayerControlScrollStrip("))
    assertTrue(landscape.contains("showEdgeFades = true"))
    assertTrue(landscape.contains("if (scrollable)"))
    assertFalse(landscape.contains("FlowRow("))
    assertTrue(portrait.contains("PlayerControlScrollStrip("))
    assertTrue(portrait.contains("showEdgeFades = true"))
    assertFalse(portrait.contains("FlowRow("))
    val strip = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlScrollStrip.kt")
    assertTrue(strip.contains("Modifier.horizontalScroll(scrollState, reverseScrolling = reverseScrolling).widthIn(min = maxWidth)"))
    assertTrue(strip.contains("modifier.fillMaxWidth().clipToBounds()"))
    assertTrue(preview.contains("AnimatedVisibility("))
    assertTrue(preview.contains("activeRegion == ControlRegion.PORTRAIT_BOTTOM"))
  }

  @Test
  fun browserVideoTilesAndPlayerPlaylistRemainAvailable() {
    val videoList = read("app/src/main/java/app/infinity/mpvz/ui/browser/videolist/VideoListScreen.kt")
    val playlists = read("app/src/main/java/app/infinity/mpvz/ui/browser/playlist/PlaylistScreen.kt")
    val playerSheets = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerSheets.kt")
    val playlistSheet = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/PlaylistSheet.kt")
    val activity = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val playlistPublisher =
      activity.substringAfter("private fun publishPlaylistToSession()")
        .substringBefore("private fun buildNetworkMediaIdentifier")
    val autoPlaylistLoader =
      activity.substringAfter("private suspend fun generatePlaylistFromFolderInternal(")
        .substringBefore("private fun isCurrentPlaylistM3U()")

    assertTrue(videoList.contains("LazyVerticalGrid("))
    assertTrue(videoList.contains("LazyColumn("))
    assertTrue(videoList.contains("VideoCard("))
    assertTrue(playlists.contains("LazyVerticalGrid("))
    assertTrue(playlists.contains("LazyColumn("))
    assertTrue(playlists.contains("PlaylistCard("))
    assertTrue(playerSheets.contains("Sheets.Playlist ->"))
    assertTrue(playerSheets.contains("PlaylistSheet("))
    assertTrue(playlistSheet.contains("fun PlaylistTrackListItem("))
    assertTrue(playlistSheet.contains("PlaylistThumbnail("))
    assertTrue(autoPlaylistLoader.contains("publishPlaylistToSession()"))
    assertTrue(playlistPublisher.contains("isExplicitQueue = true"))
  }

  @Test
  fun contentUriLaunchDefersProviderIoUntilPlaybackSessionLoad() {
    val activity = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val playableUri = activity.substringAfter("private fun getPlayableUri(intent: Intent): String? {")
      .substringBefore("override fun onConfigurationChanged(")
    val launchIdentity = activity.substringAfter("private fun getMediaIdentifier(")
      .substringBefore("private fun getLegacyMediaIdentifier(")
    val legacyIdentity = activity.substringAfter("private fun getLegacyMediaIdentifier(")
      .substringBefore("private fun loadNetworkPlaylistMetadata(")
    val playbackSession = read("app/src/main/java/app/infinity/mpvz/ui/player/PlaybackSession.kt")
    val contentUriResolver = playbackSession.substringAfter("private fun resolvePlayableUri(item: PlaybackItem): ResolvedPlayable {")
      .substringBefore("private fun releaseActiveNetworkStream()")
    val folderPlaylistLoader = activity.substringAfter("private fun generatePlaylistFromFolder(sourceIntent: Intent) {")
      .substringBefore("private suspend fun generatePlaylistFromFolderInternal(")
    val createPlaylistBranch = activity.substringAfter("// Only auto-generate playlist from folder")
      .substringBefore("// Extract fileName early")
    val newIntentPlaylistBranch = activity.substringAfter("// Auto-generate playlist from folder if playlist mode is enabled and no playlist_id")
      .substringBefore("// Extract the new fileName")
    val fileName = activity.substringAfter("private fun getFileName(intent: Intent): String {")
      .substringBefore("private fun extractFileNameFromUri(")

    assertTrue(playableUri.contains("sourceUri?.scheme.equals(\"content\", ignoreCase = true)"))
    assertTrue(playableUri.contains("return sourceUri.toString()"))
    assertTrue(playableUri.contains("intent.getStringExtra(\"local_media_path\")"))
    assertTrue(playableUri.contains("path.isNotBlank()"))
    assertFalse(playableUri.contains("File(path).canRead()"))
    assertTrue(playableUri.indexOf("if (sourceUri?.scheme.equals") < playableUri.indexOf("intent.getStringExtra(\"local_media_path\")"))
    assertFalse("The launch path must not open a one-shot content descriptor", playableUri.contains("openContentFd"))
    assertTrue("MPV must resolve provider URIs to a fresh path or fd off the Activity thread", contentUriResolver.contains(".openContentFd(context)"))
    assertTrue("A detached descriptor must be tracked until MPV consumes it", contentUriResolver.contains("ownedFd = ownedFd"))
    assertTrue(launchIdentity.contains("takeUnless { it.scheme.equals(\"content\", ignoreCase = true) }"))
    assertTrue(launchIdentity.indexOf("takeUnless") < launchIdentity.indexOf("resolveLocalPath(this)"))
    assertFalse("Content URI legacy identity must not query providers on the Activity thread", legacyIdentity.contains("resolveLocalPath(this)"))
    assertTrue(folderPlaylistLoader.contains("lifecycleScope.launch(Dispatchers.IO)"))
    assertTrue(folderPlaylistLoader.indexOf("parsePathFromIntent(sourceIntent)") > folderPlaylistLoader.indexOf("lifecycleScope.launch(Dispatchers.IO)"))
    assertTrue(activity.contains("generatePlaylistFromFolderInternal(path, sourceIntent)"))
    assertFalse(activity.contains("generatePlaylistFromFolderInternal(path)"))
    assertTrue(createPlaylistBranch.contains("generatePlaylistFromFolder(intent)"))
    assertFalse(createPlaylistBranch.contains("parsePathFromIntent(intent)"))
    assertTrue(newIntentPlaylistBranch.contains("generatePlaylistFromFolder(intent)"))
    assertFalse(newIntentPlaylistBranch.contains("parsePathFromIntent(intent)"))
    assertFalse("Document-provider title queries must not block Activity launch", fileName.contains("getDisplayNameFromUri"))
  }

  @Test
  fun remoteMkvProbeOverrideIsAttachedOnlyToTheCurrentLoadOptions() {
    val models = read("app/src/main/java/app/infinity/mpvz/ui/player/PlaybackModels.kt")
    val session = read("app/src/main/java/app/infinity/mpvz/ui/player/PlaybackSession.kt")
    val loadOptions = session.substringAfter("val loadOptions =").substringBefore("MPVLib.command(\"loadfile\"")

    assertTrue(models.contains("playableUri.startsWith(\"http://\", ignoreCase = true)"))
    assertTrue(models.contains("playableUri.startsWith(\"https://\", ignoreCase = true)"))
    assertTrue(loadOptions.contains("shouldSkipRemoteMkvDurationProbe(playableUri)"))
    assertTrue(loadOptions.contains("add(\"demuxer-mkv-probe-video-duration=no\")"))
  }

  @Test
  fun externalM3uPreflightDoesNotGatePlayerStartupOrDuplicateParsing() {
    val activity = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val startup = activity.substringAfter("if (shouldPreflightExternalM3u()) {")
      .substringBefore("private fun shouldPreflightExternalM3u")
    val startupPreflightBlock = startup.substringAfter("m3uPreflightJob = lifecycleScope.launch {")
      .substringBefore("\n      }")
    val dynamicLoad = activity.substringAfter("private suspend fun loadDynamicM3uPlaylist(")
      .substringBefore("private suspend fun")
    val newIntent = activity.substringAfter("override fun onNewIntent(intent: Intent)")
      .substringBefore("private fun handleNewIntent")
    val pendingNewIntent = newIntent.substringAfter("if (!playerInitializationComplete) {")
      .substringBefore("} else if (shouldPreflightExternalM3u(intent))")
    val newIntentPreflightBlock = pendingNewIntent.substringAfter("m3uPreflightJob = lifecycleScope.launch {")
      .substringBefore("\n        }")

    assertTrue(startup.contains("m3uPreflightJob = lifecycleScope.launch"))
    assertTrue(startup.contains("continuePlayerInitialization()"))
    assertTrue(startup.indexOf("continuePlayerInitialization()") > startup.indexOf("preflightExternalM3u(launchIntent, preflightGeneration)"))
    assertFalse(startupPreflightBlock.contains("continuePlayerInitialization()"))
    assertTrue(pendingNewIntent.contains("continuePlayerInitialization()"))
    assertFalse(newIntentPreflightBlock.contains("continuePlayerInitialization()"))
    assertTrue(dynamicLoad.contains("m3uPreflightJob?.join()"))
    assertTrue(dynamicLoad.contains("preflightM3uParse"))
    assertTrue(dynamicLoad.contains("it.generation == m3uPreflightGeneration && it.sourceUri == uriString"))
    assertTrue(dynamicLoad.contains("shouldRetryM3uPreflightFailure(cachedResult)"))
    assertTrue(activity.contains("message.startsWith(\"http error: 5\")"))
    assertTrue(activity.contains("M3UParseResult.Error(\"M3U preflight timed out\")"))
    assertTrue(activity.contains("if (generation != m3uPreflightGeneration) return"))
  }

  @Test
  fun initialMpvStartupMovesAssetAndCoreWorkOffMainWithoutChangingFlowSnapshotting() {
    val activity = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val mpvView = read("app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt")
    val session = read("app/src/main/java/app/infinity/mpvz/ui/player/PlaybackSession.kt")
    val initialization = activity.substringAfter("private fun continuePlayerInitialization()")
      .substringBefore("\n  private fun completePlayerCreation(")
    val assetPreparation = activity.substringAfter("private fun startMpvAssetPreparation()")
      .substringBefore("\n  /** Runs after owner handoff")
    val corePreparation = activity.substringAfter("private fun startMpvCorePreparation()")
      .substringBefore("\n  private suspend fun initializeMpvCoreWithOwnerGuard()")
    val playableUri = activity.substringAfter("private fun getPlayableUri(intent: Intent)")
      .substringBefore("\n  /**")
    val playerCompletion = activity.substringAfter("private fun completePlayerCreation(initError: String?)")
      .substringBefore("\n  private ")

    assertTrue(initialization.contains("playerInitializationStarted = true"))
    assertFalse(initialization.contains("playerInitializationComplete = true"))
    assertTrue(playerCompletion.contains("playerInitializationComplete = true"))
    assertTrue(initialization.contains("startMpvAssetPreparation()"))
    assertTrue(initialization.contains("releaseDetachedBackgroundPlaybackBeforeFreshLaunch()"))
    assertTrue(initialization.indexOf("releaseDetachedBackgroundPlaybackBeforeFreshLaunch()") < initialization.indexOf("startMpvCorePreparation()"))
    assertTrue(assetPreparation.contains("lifecycleScope.launch(Dispatchers.IO)"))
    assertTrue(corePreparation.contains("lifecycleScope.async(Dispatchers.IO)"))
    assertTrue(corePreparation.contains("mpvAssetPreparationJob?.join()"))
    assertTrue(corePreparation.contains("snapshotDisplayRefreshRateOnMain()"))
    assertTrue(activity.contains("initializePlayerWithRendererFallback(ownerIsActive)"))
    assertTrue(activity.contains("finishMPVSetup(initError)"))
    assertTrue(mpvView.contains("ownerIsActive = ownerIsActive"))
    assertTrue(mpvView.contains("decoderPreferences.mpvFlowFrameInterpolation.get()"))
    assertTrue(mpvView.contains("effectiveMpvFlowTargetFps("))
    assertTrue(session.contains("ensureOwnerActive()"))
    assertFalse(playableUri.contains("File(path).canRead()"))
    assertTrue(playableUri.indexOf("sourceUri?.scheme.equals(\"content\"") < playableUri.indexOf("local_media_path"))
  }

  @Test
  fun onlineVideoStartupOverlapsReadyChecksAndRevealsAllControls() {
    val activity = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val viewModel = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerViewModel.kt")
    val ytdlp = read("app/src/main/java/app/infinity/mpvz/ui/player/ytdlp/YtdlpManager.kt")
    val geometry = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsGeometry.kt")
    val shared = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsShared.kt")
    val frameBranch = shared.substringAfter("PlayerButton.FRAME_NAVIGATION ->").substringBefore("PlayerButton.VIDEO_ZOOM ->")
    val timeNetworkBranch = shared.substringAfter("PlayerButton.TIME_NETWORK ->").substringBefore("PlayerButton.NONE ->")
    val prepare = ytdlp.substringAfter("suspend fun prepareForPlayback(").substringBefore("/** Resolves a web page")
    val resolve = ytdlp.substringAfter("suspend fun resolveDirectMediaUrl(").substringBefore("/** Unwraps common provider")
    val cancellableProcess =
      ytdlp.substringAfter("private suspend fun executePythonProcessCancellable(")
        .substringBefore("private fun runPythonProcess(")
    val playbackPreflight =
      activity.substringAfter("val (preflight, resolvedPageMediaUrl) =")
        .substringBefore("val (savedPlaybackState, radioMimeType, ytdlpReady) = preflight")
    val radioProbeGate = activity.substringAfter("val shouldProbeRadio =").substringBefore("// Extensionless Icecast")
    val radioProbeAwait = activity.substringAfter("val radioMimeType =").substringBefore("ensureCurrentMediaRequest(requestGeneration)")
    val loadStart =
      viewModel.substringAfter("fun onVideoLoadStarted()")
        .substringAfter("{")
        .substringBefore("fun onVideoLoadCompleted()")

    assertTrue(activity.contains("val savedPlaybackStateJob ="))
    assertTrue(activity.contains("val ytdlpPreparationJob ="))
    assertTrue(activity.contains("coroutineScope {"))
    assertTrue(activity.contains("async(Dispatchers.IO)"))
    assertTrue(playbackPreflight.indexOf("YtdlpManager.resolveDirectMediaUrl") < playbackPreflight.indexOf("savedPlaybackStateJob.await()"))
    assertTrue(playbackPreflight.contains("resolvedPageMediaUrlJob?.await()"))
    assertTrue(loadStart.trimStart().startsWith("showControls()"))
    assertTrue(prepare.indexOf("isPlaybackRuntimeReady(context)") < prepare.indexOf("prepareRuntimeAssets(context, onLog)"))
    assertTrue(prepare.contains("installYtdlpForPlayback(context, onLog)"))
    assertTrue(resolve.contains("timeoutMs = PLAYBACK_DIRECT_URL_RESOLVE_TIMEOUT_MS"))
    assertTrue(ytdlp.contains("PLAYBACK_DIRECT_URL_RESOLVE_TIMEOUT_MS = 8_000L"))
    assertTrue(resolve.contains("unwrapProviderMediaUrl(source, onLog)?.let { return@withContext it }"))
    assertTrue(cancellableProcess.contains("withTimeoutOrNull(timeoutMs)"))
    assertTrue(cancellableProcess.contains("yt-dlp process timed out after"))
    assertTrue(cancellableProcess.contains("runInterruptible { process.waitFor() }"))
    assertTrue(cancellableProcess.contains("process.destroyForcibly()"))
    assertTrue(ytdlp.contains("PLAYBACK_RUNTIME_INSTALL_TIMEOUT_MS = 90_000L"))
    assertTrue(radioProbeGate.contains("!streamVideoHint"))
    assertTrue(radioProbeAwait.contains("withTimeoutOrNull(RADIO_PROBE_BUDGET_MS)"))
    assertTrue(geometry.contains("PlaylistSkipButtonSize = 48.dp"))
    assertTrue(geometry.contains("PlayPauseButtonSize = 56.dp"))
    assertTrue(frameBranch.contains("targetState = isExpanded && !compact"))
    assertTrue(frameBranch.contains("using(SizeTransform(clip = false))"))
    assertTrue(frameBranch.contains("Modifier.height(buttonSize).playerGlassPane"))
    assertFalse(frameBranch.contains("slotWidth"))
    assertTrue(timeNetworkBranch.contains("targetState = timeNetworkPillExpanded && !compact"))
    assertTrue(timeNetworkBranch.contains("using(SizeTransform(clip = false))"))
    assertTrue(timeNetworkBranch.contains(".widthIn(min = 176.dp)"))
    assertFalse(timeNetworkBranch.contains("slotWidth"))
  }

  @Test
  fun sampledPlayerGlassDoesNotStackTheIndependentPageLensPass() {
    val glass = read("app/src/main/java/app/infinity/mpvz/ui/theme/LiquidGlassBackdrop.kt")

    assertTrue(glass.contains("backdrop == null && LocalLensGlassEnabled.current && LocalPageBackdrop.current != null"))
    assertTrue(glass.contains("val sampled = withContext(Dispatchers.Default)"))
    assertTrue(glass.contains("captureBuffers.sampleMutex.withLock"))
    assertTrue(glass.contains(".glassRimLight(shape = shape"))
    assertTrue(glass.contains("drawRoundRect(Brush.verticalGradient"))
  }

  @Test
  fun sheetsUseOneSampledGlassPaneAndLightweightTintedInsets() {
    val playlist = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/PlaylistSheet.kt")
    val more = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/MoreSheet.kt")
    val surface = read("app/src/main/java/app/infinity/mpvz/ui/theme/GlassSurface.kt")

    assertTrue(playlist.contains("glassInsetSurface("))
    assertFalse(playlist.contains(".playerGlassPane("))
    assertTrue(playlist.contains("hasPositionedPlaylistViewport"))
    assertTrue(playlist.contains("if (animate) lazyListState.animateScrollToItem(playingItemIndex)"))
    assertTrue(playlist.contains("else lazyListState.scrollToItem(playingItemIndex)"))
    assertTrue(more.contains("glassInsetSurface("))
    assertFalse(more.contains(".playerGlassPane("))
    assertTrue(surface.contains("fun Modifier.glassInsetSurface("))
  }

  @Test
  fun startupControlsWaitForTheRequestedLayoutAndUseTheCurrentLaunchTitle() {
    val activity = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    val startupOrientation = activity.substringAfter("private fun applyInitialVideoOrientation(")
      .substringBefore("/** External players often provide a bare CDN URL")

    assertTrue(startupOrientation.contains("gateControlsUntilInitialOrientationSettles(targetLandscape)"))
    assertTrue(startupOrientation.contains("controls.visibility = View.GONE"))
    assertTrue(startupOrientation.contains("addOnLayoutChangeListener(listener)"))
    assertTrue(startupOrientation.contains("resources.configuration.orientation == expectedOrientation"))
    assertTrue(startupOrientation.contains("postOnAnimation"))
    assertTrue(activity.contains("startupControlsTargetLandscape?.let"))
    assertTrue(activity.contains("File(it).name.takeIf(String::isNotBlank)"))
    assertTrue(activity.indexOf("    setupPlayerControls()") > activity.indexOf("restorePreparedPlaybackQueue(intent)"))
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
  fun transportUsesAboveControlAnchorsAndPreservesBelowPlacement() {
    val preferences = read("app/src/main/java/app/infinity/mpvz/preferences/AppearancePreferences.kt")
    val controls = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
    val transport = controls.substringAfter("Modifier.constrainAs(playerPauseButton) {")
      .substringBefore("\n              },")
    val aboveBranch = transport.substringAfterLast("} else {")

    assertTrue(preferences.contains("preferenceStore.getEnum(\"portrait_playback_controls_position\", PlaybackControlsPosition.AboveSeekbar)"))
    assertTrue(aboveBranch.contains("top.linkTo(parent.top)"))
    assertTrue(aboveBranch.contains("bottom.linkTo(parent.bottom)"))
    assertFalse(aboveBranch.contains("landscapeSideControlBandTop"))
    assertTrue(transport.contains("bottom.linkTo(portraitControlsAboveGroupTop, spacing.small)"))
    assertTrue(transport.contains("bottom.linkTo(bottomRightControls.top, spacing.small)"))
    assertTrue(transport.contains("bottom.linkTo(parent.bottom, spacing.large)"))
    assertTrue(controls.contains("TopLeftPlayerControlsLandscape("))
    assertTrue(controls.contains("mediaTitle = mediaTitle"))
    assertTrue(controls.contains("val hasPlaylistControls ="))
    assertTrue(controls.contains("playlistMode && (playlistItems.size > 1 || viewModel.getPlaylistTotalCount() > 1)"))
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
