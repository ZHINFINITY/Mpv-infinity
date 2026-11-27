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

/** Every output type gets its own icon and label instead of one shared "Audio output" row. */
private fun AudioDeviceInfo.labelAndIcon(): Pair<String, app.infinity.mpvz.ui.icons.AppIcon>? =
  when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> null // never a media output; routing here sends audio to the earpiece
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    -> defaultLabel("Bluetooth") to Icons.RoundedFilled.Bluetooth
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    -> defaultLabel("Wired headphones") to Icons.RoundedFilled.Headset
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    -> defaultLabel("USB audio") to Icons.RoundedFilled.Usb
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_TELEPHONY,
    -> "Speaker" to Icons.RoundedFilled.Speaker
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_HDMI_EARC,
    -> "HDMI" to Icons.RoundedFilled.Tv
    AudioDeviceInfo.TYPE_DOCK -> "Dock" to Icons.RoundedFilled.Cast
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
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
  var selectedDeviceId by remember {
    // A pick made earlier in the session wins over guessing from the platform's active list.
    mutableStateOf((AudioOutputRouter.selectedDevice() ?: AudioOutputRouter.activeMediaDevice(context))?.id)
  }
  val devices =
    remember {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Media outputs, not availableCommunicationDevices: USB-C and wired earbuds often have
        // no communication route at all and were missing from (or mislabelled in) this list.
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
      } else {
        emptyList()
      }
    }.filter { it.type != AudioDeviceInfo.TYPE_UNKNOWN && it.labelAndIcon() != null }

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
          val isSelected = device.id == selectedDeviceId
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
                  selectedDeviceId = device.id
                  // Media routing, not communication routing: setCommunicationDevice rejects
                  // HDMI/line/dock outputs with IllegalArgumentException and never moved the
                  // music stream, which is why Bluetooth -> speaker did nothing.
                  AudioOutputRouter.select(device)
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
