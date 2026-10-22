package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source contracts for regular-material routing of text-bearing browser popups. */
class PopupGlassRoutingContractTest {
  private val projectRoot =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private val browserRoot = File(projectRoot, "app/src/main/java/app/infinity/mpvz/ui/browser")

  @Test
  fun sortAndViewDialogUsesRegularOuterPaneWithConditionalContainerColor() {
    val source = File(browserRoot, "dialogs/SortDialog.kt").readText()
    assertTrue(source.contains("settingsGlassSurface(cornerRadius = 28.dp, independent = true)"))
    assertTrue(source.contains("settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh)"))
    assertFalse(source.contains("containerColor = MaterialTheme.colorScheme.surfaceContainerHigh"))
    assertFalse(source.contains("liquidGlassPane(enabled = glassEnabled, cornerRadius = 28.dp)"))
  }

  @Test
  fun editConnectionSheetUsesRegularOuterPaneWithConditionalContainerColor() {
    val source = File(browserRoot, "dialogs/EditConnectionDialog.kt").readText()
    assertTrue(source.contains("ModalBottomSheet("))
    assertTrue(source.contains("settingsGlassSurface(cornerRadius = 28.dp, independent = true)"))
    assertTrue(source.contains("settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerLow)"))
    assertFalse(source.contains("containerColor = MaterialTheme.colorScheme.surfaceContainerLow"))
  }

  @Test
  fun playlistSheetKeepsOneMeasuredRegularPaneAndNoWindowSizedOptics() {
    val source = File(browserRoot, "sheets/PlaylistActionSheet.kt").readText()
    assertTrue(source.contains(".settingsGlassSurface(cornerRadius = 28.dp, independent = true)"))
    assertTrue(source.contains("containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerLow)"))
    val sheet = source.substringAfter("fun PlaylistActionSheet(").substringBefore("// Create Playlist Dialog")
    assertTrue(sheet.split("settingsGlassSurface(").size - 1 == 1)
    val sheetArguments = sheet.substringAfter("ModalBottomSheet(").substringBefore("Column(")
    assertFalse(sheetArguments.contains(".settingsGlassSurface("))
    assertFalse(source.contains("containerColor = MaterialTheme.colorScheme.surfaceContainerLow"))
  }

  @Test fun separatePlaylistCreateAndImportDialogsKeepTransparentConditionalCardFills() {
    val source = File(browserRoot, "sheets/PlaylistActionSheet.kt").readText().substringAfter("// Create Playlist Dialog")
    assertTrue(source.split("settingsGlassSurface(cornerRadius = 28.dp, independent = true)").size - 1 == 2)
    assertTrue(source.split("settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHighest)").size - 1 == 2)
  }
}
