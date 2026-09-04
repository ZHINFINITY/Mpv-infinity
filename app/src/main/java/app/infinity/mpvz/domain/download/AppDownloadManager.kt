/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.domain.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import app.infinity.mpvz.R
import app.infinity.mpvz.database.dao.DownloadItemDao
import app.infinity.mpvz.database.entities.DownloadItemEntity
import app.infinity.mpvz.network.SharedHttpClient
import app.infinity.mpvz.network.awaitResponse
import app.infinity.mpvz.utils.media.PlaybackSubtitleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * In-app direct-file download engine: streams with OkHttp straight into the selected
 * download folder (`<name>.part` in place, renamed on completion), resumes interrupted
 * transfers with Range requests, and reports live progress/speed. Runs inside
 * [DirectDownloadService] so transfers survive backgrounding, with the same
 * notification treatment as the yt-dlp engine.
 *
 * Subtitle sidecars are fetched eagerly and non-fatally next to the video with the same
 * basename, so the player's sibling-subtitle autoload picks them up for local playback.
 */
class AppDownloadManager(
  private val context: Context,
  private val dao: DownloadItemDao,
  val locations: DownloadLocations,
) {
  /** Live notification/UI info for the transfer currently on the wire. */
  data class ActiveSnapshot(
    val id: Long,
    val title: String,
    val progress: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
  )

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mainHandler = Handler(Looper.getMainLooper())

  private val httpClient =
    SharedHttpClient.derive {
      connectTimeout(30, TimeUnit.SECONDS)
      readTimeout(60, TimeUnit.SECONDS)
    }

  private val cancelledIds: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf<Long>())

  private val _activeSnapshot = MutableStateFlow<ActiveSnapshot?>(null)

  /** Snapshot of the currently transferring item (speed for the Downloads screen). */
  val activeSnapshot: StateFlow<ActiveSnapshot?> = _activeSnapshot.asStateFlow()

  /** All registry rows, newest first. */
  val downloads: StateFlow<List<AppDownload>> =
    dao
      .observeAll()
      .map { entities -> entities.map(::AppDownload) }
      .stateIn(scope, SharingStarted.Eagerly, emptyList())

  init {
    // Rows left QUEUED/RUNNING by a killed process resume from their .part files.
    scope.launch {
      val unfinished =
        dao.getAll().filter {
          AppDownloadStatus.from(it.status).let { s -> s == AppDownloadStatus.QUEUED || s == AppDownloadStatus.RUNNING }
        }
      if (unfinished.isNotEmpty()) {
        unfinished.forEach { dao.update(it.copy(status = AppDownloadStatus.QUEUED.name)) }
        DirectDownloadService.start(context)
      }
    }
  }

  fun enqueueVideo(
    url: String,
    directory: File,
    fileName: String,
    meta: DownloadMetadata,
    headers: Map<String, String> = emptyMap(),
  ) {
    scope.launch {
      runCatching {
        if (!directory.exists()) directory.mkdirs()
        check(directory.isDirectory && directory.canWrite()) {
          context.getString(R.string.downloads_location_invalid)
        }
        dao.insert(
          DownloadItemEntity(
            url = url,
            dirPath = directory.absolutePath,
            fileName = fileName,
            // Auth headers ride along for the worker, encoded as simple lines.
            stagingPath = encodeHeaders(headers),
            status = AppDownloadStatus.QUEUED.name,
            source = meta.source,
            title = meta.title,
            posterUrl = meta.posterUrl,
            sourceUrl = meta.sourceUrl,
            jellyfinServerId = meta.jellyfinServerId,
            jellyfinItemId = meta.jellyfinItemId,
            jellyfinSeriesName = meta.jellyfinSeriesName,
            seasonNumber = meta.seasonNumber,
            episodeNumber = meta.episodeNumber,
            isAudio = meta.isAudio,
          ),
        )
        DirectDownloadService.start(context)
      }.onFailure { error ->
        Log.e(TAG, "Failed to enqueue download for $url", error)
        mainHandler.post {
          Toast
            .makeText(
              context,
              context.getString(R.string.downloads_failed) + ": " + (error.message ?: error.javaClass.simpleName),
              Toast.LENGTH_LONG,
            ).show()
        }
      }
    }
  }

  /** Runs queued rows sequentially until the queue drains. Called from the service. */
  suspend fun drainQueue(onUpdate: (ActiveSnapshot) -> Unit) {
    while (true) {
      val next =
        dao.getAll()
          .filter { it.status == AppDownloadStatus.QUEUED.name }
          .minByOrNull { it.timeQueued } ?: break
      runDownload(next, onUpdate)
    }
    _activeSnapshot.value = null
  }

  fun queuedCount(): Int = downloads.value.count { it.status == AppDownloadStatus.QUEUED }

  /** Cancels whatever transfer the service is currently running. */
  fun cancelActive() {
    _activeSnapshot.value?.let { cancel(it.id) }
  }

  private suspend fun runDownload(
    entity: DownloadItemEntity,
    onUpdate: (ActiveSnapshot) -> Unit,
  ) {
    val id = entity.id
    cancelledIds.remove(id)
    dao.update(entity.copy(status = AppDownloadStatus.RUNNING.name))

    val directory = File(entity.dirPath)
    val finalFile = File(directory, entity.fileName)
    val partFile = File(directory, entity.fileName + PART_SUFFIX)

    val result =
      runCatching {
        if (!directory.exists()) directory.mkdirs()
        var resumeFrom = if (partFile.isFile) partFile.length() else 0L

        val requestBuilder = Request.Builder().url(entity.url).get()
        decodeHeaders(entity.stagingPath).forEach { (key, value) -> requestBuilder.header(key, value) }
        if (resumeFrom > 0) requestBuilder.header("Range", "bytes=$resumeFrom-")

        httpClient.newCall(requestBuilder.build()).awaitResponse().use { response ->
          if (resumeFrom > 0 && response.code != 206) {
            // Server ignored the range; start over.
            partFile.delete()
            resumeFrom = 0
          }
          check(response.isSuccessful) { "HTTP ${response.code}" }
          val body = response.body
          val totalBytes =
            body.contentLength().takeIf { it > 0 }?.plus(resumeFrom) ?: 0L

          var downloaded = resumeFrom
          var windowBytes = 0L
          var windowStart = System.currentTimeMillis()
          var lastDbWrite = 0L

          body.byteStream().use { input ->
            FileOutputStream(partFile, resumeFrom > 0).use { output ->
              val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
              while (true) {
                if (id in cancelledIds) throw CancelledDownloadException()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                downloaded += read
                windowBytes += read

                val now = System.currentTimeMillis()
                if (now - windowStart >= PROGRESS_INTERVAL_MS) {
                  val speed = windowBytes * 1000 / (now - windowStart).coerceAtLeast(1)
                  windowBytes = 0
                  windowStart = now
                  val progress =
                    if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                  val snapshot =
                    ActiveSnapshot(
                      id = id,
                      title = entity.title.ifBlank { entity.fileName },
                      progress = progress,
                      downloadedBytes = downloaded,
                      totalBytes = totalBytes,
                      speedBytesPerSec = speed,
                    )
                  _activeSnapshot.value = snapshot
                  onUpdate(snapshot)
                  if (now - lastDbWrite >= DB_WRITE_INTERVAL_MS) {
                    lastDbWrite = now
                    dao.findById(id)?.let {
                      dao.update(it.copy(progress = progress, totalBytes = totalBytes))
                    }
                  }
                }
              }
            }
          }

          check(partFile.renameTo(finalFile)) { "Could not finalize file in download folder" }
          totalBytes.takeIf { it > 0 } ?: finalFile.length()
        }
      }

    result
      .onSuccess { total ->
        dao.findById(id)?.let {
          dao.update(
            it.copy(
              status = AppDownloadStatus.SUCCESS.name,
              progress = 100,
              totalBytes = total,
              failureReason = null,
              stagingPath = null,
            ),
          )
        }
      }.onFailure { error ->
        when {
          error is CancelledDownloadException || id in cancelledIds -> {
            runCatching { partFile.delete() }
            dao.findById(id)?.let { dao.update(it.copy(status = AppDownloadStatus.CANCELLED.name)) }
          }
          else -> {
            Log.e(TAG, "Download failed for ${entity.url}", error)
            // Keep the .part file so retry resumes from where it stopped.
            dao.findById(id)?.let {
              dao.update(
                it.copy(
                  status = AppDownloadStatus.FAILED.name,
                  failureReason = error.message ?: error.javaClass.simpleName,
                ),
              )
            }
          }
        }
      }
    cancelledIds.remove(id)
  }

  /**
   * Fetches external subtitle tracks as sidecar files named `<videoBaseName>.<label>.<ext>`.
   * Failures are logged and skipped: subtitles must never block a video download.
   */
  fun enqueueSubtitleSidecars(
    directory: File,
    videoFileName: String,
    tracks: List<PlaybackSubtitleTrack>,
  ) {
    if (tracks.isEmpty()) return
    val baseName = videoFileName.substringBeforeLast('.')
    scope.launch {
      tracks.forEachIndexed { index, track ->
        runCatching {
          val extension = subtitleExtension(track.url)
          val label =
            DownloadLocations
              .sanitizeName(track.languageCode ?: track.label.ifBlank { "sub${index + 1}" })
              .replace(' ', '_')
              .take(24)
          val target = File(directory, "$baseName.$label.$extension")
          if (target.isFile && target.length() > 0) return@forEachIndexed
          val request = Request.Builder().url(track.url).get().build()
          httpClient.newCall(request).awaitResponse().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body.bytes()
            check(body.isNotEmpty()) { "Empty subtitle body" }
            if (!directory.exists()) directory.mkdirs()
            target.writeBytes(body)
          }
          Log.d(TAG, "Saved subtitle sidecar: ${target.name}")
        }.onFailure { error ->
          Log.w(TAG, "Subtitle sidecar failed for ${track.url}: ${error.message}")
        }
      }
    }
  }

  /** Sidecar subtitle files saved next to a completed download. */
  fun sidecarSubtitles(download: AppDownload): List<File> {
    val baseName = download.entity.fileName.substringBeforeLast('.')
    return download.file.parentFile
      ?.listFiles()
      ?.filter { file ->
        file.isFile &&
          file.name != download.entity.fileName &&
          !file.name.endsWith(PART_SUFFIX) &&
          file.nameWithoutExtension.startsWith(baseName) &&
          file.extension.lowercase() in SUBTITLE_EXTENSIONS
      }.orEmpty()
  }

  fun cancel(id: Long) {
    cancelledIds.add(id)
    scope.launch {
      val entity = dao.findById(id) ?: return@launch
      // The worker handles rows it is streaming; queued rows are finalized here.
      if (entity.status == AppDownloadStatus.QUEUED.name) {
        dao.update(entity.copy(status = AppDownloadStatus.CANCELLED.name))
      }
    }
  }

  fun retry(id: Long) {
    scope.launch {
      val entity = dao.findById(id) ?: return@launch
      dao.update(
        entity.copy(
          status = AppDownloadStatus.QUEUED.name,
          failureReason = null,
        ),
      )
      DirectDownloadService.start(context)
    }
  }

  /** Removes the registry entry; optionally deletes the file and its subtitle sidecars. */
  fun remove(
    download: AppDownload,
    deleteFile: Boolean,
  ) {
    scope.launch {
      val entity = download.entity
      if (download.isActive) cancelledIds.add(entity.id)
      runCatching { File(entity.dirPath, entity.fileName + PART_SUFFIX).delete() }
      if (deleteFile) {
        sidecarSubtitles(download).forEach { runCatching { it.delete() } }
        runCatching { download.file.delete() }
      }
      dao.delete(entity.id)
    }
  }

  /** Completed, still-on-disk download for a Jellyfin item, if any. */
  fun playableForJellyfinItem(itemId: String): AppDownload? =
    downloads.value.firstOrNull { it.entity.jellyfinItemId == itemId && it.isPlayable }

  /** Any live or completed entry for a Jellyfin item, used to avoid duplicates. */
  fun entryForJellyfinItem(itemId: String): AppDownload? =
    downloads.value.firstOrNull {
      it.entity.jellyfinItemId == itemId && (it.isActive || it.isPlayable)
    }

  private class CancelledDownloadException : IOException("Cancelled")

  companion object {
    private const val TAG = "AppDownloadManager"
    private const val PART_SUFFIX = ".part"
    private const val PROGRESS_INTERVAL_MS = 750L
    private const val DB_WRITE_INTERVAL_MS = 1_500L

    val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "sup", "idx", "txt")

    fun subtitleExtension(url: String): String {
      val path = url.substringBefore('?').substringBefore('#')
      val ext = path.substringAfterLast('.', "").lowercase()
      return if (ext in SUBTITLE_EXTENSIONS) ext else "srt"
    }

    /** Headers are stored one per line as `name: value` in the registry row. */
    fun encodeHeaders(headers: Map<String, String>): String? =
      headers.takeIf { it.isNotEmpty() }?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }

    fun decodeHeaders(encoded: String?): Map<String, String> =
      encoded
        ?.lineSequence()
        ?.mapNotNull { line ->
          val separator = line.indexOf(": ")
          if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 2)
        }?.toMap()
        .orEmpty()
  }
}
