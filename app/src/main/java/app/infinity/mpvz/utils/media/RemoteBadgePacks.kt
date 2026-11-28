/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.utils.media

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Remote badge packs (FR-Badges and Nuvio badge kit JSONs). The selected pack's
 * JSON is downloaded once and cached under files/badge-packs/<style>/, together
 * with every badge PNG/WebP it references, so the full set (resolution, HDR,
 * Dolby Vision, audio codecs, editions, ...) renders offline afterwards.
 */
object RemoteBadgePacks {
  private val styleUrls: Map<String, String> = mapOf(
    "glass_vibrant" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-glass-vibrant.json",
    "neon" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-neon.json",
    "solid" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-solid.json",
    "frost" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-frost.json",
    "liquid" to "https://raw.githubusercontent.com/jyotiprakash101/nuvio-badge-kit/main/liquid.json",
    "prism" to "https://raw.githubusercontent.com/jyotiprakash101/nuvio-badge-kit/main/prism.json",
    // Packs indexed on nuviobadges.com (video player only).
    "neon_cinema" to "https://raw.githubusercontent.com/EDGEL00P/nuvio-badges/main/badges.json",
    "goat" to "https://raw.githubusercontent.com/GOAT4Real/NuvioBadges/main/NuvioBadges.json",
    "shamil" to "https://raw.githubusercontent.com/aliabdoxd14/shamil-badges/main/badges.json",
    "shamil_svg" to "https://raw.githubusercontent.com/aliabdoxd14/shamil-badges/main/badges-svg.json",
    "cyrusir" to "https://raw.githubusercontent.com/cyrusir/nuvio-badges/main/nuvio-badges.json",
    "blry12" to "https://raw.githubusercontent.com/blry12/Badges/main/badges.json",
    "iaemtary" to "https://raw.githubusercontent.com/IaemTary/nuvio-badges/main/badges.json",
    "dustincos" to "https://raw.githubusercontent.com/dustincos/nuvio-badges/main/badges.json",
    "tea" to "https://raw.githubusercontent.com/Tbagzhy/tea-badges-nuvio/main/badges.json",
    "rayw" to "https://raw.githubusercontent.com/RayW1986/ntv-assets/main/badges/badges.json",
    "gold" to "https://raw.githubusercontent.com/djgenesis/badges/refs/heads/main/gold_badges_complete.json",
    "sterzeck" to "https://raw.githubusercontent.com/danielsdian/ColorfulAndConcise/main/Sterzeck_badge.json",
    "bordered_coloured" to "https://raw.githubusercontent.com/Highlander-exe/Bordered-Coloured-Regex-Badges.json/main/Bordered%20Coloured%20Regex%20Badges.json",
    "filled_coloured" to "https://raw.githubusercontent.com/Highlander-exe/Filled-Coloured-Regex-Badges.json/main/Filled%20Coloured%20Regex%20Badges.json",
    "fr_black_white" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-black-white.json",
    "fr_icon_dark" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-icon-dark.json",
    "fr_icon_light" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-icon-light.json",
    "fr_light" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-light.json",
    "fr_minimal" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-minimal.json",
    "fr_neon_icon" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-neon-icon.json",
    "fr_split" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-split.json",
    "fr_text_black" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-text-black.json",
    "fr_text_white" to "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges-text-white.json",
  )

  private val json = Json { ignoreUnknownKeys = true }
  private val client =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .build()

  @Volatile private var cached: Pair<String, List<MediaBadge>>? = null

  fun dirFor(context: Context, styleId: String): File =
    File(context.applicationContext.filesDir, "badge-packs/$styleId")

  /** Badges for the style, downloading and caching the pack on first use. */
  suspend fun load(context: Context, styleId: String): List<MediaBadge> {
    val url = styleUrls[styleId] ?: return emptyList()
    cached?.let { if (it.first == styleId) return it.second }
    return withContext(Dispatchers.IO) {
      val dir = dirFor(context, styleId)
      val jsonFile = File(dir, "pack.json")
      val text =
        if (jsonFile.isFile) {
          jsonFile.readText()
        } else {
          try {
            val body = client.newCall(Request.Builder().url(url).build()).execute().body
            val downloaded = body?.string()
            body?.close()
            if (downloaded.isNullOrBlank()) return@withContext emptyList()
            dir.mkdirs()
            jsonFile.writeText(downloaded)
            downloaded
          } catch (_: Exception) {
            return@withContext emptyList()
          }
        }
      val badges = try {
        val root = json.parseToJsonElement(text).jsonObject
        root["filters"]!!.jsonArray.mapNotNull { element ->
          val entry = element.jsonObject
          val id = entry["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
          val imageUrl = entry["imageURL"]?.jsonPrimitive?.content ?: return@mapNotNull null
          val enabled = entry["isEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
          if (!enabled) return@mapNotNull null
          val file = File(dir, imageUrl.substringAfterLast('/'))
          val name = entry["name"]?.jsonPrimitive?.content ?: id
          val rawPattern = entry["pattern"]?.jsonPrimitive?.content ?: return@mapNotNull null
          // A marker-encoded pattern can never match plain text; fall back to the readable name.
          val keywords =
            if (BadgeNameKeywords.isMarkerEncoded(rawPattern)) BadgeNameKeywords.keywordsFor(name)
            else emptyList()
          val regex = runCatching { Regex(rawPattern) }.getOrNull()
          if (regex == null && keywords.isEmpty()) return@mapNotNull null
          MediaBadge(
            id = id,
            name = name,
            groupId = entry["groupId"]?.jsonPrimitive?.content ?: "",
            assetPath = file.absolutePath,
            pattern = regex ?: NEVER_MATCH,
            remoteUrl = if (file.isFile) null else imageUrl,
            keywords = keywords,
          )
        }
      } catch (_: Exception) {
        emptyList()
      }
      if (badges.isNotEmpty()) {
        cached = styleId to badges
        // Warm the on-disk cache in the background; matching and rendering below
        // never wait on these downloads (a full pack can be hundreds of images).
        backgroundWarm.launch {
          badges.forEach { badge ->
            val url = badge.remoteUrl ?: return@forEach
            val file = File(badge.assetPath)
            if (file.isFile && file.length() > 0L) return@forEach
            try {
              val resp = client.newCall(Request.Builder().url(url).build()).execute()
              resp.body?.byteStream()?.use { input -> file.outputStream().use { input.copyTo(it) } }
              resp.close()
            } catch (_: Exception) {
              // Left un-cached: the composable falls back to the remote URL.
            }
          }
        }
      }
      badges
    }
  }

  private val NEVER_MATCH = Regex("(?!)")

  private val backgroundWarm = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
