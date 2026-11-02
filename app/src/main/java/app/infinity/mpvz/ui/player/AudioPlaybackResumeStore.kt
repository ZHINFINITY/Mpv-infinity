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
import java.util.UUID
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

private fun isValidAudioResumeUri(uri: String): Boolean = uri.isNotBlank() && uri != "null"

/**
 * PlaybackSession is deliberately process-local. Keep only audio resume data in encrypted app
 * preferences so PlayerActivity can reconstruct it after process death without changing video or
 * audiobook resume behavior. Stream headers are included because authenticated audio URLs may need
 * them to reopen, and this store uses Android Keystore-backed encrypted preferences.
 *
 * The queue and frequently changing position are stored separately. A queue revision ties each
 * small playback-state record to the queue it describes; position saves only rewrite that small
 * record, while queue changes atomically replace both records.
 */
internal object AudioPlaybackResumeStore {
  private const val TAG = "AudioPlaybackResumeStore"
  private const val PREFS_NAME = "audio_playback_resume"
  internal const val LEGACY_SNAPSHOT_KEY = "snapshot_v1"
  internal const val QUEUE_KEY = "queue_v2"
  internal const val STATE_KEY = "state_v2"
  private const val LEGACY_SNAPSHOT_VERSION = 1
  private const val SPLIT_RECORD_VERSION = 2

  /**
   * Upper bound on persisted queue items. A library "play song" queues the whole library, which used
   * to exceed the old limit of 100 and fall back to saving only the current song, so a resumed
   * session "did not know the library". Larger queues now keep a window around the current song.
   */
  internal const val MAX_PERSISTED_QUEUE_ITEMS = 500

  private data class QueueRecord(
    val revision: String,
    val items: List<PlaybackItem>,
  )

  private data class PlaybackStateRecord(
    val queueRevision: String,
    val currentIndex: Int,
    val isExplicitQueue: Boolean,
    val isM3u: Boolean,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val positionSeconds: Double,
    val paused: Boolean,
    val closedByUser: Boolean,
  )

  private data class CachedQueue(
    val revision: String,
    val items: List<PlaybackItem>,
    val encoded: String,
  )

  @Volatile private var cachedPreferences: SharedPreferences? = null
  @Volatile private var preferencesOverrideForTests: SharedPreferences? = null
  private var cachedQueue: CachedQueue? = null

  /** Test seam for exercising store logic with Robolectric's in-memory preferences. */
  @Synchronized
  internal fun setPreferencesForTests(preferences: SharedPreferences?) {
    preferencesOverrideForTests = preferences
    cachedQueue = null
  }

  internal fun canPersistCurrentItem(currentItem: PlaybackItem?): Boolean =
    currentItem?.let { item ->
      isValidAudioResumeUri(item.originalUri) &&
        item.isDefinitelyAudioOnly() &&
        item.audiobook == null &&
        item.torrentFileIndex == null &&
        !item.requiresTorrentResolution()
    } == true

  @Synchronized
  fun save(
    context: Context,
    queueState: PlaybackQueueState,
    positionSeconds: Double,
    paused: Boolean,
    closedByUser: Boolean = false,
  ): Boolean {
    val currentItem = queueState.currentItem ?: return false
    if (!canPersistCurrentItem(currentItem)) {
      clear(context)
      return false
    }

    val (windowStart, windowEnd) =
      queueWindow(queueState.items.size, queueState.currentIndex, MAX_PERSISTED_QUEUE_ITEMS)
    val windowItems = queueState.items.subList(windowStart, windowEnd)
    // Do not let the fallback-to-current-item path silently discard a malformed neighbor.
    if (windowItems.any { !isValidAudioResumeUri(it.originalUri) }) {
      clear(context)
      return false
    }
    val canSaveQueue =
      windowItems.all { item ->
        item.isDefinitelyAudioOnly() &&
          item.audiobook == null &&
          item.torrentFileIndex == null &&
          !item.requiresTorrentResolution()
      }
    val savedItems =
      (if (canSaveQueue) windowItems else listOf(currentItem))
        .map { item -> item.copy(headers = item.headers.toMap()) }
    if (savedItems.any { !isValidAudioResumeUri(it.originalUri) }) {
      clear(context)
      return false
    }
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
    val previousQueue = cachedQueue
    val queueChanged = previousQueue == null || previousQueue.items != savedItems
    val queueRecord =
      if (queueChanged) {
        val revision = UUID.randomUUID().toString()
        CachedQueue(revision = revision, items = savedItems, encoded = encodeQueue(revision, savedItems))
      } else {
        previousQueue
      } ?: return false
    val encodedState = encodeState(snapshot, queueRecord.revision)

    return runCatching {
      val editor = preferences.edit()
      if (queueChanged) editor.putString(QUEUE_KEY, queueRecord.encoded)
      editor.putString(STATE_KEY, encodedState)
      editor.remove(LEGACY_SNAPSHOT_KEY)
      val committed = editor.commit()
      if (committed) cachedQueue = queueRecord
      committed
    }.onFailure { error ->
      Log.e(TAG, "Unable to persist the audio playback state", error)
    }.getOrDefault(false)
  }

