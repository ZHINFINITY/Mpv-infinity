package app.infinity.mpvz.ui.player.controls

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelCardFrostPolicyTest {
  private val darkContainer = Color(0xFF1B1B1F)
  private val onDark = Color(0xFFE4E1E6)

  @Test
  fun `without glass inner cards keep the classic readable surface`() {
    val c = PanelCardFrostPolicy.container(darkContainer, onDark, glass = false)
    assertEquals(0.85f, c.alpha, 0.001f)
    assertEquals(darkContainer.red, c.red, 0.001f)
  }

  @Test
  fun `with glass inner cards are a lighter translucent second frost layer`() {
    val c = PanelCardFrostPolicy.container(darkContainer, onDark, glass = true)
    // Clearly translucent, so it never reads as a near-solid black block over the sheet.
    assertTrue(c.alpha <= 0.45f)
    assertTrue(c.alpha >= 0.25f)
    // Lifted toward the content colour, so it still separates from the sheet behind it.
    assertTrue(c.red > darkContainer.red && c.green > darkContainer.green && c.blue > darkContainer.blue)
  }

  @Test
  fun `glass card ignores a pre-applied base alpha`() {
    val c = PanelCardFrostPolicy.container(darkContainer.copy(alpha = 0.1f), onDark, glass = true)
    assertEquals(PanelCardFrostPolicy.GlassAlpha, c.alpha, 0.001f)
  }
}
