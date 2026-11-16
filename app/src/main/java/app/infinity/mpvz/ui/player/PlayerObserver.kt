/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import android.content.pm.ActivityInfo
import app.infinity.mpvz.preferences.PlayerPreferences
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PlayerObserver(
  private val activity: PlayerActivity,
) : MPVLib.EventObserver, KoinComponent {
  private val playerPreferences: PlayerPreferences by inject()

  private fun shouldIgnoreCallback(): Boolean =
    activity.player.isExiting || !activity.isActivePlaybackOwner()

  private fun dispatch(block: () -> Unit) {
    if (shouldIgnoreCallback()) return
    val token = activity.capturePlayerObserverCallbackToken()
    activity.runOnUiThread {
      if (!activity.isPlayerObserverCallbackCurrent(token) || shouldIgnoreCallback()) return@runOnUiThread
      block()
    }
  }

  private fun dispatchImmediate(block: () -> Unit) {
    if (shouldIgnoreCallback()) return
    val token = activity.capturePlayerObserverCallbackToken()
    activity.runIfActivePlaybackOwner {
      if (activity.isPlayerObserverCallbackCurrent(token)) block()
    }
  }

  private fun isVideoGeometryProperty(property: String): Boolean =
    property == "video-params/aspect" || property == "video-params/w" || property == "video-params/h"

  private fun shouldBypassUiThread(property: String): Boolean =
    isVideoGeometryProperty(property) || property == "container-fps"

  private fun requestStretchVideoOrientationUpdate(property: String? = null) {
    if (property != null && !isVideoGeometryProperty(property)) return
    dispatch {
      if (activity.isFinishing || activity.isDestroyed) return@dispatch
      if (activity.hasManualOrientationOverride()) return@dispatch
      if (playerPreferences.orientation.get() != PlayerOrientation.Video) return@dispatch
      if (playerPreferences.lastCustomAspectRatio.get() > 0f) return@dispatch
      if (playerPreferences.lastVideoAspect.get() != VideoAspect.Stretch) return@dispatch
      val sourceAspect = runCatching { activity.player.getVideoOutAspect() }.getOrNull()?.takeIf { it > 0.0 }
        ?: return@dispatch
      val targetOrientation = if (sourceAspect > 1.0) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      } else {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      }
      if (activity.requestedOrientation != targetOrientation) activity.requestedOrientation = targetOrientation
    }
  }

  override fun eventProperty(property: String) = dispatch { activity.onObserverEvent(property) }

  override fun eventProperty(property: String, value: Long) {
    if (shouldBypassUiThread(property)) {
      dispatchImmediate { activity.onObserverEvent(property, value) }
      requestStretchVideoOrientationUpdate(property)
    } else dispatch { activity.onObserverEvent(property, value) }
  }

  override fun eventProperty(property: String, value: Boolean) = dispatch { activity.onObserverEvent(property, value) }
  override fun eventProperty(property: String, value: String) = dispatch { activity.onObserverEvent(property, value) }

  override fun eventProperty(property: String, value: Double) {
    if (shouldBypassUiThread(property)) {
      dispatchImmediate { activity.onObserverEvent(property, value) }
      requestStretchVideoOrientationUpdate(property)
    } else dispatch { activity.onObserverEvent(property, value) }
  }

  override fun eventProperty(property: String, value: MPVNode) = dispatch { activity.onObserverEvent(property, value) }

  override fun event(eventId: Int, data: MPVNode) {
    if (shouldIgnoreCallback()) return
    val token = activity.capturePlayerObserverCallbackToken()
    val naturalEnd = eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE && PlaybackSession.isNaturalEndFile(data)
    activity.runOnUiThread {
      if (!activity.isPlayerObserverCallbackCurrent(token) || shouldIgnoreCallback()) return@runOnUiThread
      activity.event(eventId)
      if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) requestStretchVideoOrientationUpdate()
      if (naturalEnd) activity.onObserverEvent("eof-reached", true)
    }
  }
}
