package app.infinity.mpvz.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassSurfacePolicyTest {
  @Test fun opacityIsBounded() {
    assertEquals(GlassSurfacePolicy.MinOpacity, GlassSurfacePolicy.opacity(0f), 0f)
    assertEquals(GlassSurfacePolicy.MaxOpacity, GlassSurfacePolicy.opacity(2f), 0f)
    assertEquals(1f, GlassSurfacePolicy.opacity(1f), 0f)
  }

  @Test fun highlightIsBounded() {
    assertEquals(GlassSurfacePolicy.MinHighlight, GlassSurfacePolicy.highlight(-1f), 0f)
    assertEquals(GlassSurfacePolicy.MaxHighlight, GlassSurfacePolicy.highlight(1f), 0f)
  }

  @Test fun invalidImportedSettingsUseReadableDefaults() {
    assertEquals(0.88f, GlassSurfacePolicy.opacity(Float.NaN), 0f)
    assertEquals(0.88f, GlassSurfacePolicy.opacity(Float.POSITIVE_INFINITY), 0f)
    assertEquals(0.18f, GlassSurfacePolicy.highlight(Float.NEGATIVE_INFINITY), 0f)
  }
}
