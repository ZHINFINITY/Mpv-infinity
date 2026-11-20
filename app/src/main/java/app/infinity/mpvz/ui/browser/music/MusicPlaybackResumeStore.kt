/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.music

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class MusicPlaybackResumeSnapshot(
  val launchToken: String,
  val launchSource: String,
  val queue: List<MusicQueueTrackSnapshot>,
  val currentIndex: Int,
  val positionMs: Long,
)

/** Durable state for the dedicated Music player; deliberately separate from video/audio queues. */
internal object MusicPlaybackResumeStore {
  private const val TAG = "MusicPlaybackResumeStore"
  private const val PREFS_NAME = "music_playback_resume"
  internal const val SNAPSHOT_KEY = "snapshot_v1"
  private const val VERSION = 1
  internal const val MAX_PERSISTED_QUEUE_ITEMS = 500

  @Volatile private var cachedPreferences: SharedPreferences? = null
  @Volatile private var preferencesOverrideForTests: SharedPreferences? = null

  /** Test seam for using Robolectric's in-memory preferences without creating a Keystore key. */
  @Synchronized
  internal fun setPreferencesForTests(preferences: SharedPreferences?) {
    preferencesOverrideForTests = preferences
    cachedPreferences = null
  }

  @Synchronized
  fun save(context: Context, snapshot: MusicPlaybackSnapshot): Boolean {
    val launchToken = snapshot.launchToken?.takeIf { it.isNotBlank() } ?: return false
    val queue = snapshot.queue
    val currentIndex = snapshot.currentIndex
    if (queue.isEmpty() || currentIndex !in queue.indices || snapshot.positionMs < 0L) return false

    val (windowStart, windowEnd) = queueWindow(queue.size, currentIndex, MAX_PERSISTED_QUEUE_ITEMS)
    val persistedQueue = queue.subList(windowStart, windowEnd)
    if (persistedQueue.any { !it.isValidResumeTrack() }) return false

    val resume =
      MusicPlaybackResumeSnapshot(
        launchToken = launchToken,
        launchSource = snapshot.launchSource?.takeIf { it.isNotBlank() } ?: "music_library",
        queue = persistedQueue,
        currentIndex = currentIndex - windowStart,
        positionMs = snapshot.positionMs,
      )
    val preferences = preferences(context) ?: return false
    return runCatching {
      preferences.edit().putString(SNAPSHOT_KEY, encode(resume)).commit()
    }.onFailure { error ->
      Log.e(TAG, "Unable to persist the Music playback state", error)
    }.getOrDefault(false)
  }

  @Synchronized
  fun load(context: Context): MusicPlaybackResumeSnapshot? {
    val preferences = preferences(context) ?: return null
    val encoded =
      runCatching { preferences.getString(SNAPSHOT_KEY, null) }
        .onFailure { error -> Log.w(TAG, "Unable to read the Music playback snapshot", error) }
        .getOrNull() ?: return null
    return runCatching { decode(encoded) }
      .onFailure { error ->
        Log.w(TAG, "Discarding an invalid Music playback snapshot", error)
        runCatching { preferences.edit().remove(SNAPSHOT_KEY).apply() }
      }.getOrNull()
  }

  @Synchronized
  fun clear(context: Context) {
    runCatching { preferences(context)?.edit()?.remove(SNAPSHOT_KEY)?.commit() }
      .onFailure { error -> Log.w(TAG, "Unable to clear the Music playback snapshot", error) }
  }

  internal fun queueWindow(size: Int, currentIndex: Int, max: Int): Pair<Int, Int> {
    if (size <= max) return 0 to size
    val start = (currentIndex - max / 4).coerceIn(0, size - max)
    return start to start + max
  }

  private fun MusicQueueTrackSnapshot.isValidResumeTrack(): Boolean {
    if (mediaId.isBlank() || durationMs < 0L) return false
    val parsed = uri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
    val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return false
    return scheme == "file" || scheme == "content"
  }

  private fun encode(snapshot: MusicPlaybackResumeSnapshot): String =
    JSONObject().apply {
      put("version", VERSION)
      put("launchToken", snapshot.launchToken)
      put("launchSource", snapshot.launchSource)
      put("currentIndex", snapshot.currentIndex)
      put("positionMs", snapshot.positionMs)
      put(
        "queue",
        JSONArray().apply { snapshot.queue.forEach { put(encodeTrack(it)) } },
      )
    }.toString()