  @Synchronized
  fun load(context: Context): AudioPlaybackResumeSnapshot? {
    cachedQueue = null
    val preferences = preferences(context) ?: return null
    val queueEncoded =
      runCatching { preferences.getString(QUEUE_KEY, null) }
        .onFailure { error -> Log.e(TAG, "Unable to read the audio playback queue", error) }
        .getOrNull()
    val stateEncoded =
      runCatching { preferences.getString(STATE_KEY, null) }
        .onFailure { error -> Log.e(TAG, "Unable to read the audio playback state", error) }
        .getOrNull()

    if (queueEncoded != null && stateEncoded != null) {
      val decoded =
        runCatching {
          val queue = decodeQueue(queueEncoded)
          val state = decodeState(stateEncoded)
          require(queue.revision == state.queueRevision) { "Queue and playback state revisions differ" }
          queue to state
        }.onFailure { error ->
          Log.w(TAG, "Discarding inconsistent audio playback records", error)
        }.getOrNull()
      val snapshot = decoded?.let { (queue, state) -> snapshot(queue, state) }
      if (snapshot?.isValidResumeSnapshot() == true) {
        val queue = decoded!!.first
        cachedQueue = CachedQueue(queue.revision, queue.items, queueEncoded)
        runCatching { preferences.edit().remove(LEGACY_SNAPSHOT_KEY).apply() }
        return snapshot
      }
    }

    return loadLegacyAndMigrate(preferences, queueEncoded != null || stateEncoded != null)
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

  @Synchronized
  fun clear(context: Context) {
    cachedQueue = null
    runCatching {
      preferences(context)?.edit()
        ?.remove(LEGACY_SNAPSHOT_KEY)
        ?.remove(QUEUE_KEY)
        ?.remove(STATE_KEY)
        ?.commit()
    }.onFailure { error -> Log.e(TAG, "Unable to clear the audio playback snapshot", error) }
  }

  private fun loadLegacyAndMigrate(
    preferences: SharedPreferences,
    hasPartialSplitRecords: Boolean,
  ): AudioPlaybackResumeSnapshot? {
    val legacyEncoded =
      runCatching { preferences.getString(LEGACY_SNAPSHOT_KEY, null) }
        .onFailure { error -> Log.e(TAG, "Unable to read the legacy audio playback snapshot", error) }
        .getOrNull()
    if (legacyEncoded == null) {
      cachedQueue = null
      if (hasPartialSplitRecords) removeSplitRecords(preferences)
      return null
    }

    val snapshot =
      runCatching { decodeLegacySnapshot(legacyEncoded) }
        .onFailure { error -> Log.w(TAG, "Discarding an invalid legacy audio playback snapshot", error) }
        .getOrNull()
    if (snapshot?.isValidResumeSnapshot() != true) {
      cachedQueue = null
      removeAllRecords(preferences)
      return null
    }

    val revision = UUID.randomUUID().toString()
    val queueItems = snapshot.queueState.items.toList()
    val cached = CachedQueue(revision, queueItems, encodeQueue(revision, queueItems))
    val state = encodeState(snapshot, revision)
    val migrated =
      runCatching {
        preferences.edit()
          .putString(QUEUE_KEY, cached.encoded)
          .putString(STATE_KEY, state)
          .remove(LEGACY_SNAPSHOT_KEY)
          .commit()
      }.onFailure { error -> Log.e(TAG, "Unable to migrate the legacy audio playback snapshot", error) }
        .getOrDefault(false)
    if (migrated) cachedQueue = cached
    return snapshot
  }

  private fun removeSplitRecords(preferences: SharedPreferences) {
    runCatching {
      preferences.edit().remove(QUEUE_KEY).remove(STATE_KEY).commit()
    }.onFailure { error -> Log.e(TAG, "Unable to clear inconsistent audio playback records", error) }
  }

  private fun removeAllRecords(preferences: SharedPreferences) {
    runCatching {
      preferences.edit()
        .remove(LEGACY_SNAPSHOT_KEY)
        .remove(QUEUE_KEY)
        .remove(STATE_KEY)
        .commit()
    }.onFailure { error -> Log.e(TAG, "Unable to clear invalid audio playback records", error) }
  }

  private fun AudioPlaybackResumeSnapshot.isValidResumeSnapshot(): Boolean =
    currentItem?.let { item ->
      item.isDefinitelyAudioOnly() && item.audiobook == null && !item.requiresTorrentResolution()
    } == true

  private fun snapshot(queue: QueueRecord, state: PlaybackStateRecord): AudioPlaybackResumeSnapshot {
    val items = queue.items
    require(items.isNotEmpty()) { "Empty audio queue" }
    return AudioPlaybackResumeSnapshot(
      queueState =
        PlaybackQueueState(
          items = items,
          currentIndex = state.currentIndex.coerceIn(items.indices),
          isExplicitQueue = state.isExplicitQueue,
          isM3u = state.isM3u,
          repeatMode = state.repeatMode,
          shuffleEnabled = state.shuffleEnabled && items.size > 1,
        ),
      positionSeconds = state.positionSeconds,
      paused = state.paused,
      closedByUser = state.closedByUser,
    )
  }

  private fun preferences(context: Context): SharedPreferences? {
    preferencesOverrideForTests?.let { return it }
    cachedPreferences?.let { return it }
    return synchronized(this) {
      preferencesOverrideForTests?.let { return@synchronized it }
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

  private fun encodeQueue(revision: String, items: List<PlaybackItem>): String =
    JSONObject().apply {
      put("version", SPLIT_RECORD_VERSION)
      put("revision", revision)
      put(
        "items",
        JSONArray().apply {
          items.forEach { item -> put(encodeItem(item)) }
        },
      )
    }.toString()

  private fun encodeState(snapshot: AudioPlaybackResumeSnapshot, queueRevision: String): String =
    JSONObject().apply {
      put("version", SPLIT_RECORD_VERSION)
      put("queueRevision", queueRevision)
      put("positionSeconds", snapshot.positionSeconds)
      put("paused", snapshot.paused)
      put("closedByUser", snapshot.closedByUser)
      put("currentIndex", snapshot.queueState.currentIndex)
      put("explicitQueue", snapshot.queueState.isExplicitQueue)
      put("m3u", snapshot.queueState.isM3u)
      put("repeatMode", snapshot.queueState.repeatMode.name)
      put("shuffleEnabled", snapshot.queueState.shuffleEnabled)
    }.toString()

  private fun decodeQueue(encoded: String): QueueRecord {
    val root = JSONObject(encoded)
    require(root.optInt("version", -1) == SPLIT_RECORD_VERSION) { "Unsupported queue record version" }
    val revision = root.optString("revision").takeIf(String::isNotBlank)
      ?: throw IllegalArgumentException("Missing queue revision")
    val sourceItems = root.optJSONArray("items") ?: throw IllegalArgumentException("Missing audio queue")
    val items = buildList {
      for (index in 0 until sourceItems.length()) {
        val item = decodeItem(sourceItems.optJSONObject(index))
          ?: throw IllegalArgumentException("Invalid audio queue item at index $index")
        add(item)
      }
    }
    require(items.isNotEmpty()) { "Empty audio queue" }
    return QueueRecord(revision, items)
  }

  private fun decodeState(encoded: String): PlaybackStateRecord {
    val root = JSONObject(encoded)
    require(root.optInt("version", -1) == SPLIT_RECORD_VERSION) { "Unsupported playback state version" }
    val queueRevision = root.optString("queueRevision").takeIf(String::isNotBlank)
      ?: throw IllegalArgumentException("Missing playback queue revision")
    val repeatMode =
      runCatching { RepeatMode.valueOf(root.optString("repeatMode", RepeatMode.OFF.name)) }
        .getOrDefault(RepeatMode.OFF)
    val positionSeconds =
      root.optDouble("positionSeconds", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    return PlaybackStateRecord(
      queueRevision = queueRevision,
      currentIndex = root.optInt("currentIndex", 0),
      isExplicitQueue = root.optBoolean("explicitQueue", true),
      isM3u = root.optBoolean("m3u", false),
      repeatMode = repeatMode,
      shuffleEnabled = root.optBoolean("shuffleEnabled", false),
      positionSeconds = positionSeconds,
      paused = root.optBoolean("paused", true),
      closedByUser = root.optBoolean("closedByUser", false),
    )
  }

  private fun decodeLegacySnapshot(encoded: String): AudioPlaybackResumeSnapshot {
    val root = JSONObject(encoded)
    require(root.optInt("version", -1) == LEGACY_SNAPSHOT_VERSION) { "Unsupported legacy snapshot version" }
    val sourceItems = root.optJSONArray("items") ?: throw IllegalArgumentException("Missing audio queue")
    val items = buildList {
      for (index in 0 until sourceItems.length()) {
        val item = decodeItem(sourceItems.optJSONObject(index))
          ?: throw IllegalArgumentException("Invalid legacy audio queue item at index $index")
        add(item)
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
      item.directAudiobook?.let { marker ->
        put(
          "directAudiobook",
          JSONObject().apply {
            put("queueIdentity", marker.queueIdentity)
            put("trackIdentity", marker.trackIdentity)
          },
        )
      }
    }

  private fun decodeItem(json: JSONObject?): PlaybackItem? {
    json ?: return null
    val originalUri = json.optString("originalUri").takeIf(::isValidAudioResumeUri) ?: return null
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
    val directAudiobook =
      if (json.has("directAudiobook")) {
        val marker = json.optJSONObject("directAudiobook") ?: return null
        val queueIdentity =
          marker.optString("queueIdentity").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val trackIdentity =
          marker.optString("trackIdentity").takeIf { it.isNotBlank() && it != "null" } ?: return null
        DirectAudiobookPlaybackInfo(queueIdentity, trackIdentity)
      } else {
        null
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
    ).copy(directAudiobook = directAudiobook)
  }
}
