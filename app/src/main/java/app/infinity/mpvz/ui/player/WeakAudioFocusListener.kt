/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import android.media.AudioManager
import java.lang.ref.WeakReference

/** What an Activity does with a focus change. Kept strongly by the Activity only. */
fun interface AudioFocusChangeHandler {
  fun onAudioFocusChange(focusChange: Int)
}

/**
 * Focus listener that holds its [AudioFocusChangeHandler] only weakly.
 *
 * AudioManager stores every listener in a map that lives as long as the process
 * (`mAudioFocusIdListenerMap`, reachable from a native global through the focus dispatcher), and only
 * removes it in `abandonAudioFocusRequest`. With a plain lambda that captured the Activity, any
 * path that skipped the abandon kept the whole destroyed PlayerActivity alive (LeakCanary,
 * OnePlus API 37, about 1.5-1.8 MB per closed player). With this wrapper the map can only retain
 * this small object; the Activity stays collectable even if an abandon is missed or an OEM build
 * keeps the entry longer.
 */
class WeakAudioFocusListener(handler: AudioFocusChangeHandler) : AudioManager.OnAudioFocusChangeListener {
  private val handlerRef = WeakReference(handler)

  /** True once the owning Activity (and with it the handler) has been collected. */
  val isDetached: Boolean get() = handlerRef.get() == null

  override fun onAudioFocusChange(focusChange: Int) {
    handlerRef.get()?.onAudioFocusChange(focusChange)
  }
}

object AudioFocusRegistrationPolicy {
  /**
   * Whether [AudioManager.abandonAudioFocusRequest] must run. It depends on whether the request was
   * ever submitted, not on whether focus is held right now: an AUDIOFOCUS_LOSS (for example the
   * Mini Player service taking focus) clears the Activity's "held" flags, but not AudioManager's
   * listener map.
   */
  fun mustAbandon(requestSubmitted: Boolean): Boolean = requestSubmitted

  /**
   * Whether onDestroy should drop the registration. Every real exit does (finish, Back, minimize
   * handoff, engine switch, process restore); only a configuration recreation that still holds
   * focus keeps it, so another app is not handed focus mid-song while the new instance attaches.
   */
  fun releaseOnDestroy(changingConfigurations: Boolean, holdsFocus: Boolean): Boolean =
    !(changingConfigurations && holdsFocus)
}
