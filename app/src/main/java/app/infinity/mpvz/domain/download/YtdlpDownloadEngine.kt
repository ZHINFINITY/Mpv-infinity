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
import android.util.Log
import app.infinity.mpvz.network.AndroidCookieJar
import app.infinity.mpvz.preferences.YtdlPreferences
import app.infinity.mpvz.ui.player.ytdlp.YtdlpManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads HLS / extractor-backed links (YouTube, m3u8, ...) with the bundled yt-dlp
 * runtime, which handles playlist resolution, segment downloading and AES-128 decryption.
 * Jobs run one at a time inside [YtdlpDownloadService] so they survive backgrounding.
 */
class YtdlpDownloadEngine(
  private val context: Context,
  private val preferences: YtdlPreferences,
) {
  enum class JobState { QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED }

  data class Job(
    val id: Int,
    val url: String,
    val title: String,
    val directory: String,
    val state: JobState = JobState.QUEUED,
    val progressPercent: Float = 0f,
    val detail: String = "",
    val error: String? = null,
    val outputFile: String? = null,
  ) {
    val isActive: Boolean get() = state == JobState.QUEUED || state == JobState.RUNNING
  }

  private val nextId = AtomicInteger(1)
  private val _jobs = MutableStateFlow<List<Job>>(emptyList())
  val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

  @Volatile
  private var activeProcess: Process? = null

  @Volatile
  private var activeJobId: Int = -1

  @Volatile
  private var cancelRequested = false

  fun enqueue(
    url: String,
    title: String,
    directory: File,
  ): Int {
    val id = nextId.getAndIncrement()
    if (!directory.exists()) directory.mkdirs()
    _jobs.update { current ->
      current + Job(id = id, url = url, title = title, directory = directory.absolutePath)
    }
    YtdlpDownloadService.start(context)
    return id
  }

  fun cancel(id: Int) {
    _jobs.update { current ->
      current.map { job ->
        if (job.id == id && job.state == JobState.QUEUED) job.copy(state = JobState.CANCELLED) else job
      }
    }
    if (activeJobId == id) {
      cancelRequested = true
      activeProcess?.destroyForcibly()
    }
  }

  fun retry(id: Int) {
    _jobs.update { current ->
      current.map { job ->
        if (job.id == id && (job.state == JobState.FAILED || job.state == JobState.CANCELLED)) {
          job.copy(state = JobState.QUEUED, progressPercent = 0f, error = null, detail = "")
        } else {
          job
        }
      }
    }
    YtdlpDownloadService.start(context)
  }

  fun remove(id: Int) {
    val job = _jobs.value.firstOrNull { it.id == id } ?: return
    if (job.isActive) cancel(id)
    _jobs.update { current -> current.filterNot { it.id == id } }
  }

  fun hasQueuedWork(): Boolean = _jobs.value.any { it.state == JobState.QUEUED }

  /** Runs queued jobs sequentially until the queue drains. Called from the service. */
  suspend fun drainQueue(onJobUpdate: (Job) -> Unit) {
    while (true) {
      val job = _jobs.value.firstOrNull { it.state == JobState.QUEUED } ?: return
      updateJob(job.id) { it.copy(state = JobState.RUNNING) }
      currentJob(job.id)?.let(onJobUpdate)
      runJob(job.id, onJobUpdate)
    }
  }

  private suspend fun runJob(
    id: Int,
    onJobUpdate: (Job) -> Unit,
  ) {
    val job = currentJob(id) ?: return
    cancelRequested = false
    activeJobId = id

    val ready = YtdlpManager.ensureRuntimeInstalled(context)
    if (!ready) {
      updateJob(id) { it.copy(state = JobState.FAILED, error = "yt-dlp runtime is not installed") }
      currentJob(id)?.let(onJobUpdate)
      return
    }

    val outputTemplate = "${job.directory}/${DownloadLocations.sanitizeName(job.title)}.%(ext)s"
    val command = buildCommand(job.url, outputTemplate)

    val result =
      withContext(Dispatchers.IO) {
        runCatching {
          val process = startProcess(command)
          activeProcess = process
          var destination: String? = null
          BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
            lines.forEach { line ->
              parseDestination(line)?.let { destination = it }
              val progress = parseProgressLine(line)
              if (progress != null) {
                updateJob(id) { it.copy(progressPercent = progress.first, detail = progress.second) }
                currentJob(id)?.let(onJobUpdate)
              }
            }
          }
          val exitCode = runInterruptible { process.waitFor() }
          Pair(exitCode, destination)
        }
      }

    activeProcess = null
    activeJobId = -1

    result
      .onSuccess { (exitCode, destination) ->
        when {
          cancelRequested -> updateJob(id) { it.copy(state = JobState.CANCELLED, detail = "") }
          exitCode == 0 -> {
            val resolved = destination ?: findNewestOutput(job)
            updateJob(id) {
              it.copy(state = JobState.SUCCESS, progressPercent = 100f, detail = "", outputFile = resolved)
            }
          }
          else ->
            updateJob(id) {
              it.copy(state = JobState.FAILED, error = "yt-dlp exited with code $exitCode")
            }
        }
      }.onFailure { error ->
        if (error is CancellationException) throw error
        Log.e(TAG, "yt-dlp download failed", error)
        updateJob(id) { it.copy(state = JobState.FAILED, error = error.message ?: "Unknown error") }
      }
    currentJob(id)?.let(onJobUpdate)
  }

  private fun buildCommand(
    url: String,
    outputTemplate: String,
  ): List<String> =
    buildList {
      add(YtdlpManager.getExecutablePath(context))
      add(File(YtdlpManager.getYtdlDir(context), "yt-dlp").absolutePath)
      add("--ignore-config")
      add("--no-playlist")
      add("--newline")
      add("--no-warnings")
      add("--retries")
      add("5")
      add("--fragment-retries")
      add("5")
      add("--concurrent-fragments")
      add("4")
      add("-o")
      add(outputTemplate)

      preferences.customUserAgent.get().takeIf(String::isNotBlank)?.let { userAgent ->
        add("--user-agent")
        add(userAgent)
      }
      preferences.referer.get().takeIf(String::isNotBlank)?.let { referer ->
        add("--referer")
        add(referer)
      }
      preferences.proxy.get().takeIf(String::isNotBlank)?.let { proxy ->
        add("--proxy")
        add(proxy)
      }
      preferences.extractorArgs.get().takeIf(String::isNotBlank)?.let { extractorArgs ->
        add("--extractor-args")
        add(extractorArgs)
      }
      if (preferences.geoBypass.get()) add("--geo-bypass")

      val cookiesFile =
        preferences.cookiesFile.get().takeIf(String::isNotBlank)
          ?.let(::File)
          ?.takeIf(File::isFile)
          ?: AndroidCookieJar.playbackCookieFile(context).takeIf(File::isFile)
      cookiesFile?.let { file ->
        add("--cookies")
        add(file.absolutePath)
      }

      File(context.applicationInfo.nativeLibraryDir, "libqjs.so")
        .takeIf(File::isFile)
        ?.let { quickJs ->
          add("--js-runtimes")
          add("quickjs:${quickJs.absolutePath}")
        }
      add("--")
      add(url)
    }

  private fun startProcess(command: List<String>): Process {
    val processBuilder =
      ProcessBuilder(command)
        .directory(YtdlpManager.getYtdlDir(context))
        .redirectErrorStream(true)
    val env = processBuilder.environment()
    val ytdlDir = YtdlpManager.getYtdlDir(context).absolutePath
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    env.remove("YTDL_SCRIPT")
    env["YTDL_PYTHON"] = File(nativeLibDir, "libpython.so").absolutePath
    env["PYTHONHOME"] = ytdlDir
    env["PYTHONPATH"] = "$ytdlDir/python313.zip"
    env["SSL_CERT_FILE"] = File(context.filesDir, "cacert.pem").absolutePath
    env["LD_LIBRARY_PATH"] = nativeLibDir
    return processBuilder.start()
  }

  private fun findNewestOutput(job: Job): String? {
    val prefix = DownloadLocations.sanitizeName(job.title)
    return File(job.directory)
      .listFiles()
      ?.filter { it.isFile && it.name.startsWith(prefix) && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
      ?.maxByOrNull { it.lastModified() }
      ?.absolutePath
  }

  private fun currentJob(id: Int): Job? = _jobs.value.firstOrNull { it.id == id }

  private fun updateJob(
    id: Int,
    transform: (Job) -> Job,
  ) {
    _jobs.update { current -> current.map { if (it.id == id) transform(it) else it } }
  }

  companion object {
    private const val TAG = "YtdlpDownloadEngine"

    // Example: "[download]  42.3% of ~ 123.45MiB at 2.34MiB/s ETA 01:23"
    private val PROGRESS_REGEX = Regex("""\[download]\s+([0-9.]+)%(.*)""")
    private val DESTINATION_REGEX = Regex("""\[download] Destination: (.+)""")
    private val ALREADY_DOWNLOADED_REGEX = Regex("""\[download] (.+) has already been downloaded""")

    fun parseProgressLine(line: String): Pair<Float, String>? {
      val match = PROGRESS_REGEX.find(line.trim()) ?: return null
      val percent = match.groupValues[1].toFloatOrNull() ?: return null
      return percent.coerceIn(0f, 100f) to match.groupValues[2].trim()
    }

    fun parseDestination(line: String): String? =
      DESTINATION_REGEX.find(line.trim())?.groupValues?.get(1)?.trim()
        ?: ALREADY_DOWNLOADED_REGEX.find(line.trim())?.groupValues?.get(1)?.trim()
  }
}
