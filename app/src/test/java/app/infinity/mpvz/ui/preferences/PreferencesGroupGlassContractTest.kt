package app.infinity.mpvz.ui.preferences
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class PreferencesGroupGlassContractTest {
  @Test fun globalGroupsUseSharedGlassAndTransparentContainer() {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val source = File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/PreferencesScreen.kt").readText()
    assertTrue(source.contains("settingsGlassSurface(cornerRadius = 28.dp)"))
    assertTrue(source.contains("settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerLow)"))
  }
}
