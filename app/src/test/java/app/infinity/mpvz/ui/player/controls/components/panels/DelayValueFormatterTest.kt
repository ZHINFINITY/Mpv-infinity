package app.infinity.mpvz.ui.player.controls.components.panels

import org.junit.Assert.assertEquals
import org.junit.Test

class DelayValueFormatterTest {
  @Test
  fun delayValuesAlwaysShowTwoDecimalPlaces() {
    assertEquals("0.00", formatDelaySeconds(0f))
    assertEquals("1.20", formatDelaySeconds(1.2f))
    assertEquals("-0.50", formatDelaySeconds(-0.5f))
    assertEquals("0.33", formatDelaySeconds(1f / 3f))
  }
}
