package app.infinity.mpvz.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PopupGlassAndSheetLatencyPolicyTest {
  private fun source(path: String) = File(path).takeIf { it.exists() }?.readText()
    ?: File("app/$path").readText()

  @Test
  fun onlyPanesInAnotherWindowSampleRouteContent() {
    assertTrue(GlassScenePolicy.samplesPopupContent(hasOwnerWindow = true, sameWindowAsOwner = false))
    assertFalse(GlassScenePolicy.samplesPopupContent(hasOwnerWindow = true, sameWindowAsOwner = true))
    assertFalse(GlassScenePolicy.samplesPopupContent(hasOwnerWindow = false, sameWindowAsOwner = false))
  }

  @Test
  fun sheetTransitionPausesSamplingOnlyWhenAFrameExists() {
    assertEquals(200L, GlassSamplingPolicy.settleDelay(now = 1_000L, settleUntil = 1_200L, hasFrame = true))
    assertEquals(0L, GlassSamplingPolicy.settleDelay(now = 1_000L, settleUntil = 1_200L, hasFrame = false))
    assertEquals(0L, GlassSamplingPolicy.settleDelay(now = 1_300L, settleUntil = 1_200L, hasFrame = true))
    assertTrue(GlassSamplingPolicy.TransitionSettleMs in 200L..400L)
  }

  @Test
  fun sheetsKeepDisplayCadenceAndControlsOnlyHalveIt() {
    assertEquals(GlassSamplingPolicy.PlayingIntervalMs, GlassSamplingPolicy.interval(true, 0, 0, largeSurface = true))
    assertEquals(GlassSamplingPolicy.ControlsPlayingIntervalMs, GlassSamplingPolicy.interval(true, 0, 0, largeSurface = false))
    assertEquals(GlassSamplingPolicy.PausedIntervalMs, GlassSamplingPolicy.interval(false, 0, 0, largeSurface = true))
    assertEquals(GlassSamplingPolicy.PlayingIntervalMs, GlassSamplingPolicy.interval(true, 0))
  }

  @Test
  fun popupContentSceneIsRecordedOnlyWhileAPopupSamplesIt() {
    val scene = source("src/main/java/app/infinity/mpvz/ui/theme/BrowserGlassScene.kt")
    assertTrue(scene.contains("LocalBrowserPopupScene provides popupScene"))
    assertTrue(scene.contains("val popupsOpen = popupScene.popupConsumers > 0"))
    assertTrue(scene.contains("if (popupsOpen && !popupScene.isRecording)"))
    assertTrue(scene.contains("materialRole == GlassSurfacePolicy.MaterialRole.Regular &&"))
    assertTrue(scene.contains("popupScene?.ownerView === hostView"))
    assertTrue(scene.contains("val scene = if (popupWindow) popupScene else routeScene"))
    assertTrue(scene.contains("val popupSceneReady = !popupWindow || scene?.popupReady == true"))
  }

  @Test
  fun glassProgramsAreCompiledOncePerSourceNotPerPane() {
    val scene = source("src/main/java/app/infinity/mpvz/ui/theme/BrowserGlassScene.kt")
    val player = source("src/main/java/app/infinity/mpvz/ui/theme/LiquidGlassBackdrop.kt")
    assertFalse(scene.contains("runCatching { RuntimeShader(BROWSER_LENS) }"))
    assertFalse(player.contains("runCatching { RuntimeShader(GLASS_OPTICS) }"))
    assertTrue(scene.contains("GlassShaderCache.shared(BROWSER_LENS)"))
    assertTrue(player.contains("GlassShaderCache.shared(GLASS_OPTICS)"))
    val activity = source("src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt")
    assertTrue(activity.contains("transitionKey = sheet to panel"))
  }

  @Test
  fun sortAndViewInnerControlsAreFillsInsideOnePane() {
    val dialog = source("src/main/java/app/infinity/mpvz/ui/browser/dialogs/SortDialog.kt")
    assertFalse("no nested floating glass panes inside the popup", dialog.contains("liquidGlassPane("))
    assertTrue(dialog.contains("private fun Modifier.popupInnerFill("))
    assertTrue(dialog.contains("selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)"))
    assertFalse("selected chips must be visible", dialog.contains("selectedContainerColor = Color.Transparent"))
  }
}
