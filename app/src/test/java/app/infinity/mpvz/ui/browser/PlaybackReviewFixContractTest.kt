/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReviewFixContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  private fun read(path: String) = File(root, path).readText()

  @Test
  fun gridAndListUseSwipeOnlyMarkAsWithoutThreeDotMenu() {
    val screen = read("app/src/main/java/app/infinity/mpvz/ui/browser/videolist/VideoListScreen.kt")
    val card = read("app/src/main/java/app/infinity/mpvz/ui/browser/cards/VideoCard.kt")

    // The 3-dot mark-as menu was removed in favor of swipe-only mark-as
    // (commit 23a52905); the card keeps its accessible mark-as description.
    assertEquals(0, Regex("onMarkAsClick =").findAll(screen).count())
    assertEquals(0, Regex("""if \(selectionManager\.isInSelectionMode\) null""").findAll(screen).count())
    assertTrue(card.contains("contentDescription = stringResource(R.string.video_mark_as_action)"))
  }

  @Test
  fun subtitleDelayGestureStateAndEffectAreKeyedToDelayType() {
    val panel = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/panels/SubtitleDelayPanel.kt")

    assertTrue(panel.contains("remember(delayType) { mutableStateOf<Boolean?>(null) }"))
    assertTrue(panel.contains("remember(delayType) { mutableStateOf(delay) }"))
    assertTrue(panel.contains("LaunchedEffect(delayType, isDirectionPositive)"))
  }

  @Test
  fun subtitleDialogSeekUsesTheSelectedTrackLane() {
    val gesture = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/GestureHandler.kt")

    assertTrue(gesture.contains("val track = if (subtitleTouchLane == SubtitleLane.Secondary) \"secondary\" else \"primary\""))
    assertTrue(gesture.contains("PlaybackSession.command(\"sub-seek\", direction, track)"))
  }
}
