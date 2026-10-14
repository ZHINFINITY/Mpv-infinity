/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.utils.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Broadcast receiver that listens for media scanner events
 * Automatically notifies the app when new media files are added to the device
 */
class MediaScanReceiver : BroadcastReceiver() {
  companion object {
    private const val TAG = "MediaScanReceiver"
  }

  override fun onReceive(
    context: Context?,
    intent: Intent?,
  ) {
    if (context == null || intent == null) return

    when (intent.action) {
      Intent.ACTION_MEDIA_SCANNER_FINISHED -> {
        val data = intent.data
        Log.d(TAG, "Media scan event: ${intent.action}, data: $data")

        // Notify the app that media library has changed
        MediaLibraryEvents.notifyChanged()
      }
    }
  }
}
