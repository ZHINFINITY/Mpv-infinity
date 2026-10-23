/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 1897: player sheets and the chapter pill must follow the ambience, not solid black. */
class PlayerAmbientTintTest {
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private fun read(path: String) = File(root, path).readText()
  private val themed = Color(0xFF1E1E22)
  private val ambient = Color(0xFF3060C0)

  @After fun reset() = PlayerAmbientTint.clear()

  @Test fun playerHostAdoptsDarkenedAmbientColour() {
    val film = PlayerAmbientTintPolicy.filmColor(themed, ambient, isPlayerHost = true)
    assertNotEquals(themed, film)
    // Same hue family, darker for white text legibility, fully opaque before filmAlpha.
    assertTrue(film.blue > film.red && film.blue > film.green)
    assertTrue(film.blue < ambient.blue)
    assertEquals(1f, film.alpha)
  }

  @Test fun browserAndSettingsPanesKeepThemedFilm() {
    assertEquals(themed, PlayerAmbientTintPolicy.filmColor(themed, ambient, isPlayerHost = false))
  }

  @Test fun noAmbientKeepsThemedFilm() {
    assertEquals(themed, PlayerAmbientTintPolicy.filmColor(themed, null, isPlayerHost = true))
  }

  @Test fun publishNormalisesAlphaAndClearRemovesTint() {
    PlayerAmbientTint.publish(ambient.copy(alpha = 0.4f))
    assertEquals(1f, PlayerAmbientTint.color!!.alpha)
    PlayerAmbientTint.publish(Color.Transparent)
    assertNull(PlayerAmbientTint.color)
    PlayerAmbientTint.publish(ambient)
    PlayerAmbientTint.clear()
    assertNull(PlayerAmbientTint.color)
  }

  @Test fun ambientCompositionPublishesOnlyWhilePresentedAndClearsOnDispose() {
    val activity = read("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    assertTrue(activity.contains("if (presentationActive) ambientFrame.base else null"))
    assertTrue(activity.contains("onDispose { app.infinity.mpvz.ui.theme.PlayerAmbientTint.clear() }"))
  }

  @Test fun glassFilmReadsAmbientInDrawPhaseForPlayerHostsOnly() {
    val pane = read("app/src/main/java/app/infinity/mpvz/ui/theme/LiquidGlassBackdrop.kt")
    val draw = pane.indexOf("PlayerAmbientTintPolicy.filmColor(themedTint, PlayerAmbientTint.color,")
    assertTrue(draw > 0)
    assertTrue(pane.substring(draw, draw + 200).contains("isPlayerHost = backdrop != null"))
    // Read inside the onDrawBehind material pass, not during composition.
    assertTrue(pane.lastIndexOf("onDrawBehind", draw) > pane.lastIndexOf("fun Modifier.liquidGlassPane", draw))
  }

  @Test fun seekbarAccentFollowsPlayerAmbience() {
    val seekbar = read("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/Seekbar.kt")
    assertTrue(seekbar.contains("private fun seekbarAccentColor(): Color"))
    assertTrue(seekbar.contains("PlayerAmbientTintPolicy.filmColor("))
    assertTrue(seekbar.contains("ambient = PlayerAmbientTint.color"))
    assertFalse(seekbar.contains("val primaryColor = MaterialTheme.colorScheme.primary"))
  }
}
