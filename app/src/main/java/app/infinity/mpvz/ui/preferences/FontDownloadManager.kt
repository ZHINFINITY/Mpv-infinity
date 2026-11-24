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
      "Anton" to "ofl/anton/Anton-Regular.ttf",
      "OpenSans" to "ofl/opensans/OpenSans%5Bwdth%2Cwght%5D.ttf",
      "Roboto" to "ofl/roboto/Roboto%5Bwdth%2Cwght%5D.ttf",
      "NotoSans" to "ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf",
      "NotoSansArabic" to "ofl/notosansarabic/NotoSansArabic%5Bwdth%2Cwght%5D.ttf",
      "NotoSansJP" to "ofl/notosansjp/NotoSansJP%5Bwght%5D.ttf",
      "Montserrat" to "ofl/montserrat/Montserrat%5Bwght%5D.ttf",
      "Lato" to "ofl/lato/Lato-Regular.ttf",
      "Poppins" to "ofl/poppins/Poppins-Regular.ttf",
      "Cairo" to "ofl/cairo/Cairo%5Bslnt%2Cwght%5D.ttf",
    )

  // Mirrors of the google/fonts repository, tried in order: GitHub is unreachable or
  // rate-limited on some networks, so each font falls back through the CDNs.
  private fun mirrors(path: String): List<String> =
    listOf(
      "https://cdn.jsdelivr.net/gh/google/fonts@main/$path",
      "https://raw.githubusercontent.com/google/fonts/main/$path",
      "https://github.com/google/fonts/raw/main/$path",
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
        if (!downloadOne(FONT_URLS.getValue(name), out)) {
          failed[name] = true
          return@mapNotNull null
        }
        name
      }
    }
  }

  /** Tries each mirror in order; leaves a non-empty file only on success. */
  private fun downloadOne(path: String, out: File): Boolean =
    mirrors(path).any { url ->
      val ok = runCatching { fetch(out, url) }.isSuccess && out.isFile && out.length() > 0L
      if (!ok) out.delete()
      ok
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
