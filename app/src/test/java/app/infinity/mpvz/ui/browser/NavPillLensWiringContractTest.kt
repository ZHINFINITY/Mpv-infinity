package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NavPillLensWiringContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  @Test
  fun lensIsGatedAndPopsOnlyDuringMotion() {
    val main = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/MainScreen.kt").readText()
    assertTrue(main.contains("LocalLensGlassEnabled.current"))
    assertTrue(main.contains("NavigationGlassLensPolicy.geometry"))
    assertTrue(main.contains("lensPopActive"))
    assertTrue(main.contains("NavigationGlassLensPolicy.MaxMagnification"))
  }

  @Test
  fun browserLensUsesSiblingSceneAndExplicitLensGate() {
    val scene = File(root, "app/src/main/java/app/infinity/mpvz/ui/theme/BrowserGlassScene.kt").readText()
    assertTrue(scene.contains("LocalLensGlassEnabled.current"))
    assertTrue(scene.contains("selected && lensEnabled && capabilities.refraction"))
    assertTrue(scene.contains("navLayer.record"))
    assertTrue(scene.contains("navigationContent()"))
    assertTrue(scene.contains("never self-sample"))
  }
}
