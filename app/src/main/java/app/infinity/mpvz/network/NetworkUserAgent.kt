/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.network

import android.content.Context
import android.os.Build
import android.os.Looper
import android.webkit.WebSettings
import app.infinity.mpvz.BuildConfig

/** Uses the installed Android WebView identity when available, with a truthful app fallback. */
object NetworkUserAgent {
  @Volatile private var cachedWebViewUserAgent: String? = null

  fun resolve(
    context: Context,
    customUserAgent: String? = null,
  ): String {
    customUserAgent?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
    cachedWebViewUserAgent?.let { return it }

    if (Looper.myLooper() == Looper.getMainLooper()) {
      runCatching { WebSettings.getDefaultUserAgent(context.applicationContext) }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?.let { userAgent ->
          cachedWebViewUserAgent = userAgent
          return userAgent
        }
    }

    return "Mpv∞/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL})"
  }
}
