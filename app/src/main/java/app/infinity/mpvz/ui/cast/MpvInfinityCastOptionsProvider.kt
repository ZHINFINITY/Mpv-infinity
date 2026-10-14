/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

class MpvInfinityCastOptionsProvider : OptionsProvider {
  override fun getCastOptions(context: Context): CastOptions {
    val notificationOptions =
      NotificationOptions
        .Builder()
        .setTargetActivityClassName(CastRemoteControllerActivity::class.java.name)
        .build()
    val mediaOptions =
      CastMediaOptions
        .Builder()
        .setNotificationOptions(notificationOptions)
        .setExpandedControllerActivityClassName(CastRemoteControllerActivity::class.java.name)
        .build()

    return CastOptions
      .Builder()
      .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
      .setCastMediaOptions(mediaOptions)
      .setRemoteToLocalEnabled(true)
      .build()
  }

  override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
