package app.infinity.mpvz.ui.player.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicMinimizeTransitionPolicyTest {
  private val screen = MusicMinimizeTransitionPolicy.Rect(0f, 0f, 400f, 800f)
  private val mini = MusicMinimizeTransitionPolicy.Rect(10f, 700f, 380f, 72f)
  private val fullArt = MusicMinimizeTransitionPolicy.Rect(50f, 100f, 300f, 300f)
  private val miniArt = MusicMinimizeTransitionPolicy.Rect(24f, 712f, 48f, 48f)

  @Test fun linearDragAndReleaseThresholds() {
    assertEquals(.75f, MusicMinimizeTransitionPolicy.dragValue(1f, 200f, 800f), .0001f)
    assertEquals(0f, MusicMinimizeTransitionPolicy.dragValue(1f, 900f, 800f), .0001f)
    assertEquals(0f, MusicMinimizeTransitionPolicy.settleTarget(.69f, 0f, true), .0001f)
    assertEquals(0f, MusicMinimizeTransitionPolicy.settleTarget(.95f, 1.01f, true), .0001f)
    assertEquals(1f, MusicMinimizeTransitionPolicy.settleTarget(.5f, 1f, false), .0001f)
  }

  @Test fun releaseUsesMornyeThresholdsInMiniProgressConvention() {
    // miniProgress 0 = open. Dragged 31% down while moving down: value .69 < .7 -> closes.
    assertEquals(1f, MusicMinimizeTransitionPolicy.releaseTargetMiniProgress(.31f, 10f, 800f, true), 0f)
    // Only 20% down and slow: settles back open.
    assertEquals(0f, MusicMinimizeTransitionPolicy.releaseTargetMiniProgress(.2f, 100f, 800f, true), 0f)
    // Fling faster than 1 page/s closes even from 5% travel.
    assertEquals(1f, MusicMinimizeTransitionPolicy.releaseTargetMiniProgress(.05f, 900f, 800f, true), 0f)
    // Far down but finger reversed upward slowly: back open.
    assertEquals(0f, MusicMinimizeTransitionPolicy.releaseTargetMiniProgress(.5f, -100f, 800f, false), 0f)
    // Invalid page height never divides by zero.
    assertEquals(0f, MusicMinimizeTransitionPolicy.releaseTargetMiniProgress(.1f, 5000f, 0f, true), 0f)
  }

  @Test fun durationsMatchCloseSettleAndOpenFromMini() {
    assertEquals(300, MusicMinimizeTransitionPolicy.settleDurationMs(1f, programmatic = false))
    assertEquals(300, MusicMinimizeTransitionPolicy.settleDurationMs(1f, programmatic = true))
    assertEquals(220, MusicMinimizeTransitionPolicy.settleDurationMs(0f, programmatic = false))
    assertEquals(400, MusicMinimizeTransitionPolicy.settleDurationMs(0f, programmatic = true))
  }

  @Test fun settleEasingStartsAtCurrentValue() {
    assertEquals(.42f, MusicMinimizeTransitionPolicy.easeOutCubic(.42f, 1f, 0f), .0001f)
    assertEquals(1f, MusicMinimizeTransitionPolicy.easeOutCubic(.42f, 1f, 1f), .0001f)
  }

  @Test fun openEndpointIsTheUntransformedFullPage() {
    // Regression: the open state must never be drawn as the mini rectangle.
    val open = MusicMinimizeTransitionPolicy.frame(0f, screen, mini, fullArt, miniArt)
    assertEquals(screen, open.page)
    assertEquals(1f, open.contentScale, 0f)
    assertEquals(1f, open.contentAlpha, 0f)
    assertEquals(0f, open.cornerRadiusDp, 0f)
    val layer = MusicMinimizeTransitionPolicy.pageLayer(open, 0f, 0f)
    assertEquals(MusicMinimizeTransitionPolicy.Layer(0f, 0f, 1f, 1f), layer)
    assertEquals(fullArt, open.artwork)
    assertEquals(12f, open.artworkRadiusDp, 0f)
  }

  @Test fun closedEndpointLandsExactlyOnTheMiniSurface() {
    val closed = MusicMinimizeTransitionPolicy.frame(1f, screen, mini, fullArt, miniArt)
    assertEquals(mini, closed.page)
    assertEquals(28f, closed.cornerRadiusDp, 0f)
    assertEquals(0f, closed.contentAlpha, 0f)
    assertEquals(mini.width / screen.width, closed.contentScale, .0001f)
    assertEquals(miniArt, closed.artwork)
    assertEquals(6f, closed.artworkRadiusDp, 0f)
  }

  @Test fun scaleFollowsProgressAndClipIsExpressedInLayerCoordinates() {
    val half = MusicMinimizeTransitionPolicy.frame(.5f, screen, mini, fullArt, miniArt)
    assertEquals(5f, half.page.left, .001f)
    assertEquals(14f, half.cornerRadiusDp, .001f)
    assertEquals(390f / 400f, half.contentScale, .001f)
    // After the layer's uniform scale, the pre-scale clip equals the page rect and radius.
    assertEquals(half.page.width, half.clipWidth * half.contentScale, .01f)
    assertEquals(half.page.height, half.clipHeight * half.contentScale, .01f)
    assertEquals(half.cornerRadiusDp, half.clipCornerRadiusDp * half.contentScale, .001f)
    assertEquals(1f, MusicMinimizeTransitionPolicy.frame(.1f, screen, mini, fullArt, miniArt).contentAlpha, .001f)
    assertEquals(.5f, half.contentAlpha, .001f)
    assertEquals(0f, MusicMinimizeTransitionPolicy.frame(.9f, screen, mini, fullArt, miniArt).contentAlpha, .001f)
  }

  @Test fun artworkLayerLandsOnTargetThroughTheParentScale() {
    for (p in listOf(0f, .3f, .5f, .8f, 1f)) {
      val frame = MusicMinimizeTransitionPolicy.frame(p, screen, mini, fullArt, miniArt)
      val parent = MusicMinimizeTransitionPolicy.pageLayer(frame, 0f, 0f)
      val child = MusicMinimizeTransitionPolicy.artworkLayer(frame, 0f, 0f, fullArt)
      // Compose: window = parentT + s * (childLocalOrigin + childT + childScale * r)
      val left = parent.translationX + parent.scaleX * (fullArt.left + child.translationX)
      val top = parent.translationY + parent.scaleY * (fullArt.top + child.translationY)
      val width = parent.scaleX * fullArt.width * child.scaleX
      val height = parent.scaleY * fullArt.height * child.scaleY
      assertEquals("left@$p", frame.artwork.left, left, .01f)
      assertEquals("top@$p", frame.artwork.top, top, .01f)
      assertEquals("width@$p", frame.artwork.width, width, .01f)
      assertEquals("height@$p", frame.artwork.height, height, .01f)
    }
  }

  @Test fun latestAnchorIsUsedAndStaleAnchorFallsBack() {
    MiniPlayerTransitionAnchor.publish(1f, 2f, 100f, 40f, 400f)
    MiniPlayerTransitionAnchor.publish(8f, 9f, 120f, 44f, 400f)
    assertEquals(8f, MusicMinimizeTransitionPolicy.currentAnchor(400f, 400f)!!.left, 0f)
    assertNull(MusicMinimizeTransitionPolicy.currentAnchor(400f, 900f))
    MiniPlayerTransitionAnchor.invalidate()
    assertTrue(MusicMinimizeTransitionPolicy.useSlideFallback(null, 400f, 800f))
  }

  @Test fun noOpaqueBackgroundDuringTransitionAndAudioOnlyWindow() {
    assertEquals(0f, MusicMinimizeTransitionPolicy.backgroundAlpha(false, true), 0f)
    assertEquals(0f, MusicMinimizeTransitionPolicy.backgroundAlpha(true, true), 0f)
    assertEquals(1f, MusicMinimizeTransitionPolicy.backgroundAlpha(true, false), 0f)
    assertTrue(MusicMinimizeTransitionPolicy.shouldTranslucent(true, 30))
    assertFalse(MusicMinimizeTransitionPolicy.shouldTranslucent(false, 34))
    assertFalse(MusicMinimizeTransitionPolicy.shouldTranslucent(true, 29))
  }
}
