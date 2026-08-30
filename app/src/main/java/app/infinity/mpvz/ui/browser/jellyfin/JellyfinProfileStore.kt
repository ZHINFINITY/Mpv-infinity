/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.jellyfin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class JellyfinProfile(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val serverUrl: String,
  val username: String,
  val userId: String,
  val accessToken: String,
)

internal class JellyfinProfileStore(context: Context) {
  private val preferences = context.getSharedPreferences("jellyfin_profiles", Context.MODE_PRIVATE)
  private val key = "profiles"

  fun getAll(): List<JellyfinProfile> {
    val raw = preferences.getString(key, null) ?: return emptyList()
    return runCatching {
      val array = JSONArray(raw)
      buildList {
        for (index in 0 until array.length()) {
          val item = array.optJSONObject(index) ?: continue
          val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
          val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
          val url = item.optString("serverUrl").takeIf { it.isNotBlank() } ?: continue
          val userId = item.optString("userId").takeIf { it.isNotBlank() } ?: continue
          val token = item.optString("accessToken").takeIf { it.isNotBlank() } ?: continue
          add(JellyfinProfile(id, name, url, item.optString("username"), userId, token))
        }
      }
    }.getOrDefault(emptyList())
  }

  fun upsert(profile: JellyfinProfile) {
    val profiles = getAll().filterNot { it.id == profile.id } + profile
    val array = JSONArray()
    profiles.forEach { item ->
      array.put(
        JSONObject()
          .put("id", item.id)
          .put("name", item.name)
          .put("serverUrl", item.serverUrl)
          .put("username", item.username)
          .put("userId", item.userId)
          .put("accessToken", item.accessToken),
      )
    }
    preferences.edit().putString(key, array.toString()).apply()
  }

  fun remove(profileId: String) {
    val array = JSONArray()
    getAll().filterNot { it.id == profileId }.forEach { item ->
      array.put(
        JSONObject()
          .put("id", item.id)
          .put("name", item.name)
          .put("serverUrl", item.serverUrl)
          .put("username", item.username)
          .put("userId", item.userId)
          .put("accessToken", item.accessToken),
      )
    }
    preferences.edit().putString(key, array.toString()).apply()
  }
}
