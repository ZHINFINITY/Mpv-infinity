/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLayoutPreviewInteractionTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  private fun source(path: String): String = File(root, path).readText()

  @Test
  fun settingsPreviewSimulatesPlaybackAndEditsOnlyTheSelectedLayout() {
    val preview = source("app/src/main/java/app/infinity/mpvz/ui/preferences/PlayerLayoutPreview.kt")
    val editor = source("app/src/main/java/app/infinity/mpvz/ui/preferences/ControlLayoutEditorScreen.kt")

    assertTrue(preview.contains("rememberReorderableLazyListState"))
    assertTrue(preview.contains("ReorderableItem(reorderState, key = button)"))
    assertTrue(preview.contains("Modifier.draggableHandle()"))
    assertTrue(preview.contains("LazyRow("))
    assertTrue(preview.contains("detectTapGestures"))
    assertTrue(preview.contains("detectDragGestures"))
    assertTrue(preview.contains("description = \"Toggle simulated playback\""))
    assertTrue(preview.contains("previewProgress.floatValue"))
    assertTrue(editor.contains("onPreviewButtonTap = { button ->"))
    assertTrue(editor.contains("selectedButtons = selectedButtons - button"))
    assertTrue(editor.contains("onPreviewReorder = { fromButton, toButton ->"))
    assertTrue(editor.contains("add(toIndex, removeAt(fromIndex))"))
    assertFalse(preview.contains("PlayerViewModel"))
    assertFalse(preview.contains("PlaybackSession"))
    assertFalse(preview.contains("PlayerActivity"))
  }

  @Test
  fun overflowStripsUseTheSharedBoundedPlayerViewport() {
    val controls = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
    val landscape = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsLandscape.kt")
    val portrait = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsPortrait.kt")
    val preview = source("app/src/main/java/app/infinity/mpvz/ui/preferences/PlayerLayoutPreview.kt")
    val strip = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlScrollStrip.kt")

    assertEquals(2, Regex("scrollable = playbackControlsPosition == PlaybackControlsPosition.BelowSeekbar").findAll(controls).count())
    assertTrue(landscape.contains("PlayerControlScrollStrip("))
    assertTrue(landscape.contains("reverseScrolling = reverseScrolling"))
    assertTrue(portrait.contains("PlayerControlScrollStrip("))
    assertTrue(strip.contains("modifier.fillMaxWidth().clipToBounds()"))
    assertTrue(strip.contains("horizontalScroll(scrollState, reverseScrolling = reverseScrolling)"))
    assertTrue(preview.contains("LazyRow("))
    assertTrue(preview.contains("rememberLazyListState()"))
  }

  @Test
  fun transportButtonSizingIsSharedAndKeepsFortyEightDpSideTouchTargets() {
    assertEquals(48.dp, PlayerControlsGeometry.PlaylistSkipButtonSize)
    assertEquals(56.dp, PlayerControlsGeometry.PlayPauseButtonSize)
    assertEquals(16.dp, PlayerControlsGeometry.PlaylistButtonGap)
    val controls = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt")
    assertTrue(controls.contains(".size(PlayerControlsGeometry.PlaylistSkipButtonSize)"))
    assertTrue(controls.contains(".size(PlayerControlsGeometry.PlayPauseButtonSize)"))
    val preview = source("app/src/main/java/app/infinity/mpvz/ui/preferences/PlayerLayoutPreview.kt")
    assertTrue(preview.contains("PlayerControlsGeometry.PlaylistSkipButtonSize * frameScale"))
    assertTrue(preview.contains("PlayerControlsGeometry.PlayPauseButtonSize * frameScale"))
  }
}
