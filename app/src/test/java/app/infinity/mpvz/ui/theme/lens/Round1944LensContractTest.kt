/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme.lens

import app.infinity.mpvz.ui.preferences.isAboutSheetState
import app.infinity.mpvz.ui.update.UpdateViewModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 1944: lens controls, video lens chips, live preview and their fallbacks. */
class Round1944LensContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private fun src(path: String) = File(root, "app/src/main/java/app/infinity/mpvz/$path").readText()

  @Test fun touchedThumbBecomesAClearMagnifyingLensAndRestsSolid() {
    assertEquals(1f, LensControlPolicy.thumbScale(0f), 0f)
    assertEquals(LensControlPolicy.PressedScale, LensControlPolicy.thumbScale(1f), 1e-6f)
    assertEquals(1f, LensControlPolicy.surfaceAlpha(0f), 0f)
    assertEquals(0f, LensControlPolicy.surfaceAlpha(1f), 0f)
    assertTrue(LensControlPolicy.trackMagnification(1f) > 1.1f)
    assertEquals(0f, LensControlPolicy.blurDp(1f), 0f)
    // Out-of-range / non-finite progress never explodes the thumb.
    assertEquals(LensControlPolicy.PressedScale, LensControlPolicy.thumbScale(7f), 1e-6f)
    assertEquals(1f, LensControlPolicy.thumbScale(Float.NaN), 0f)
  }

  @Test fun sliderAndSwitchLensSampleASiblingTrackNeverThemselves() {
    val g = src("ui/player/controls/components/GlassControls.kt")
    val slider = g.substring(g.indexOf("fun GlassSlider("), g.indexOf("private fun Modifier.glassTickDots"))
    // The track records the layer; the thumb consumes it.
    assertTrue(slider.contains(".then(if (lensEnabled) Modifier.layerBackdrop(trackBackdrop) else Modifier)"))
    assertTrue(slider.contains(".lensControlThumb(trackBackdrop"))
    assertTrue(slider.contains("collectIsPressedAsState()"))
    val switch = g.substring(g.indexOf("fun GlassSwitch("), g.indexOf("fun GlassSlider("))
    // Lens switch: the track is a separate sibling box drawn under the Material Switch.
    val trackBox = switch.indexOf("Box(Modifier.matchParentSize().layerBackdrop(trackBackdrop)")
    val switchCall = switch.indexOf("Switch(", trackBox)
    assertTrue(trackBox > 0 && switchCall > trackBox)
    assertTrue(switch.contains(".lensControlThumb(trackBackdrop"))
    // Gated by the device/API lens gate AND the glass setting; fallback keeps the solid capsule.
    assertTrue(switch.contains("val lensEnabled = glass && LocalLensGlassEnabled.current"))
    assertTrue(slider.contains("val lensEnabled = glass && LocalLensGlassEnabled.current"))
    assertTrue(switch.contains(".glassCapsuleBody(") && slider.contains(".glassCapsuleBody("))
    // The old page-backdrop path (absent outside browser scenes) must not come back.
    assertFalse(g.contains("pageBackdrop!!"))
  }

  @Test fun videoLensFrameLandsAtTheConsumersOffset() {
    val p = VideoLensPolicy.placement(0f, 0f, 1080f, 2400f, 100f, 2200f)
    assertNotNull(p)
    assertEquals(-100, p!!.left); assertEquals(-2200, p.top)
    assertEquals(1080, p.width); assertEquals(2400, p.height)
    assertNull(VideoLensPolicy.placement(0f, 0f, 0f, 2400f, 0f, 0f))
    assertNull(VideoLensPolicy.placement(Float.NaN, 0f, 10f, 10f, 0f, 0f))
  }

  @Test fun onlyPillAndCircleChipsUseTheVideoLensAndBrightVideoGetsMoreFilm() {
    assertTrue(VideoLensPolicy.isLensChip(999f))
    assertFalse(VideoLensPolicy.isLensChip(24f))
    assertTrue(VideoLensPolicy.filmAlpha(1f, .8f) > VideoLensPolicy.filmAlpha(0f, .8f))
    assertTrue(VideoLensPolicy.filmAlpha(Float.NaN, Float.NaN) in 0f..1f)
    val g = src("ui/player/controls/components/GlassControls.kt")
    val pane = g.substring(g.indexOf("fun Modifier.playerGlassPane("))
    assertTrue(pane.contains("LocalLensGlassEnabled.current && app.infinity.mpvz.ui.theme.lens.VideoLensPolicy.isLensChip(cornerRadius.value)"))
    assertTrue(pane.contains("return liquidGlassPane(on,"))
    // One sampler: the adapter reuses the existing captured frame, no new capture.
    val chip = src("ui/theme/lens/VideoLensChip.kt")
    assertFalse(chip.contains("PixelCopy"))
    assertTrue(chip.contains("frame.value ?: return"))
  }

  @Test fun previewUsesACheckerboardSiblingAndLiveLensSettings() {
    val a = src("ui/preferences/AppearancePreferencesScreen.kt")
    val preview = a.substring(a.indexOf("private fun LensGlassPreview("))
    assertTrue(preview.contains("GlassPreviewCheckerboard(Modifier.matchParentSize().layerBackdrop(checker))"))
    assertTrue(preview.contains("tuning.lensDepth") && preview.contains("tuning.lensRefraction") && preview.contains("tuning.colourEdge"))
    assertTrue(a.contains("if (lens) {\n    LensGlassPreview("))
    // Fallback keeps the frosted GlassSurface preview with the same checkerboard.
    assertTrue(a.contains("backdrop = { GlassPreviewCheckerboard(Modifier.fillMaxSize()) }"))
  }

  @Test fun sliderSummariesUseTheFixedValueBox() {
    val a = src("ui/preferences/AppearancePreferencesScreen.kt")
    assertFalse(a.contains("% · keeps text readable"))
    assertFalse(a.contains("% · edge reflections"))
    assertFalse(a.contains("% · \$summary"))
    assertEquals(3, Regex("SliderValueSummary\\(").findAll(a).count())
  }

  @Test fun aboutSheetNeverDuplicatesTheAppUpdateSheet() {
    val about = src("ui/preferences/AboutScreen.kt")
    assertTrue(about.contains("if (updateState.isAboutSheetState()) UpdateResultSheet(updateState, updateViewModel::dismissNoUpdate,"))
    val main = src("MainActivity.kt")
    assertTrue(main.contains("is UpdateViewModel.UpdateState.Available -> {"))
    assertTrue(UpdateViewModel.UpdateState.Loading.isAboutSheetState())
    assertTrue(UpdateViewModel.UpdateState.NoUpdate.isAboutSheetState())
    assertTrue(UpdateViewModel.UpdateState.Error.isAboutSheetState())
    assertFalse(UpdateViewModel.UpdateState.Idle.isAboutSheetState())
  }

  @Test fun aboutHeaderIsOneSurfaceAndDeviceInfoGlyphsClearTheCornerClip() {
    val about = src("ui/preferences/AboutScreen.kt")
    val header = about.indexOf("Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {")
    assertTrue(header > 0)
    // The coloured header box is not nested inside a PreferenceCard any more.
    assertFalse(about.substring(about.lastIndexOf("Column(", header), header).contains("PreferenceCard {"))
    assertTrue(about.contains(".padding(horizontal = 6.dp, vertical = 6.dp),"))
  }
}
