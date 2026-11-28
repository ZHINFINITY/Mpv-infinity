/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.sheets

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.ui.browser.music.AudioOutputRouter
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.preferences.components.settingsGlassContainerColor
import app.infinity.mpvz.ui.preferences.components.settingsGlassSurface
import app.infinity.mpvz.ui.player.controls.components.GlassSlider

/** The device's own product name when the platform reports one, so two pairs of earbuds differ. */
private fun AudioDeviceInfo.defaultLabel(fallback: String): String =
  productName?.toString()?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) } ?: fallback

/**
 * Route types that exist on the phone but can never play a media AudioTrack: tuners, the
 * remote-submix tap and LE broadcast announcements. Selecting one of those used to hand mpv a
 * preferred device that produced no audio at all, so they are not offered.
 */
private fun AudioDeviceInfo.isMediaOutput(): Boolean =
  when (type) {
    AudioDeviceInfo.TYPE_UNKNOWN,
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    AudioDeviceInfo.TYPE_TELEPHONY,
    AudioDeviceInfo.TYPE_FM,
    AudioDeviceInfo.TYPE_FM_TUNER,
    AudioDeviceInfo.TYPE_TV_TUNER,
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_IP,
    AudioDeviceInfo.TYPE_BUS,
    -> false
    else -> true
  }

/**
 * Some OEM ROMs (Xiaomi/HyperOS here) publish the phone itself as an extra output whose product
 * name is the model number ("25053PC47I"). It is the builtin speaker under another name and plays
 * nothing when an AudioTrack is bound to it.
 */
private fun AudioDeviceInfo.isSelfAlias(): Boolean {
  if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
    type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
  ) {
    return false
  }
  val name = productName?.toString()?.trim()?.lowercase().orEmpty()
  if (name.isEmpty()) return false
  return name == Build.MODEL.lowercase() || name == Build.DEVICE.lowercase()
}

/**
 * One physical Bluetooth headset is reported as separate A2DP, SCO and BLE outputs, so the sheet
 * listed the same pair twice with the same icon. Entries that share a product name share a key.
 */
private fun AudioDeviceInfo.outputGroupKey(): String =
  when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_HEARING_AID,
    ->
      "bt:" +
        (productName?.toString()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "null" }
          ?: "")
    // Phones report the same speaker twice (BUILTIN_SPEAKER and, since Android 12,
    // BUILTIN_SPEAKER_SAFE carrying the model name as product name). Both are one speaker.
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
    -> "builtin:speaker"
    else -> "id:$id"
  }

/** Keeps one row per physical output, preferring the A2DP entry that carries the media route. */
private fun List<AudioDeviceInfo>.collapseOutputs(): List<AudioDeviceInfo> =
  groupBy { it.outputGroupKey() }.values.map { group ->
    group.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
      ?: group.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
      ?: group.first()
  }

/**
 * The "safe" speaker variant is a volume-limited alias with no media track of its own, so a pick on
 * it is routed through the plain builtin speaker that the same phone always also reports.
 */
private fun AudioDeviceInfo.asRoutableOutput(devices: List<AudioDeviceInfo>): AudioDeviceInfo =
  if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE) {
    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: this
  } else {
    this
  }

/** Every output type gets its own icon and label instead of one shared "Audio output" row. */
private fun AudioDeviceInfo.labelAndIcon(): Pair<String, app.infinity.mpvz.ui.icons.AppIcon>? =
  when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    AudioDeviceInfo.TYPE_TELEPHONY,
    -> null // never a media output; routing here sends audio to the earpiece
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_HEARING_AID,
    -> defaultLabel("Bluetooth") to Icons.RoundedFilled.Bluetooth
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    -> defaultLabel("Wired headphones") to Icons.RoundedFilled.Headset
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    -> defaultLabel("USB audio") to Icons.RoundedFilled.Usb
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
    -> "Speaker" to Icons.RoundedFilled.Speaker
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_HDMI_EARC,
    -> "HDMI" to Icons.RoundedFilled.Tv
    AudioDeviceInfo.TYPE_DOCK -> "Dock" to Icons.RoundedFilled.Cast
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
    AudioDeviceInfo.TYPE_AUX_LINE,
    -> "Line out" to Icons.RoundedFilled.VolumeUp
    else -> defaultLabel("Audio output") to Icons.RoundedFilled.VolumeUp
  }

@Composable
fun AudioOutputSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val audioManager = remember { context.getSystemService(AudioManager::class.java) }
  var volume by remember {
    mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
  }
  val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
  val initialSelection =
    remember {
      // A pick made earlier in the session wins over guessing from the platform's active list.
      AudioOutputRouter.selectedDevice() ?: AudioOutputRouter.activeMediaDevice(context)
    }
  var selectedDeviceId by remember { mutableStateOf(initialSelection?.id) }
  var selectedGroupKey by remember { mutableStateOf(initialSelection?.outputGroupKey()) }
  val devices =
    remember {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Media outputs, not availableCommunicationDevices: USB-C and wired earbuds often have
        // no communication route at all and were missing from (or mislabelled in) this list.
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
      } else {
        emptyList()
      }
    }.filter {
      it.isMediaOutput() &&
        !it.isSelfAlias() &&
        it.type != AudioDeviceInfo.TYPE_UNKNOWN &&
        it.labelAndIcon() != null
    }.collapseOutputs()

  PlayerSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 48.dp),
    ) {
      Text(
        text = "AUDIO OUTPUT",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.SemiBold,
      )

      Spacer(modifier = Modifier.height(16.dp))

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && devices.isNotEmpty()) {
        devices.forEach { device ->
          val (label, icon) = device.labelAndIcon() ?: return@forEach
          val isSelected = device.id == selectedDeviceId || device.outputGroupKey() == selectedGroupKey
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .settingsGlassSurface(cornerRadius = 12.dp)
                .background(settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)))
                .border(
                  1.dp,
                  if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                  } else {
                    Color.Transparent
                  },
                  RoundedCornerShape(12.dp),
                )
                .clickable {
                  val target = device.asRoutableOutput(devices)
                  selectedDeviceId = target.id
                  selectedGroupKey = target.outputGroupKey()
                  // Media routing, not communication routing: setCommunicationDevice rejects
                  // HDMI/line/dock outputs with IllegalArgumentException and never moved the
                  // music stream, which is why Bluetooth -> speaker did nothing.
                  AudioOutputRouter.select(target)
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
          ) {
            Icon(
              imageVector = icon,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
            Text(
              text = label,
              style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
          Spacer(modifier = Modifier.height(8.dp))
        }
      } else {
        val activeDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull()
        if (activeDevice != null) {
          val (label, icon) = activeDevice.labelAndIcon() ?: ("Audio output" to Icons.RoundedFilled.VolumeUp)
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .settingsGlassSurface(cornerRadius = 12.dp)
                .background(settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)))
                .padding(horizontal = 16.dp, vertical = 12.dp),
          ) {
            Icon(
              imageVector = icon,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
            Text(
              text = label,
              style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(20.dp))

      Text(
        text = "VOLUME",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.VolumeUp,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(20.dp),
        )
        GlassSlider(
          value = volume.toFloat(),
          onValueChange = { newValue ->
            val target = newValue.toInt().coerceIn(0, maxVolume)
            if (target != volume) {
              audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
              volume = target
            }
          },
          valueRange = 0f..maxVolume.toFloat(),
          modifier = Modifier.weight(1f),
        )
        Text(
          text = "$volume",
          style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
