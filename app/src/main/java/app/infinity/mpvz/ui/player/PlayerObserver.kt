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
) : MPVLib.EventObserver,
  KoinComponent {
  private val playerPreferences: PlayerPreferences by inject()

  private fun shouldIgnoreCallback(): Boolean =
    activity.player.isExiting || !activity.isActivePlaybackOwner()

  private fun isVideoGeometryProperty(property: String): Boolean =
    property == "video-params/aspect" ||
      property == "video-params/w" ||
      property == "video-params/h"

  private fun shouldBypassUiThread(property: String): Boolean =
    isVideoGeometryProperty(property) ||
      property == "container-fps"

  /**
   * Stretch deliberately keeps a positive video-aspect-override so the picture fills the current
   * viewport. PlayerActivity's normal "Video" orientation refresh ignores positive overrides to
   * avoid treating custom aspect ratios as source geometry, which unintentionally also excluded
   * Stretch. Read the source video geometry from MPVView instead and only restore auto-orientation
   * for the built-in Stretch mode; custom aspect ratios remain untouched.
   */
  private fun requestStretchVideoOrientationUpdate(property: String? = null) {
    if (property != null && !isVideoGeometryProperty(property)) return

    activity.runOnUiThread {
      if (shouldIgnoreCallback() || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
      if (activity.hasManualOrientationOverride()) return@runOnUiThread
      if (playerPreferences.orientation.get() != PlayerOrientation.Video) return@runOnUiThread
      if (playerPreferences.lastCustomAspectRatio.get() > 0f) return@runOnUiThread
      if (playerPreferences.lastVideoAspect.get() != VideoAspect.Stretch) return@runOnUiThread

      val sourceAspect =
        runCatching { activity.player.getVideoOutAspect() }
          .getOrNull()
          ?.takeIf { it > 0.0 }
          ?: return@runOnUiThread

      val targetOrientation =
        if (sourceAspect > 1.0) {
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

      if (activity.requestedOrientation != targetOrientation) {
        activity.requestedOrientation = targetOrientation
      }
    }
  }

  override fun eventProperty(property: String) {
    if (shouldIgnoreCallback()) return
    activity.runOnUiThread {
      if (!shouldIgnoreCallback()) activity.onObserverEvent(property)
    }
  }

  override fun eventProperty(
    property: String,
    value: Long,
  ) {
    if (shouldIgnoreCallback()) return
    if (shouldBypassUiThread(property)) {
      activity.runIfActivePlaybackOwner { activity.onObserverEvent(property, value) }
      requestStretchVideoOrientationUpdate(property)
    } else {
      activity.runOnUiThread {
        if (!shouldIgnoreCallback()) activity.onObserverEvent(property, value)
      }
    }
  }

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) {
    if (shouldIgnoreCallback()) return
    // keep-open holds the last frame instead of emitting a natural END_FILE, so the raw
    // eof-reached edge is the only end-of-playback signal; PlayerActivity validates it by
    // position before advancing (a network stall can flip it mid-file).
    activity.runOnUiThread {
      if (!shouldIgnoreCallback()) activity.onObserverEvent(property, value)
    }
  }

  override fun eventProperty(
    property: String,
    value: String,
  ) {
    if (shouldIgnoreCallback()) return
    activity.runOnUiThread {
      if (!shouldIgnoreCallback()) activity.onObserverEvent(property, value)
    }
  }

  override fun eventProperty(
    property: String,
    value: Double,
  ) {
    if (shouldIgnoreCallback()) return
    if (shouldBypassUiThread(property)) {
      activity.runIfActivePlaybackOwner { activity.onObserverEvent(property, value) }
      requestStretchVideoOrientationUpdate(property)
    } else {
      activity.runOnUiThread {
        if (!shouldIgnoreCallback()) activity.onObserverEvent(property, value)
      }
    }
  }

  @Suppress("EmptyFunctionBlock")
  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) {
    if (shouldIgnoreCallback()) return
    activity.runOnUiThread {
      if (!shouldIgnoreCallback()) activity.onObserverEvent(property, value)
    }
  }

  override fun event(
    eventId: Int,
    data: MPVNode,
  ) {
    if (shouldIgnoreCallback()) return
    val naturalEnd = eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE && PlaybackSession.isNaturalEndFile(data)
    activity.runOnUiThread {
      if (shouldIgnoreCallback()) return@runOnUiThread
      activity.event(eventId)
      if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
        requestStretchVideoOrientationUpdate()
      }
      if (naturalEnd) {
        activity.onObserverEvent("eof-reached", true)
      }
    }
  }
}
