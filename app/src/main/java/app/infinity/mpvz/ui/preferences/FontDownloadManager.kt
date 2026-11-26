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
import java.net.URLEncoder
import org.json.JSONObject

/** One family from the Google Fonts catalog: name plus category. */
data class GoogleFontCatalogEntry(val family: String, val category: String)

/**
 * Browses and downloads any Google Font (all ~2000 openly licensed families from the google/fonts
 * repository) into the app's internal fonts directory so it can be picked as the subtitle font
 * without any SAF folder picking. The catalog is fetched once from fonts.google.com/metadata/fonts
 * and each family is downloaded individually through the Fonts CSS API, which resolves to a direct
 * .ttf on fonts.gstatic.com for a non-browser User-Agent.
 */
object FontDownloadManager {
  private const val CATALOG_URL = "https://fonts.google.com/metadata/fonts"

  // A non-browser User-Agent keeps the CSS API serving .ttf instead of .woff2 (which mpv/libass
  // cannot use directly). curl-style UAs have been verified to get TTF for every family.
  private const val CSS_UA = "Wget/1.21"

  @Volatile private var cachedCatalog: List<GoogleFontCatalogEntry>? = null

  /**
   * The full family catalog, fetched once per process and cached. Must be called off the main
   * thread. Returns an empty list when the catalog cannot be reached.
   */
  fun fetchCatalog(): List<GoogleFontCatalogEntry> {
    cachedCatalog?.let { return it }
    val parsed = runCatching {
      val connection = URL(CATALOG_URL).openConnection() as HttpURLConnection
      try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", CSS_UA)
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        // The response body starts with Google's ")]}'" XSSI guard before the JSON object.
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body.substring(body.indexOf('{')))
        json
          .getJSONArray("familyMetadataList")
          .let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
          .map { entry ->
            GoogleFontCatalogEntry(
              family = entry.getString("family"),
              category = entry.optString("category").ifBlank { "Sans Serif" },
            )
          }
          .sortedBy { it.family.lowercase() }
      } finally {
        connection.disconnect()
      }
    }.getOrElse { emptyList() }
    if (parsed.isNotEmpty()) cachedCatalog = parsed
    return parsed
  }

  /** Whether this family has already been downloaded into the internal fonts directory. */
  fun isDownloaded(context: Context, family: String): Boolean = fontFile(context, family).let { it.isFile && it.length() > 0L }

  /** Downloads one family; returns false (leaving no partial file) when every attempt fails. */
  fun downloadFont(context: Context, family: String): Boolean {
    val out = fontFile(context, family)
    if (out.isFile && out.length() > 0L) return true
    val dir = out.parentFile ?: return false
    dir.mkdirs()
    val ok = runCatching {
      val ttfUrl = resolveTtfUrl(family) ?: return@runCatching false
      fetch(out, ttfUrl)
    }.isSuccess && out.isFile && out.length() > 0L
    if (!ok) out.delete()
    return ok
  }

  /** Names (without extension) of the fonts already in the internal fonts directory. */
  fun downloadedFontNames(context: Context): List<String> =
    File(context.filesDir, "fonts").listFiles { file -> file.isFile }?.map { it.name.removeSuffix(".ttf") }?.sorted() ?: emptyList()

  private fun fontFile(context: Context, family: String): File =
    File(File(context.filesDir, "fonts").apply { mkdirs() }, "$family.ttf")

  /** Asks the CSS API for the family and pulls the direct fonts.gstatic.com .ttf out of the CSS. */
  private fun resolveTtfUrl(family: String): String? {
    val connection =
      URL("https://fonts.googleapis.com/css2?family=${URLEncoder.encode(family, "UTF-8")}").openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = 10_000
      connection.readTimeout = 20_000
      connection.setRequestProperty("User-Agent", CSS_UA)
      if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
      val css = connection.inputStream.bufferedReader().use { it.readText() }
      return Regex("""https://fonts\.gstatic\.com/[^)\s]+\.ttf""").find(css)?.value
    } finally {
      connection.disconnect()
    }
  }

  private fun fetch(out: File, spec: String) {
    val connection = URL(spec).openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = 10_000
      connection.readTimeout = 30_000
      connection.instanceFollowRedirects = true
      if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
      connection.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
      if (out.length() == 0L) error("empty body")
    } finally {
      connection.disconnect()
    }
  }
}
