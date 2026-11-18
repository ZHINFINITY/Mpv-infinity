package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePositioningPolicyTest {
  @Test
  fun secondarySubtitleActivationRespectsTheAssOverridePreference() {
    assertEquals("no", subtitleAssOverrideValue(forceAssOverride = false))
    assertEquals("force", subtitleAssOverrideValue(forceAssOverride = true))
  }

  @Test
  fun secondaryPositionUsesManualOverrideOrAutomaticLayout() {
    assertEquals(74, resolveSecondarySubtitlePosition(74, -1))
    assertEquals(92, resolveSecondarySubtitlePosition(74, 92))
    assertEquals(150, resolveSecondarySubtitlePosition(74, 180))
  }

  @Test
  fun touchTargetsTheActiveSubtitleLaneAndChoosesNearestWhenBoundsOverlap() {
    assertEquals(SubtitleLane.Primary, resolveSubtitleLaneForTouch(true, false, 12f, Float.POSITIVE_INFINITY))
    assertEquals(SubtitleLane.Secondary, resolveSubtitleLaneForTouch(false, true, Float.POSITIVE_INFINITY, 9f))
    assertEquals(SubtitleLane.Secondary, resolveSubtitleLaneForTouch(true, true, 20f, 5f))
    assertEquals(SubtitleLane.Primary, resolveSubtitleLaneForTouch(true, true, 4f, 8f))
    assertNull(resolveSubtitleLaneForTouch(false, false, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))
  }

  @Test
  fun subtitleTouchBoundsIncludeTextAreaAndExcludeDistantGestures() {
    val horizontal = estimateSubtitleHorizontalHitboxBounds(
      text = "A short subtitle",
      fontSize = 36f,
      subScale = 1f,
      screenWidth = 1080f,
      screenHeight = 1920f,
      marginX = 25f,
    )
    val vertical = -50f to 150f

    assertTrue(isWithinSubtitleTouchBounds(540f, 1490f, 1500f, horizontal, vertical))
    assertFalse(isWithinSubtitleTouchBounds(0f, 1490f, 1500f, horizontal, vertical))
    assertFalse(isWithinSubtitleTouchBounds(540f, 1000f, 1500f, horizontal, vertical))
  }
}
