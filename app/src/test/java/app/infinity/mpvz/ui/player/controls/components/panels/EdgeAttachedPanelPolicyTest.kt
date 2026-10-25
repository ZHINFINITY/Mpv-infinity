/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls.components.panels

import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeAttachedPanelPolicyTest {
  @Test fun attachmentIsOptInAndLandscapeOnly() {
    assertTrue(EdgeAttachedPanelPolicy.attaches(requested = true, portrait = false))
    assertFalse(EdgeAttachedPanelPolicy.attaches(requested = true, portrait = true))
    assertFalse(EdgeAttachedPanelPolicy.attaches(requested = false, portrait = false))
  }

  @Test fun attachedPanelsTouchTopBottomAndEndButLeaveTheStartFree() {
    assertEquals(
      setOf(SideSheetEdge.Top, SideSheetEdge.Bottom, SideSheetEdge.End),
      EdgeAttachedPanelPolicy.attachedEdges(attached = true),
    )
    assertTrue(EdgeAttachedPanelPolicy.attachedEdges(attached = false).isEmpty())
  }

  @Test fun onlyInnerCornersAreRoundedAndMirrorForRtl() {
    assertEquals(
      setOf(SideSheetCorner.TopStart, SideSheetCorner.BottomStart),
      EdgeAttachedPanelPolicy.innerCorners(LayoutDirection.Ltr),
    )
    assertEquals(
      setOf(SideSheetCorner.TopEnd, SideSheetCorner.BottomEnd),
      EdgeAttachedPanelPolicy.innerCorners(LayoutDirection.Rtl),
    )
  }

  @Test fun bleedIsTheGapBetweenTheHostAndTheWindowEdges() {
    // Host inset by 30px at the top, 20px at the bottom and 44px on the right of a 2400x1080 root.
    val ltr = EdgeAttachedPanelPolicy.bleed(
      hostLeft = 0, hostTop = 30, hostRight = 2356, hostBottom = 1060,
      rootWidth = 2400, rootHeight = 1080, layoutDirection = LayoutDirection.Ltr,
    )
    assertEquals(EdgeBleed(top = 30, bottom = 20, end = 44), ltr)
    // In RTL the panel sits at the left, so the end gap is the host's left offset.
    val rtl = EdgeAttachedPanelPolicy.bleed(
      hostLeft = 44, hostTop = 0, hostRight = 2400, hostBottom = 1080,
      rootWidth = 2400, rootHeight = 1080, layoutDirection = LayoutDirection.Rtl,
    )
    assertEquals(EdgeBleed(top = 0, bottom = 0, end = 44), rtl)
  }

  @Test fun fullScreenHostNeedsNoBleed() {
    assertEquals(
      EdgeBleed.Zero,
      EdgeAttachedPanelPolicy.bleed(0, 0, 2400, 1080, 2400, 1080, LayoutDirection.Ltr),
    )
  }

  @Test fun slideAnimationPastTheEdgeClampsAndSettlesToTheRestingGap() {
    // Mid slide-in the host is translated 200px to the right, past the window edge.
    val midSlide = EdgeAttachedPanelPolicy.bleed(200, 0, 2600, 1080, 2400, 1080, LayoutDirection.Ltr)
    assertEquals(0, midSlide.end)
    val resting = EdgeBleed(top = 0, bottom = 0, end = 44)
    // The largest gap seen wins, so frames during the slide don't shrink or relayout the glass.
    assertEquals(resting, EdgeAttachedPanelPolicy.settle(resting, midSlide))
    assertEquals(resting, EdgeAttachedPanelPolicy.settle(EdgeBleed.Zero, resting))
  }

  @Test fun contentStaysClearOfCutoutsAndSystemBars() {
    assertEquals(96, EdgeAttachedPanelPolicy.contentInset(bleedPx = 0, safeInsetPx = 96))
    assertEquals(44, EdgeAttachedPanelPolicy.contentInset(bleedPx = 44, safeInsetPx = 0))
    assertEquals(0, EdgeAttachedPanelPolicy.contentInset(bleedPx = -5, safeInsetPx = -1))
  }

  @Test fun attachedPanelsCannotBeDraggedAwayFromTheEdge() {
    assertEquals(0f..0f, EdgeAttachedPanelPolicy.dragRange(attached = true, freeSpacePx = 900f))
    assertEquals(-900f..0f, EdgeAttachedPanelPolicy.dragRange(attached = false, freeSpacePx = 900f))
    assertEquals(0f..0f, EdgeAttachedPanelPolicy.dragRange(attached = false, freeSpacePx = -3f))
  }

  @Test fun onlyTheGridMoreDrawerAndSubtitleSettingsOptIn() {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main").isDirectory }
    val main = File(root, "app/src/main/java")
    val optIns = main.walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .filter { it.readText().contains("attachToScreenEdges = true") }
      .map { it.name }
      .toSet()
    assertEquals(setOf("PlayerControlDrawer.kt", "SubtitleSettingsPanel.kt"), optIns)

    val panel = File(main, "app/infinity/mpvz/ui/player/controls/components/panels/DraggablePanel.kt").readText()
    assertTrue(panel.contains("attachToScreenEdges: Boolean = false,"))
    assertTrue(panel.contains("EdgeAttachedPanelPolicy.shape(layoutDirection)"))
    assertTrue(panel.contains("findRootCoordinates()"))
    assertTrue(panel.contains("WindowInsets.safeDrawing"))
    // Panels that don't opt in keep the 1909 width, height cap and full rounded shape.
    assertTrue(panel.contains(".heightIn(max = panelMaxHeight)"))
    assertTrue(panel.contains("shape ?: MaterialTheme.shapes.extraLarge"))

    val panels = File(main, "app/infinity/mpvz/ui/player/controls/PlayerPanels.kt").readText()
    assertTrue(panels.contains(".using(SizeTransform(clip = false))"))
  }
}
