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
          MediaBadge(
            id = id,
            name = entry["name"]?.jsonPrimitive?.content ?: id,
            groupId = entry["groupId"]?.jsonPrimitive?.content ?: "",
            assetPath = file.absolutePath,
            pattern = Regex(entry["pattern"]?.jsonPrimitive?.content ?: return@mapNotNull null),
            remoteUrl = if (file.isFile) null else imageUrl,
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

  private val backgroundWarm = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
