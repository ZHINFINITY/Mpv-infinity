/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.music

import android.media.AudioDeviceInfo
import app.infinity.mpvz.ui.player.DeclaredPlaybackMediaKind
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.declaredMediaKind

/**
 * Sends the mpv music session to the output the user picked.
 *
 * The library music player is mpv (`is_audio` launches never build the Media3 engine), and mpv's
 * Android audio output builds its own `AudioTrack`, so `ExoPlayer.setPreferredAudioDevice` cannot
 * reach it. The device reference goes through [nativeSetPreferredDevice] into the patched
 * `ao_audiotrack.c`, which binds every track it creates to that device.
 *
 * OpenSL ES has no device selection at all, so a pick also switches the session onto the
 * AudioTrack driver and reloads the output, rebuilding the track against the chosen device.
 * Video sessions keep the platform default: nothing here runs for them.
 */
object MpvAudioRoute {
  private val bridgeAvailable: Boolean by lazy {
    runCatching { System.loadLibrary("mpvaudio") }.isSuccess
  }

  /** True when the running session is mpv on an audio item, i.e. the music player. */
  fun appliesToCurrentSession(): Boolean {
    val kind = PlaybackSession.queue.value.currentItem?.declaredMediaKind()
    return kind != DeclaredPlaybackMediaKind.VIDEO
  }

  /** Routes the current music session to [device]; returns false when mpv cannot take it. */
  fun select(device: AudioDeviceInfo?): Boolean {
    if (!bridgeAvailable) return false
    if (!appliesToCurrentSession()) return false
    if (!runCatching { nativeSetPreferredDevice(device) }.getOrDefault(false)) return false
    // No mpv session is open while the Media3 music activity plays: the pick still applies to
    // the next mpv session, so a missing session is not an error.
    runCatching {
      PlaybackSession.setOptionString("ao", "audiotrack")
      PlaybackSession.command("ao-reload")
    }
    return true
  }

  @JvmStatic
  private external fun nativeSetPreferredDevice(device: AudioDeviceInfo?): Boolean

}
