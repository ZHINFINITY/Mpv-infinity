/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal enum class FlowDiagnosticsCapturePhase {
  IDLE,
  STARTING,
  CAPTURING,
  STOPPING,
  COMPLETE,
  PARTIAL,
  FAILED,
}

internal data class FlowDiagnosticsCaptureUiState(
  val phase: FlowDiagnosticsCapturePhase = FlowDiagnosticsCapturePhase.IDLE,
  val engine: String? = null,
  val pairs: Int = 0,
  val synthesizedOutputs: Int = 0,
  val bytesWritten: Long = 0L,
  val missingChannels: List<String> = emptyList(),
  val message: String? = null,
  val savedLocation: String? = null,
  val savedUri: Uri? = null,
) {
  val active: Boolean
    get() = phase in setOf(
      FlowDiagnosticsCapturePhase.STARTING,
      FlowDiagnosticsCapturePhase.CAPTURING,
      FlowDiagnosticsCapturePhase.STOPPING,
  )
}

internal fun canStartFlowDiagnosticsCapture(
  state: FlowDiagnosticsCaptureUiState,
  currentSessionId: String?,
): Boolean = !state.active && currentSessionId == null

/** A short, user-started local capture; the native writer enforces the image/session bounds. */
internal class FlowDiagnosticsCaptureManager(context: Context) {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val _state = MutableStateFlow(FlowDiagnosticsCaptureUiState())
  val state: StateFlow<FlowDiagnosticsCaptureUiState> = _state.asStateFlow()

  private var sessionId: String? = null
  private var sessionRoot: File? = null
  private var sessionEngine: String? = null
  private var stopRequest: ((String) -> String?)? = null
  private var timingSnapshot: (() -> JSONObject?)? = null
  private var monitorJob: Job? = null

  /** Return null when the selected engine accepted the start request; otherwise return a reason. */
  fun start(
    engine: String,
    startRequest: (sessionId: String, baseDirectory: File) -> String?,
    stopRequest: (sessionId: String) -> String?,
    timingSnapshot: () -> JSONObject?,
  ): String? {
    if (engine !in setOf("mpv", "native-media3")) return "unsupported_engine"
    if (!canStartFlowDiagnosticsCapture(_state.value, sessionId)) return "capture_already_active"
    val root = File(appContext.cacheDir, "flow-diagnostics")
    if (!root.isDirectory && !root.mkdirs()) return "capture_cache_unavailable"
    val id = "flow_${UUID.randomUUID().toString().replace("-", "")}"
    val priorSession = File(root, id)
    if (priorSession.exists()) return "capture_session_collision"
    val error = runCatching { startRequest(id, root) }.getOrElse { "engine_start_${it.javaClass.simpleName.lowercase()}" }
    if (error != null) {
      _state.value = FlowDiagnosticsCaptureUiState(
        phase = FlowDiagnosticsCapturePhase.FAILED,
        engine = engine,
        message = "Capture could not start: $error",
      )
      return error
    }
    sessionId = id
    sessionRoot = root
    sessionEngine = engine
    this.stopRequest = stopRequest
    this.timingSnapshot = timingSnapshot
    _state.value = FlowDiagnosticsCaptureUiState(
      phase = FlowDiagnosticsCapturePhase.STARTING,
      engine = engine,
      message = "Capture armed. Replay the marked moment; it stops at the capture limit.",
    )
    monitorJob?.cancel()
    monitorJob = scope.launch { monitor(id, root, engine) }
    return null
  }

