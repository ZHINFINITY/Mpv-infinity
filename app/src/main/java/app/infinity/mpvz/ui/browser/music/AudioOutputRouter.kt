/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.music

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sends the music player's media stream to the output the user picked in the audio output sheet.
 *
 * Two engines can be running the music: the mpv player (library/search playback, [MpvAudioRoute])
 * and the Media3 players this object tracks. Both are routed, because a pick has to hold whichever
 * one is playing.
 *
 * Media3 routing goes through [ExoPlayer.setPreferredAudioDevice], which accepts any audio *media*
 * output. The previous implementation used AudioManager's *communication* API
 * (`setCommunicationDevice`), which only accepts earpiece/speaker/Bluetooth-SCO/hearing-aid
 * devices and throws `IllegalArgumentException: invalid device type` for everything else
 * (HDMI, line-out, docks), crashing the sheet and silently ignoring Bluetooth -> speaker.
 */
@OptIn(UnstableApi::class)
object AudioOutputRouter {
  private val players = CopyOnWriteArrayList<ExoPlayer>()

  @Volatile private var preferredDevice: AudioDeviceInfo? = null

  /** Remembers a newly built player so later picks reach it, and re-applies the current pick. */
  fun attach(player: ExoPlayer) {
    players += player
    preferredDevice?.let { device -> runCatching { player.setPreferredAudioDevice(device) } }
  }

  fun detach(player: ExoPlayer) {
    players -= player
  }

  fun selectedDevice(): AudioDeviceInfo? = preferredDevice

  /** Applies a user pick to every live music player and remembers it for later sessions. */
  fun select(device: AudioDeviceInfo) {
    preferredDevice = device
    players.forEach { player -> runCatching { player.setPreferredAudioDevice(device) } }
    MpvAudioRoute.select(device)
  }

  /**
   * The output media is really playing through, used as the sheet's initial selection.
   * Prefers a plugged-in or wireless sink over the built-in speaker.
   */
  fun activeMediaDevice(context: Context): AudioDeviceInfo? {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return null
    val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
      .getOrDefault(emptyArray())
    return outputs.firstOrNull { it.isPreferredMediaSink() } ?: outputs.firstOrNull { it.isSink }
  }
}

/** True for outputs a user would pick to hear media: plugged-in or wireless devices first. */
internal fun AudioDeviceInfo.isPreferredMediaSink(): Boolean =
  type in
    setOf(
      AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
      AudioDeviceInfo.TYPE_WIRED_HEADSET,
      AudioDeviceInfo.TYPE_USB_HEADSET,
      AudioDeviceInfo.TYPE_USB_DEVICE,
      AudioDeviceInfo.TYPE_USB_ACCESSORY,
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
      AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )
