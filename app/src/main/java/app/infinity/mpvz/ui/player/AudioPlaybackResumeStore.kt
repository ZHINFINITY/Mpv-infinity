/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** A small durable snapshot for restoring music after Android kills the playback process. */
internal data class AudioPlaybackResumeSnapshot(
  val queueState: PlaybackQueueState,
  val positionSeconds: Double,
  val paused: Boolean,
  /**
   * The user closed the session (mini player X, notification close or swipe). The snapshot is
   * kept for a later process-recreation restore, but the library does not offer it as a resume card:
   * offering it right after X looked like a second mini player for the song just closed.
   */
  val closedByUser: Boolean = false,
) {
  val currentItem: PlaybackItem?
    get() = queueState.currentItem
}

/**
 * PlaybackSession is deliberately process-local. Keep only audio resume data in encrypted app
 * preferences so PlayerActivity can reconstruct it after process death without changing video or
 * audiobook resume behavior. Stream headers are included because authenticated audio URLs may need
 * them to reopen, and this store uses Android Keystore-backed encrypted preferences.
 */
internal object AudioPlaybackResumeStore {
  private const val TAG = "AudioPlaybackResumeStore"
  private const val PREFS_NAME = "audio_playback_resume"
  private const val SNAPSHOT_KEY = "snapshot_v1"
  private const val SNAPSHOT_VERSION = 1
  /**
   * Upper bound on persisted queue items. A library "play song" queues the whole library, which used
   * to exceed the old limit of 100 and fall back to saving only the current song, so a resumed
   * session "did not know the library". Larger queues now keep a window around the current song.
   */
  internal const val MAX_PERSISTED_QUEUE_ITEMS = 500

  @Volatile private var cachedPreferences: SharedPreferences? = null

  fun save(
    context: Context,
    queueState: PlaybackQueueState,
    positionSeconds: Double,
    paused: Boolean,
    closedByUser: Boolean = false,
  ): Boolean {
    val currentItem = queueState.currentItem ?: return false
    if (!currentItem.isDefinitelyAudioOnly() ||
      currentItem.audiobook != null ||
      currentItem.torrentFileIndex != null ||
      currentItem.requiresTorrentResolution()
    ) {
      clear(context)
      return false
    }

    val (windowStart, windowEnd) =
      queueWindow(queueState.items.size, queueState.currentIndex, MAX_PERSISTED_QUEUE_ITEMS)
    val windowItems = queueState.items.subList(windowStart, windowEnd)
    val canSaveQueue =
      windowItems.all { item ->
        item.isDefinitelyAudioOnly() &&
          item.audiobook == null &&
          item.torrentFileIndex == null &&
          !item.requiresTorrentResolution()
      }
    val savedItems = if (canSaveQueue) windowItems else listOf(currentItem)
    val savedIndex = if (canSaveQueue) queueState.currentIndex - windowStart else 0
    val savedQueue =
      queueState.copy(
        items = savedItems,
        currentIndex = savedIndex,
        shuffleOrder = emptyList(),
        shufflePosition = -1,
        shuffleEnabled = queueState.shuffleEnabled && savedItems.size > 1,
      )
    val snapshot =
      AudioPlaybackResumeSnapshot(
        queueState = savedQueue,
        positionSeconds = positionSeconds.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
        paused = paused,
        closedByUser = closedByUser,
      )

    val preferences = preferences(context) ?: return false
    return runCatching {
      preferences.edit().putString(SNAPSHOT_KEY, encode(snapshot)).commit()
    }.onFailure { error ->
      Log.e(TAG, "Unable to persist the audio playback snapshot", error)
    }.getOrDefault(false)
  }

  fun load(context: Context): AudioPlaybackResumeSnapshot? {
    val preferences = preferences(context) ?: return null
    val encoded =
      runCatching { preferences.getString(SNAPSHOT_KEY, null) }
        .onFailure { error -> Log.e(TAG, "Unable to read the audio playback snapshot", error) }
        .getOrNull() ?: return null
    val snapshot = runCatching { decode(encoded) }
      .onFailure { error ->
        Log.w(TAG, "Discarding an invalid audio playback snapshot", error)
        runCatching { preferences.edit().remove(SNAPSHOT_KEY).apply() }
      }.getOrNull()
    val validSnapshot = snapshot?.takeIf { snapshot ->
        snapshot.currentItem?.let { item ->
          item.isDefinitelyAudioOnly() && item.audiobook == null && !item.requiresTorrentResolution()
        } == true
      }
    if (snapshot != null && validSnapshot == null) {
      runCatching { preferences.edit().remove(SNAPSHOT_KEY).apply() }
    }
    return validSnapshot
  }

  /**
   * Half-open [start, end) range of at most [max] queue items that contains [currentIndex]. The
   * current song sits a quarter into the window, so "next" has more room than "previous".
   */
  internal fun queueWindow(size: Int, currentIndex: Int, max: Int): Pair<Int, Int> {
    if (size <= max) return 0 to size
    val start = (currentIndex - max / 4).coerceIn(0, size - max)
    return start to start + max
  }

  fun clear(context: Context) {
    runCatching { preferences(context)?.edit()?.remove(SNAPSHOT_KEY)?.commit() }
      .onFailure { error -> Log.e(TAG, "Unable to clear the audio playback snapshot", error) }
  }