  fun stop(): String? {
    val id = sessionId ?: return "no_active_capture"
    val stop = stopRequest ?: return "capture_stop_unavailable"
    val previousPhase = _state.value.phase
    appendTimingSnapshot(id, "stop")
    _state.value = _state.value.copy(
      phase = FlowDiagnosticsCapturePhase.STOPPING,
      message = "Stopping and saving the local diagnostic sample…",
    )
    val error = runCatching { stop(id) }.getOrElse { "engine_stop_${it.javaClass.simpleName.lowercase()}" }
    if (error != null) {
      val retainedPhase = previousPhase.takeIf {
        it == FlowDiagnosticsCapturePhase.STARTING || it == FlowDiagnosticsCapturePhase.CAPTURING ||
          it == FlowDiagnosticsCapturePhase.STOPPING
      } ?: FlowDiagnosticsCapturePhase.CAPTURING
      _state.value = _state.value.copy(
        phase = retainedPhase,
        message = "Stop request failed; capture may still be active. Retry Stop: $error",
      )
      return error
    }
    val root = sessionRoot
    val engine = sessionEngine
    if (monitorJob?.isActive != true && root != null && engine != null) {
      monitorJob = scope.launch { monitor(id, root, engine) }
    }
    return null
  }

  private suspend fun monitor(id: String, root: File, engine: String) {
    val session = File(root, id)
    val deadline = SystemClock.elapsedRealtime() + MAX_CAPTURE_MILLIS
    var stopRequestedForTimeout = false
    var timeoutGraceDeadline = 0L
    var nextTimeoutStopAttemptAt = deadline
    var lastTimingSnapshotAt = 0L
    try {
      while (isActive) {
        val status = withContext(Dispatchers.IO) { readManifest(File(session, MANIFEST_NAME)) }
        val timingNow = SystemClock.elapsedRealtime()
        if (timingNow - lastTimingSnapshotAt >= TIMING_SNAPSHOT_INTERVAL_MILLIS || status?.terminal == true) {
          lastTimingSnapshotAt = timingNow
          withContext(Dispatchers.IO) {
            appendTimingSnapshot(session, engine, if (status?.terminal == true) "terminal" else "sample")
          }
        }
        if (status != null) {
          _state.value = _state.value.copy(
            phase = when {
              _state.value.phase == FlowDiagnosticsCapturePhase.STOPPING -> FlowDiagnosticsCapturePhase.STOPPING
              status.terminal -> FlowDiagnosticsCapturePhase.STOPPING
              status.pairs > 0 || status.outputs > 0 -> FlowDiagnosticsCapturePhase.CAPTURING
              else -> FlowDiagnosticsCapturePhase.STARTING
            },
            pairs = status.pairs,
            synthesizedOutputs = status.outputs,
            bytesWritten = status.bytes,
            missingChannels = status.missing,
            message = status.error ?: "Capturing decoded frames and interpolation diagnostics…",
          )
          if (status.terminal) {
            val cleanup = stopRequest
            val cleanupError = if (cleanup == null) "capture_stop_unavailable"
            else runCatching { cleanup(id) }.getOrElse { "engine_cleanup_${it.javaClass.simpleName.lowercase()}" }
            if (cleanupError != null) {
              _state.value = _state.value.copy(
                phase = FlowDiagnosticsCapturePhase.CAPTURING,
                message = "Capture is complete but engine cleanup failed; retry Stop before another capture: $cleanupError",
              )
              delay(POLL_MILLIS)
              continue
            }
            val saved = withContext(Dispatchers.IO) { createAndSaveArchive(session, engine, id) }
            val terminalPhase = when {
              saved.error != null -> FlowDiagnosticsCapturePhase.FAILED
              status.status == "failed" -> FlowDiagnosticsCapturePhase.FAILED
              status.status == "partial" || status.missing.isNotEmpty() -> FlowDiagnosticsCapturePhase.PARTIAL
              else -> FlowDiagnosticsCapturePhase.COMPLETE
            }
            _state.value = _state.value.copy(
              phase = terminalPhase,
              savedLocation = saved.location,
              savedUri = saved.uri,
              message = when (terminalPhase) {
                FlowDiagnosticsCapturePhase.COMPLETE -> "Diagnostic ZIP saved locally."
                FlowDiagnosticsCapturePhase.PARTIAL -> "Partial diagnostic ZIP saved locally; inspect the manifest for missing channels."
                else -> saved.error?.let { "Diagnostics ZIP save failed: $it. Raw capture remains at ${saved.location}." }
                  ?: status.error ?: "Capture failed; any available diagnostics were saved locally."
              },
            )
            clearSession()
            return
          }
        }

        val now = SystemClock.elapsedRealtime()
        if (now >= deadline && !stopRequestedForTimeout && now >= nextTimeoutStopAttemptAt) {
          withContext(Dispatchers.IO) { appendTimingSnapshot(session, engine, "timeout_stop") }
          val stop = stopRequest
          val stopError = if (stop == null) "capture_stop_unavailable"
          else runCatching { stop(id) }.getOrElse { "engine_stop_${it.javaClass.simpleName.lowercase()}" }
          if (stopError == null) {
            stopRequestedForTimeout = true
            timeoutGraceDeadline = now + TIMEOUT_GRACE_MILLIS
            _state.value = _state.value.copy(
              phase = FlowDiagnosticsCapturePhase.STOPPING,
              message = "Capture window ended; requesting a clean stop…",
            )
          } else {
            nextTimeoutStopAttemptAt = now + TIMEOUT_STOP_RETRY_MILLIS
            _state.value = _state.value.copy(
              phase = FlowDiagnosticsCapturePhase.CAPTURING,
              message = "Automatic stop failed; capture may still be active. Press Stop to retry: $stopError",
            )
          }
        } else if (stopRequestedForTimeout && now >= timeoutGraceDeadline) {
          withContext(Dispatchers.IO) { appendTimingSnapshot(session, engine, "partial_timeout") }
          val saved = withContext(Dispatchers.IO) { createAndSaveArchive(session, engine, id) }
          val phase = if (saved.error != null) FlowDiagnosticsCapturePhase.FAILED else FlowDiagnosticsCapturePhase.PARTIAL
          _state.value = _state.value.copy(
            phase = phase,
            savedLocation = saved.location,
            savedUri = saved.uri,
            message = saved.error?.let { "Diagnostics ZIP save failed: $it. Raw capture remains at ${saved.location}." }
              ?: "No terminal native manifest arrived; saved the available partial capture locally.",
          )
          clearSession()
          return
        }
        delay(POLL_MILLIS)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      _state.value = _state.value.copy(
        phase = FlowDiagnosticsCapturePhase.CAPTURING,
        message = "Capture monitor failed; native capture may still be active. Press Stop to retry: ${error.javaClass.simpleName}",
      )
    }
  }

  private fun readManifest(file: File): ManifestStatus? = runCatching {
    if (!file.isFile || file.length() <= 0L || file.length() > MAX_MANIFEST_BYTES) return null
    val json = JSONObject(file.readText(Charsets.UTF_8))
    val status = json.optString("status", "capturing").lowercase()
    val missing = mutableListOf<String>()
    val missingArray = json.optJSONArray("missing") ?: json.optJSONArray("missing_channels")
    missingArray?.let { array ->
      for (i in 0 until array.length()) {
        val item = array.opt(i)
        val label = when (item) {
          is JSONObject -> listOf(
            item.optString("channel"),
            item.optString("stage"),
            item.optString("reason"),
          ).filter(String::isNotBlank).joinToString(":")
          else -> item?.toString().orEmpty()
        }
        if (label.isNotBlank()) missing += label
      }
    }
    val records = json.optJSONArray("records")
    if (missing.isEmpty() && records != null) {
      for (i in 0 until records.length()) {
        val record = records.optJSONObject(i) ?: continue
        if (record.has("missing_channel")) {
          val label = listOf(
            record.optString("missing_channel"),
            record.optString("error_stage"),
            record.optString("reason"),
          ).filter(String::isNotBlank).joinToString(":")
          if (label.isNotBlank()) missing += label
        }
      }
    }
    val sampleCount = json.optInt("sample_count", json.optInt("samples_written", -1))
    val outputs = if (sampleCount >= 0) sampleCount else records?.let { array ->
      (0 until array.length()).count { array.optJSONObject(it)?.optString("channel") == "synthesized" }
    } ?: 0
    ManifestStatus(
      status = status,
      pairs = json.optInt("pair_count", json.optInt("pairs_written", 0)).coerceIn(0, 8),
      outputs = outputs.coerceIn(0, 32),
      bytes = json.optLong("bytes_written", json.optLong("bytes", 0L)).coerceAtLeast(0L),
      missing = missing.distinct().take(MAX_MISSING_REASONS),
      error = json.optString("error").takeIf(String::isNotBlank),
      terminal = status in setOf("complete", "failed") ||
        (status == "partial" && json.optBoolean("stopped", json.optBoolean("terminal", false))),
    )
  }.getOrNull()

  private fun createAndSaveArchive(session: File, engine: String, id: String): SavedArchive {
    val localZip = File(appContext.cacheDir, "$id.zip")
    val contentsDir = if (session.isDirectory) session else null
    try {
      if (contentsDir != null) FlowDiagnosticsArchiveWriter.write(contentsDir, localZip)
      else ZipOutputStream(FileOutputStream(localZip)).use { zip ->
        zip.putNextEntry(ZipEntry("capture_unavailable.txt"))
        zip.write("Native capture directory was not created.\n".toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
      val saved = saveToUserStorage(localZip, engine, id)
      if (saved.uri != null || saved.location.isNotBlank()) {
        contentsDir?.deleteRecursively()
      }
      return saved
    } catch (error: Exception) {
      return SavedArchive(
        location = session.absolutePath,
        uri = null,
        error = "archive_save_${error.javaClass.simpleName.lowercase()}",
      )
    } finally {
      localZip.delete()
    }
  }

  private fun saveToUserStorage(source: File, engine: String, id: String): SavedArchive {
    val displayName = "MpvFlow_${engine}_${id}.zip"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Mpv∞/Flow Diagnostics")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
      val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: return SavedArchive("Downloads/Mpv∞/Flow Diagnostics/$displayName", null, "downloads_insert_failed")
      try {
        appContext.contentResolver.openOutputStream(uri, "w")?.use { out -> source.inputStream().use { it.copyTo(out) } }
          ?: throw IllegalStateException("downloads_output_unavailable")
        val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (appContext.contentResolver.update(uri, published, null, null) != 1) {
          throw IllegalStateException("downloads_publish_failed")
        }
        return SavedArchive("Downloads/Mpv∞/Flow Diagnostics/$displayName", uri)
      } catch (error: Exception) {
        appContext.contentResolver.delete(uri, null, null)
        throw error
      }
    }

    val directory = File(
      appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: appContext.filesDir,
      "Mpv∞/Flow Diagnostics",
    )
    if (!directory.isDirectory && !directory.mkdirs()) throw IllegalStateException("app_documents_unavailable")
    val destination = File(directory, displayName)
    source.copyTo(destination, overwrite = true)
    return SavedArchive(destination.absolutePath, null)
  }

  private fun clearSession() {
    sessionId = null
    sessionRoot = null
    sessionEngine = null
    stopRequest = null
    timingSnapshot = null
    monitorJob = null
  }

  private fun appendTimingSnapshot(session: File, engine: String, phase: String) {
    val snapshot = runCatching { timingSnapshot?.invoke() }.getOrNull() ?: return
    runCatching {
      FlowDiagnosticsTimingWriter.append(
        sessionDirectory = session,
        engine = engine,
        phase = phase,
        elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
        snapshotJson = snapshot.toString(),
      )
    }
  }

  private fun appendTimingSnapshot(id: String, phase: String) {
    val root = sessionRoot ?: return
    val engine = sessionEngine ?: return
    appendTimingSnapshot(File(root, id), engine, phase)
  }

  companion object {
    private const val MANIFEST_NAME = "manifest.json"
    private const val POLL_MILLIS = 250L
    private const val MAX_CAPTURE_MILLIS = 12_000L
    private const val TIMEOUT_GRACE_MILLIS = 2_000L
    private const val TIMEOUT_STOP_RETRY_MILLIS = 1_000L
    private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
    private const val MAX_MISSING_REASONS = 32
    private const val TIMING_SNAPSHOT_INTERVAL_MILLIS = 1_000L
  }
}

/** App-side timing samples are distinct from displayed-frame/scanout measurements. */
internal object FlowDiagnosticsTimingWriter {
  const val FILE_NAME = "app_timing.jsonl"
  private const val MAX_LINE_BYTES = 64 * 1024
  private const val MAX_FILE_BYTES = 1024 * 1024
  private const val MAX_LINES = 32

  @Synchronized
  fun append(
    sessionDirectory: File,
    engine: String,
    phase: String,
    elapsedRealtimeNs: Long,
    snapshotJson: String,
  ): Boolean {
    if (!sessionDirectory.isDirectory || engine !in setOf("mpv", "native-media3")) return false
    val root = sessionDirectory.canonicalFile
    val file = File(root, FILE_NAME)
    if (file.exists() && (file.canonicalFile.parentFile != root || file.canonicalFile.name != FILE_NAME)) return false
    val existingBytes = file.length()
    if (existingBytes >= MAX_FILE_BYTES) return false
    val existingLines = if (file.isFile) runCatching { file.useLines { it.count() } }.getOrDefault(MAX_LINES) else 0
    if (existingLines >= MAX_LINES) return false
    if (snapshotJson.length > MAX_LINE_BYTES || !snapshotJson.trim().let { it.startsWith("{") && it.endsWith("}") }) return false
    val line = "{\"schema_version\":1,\"engine\":${jsonString(engine)}," +
      "\"phase\":${jsonString(phase)},\"elapsed_realtime_ns\":${elapsedRealtimeNs.coerceAtLeast(0L)}," +
      "\"snapshot\":$snapshotJson}\n"
    val bytes = line.toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_LINE_BYTES || existingBytes + bytes.size > MAX_FILE_BYTES) return false
    return runCatching {
      FileOutputStream(file, true).use { output ->
        output.write(bytes)
        output.flush()
      }
      true
    }.getOrDefault(false)
  }

  private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
      when (char) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000c' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
      }
    }
    append('"')
  }
}

