/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.utils.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class MediaBadge(
  val id: String,
  val name: String,
  val groupId: String,
  val assetPath: String,
  val pattern: Regex,
)

/**
 * Elite-Badges (https://github.com/leonevz/Elite-Badges) bundled under assets/badges/.
 * The JSON's `imageURL` files are shipped locally; only the file name is used.
 */
object MediaBadges {
  private const val ASSET_DIR = "badges"
  private const val ASSET_JSON = "badges/badges.json"

  @Volatile private var cached: List<MediaBadge>? = null

  suspend fun load(context: Context): List<MediaBadge> =
    cached ?: withContext(Dispatchers.IO) { loadFromAssets(context.applicationContext) }.also { cached = it }

  suspend fun matchTitle(context: Context, title: String?): List<MediaBadge> {
    if (title.isNullOrBlank()) return emptyList()
    return load(context).filter { badge ->
      try {
        badge.pattern.containsMatchIn(title)
      } catch (_: Exception) {
        false
      }
    }
  }

  private fun loadFromAssets(context: Context): List<MediaBadge> =
    try {
      val root = Json.parseToJsonElement(context.assets.open(ASSET_JSON).bufferedReader().use { it.readText() }).jsonObject
      root["filters"]!!.jsonArray.mapNotNull { element ->
        val entry = element.jsonObject
        val id = entry["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val image = entry["imageURL"]?.jsonPrimitive?.content?.substringAfterLast('/') ?: return@mapNotNull null
        val enabled = entry["isEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        if (!enabled) return@mapNotNull null
        // Verify the PNG actually shipped in assets, so a JSON change cannot 404 in the UI.
        val exists = runCatching { context.assets.open("$ASSET_DIR/$image").close() }.isSuccess
        if (!exists) return@mapNotNull null
        MediaBadge(
          id = id,
          name = entry["name"]?.jsonPrimitive?.content ?: id,
          groupId = entry["groupId"]?.jsonPrimitive?.content ?: "",
          assetPath = "$ASSET_DIR/$image",
          pattern = Regex(entry["pattern"]?.jsonPrimitive?.content ?: return@mapNotNull null),
        )
      }
    } catch (_: Exception) {
      emptyList()
    }
}
