/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.presentation.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PlayerSheetLayoutPolicyTest {
  @Test fun playerSheetKeepsThe1909BottomPlacementInEveryOrientation() {
    // Build 1933: the owner asked for every sheet (3-dot More, decoder, audio, tracks...) to
    // return to its pre-1932 placement. Only the grid More drawer and the Subtitles panel,
    // both DraggablePanel side panels, attach to the display edges.
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main").isDirectory }
    val source = File(
      root,
      "app/src/main/java/app/infinity/mpvz/presentation/components/PlayerSheet.kt",
    ).readText()
    assertTrue(source.contains("contentAlignment = Alignment.BottomCenter,"))
    assertFalse(source.contains("Alignment.CenterEnd"))
    assertFalse(source.contains("PlayerSideSheetLayoutPolicy"))
    assertFalse(source.contains("WindowInsets.safeDrawing"))
    assertTrue(source.contains(".sizeIn(maxWidth = maxWidth, maxHeight = maxHeight)"))
    assertTrue(source.contains("thickSurface = true"))
  }

  @Test fun portraitSheetsReserveNinetyPercentWhenKeyboardIsHidden() {
    assertEquals(864f, PlayerSheetLayoutPolicy.maxHeightDp(960, portrait = true, imeVisible = false, customMaxHeightDp = null), 0.001f)
  }

  @Test fun keyboardGetsTheFullWindowAndDoesNotClipTheTopRow() {
    assertEquals(960f, PlayerSheetLayoutPolicy.maxHeightDp(960, portrait = true, imeVisible = true, customMaxHeightDp = null), 0.001f)
  }

  @Test fun explicitPlaylistHeightWinsOverOrientationDefaults() {
    assertEquals(480f, PlayerSheetLayoutPolicy.maxHeightDp(960, portrait = true, imeVisible = false, customMaxHeightDp = 480f), 0.001f)
  }

  @Test fun invalidWindowSizeIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      PlayerSheetLayoutPolicy.maxHeightDp(0, portrait = true, imeVisible = false, customMaxHeightDp = null)
    }
  }

  @Test fun artistTrackListsStayWellUnderHalfTheWindowSoTheAlbumArtIsVisible() {
    // The artist sheet used to grow to the 90% sheet limit and covered the playing album art.
    assertEquals(272f, PlayerSheetLayoutPolicy.listMaxHeightDp(800), 0.001f)
    val sheet = PlayerSheetLayoutPolicy.listSheetMaxHeightDp(800)
    assertTrue("sheet $sheet must leave the art visible", sheet <= 800f * 0.5f)
    assertTrue(PlayerSheetLayoutPolicy.listMaxHeightDp(360) >= 160f)
    assertTrue(PlayerSheetLayoutPolicy.listMaxHeightDp(2400) <= 320f)
    assertThrows(IllegalArgumentException::class.java) {
      PlayerSheetLayoutPolicy.listMaxHeightDp(0)
    }
  }
}