  private fun preferences(context: Context): SharedPreferences? {
    cachedPreferences?.let { return it }
    return synchronized(this) {
      cachedPreferences ?: runCatching {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
          appContext,
          PREFS_NAME,
          masterKey,
          EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
          EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { preferences -> cachedPreferences = preferences }
      }.onFailure { error ->
        Log.e(TAG, "Encrypted playback preferences are unavailable", error)
      }.getOrNull()
    }
  }

  private fun encode(snapshot: AudioPlaybackResumeSnapshot): String =
    JSONObject().apply {
      put("version", SNAPSHOT_VERSION)
      put("positionSeconds", snapshot.positionSeconds)
      put("paused", snapshot.paused)
      put("closedByUser", snapshot.closedByUser)
      put("currentIndex", snapshot.queueState.currentIndex)
      put("explicitQueue", snapshot.queueState.isExplicitQueue)
      put("m3u", snapshot.queueState.isM3u)
      put("repeatMode", snapshot.queueState.repeatMode.name)
      put("shuffleEnabled", snapshot.queueState.shuffleEnabled)
      put(
        "items",
        JSONArray().apply {
          snapshot.queueState.items.forEach { item -> put(encodeItem(item)) }
        },
      )
    }.toString()

  private fun decode(encoded: String): AudioPlaybackResumeSnapshot {
    val root = JSONObject(encoded)
    require(root.optInt("version", -1) == SNAPSHOT_VERSION) { "Unsupported snapshot version" }
    val sourceItems = root.optJSONArray("items") ?: throw IllegalArgumentException("Missing audio queue")
    val items = buildList {
      for (index in 0 until sourceItems.length()) {
        decodeItem(sourceItems.optJSONObject(index))?.let(::add)
      }
    }
    require(items.isNotEmpty()) { "Empty audio queue" }
    val currentIndex = root.optInt("currentIndex", 0).coerceIn(items.indices)
    val repeatMode =
      runCatching { RepeatMode.valueOf(root.optString("repeatMode", RepeatMode.OFF.name)) }
        .getOrDefault(RepeatMode.OFF)
    val shuffleEnabled = root.optBoolean("shuffleEnabled", false) && items.size > 1
    return AudioPlaybackResumeSnapshot(
      queueState =
        PlaybackQueueState(
          items = items,
          currentIndex = currentIndex,
          isExplicitQueue = root.optBoolean("explicitQueue", items.size > 1),
          isM3u = root.optBoolean("m3u", false),
          repeatMode = repeatMode,
          shuffleEnabled = shuffleEnabled,
        ),
      positionSeconds = root.optDouble("positionSeconds", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
      paused = root.optBoolean("paused", true),
      closedByUser = root.optBoolean("closedByUser", false),
    )
  }

  private fun encodeItem(item: PlaybackItem): JSONObject =
    JSONObject().apply {
      put("stableId", item.stableId)
      put("originalUri", item.originalUri)
      // playableUri can be a short-lived yt-dlp or proxy URL. Re-resolve from originalUri on restore.
      put("title", item.title)
      put("artist", item.artist)
      put("mimeType", item.mimeType)
      put("artworkUri", item.artworkUri)
      item.playlistItemId?.let { put("playlistItemId", it) }
      item.durationSeconds?.let { put("durationSeconds", it) }
      put(
        "headers",
        JSONObject().apply {
          item.headers.forEach { (name, value) -> put(name, value) }
        },
      )
      item.networkSource?.let { source ->
        put(
          "networkSource",
          JSONObject().apply {
            put("connectionId", source.connectionId)
            put("relativePath", source.relativePath)
          },
        )
      }
    }

  private fun decodeItem(json: JSONObject?): PlaybackItem? {
    json ?: return null
    val originalUri = json.optString("originalUri").takeIf { it.isNotBlank() && it != "null" } ?: return null
    val headersObject = json.optJSONObject("headers")
    val headers =
      buildMap {
        if (headersObject != null) {
          val keys = headersObject.keys()
          while (keys.hasNext()) {
            val key = keys.next()
            headersObject.optString(key).takeIf(String::isNotBlank)?.let { value -> put(key, value) }
          }
        }
      }
    val networkSource =
      json.optJSONObject("networkSource")?.let { source ->
        val relativePath = source.optString("relativePath").takeIf(String::isNotBlank)
        if (relativePath == null || !source.has("connectionId")) {
          null
        } else {
          NetworkPlaybackSource(source.optLong("connectionId"), relativePath)
        }
      }
    val stableId = json.optString("stableId").takeIf { it.isNotBlank() && it != "null" }
    return PlaybackItem.fromUri(
      uri = originalUri,
      stableId = stableId,
      playableUri = originalUri,
      title = json.optString("title").takeIf { it.isNotBlank() && it != "null" },
      artist = json.optString("artist").takeIf { it.isNotBlank() && it != "null" },
      mimeType = json.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
      headers = headers,
      networkSource = networkSource,
      playlistItemId = json.optInt("playlistItemId").takeIf { json.has("playlistItemId") },
      artworkUri = json.optString("artworkUri").takeIf { it.isNotBlank() && it != "null" },
      durationSeconds = json.optInt("durationSeconds").takeIf { json.has("durationSeconds") },
    )
  }
}