  private fun encodeTrack(track: MusicQueueTrackSnapshot): JSONObject =
    JSONObject().apply {
      put("mediaId", track.mediaId)
      put("uri", track.uri ?: JSONObject.NULL)
      put("title", track.title)
      put("artist", track.artist)
      put("album", track.album)
      put("albumId", track.albumId)
      put("artworkUri", track.artworkUri ?: JSONObject.NULL)
      put("durationMs", track.durationMs)
      put("songId", track.songId)
      put("path", track.path ?: JSONObject.NULL)
      put("dateAdded", track.dateAdded)
      put("trackNumber", track.trackNumber)
      put("year", track.year)
      put("size", track.size)
    }

  private fun decode(encoded: String): MusicPlaybackResumeSnapshot {
    val root = JSONObject(encoded)
    require(root.getInt("version") == VERSION) { "Unsupported Music resume snapshot version" }
    val launchToken = root.getString("launchToken").takeIf { it.isNotBlank() }
      ?: error("Missing Music launch token")
    val queueJson = root.getJSONArray("queue")
    require(queueJson.length() in 1..MAX_PERSISTED_QUEUE_ITEMS) { "Invalid Music resume queue length" }
    val queue = (0 until queueJson.length()).map { index -> decodeTrack(queueJson.getJSONObject(index)) }
    val currentIndex = root.getInt("currentIndex")
    require(currentIndex in queue.indices) { "Invalid Music resume current index" }
    val positionMs = root.getLong("positionMs")
    require(positionMs >= 0L) { "Invalid Music resume position" }
    return MusicPlaybackResumeSnapshot(
      launchToken = launchToken,
      launchSource = root.optString("launchSource", "music_library").ifBlank { "music_library" },
      queue = queue,
      currentIndex = currentIndex,
      positionMs = positionMs,
    )
  }

  private fun decodeTrack(track: JSONObject): MusicQueueTrackSnapshot {
    val decoded =
      MusicQueueTrackSnapshot(
        mediaId = track.getString("mediaId"),
        uri = track.optNullableString("uri"),
        title = track.optString("title", ""),
        artist = track.optString("artist", ""),
        artworkUri = track.optNullableString("artworkUri"),
        durationMs = track.getLong("durationMs"),
        songId = track.optLong("songId", 0L),
        album = track.optString("album", ""),
        albumId = track.optLong("albumId", 0L),
        path = track.optNullableString("path"),
        dateAdded = track.optLong("dateAdded", 0L),
        trackNumber = track.optInt("trackNumber", 0),
        year = track.optInt("year", 0),
        size = track.optLong("size", 0L),
      )
    require(decoded.isValidResumeTrack()) { "Invalid Music resume queue item" }
    return decoded
  }

  private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

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
        )
      }.onSuccess { cachedPreferences = it }
        .onFailure { error -> Log.e(TAG, "Encrypted Music resume preferences are unavailable", error) }
        .getOrNull()
    }
  }
}

/** Selects a matching saved session, dropping inaccessible neighbors and falling back safely. */
internal fun restoreMusicPlaybackLaunch(
  snapshot: MusicPlaybackResumeSnapshot?,
  requestedToken: String?,
  fallback: MusicQueueLaunch?,
  isUriAccessible: (Uri) -> Boolean,
): MusicQueueLaunch? {
  if (snapshot == null || snapshot.queue.isEmpty() || snapshot.currentIndex !in snapshot.queue.indices) return fallback
  if (requestedToken != null && snapshot.launchToken != requestedToken) return fallback

  val songs = ArrayList<MusicSong>(snapshot.queue.size)
  var restoredIndex = -1
  snapshot.queue.forEachIndexed { index, track ->
    val song = track.toMusicSongOrNull() ?: return@forEachIndexed
    val accessible = runCatching { isUriAccessible(song.uri) }.getOrDefault(false)
    if (!accessible) return@forEachIndexed
    if (index == snapshot.currentIndex) restoredIndex = songs.size
    songs += song
  }
  if (restoredIndex !in songs.indices) return fallback

  val current = songs[restoredIndex]
  val positionMs =
    current.durationMs.takeIf { it > 0L }
      ?.let { snapshot.positionMs.coerceAtMost(it) }
      ?: snapshot.positionMs
  return MusicQueueLaunch(
    token = snapshot.launchToken,
    songs = songs,
    currentIndex = restoredIndex,
    positionMs = positionMs,
    launchSource = snapshot.launchSource,
  )
}

internal fun isMusicPlaybackUriAccessible(context: Context, uri: Uri): Boolean =
  when (uri.scheme?.lowercase(Locale.ROOT)) {
    "file" -> uri.path?.let { File(it).isFile && File(it).canRead() } == true
    "content" ->
      runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }
        .getOrDefault(false)
    else -> false
  }
