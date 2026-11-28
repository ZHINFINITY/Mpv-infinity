package app.infinity.mpvz.ui.player.controls.components.sheets

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The output sheet used to offer routes that cannot play a media AudioTrack: the OEM self-alias
 * entry (the phone model number), the volume-limited speaker alias and LE broadcast taps. Picking
 * one of them played silence, so they stay out of the list.
 */
class AudioOutputSheetRouteContractTest {
  private val root: File =
    generateSequence(File(System.getProperty("user.dir").orEmpty())) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  private val sheet: String by lazy {
    File(
        root,
        "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/sheets/AudioOutputSheet.kt",
      )
      .readText()
  }

  @Test
  fun onlyMediaCapableRoutesAreListed() {
    assertTrue(sheet.contains("AudioDeviceInfo.TYPE_REMOTE_SUBMIX"))
    assertTrue(sheet.contains("AudioDeviceInfo.TYPE_BLE_BROADCAST"))
    assertTrue(sheet.contains("AudioDeviceInfo.TYPE_TV_TUNER"))
    assertTrue(sheet.contains("it.isMediaOutput()"))
  }

  @Test
  fun theOemSelfAliasRouteIsHidden() {
    assertTrue(sheet.contains("private fun AudioDeviceInfo.isSelfAlias(): Boolean"))
    assertTrue(sheet.contains("name == Build.MODEL.lowercase()"))
    assertTrue(sheet.contains("!it.isSelfAlias()"))
  }

  @Test
  fun theSafeSpeakerAliasIsRoutedThroughTheBuiltinSpeaker() {
    assertTrue(sheet.contains("private fun AudioDeviceInfo.asRoutableOutput("))
    assertTrue(sheet.contains("val target = device.asRoutableOutput(devices)"))
    assertTrue(sheet.contains("AudioOutputRouter.select(target)"))
    assertFalse(sheet.contains("AudioOutputRouter.select(device)"))
  }

  @Test
  fun bleSpeakersAndHearingAidsGroupWithBluetooth() {
    assertTrue(sheet.contains("AudioDeviceInfo.TYPE_BLE_SPEAKER"))
    assertTrue(sheet.contains("AudioDeviceInfo.TYPE_HEARING_AID"))
  }
}
