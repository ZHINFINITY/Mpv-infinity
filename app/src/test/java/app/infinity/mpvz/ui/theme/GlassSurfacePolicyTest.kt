package app.infinity.mpvz.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class GlassSurfacePolicyTest {
  @Test fun opacityIsBoundedAndOpaqueFallbackIsStable() {
    assertEquals(GlassSurfacePolicy.MinOpacity, GlassSurfacePolicy.opacity(0f))
    assertEquals(GlassSurfacePolicy.MaxOpacity, GlassSurfacePolicy.opacity(2f))
    assertEquals(1f, GlassSurfacePolicy.opacity(1f))
  }

  @Test fun highlightCannotBecomeAnUnreadableGlow() {
    assertEquals(GlassSurfacePolicy.MinHighlight, GlassSurfacePolicy.highlight(-1f))
    assertEquals(GlassSurfacePolicy.MaxHighlight, GlassSurfacePolicy.highlight(1f))
  }
}
