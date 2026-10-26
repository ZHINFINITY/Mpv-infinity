/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls

import androidx.compose.ui.graphics.Color
import app.infinity.mpvz.ui.components.GlassChipMaterial
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 1942 audit: depth details of the glass controls that a screenshot review would miss. */
class GlassControlDepthContractTest {
  private fun source(path: String): String {
    val direct = File(path)
    return (if (direct.exists()) direct else File("app/$path")).readText()
  }

  private val controls = source("src/main/java/app/infinity/mpvz/ui/player/controls/components/GlassControls.kt")

  @Test fun glassSwitchThumbIsSolidContrastColourNotTranslucentWhite() {
    // uncheckedThumb is light on dark themes and dark on light themes (SwitchContrastPolicy).
    assertTrue(controls.contains(".glassCapsuleBody(contrast.checkedThumb, enabled, checked, base = contrast.uncheckedThumb)"))
    assertTrue(controls.contains("if (base != null) {"))
  }

  @Test fun capsuleThumbsCastAContactShadowAndSwitchGrooveFitsTheThumb() {
    assertTrue(controls.contains("// Contact shadow: the thumb floats just above its groove."))
    assertTrue(controls.contains("overhang = GlassControlGeometryPolicy.switchTrackOverhangDp().dp"))
    assertTrue(controls.contains("val left = -extra"))
  }

  @Test fun sliderFillIsRaisedAndGlossyInsideASunkenGroove() {
    assertTrue(controls.contains("// Sunken groove: shaded under its top lip, lit along its lower lip."))
    assertTrue(controls.contains("// Raised accent fill: glossy crest on top, deeper accent underneath."))
    assertTrue(controls.contains("drawLine(Color.White.copy(alpha = (.42f * highlight)"))
  }

  @Test fun glassChipHasAFrostedBodyAndRimBetweenClipAndPressFeedback() {
    val chip = source("src/main/java/app/infinity/mpvz/ui/components/GlassChip.kt")
    val clip = chip.indexOf(".clip(chipShape)")
    val body = chip.indexOf(".background(Brush.verticalGradient(fill), chipShape)", clip)
    val rim = chip.indexOf(".glassRimLight(chipShape", body)
    val feedback = chip.indexOf(".glassPressFeedback", rim)
    assertTrue("glass chip must not be a bare outline", clip in 0 until body && body < rim && rim < feedback)
  }

  @Test fun selectedChipGlowsTowardTheAccentAndDisabledDims() {
    val accent = Color(0xFF3366FF)
    val selected = GlassChipMaterial.fill(selected = true, enabled = true, accent = accent, onSurface = Color.White)
    val idle = GlassChipMaterial.fill(selected = false, enabled = true, accent = accent, onSurface = Color.White)
    val disabled = GlassChipMaterial.fill(selected = true, enabled = false, accent = accent, onSurface = Color.White)
    assertEquals(accent.red, selected.first().red, 0.001f)
    assertTrue(selected.first().alpha > idle.first().alpha)
    assertTrue(disabled.first().alpha < selected.first().alpha)
    assertTrue(idle.all { it.alpha > 0f })
  }

  @Test fun newGlassSettingsStringsAreTranslatedInEveryShippedLocale() {
    val keys = listOf(
      "pref_glass_preview", "pref_glass_rim_strength", "pref_glass_rim_strength_summary",
      "pref_glass_rim_width", "pref_glass_rim_width_summary", "pref_glass_rim_softness",
      "pref_glass_rim_softness_summary",
    )
    fun values(path: String): Map<String, String> {
      val xml = source(path)
      return keys.associateWith { key ->
        Regex("<string name=\"$key\">(.*?)</string>").find(xml)?.groupValues?.get(1) ?: error("$path misses $key")
      }
    }
    val english = values("src/main/res/values/strings.xml")
    val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
    val locales = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
    assertTrue(locales.size >= 14)
    locales.forEach { dir ->
      val translated = values("src/main/res/${dir.name}/strings.xml")
      keys.forEach { key -> assertTrue("${dir.name}/$key is still English", translated[key] != english[key]) }
    }
  }
}
