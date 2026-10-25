/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.presentation.components

import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PlayerSheetLayoutPolicyTest {
  @Test fun landscapeUsesAllScreenEdgesForGlassAndSafeInsetsForContent() {
    val edges = SideSheetEdge.entries.toSet()
    assertEquals(edges, PlayerSideSheetLayoutPolicy.backgroundBleedsToScreenEdges(true))
    assertEquals(edges, PlayerSideSheetLayoutPolicy.contentUsesSafeInsets(true))
    assertTrue(PlayerSideSheetLayoutPolicy.backgroundBleedsToScreenEdges(false).isEmpty())
    assertTrue(PlayerSideSheetLayoutPolicy.contentUsesSafeInsets(false).isEmpty())
  }

  @Test fun innerCornersFollowTheFreeEdgeAndMirrorForRtl() {
    assertEquals(
      setOf(SideSheetCorner.TopStart, SideSheetCorner.BottomStart),
      PlayerSideSheetLayoutPolicy.innerCorners(LayoutDirection.Ltr),
    )
    assertEquals(
      setOf(SideSheetCorner.TopEnd, SideSheetCorner.BottomEnd),
      PlayerSideSheetLayoutPolicy.innerCorners(LayoutDirection.Rtl),
    )
  }

  @Test fun playerSheetWiringKeepsBackgroundUninsetAndContentSafe() {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main").isDirectory }
    val source = File(
      root,
      "app/src/main/java/app/infinity/mpvz/presentation/components/PlayerSheet.kt",
    ).readText()
    assertTrue(source.contains("Modifier.fillMaxHeight().sizeIn(maxWidth = maxWidth)"))
    assertTrue(source.contains("Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing))"))
    assertTrue(source.contains(".glassRimLight("))
    assertTrue(source.contains("PlayerSideSheetLayoutPolicy.shape(layoutDirection)"))
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
}
