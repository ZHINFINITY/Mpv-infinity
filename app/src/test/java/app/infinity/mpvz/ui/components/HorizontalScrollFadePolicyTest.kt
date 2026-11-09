package app.infinity.mpvz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalScrollFadePolicyTest {
  @Test
  fun normalScrollFadesOnlyEdgesWithHiddenContent() {
    assertEquals(
      HorizontalScrollFadeVisibility(start = false, end = false),
      horizontalScrollFadeVisibility(value = 0, maxValue = 0, reverseScrolling = false),
    )
    assertEquals(
      HorizontalScrollFadeVisibility(start = false, end = true),
      horizontalScrollFadeVisibility(value = 0, maxValue = 100, reverseScrolling = false),
    )
    assertEquals(
      HorizontalScrollFadeVisibility(start = true, end = true),
      horizontalScrollFadeVisibility(value = 50, maxValue = 100, reverseScrolling = false),
    )
    assertEquals(
      HorizontalScrollFadeVisibility(start = true, end = false),
      horizontalScrollFadeVisibility(value = 100, maxValue = 100, reverseScrolling = false),
    )
  }

  @Test
  fun reverseScrollMapsHiddenContentToOppositePhysicalEdge() {
    assertEquals(
      HorizontalScrollFadeVisibility(start = true, end = false),
      horizontalScrollFadeVisibility(value = 0, maxValue = 100, reverseScrolling = true),
    )
    assertEquals(
      HorizontalScrollFadeVisibility(start = true, end = true),
      horizontalScrollFadeVisibility(value = 50, maxValue = 100, reverseScrolling = true),
    )
    assertEquals(
      HorizontalScrollFadeVisibility(start = false, end = true),
      horizontalScrollFadeVisibility(value = 100, maxValue = 100, reverseScrolling = true),
    )
  }
}
