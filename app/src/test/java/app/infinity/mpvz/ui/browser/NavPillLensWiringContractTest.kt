package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavPillLensWiringContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  @Test
  fun selectedOutlineKeepsItsTabFootprintWhileFollowingMotion() {
    val main = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/MainScreen.kt").readText()
    val selectedLens = main.substringAfter("selected = true,\n        deformation =").substringBefore("\n      ) {}")

    // The active glass remains a centered capsule around the tab; deformation may stretch its width,
    // but its vertical footprint is fixed so a font-scale or spring pulse cannot flatten it to a line.
    assertTrue(selectedLens.contains("lensGeometry.left"))
    assertTrue(selectedLens.contains("lensGeometry.width"))
    assertTrue(selectedLens.contains(".height(44.dp)"))
    assertTrue(main.contains("NavigationGlassMotionPolicy.dragGeometry"))
    assertTrue(main.contains("cornerRadius = 1000.dp"))
    assertFalse(main.contains("NavigationGlassLensPolicy"))
    assertFalse(main.contains("lensPop"))
    assertFalse(main.contains("magnify ="))
  }

  @Test
  fun manualDragKeepsTheOriginalTabHitTargetsAndLensUsesItsSiblingScene() {
    val main = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/MainScreen.kt").readText()
    assertTrue(main.contains("Modifier.navPillDragGesture("))
    assertTrue(main.contains(".horizontalScroll(pillScroll)"))
    assertTrue(main.contains("role = Role.Tab"))
    assertTrue(main.contains("selected = if (dragPosition != null) hoveredTab == index"))

    val scene = File(root, "app/src/main/java/app/infinity/mpvz/ui/theme/BrowserGlassScene.kt").readText()
    assertTrue(scene.contains("LocalLensGlassEnabled.current"))
    assertTrue(scene.contains("selected && lensEnabled && capabilities.refraction"))
    assertTrue(scene.contains("navLayer.record"))
    assertTrue(scene.contains("navigationContent()"))
    assertTrue(scene.contains("never self-sample"))
  }
}
