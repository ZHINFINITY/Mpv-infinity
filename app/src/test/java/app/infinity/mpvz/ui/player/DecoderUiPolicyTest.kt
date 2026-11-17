package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderUiPolicyTest {
  private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  @Test fun qualityMappingHasTenStableEndpoints() {
    assertEquals(0, DecoderUiPolicy.profileForQuality(0))
    assertEquals(9, DecoderUiPolicy.profileForQuality(100))
    assertEquals(0, DecoderUiPolicy.qualityForProfile(0))
    assertEquals(100, DecoderUiPolicy.qualityForProfile(9))
    for (profile in 0 until DecoderUiPolicy.PROFILE_COUNT) {
      assertEquals(profile, DecoderUiPolicy.profileForQuality(DecoderUiPolicy.qualityForProfile(profile)))
    }
  }

  @Test fun playerTabsNeverExposeGlobalAuto() {
    assertEquals(listOf(PlaybackEngineMode.MPV, PlaybackEngineMode.NATIVE), DecoderUiPolicy.playerEngineTabs())
  }

  @Test fun activeRendererChipNamesTheEngineAndKeepsMpvDecoderStatus() {
    assertEquals("MPV · Auto", DecoderUiPolicy.activeEngineAndDecoderLabel(false, "Auto"))
    assertEquals("MPV · SW", DecoderUiPolicy.activeEngineAndDecoderLabel(false, "SW"))
    assertEquals("Native", DecoderUiPolicy.activeEngineAndDecoderLabel(true, "Auto"))
  }

  @Test fun topRowDecoderChipUsesTheActiveRendererLabelPolicy() {
    val source = File(
      root,
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControlsShared.kt",
    ).readText()
    val chip = source.substringAfter("PlayerButton.DECODER")
      .substringBefore("PlayerButton.FRAME_INTERPOLATION")

    assertTrue(chip.contains("DecoderUiPolicy.activeEngineAndDecoderLabel"))
    assertTrue(chip.contains("activity.isNativeEngineActive()"))
    assertTrue(chip.contains("decoder.title"))
  }
}