internal data class ManifestStatus(
  val status: String,
  val pairs: Int,
  val outputs: Int,
  val bytes: Long,
  val missing: List<String>,
  val error: String?,
  val terminal: Boolean,
)

internal data class SavedArchive(
  val location: String,
  val uri: Uri?,
  val error: String? = null,
)

/** Pure filesystem ZIP writer; keeps session files bounded and blocks path traversal. */
internal object FlowDiagnosticsArchiveWriter {
  private const val MAX_ARCHIVE_INPUT_BYTES = 68L * 1024 * 1024

  fun write(sourceDirectory: File, destination: File) {
    val root = sourceDirectory.canonicalFile
    require(root.isDirectory) { "capture_directory_missing" }
    var bytes = 0L
    val files = root.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
    ZipOutputStream(FileOutputStream(destination)).use { zip ->
      for (file in files) {
        val canonical = file.canonicalFile
        require(canonical.toPath().startsWith(root.toPath())) { "capture_path_escape" }
        if (file.name.endsWith(".part")) continue
        bytes += file.length()
        require(bytes <= MAX_ARCHIVE_INPUT_BYTES) { "capture_archive_too_large" }
        val entry = root.toPath().relativize(canonical.toPath()).toString().replace(File.separatorChar, '/')
        require(entry.isNotBlank() && !entry.startsWith("/") && !entry.split('/').contains("..")) {
          "capture_entry_invalid"
        }
        zip.putNextEntry(ZipEntry(entry))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
      }
    }
  }
}
