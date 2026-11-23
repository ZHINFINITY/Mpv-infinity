/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.preferences

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads a small curated set of freely licensed subtitle fonts (OFL / Apache 2.0, from the
 * google/fonts repository) into the app's internal fonts directory so they can be picked as the
 * subtitle font without any SAF folder picking.
 */
object FontDownloadManager {
  private val FONT_URLS =
    mapOf(
      "Anton" to "https://github.com/google/fonts/raw/main/ofl/anton/Anton-Regular.ttf",
      "OpenSans" to "https://github.com/google/fonts/raw/main/ofl/opensans/OpenSans%5Bwdth%2Cwght%5D.ttf",
      "Roboto" to "https://github.com/google/fonts/raw/main/ofl/roboto/Roboto%5Bwdth%2Cwght%5D.ttf",
      "NotoSans" to "https://github.com/google/fonts/raw/main/ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf",
      "NotoSansArabic" to "https://github.com/google/fonts/raw/main/ofl/notosansarabic/NotoSansArabic%5Bwdth%2Cwght%5D.ttf",
      "NotoSansJP" to "https://github.com/google/fonts/raw/main/ofl/notosansjp/NotoSansJP%5Bwght%5D.ttf",
      "Montserrat" to "https://github.com/google/fonts/raw/main/ofl/montserrat/Montserrat%5Bwght%5D.ttf",
      "Lato" to "https://github.com/google/fonts/raw/main/ofl/lato/Lato-Regular.ttf",
      "Poppins" to "https://github.com/google/fonts/raw/main/ofl/poppins/Poppins-Regular.ttf",
      "Cairo" to "https://github.com/google/fonts/raw/main/ofl/cairo/Cairo%5Bslnt%2Cwght%5D.ttf",
    )

  private val failed = ConcurrentHashMap<String, Boolean>()

  /** Returns the names of fonts that were newly downloaded (skips already-present files). */
  fun downloadCuratedFonts(context: Context): List<String> {
    val dir = File(context.filesDir, "fonts").apply { mkdirs() }
    return FONT_URLS.keys.sorted().mapNotNull { name ->
      val out = File(dir, "$name.ttf")
      if (out.isFile && out.length() > 0 || failed[name] == true) {
        null
      } else {
        runCatching { fetch(out, FONT_URLS.getValue(name)) }
          .onFailure { failed[name] = true }
          .getOrNull()
          ?: return@mapNotNull null
        name
      }
    }
  }

  private fun fetch(out: File, spec: String) {
    val connection = URL(spec).openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = 10_000
      connection.readTimeout = 20_000
      connection.instanceFollowRedirects = true
      if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
      connection.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
      if (out.length() == 0L) error("empty body")
    } finally {
      connection.disconnect()
    }
  }
}
