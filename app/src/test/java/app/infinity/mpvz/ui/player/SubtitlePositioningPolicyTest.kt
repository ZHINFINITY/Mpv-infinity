package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlePositioningPolicyTest {
  @Test
  fun secondarySubtitleActivationRespectsTheAssOverridePreference() {
    assertEquals("scale", subtitleAssOverrideValue(forceAssOverride = false))
    assertEquals("force", subtitleAssOverrideValue(forceAssOverride = true))
  }
}
