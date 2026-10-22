package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGlassSheetContractTest {
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  private fun source(path: String) = File(root, path).readText()

  @Test
  fun chapterAndSheetUsePlayerGlassRoutingWhenEnabled() {
    val chapter = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/CurrentChapter.kt")
    val sheet = source("app/src/main/java/app/infinity/mpvz/presentation/components/PlayerSheet.kt")
    assertTrue(chapter.contains(".playerGlassOverlay(999.dp)"))
    assertTrue(sheet.contains(".liquidGlassPane("))
    assertTrue(sheet.contains("color = if (liquidGlassSurfaces) Color.Transparent"))
  }

  @Test
  fun pitchCorrectionDoesNotAddASecondPreferenceSurface() {
    val speed = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/PlaybackSpeedSheet.kt")
    assertFalse(speed.contains("SwitchPreference("))
    assertTrue(speed.contains("GlassSwitch("))
    assertTrue(speed.contains("modifier = Modifier.playerSheetSettingRow(active = pitchCorrection)"))
  }

  @Test
  fun affectedSheetsDoNotUseFixedSheetWidthCards() {
    val files = listOf("AspectRatioSheet.kt", "SubtitleTracksSheet.kt", "DecodersSheet.kt", "VideoZoomSheet.kt", "MoreSheet.kt")
    files.forEach { name ->
      val text = source("app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/$name")
      assertFalse("$name contains a fixed width larger than the sheet", Regex("\\.width\\((?:[5-9]\\d{2}|\\d{4,})\\.dp\\)").containsMatchIn(text))
    }
  }
}
