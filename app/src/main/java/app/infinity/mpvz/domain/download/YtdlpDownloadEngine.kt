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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import app.infinity.mpvz.network.AndroidCookieJar
import app.infinity.mpvz.preferences.YtdlPreferences
import app.infinity.mpvz.ui.player.ytdlp.YtdlpManager
import app.infinity.mpvz.ui.player.ytdlp.YtdlpOptionsBuilder
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
import java.nio.ByteBuffer
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
  enum class JobState { QUEUED, RUNNING, PAUSED, SUCCESS, FAILED, CANCELLED }

  data class Job(
    val id: Int,
    val url: String,
    val title: String,
    val directory: String,
    val qualityHeight: Int = -1,
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
  @Volatile
  private var pauseRequested = false

  fun enqueue(
    url: String,
    title: String,
    directory: File,
    qualityHeight: Int = -1,
  ): Int {
    val id = nextId.getAndIncrement()
    if (!directory.exists()) directory.mkdirs()
    _jobs.update { current ->
      current + Job(
        id = id,
        url = url,
        title = title,
        directory = directory.absolutePath,
        qualityHeight = qualityHeight,
      )
    }
    YtdlpDownloadService.start(context)
    return id
  }

  fun cancel(id: Int) {
    val job = currentJob(id)
    _jobs.update { current ->
      current.map { job ->
        if (job.id == id && job.state == JobState.QUEUED) job.copy(state = JobState.CANCELLED) else job
      }
    }
    if (activeJobId == id) {
      cancelRequested = true
      activeProcess?.destroyForcibly()
    }
    job?.let(::deleteJobFiles)
  }
  fun pause(id: Int) {
    if (activeJobId == id) {
      pauseRequested = true
      activeProcess?.destroyForcibly()
    } else {
      _jobs.update { current -> current.map { job -> if (job.id == id && job.state == JobState.QUEUED) job.copy(state = JobState.PAUSED) else job } }
    }
  }
  fun resume(id: Int) {
    _jobs.update { current -> current.map { job -> if (job.id == id && job.state == JobState.PAUSED) job.copy(state = JobState.QUEUED, error = null) else job } }
    if (hasQueuedWork()) YtdlpDownloadService.start(context)
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
    deleteJobFiles(job)
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
    pauseRequested = false
    activeJobId = id

    val ready = YtdlpManager.ensureRuntimeInstalled(context)
    if (!ready) {
      updateJob(id) { it.copy(state = JobState.FAILED, error = "yt-dlp runtime is not installed") }
      currentJob(id)?.let(onJobUpdate)
      return
    }

    // Human titles and shared URL path segments are not unique (especially Instagram /share/
    // and /reel/ links). Keep every job isolated, then include yt-dlp's extractor and video ID.
    val outputTemplate = "${job.directory}/${jobFilePrefix(job)}%(extractor)s-%(id)s.%(ext)s"
    val ffmpegExecutable = ensureFfmpeg()
    val command = buildCommand(job.url, outputTemplate, job.qualityHeight, ffmpegExecutable)

    val result =
      withContext(Dispatchers.IO) {
        runCatching {
          val process = startProcess(command, ffmpegExecutable?.parentFile)
          activeProcess = process
          var destination: String? = null
          var lastOutputLine = ""
          var lastProgressUpdateAt = 0L
          val diagnosticLines = ArrayDeque<String>()
          BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
            lines.forEach { line ->
              if (line.isNotBlank()) lastOutputLine = line.trim()
              if (!line.contains("[download]", ignoreCase = true) &&
                !line.contains("[Merger]", ignoreCase = true) &&
                !line.contains("Destination:", ignoreCase = true)
              ) {
                if (diagnosticLines.size >= 12) diagnosticLines.removeFirst()
                diagnosticLines.addLast(line.trim())
              }
              parseDestination(line)?.let { destination = it }
              val progress = parseProgressLine(line)
              val now = System.currentTimeMillis()
              if (progress != null &&
                (now - lastProgressUpdateAt >= PROGRESS_UPDATE_INTERVAL_MS || progress.first >= 100f)
              ) {
                lastProgressUpdateAt = now
                updateJob(id) { it.copy(progressPercent = progress.first, detail = progress.second) }
                currentJob(id)?.let(onJobUpdate)
              }
            }
          }
          val exitCode = runInterruptible { process.waitFor() }
          Triple(exitCode, destination, diagnosticLines.joinToString("\n").ifBlank { lastOutputLine })
        }
      }

    activeProcess = null
    activeJobId = -1

    result
      .onSuccess { (exitCode, destination, lastOutputLine) ->
        // yt-dlp can download separate DASH video/audio files when ffmpeg is not available.
        // Android's MediaMuxer can combine the common MP4-compatible outputs without requiring
        // an additional native ffmpeg executable in the APK.
        val nativeMuxed =
          if (!cancelRequested && !pauseRequested) {
            runCatching { muxSeparateStreams(job, ffmpegExecutable) }
              .onFailure { error -> Log.w(TAG, "Native stream mux failed", error) }
              .getOrNull()
          } else {
            null
          }
        when {
          cancelRequested -> {
            deleteJobFiles(job)
            updateJob(id) { it.copy(state = JobState.CANCELLED, detail = "") }
          }
          pauseRequested -> updateJob(id) { it.copy(state = JobState.PAUSED, detail = "") }
          nativeMuxed != null || exitCode == 0 -> {
            val resolved = nativeMuxed ?: destination ?: findNewestOutput(job)
            updateJob(id) {
              it.copy(state = JobState.SUCCESS, progressPercent = 100f, detail = "", outputFile = resolved)
            }
          }
          else ->
            updateJob(id) {
              it.copy(
                state = JobState.FAILED,
                error = lastOutputLine.takeIf { output -> output.isNotBlank() }
                  ?: "yt-dlp exited with code $exitCode",
              )
            }
        }
      }.onFailure { error ->
        if (error is CancellationException) throw error
        Log.e(TAG, "yt-dlp download failed", error)
        if (pauseRequested) {
          updateJob(id) { it.copy(state = JobState.PAUSED, error = null, detail = "") }
        } else if (cancelRequested) {
          deleteJobFiles(job)
          updateJob(id) { it.copy(state = JobState.CANCELLED, error = null, detail = "") }
        } else {
          updateJob(id) { it.copy(state = JobState.FAILED, error = error.message ?: "Unknown error") }
        }
      }
    currentJob(id)?.let(onJobUpdate)
  }

  private fun buildCommand(
    url: String,
    outputTemplate: String,
    qualityHeight: Int,
    ffmpegExecutable: File?,
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
      add("--continue")
      add("--part")
      add("--no-overwrites")
      val sourceHost = Uri.parse(url).host?.lowercase().orEmpty()
      val isInstagram = sourceHost == "instagram.com" || sourceHost.endsWith(".instagram.com")
      add("-f")
      if (isInstagram) {
        // Instagram commonly exposes a single progressive MP4 or separate MP4/M4A streams.
        // Prefer those before the generic best-video+best-audio selection so downloads do not
        // fail when a mergeable audio-only format is unavailable.
        add(
          if (qualityHeight > 0) {
            "best[ext=mp4][vcodec!=none][acodec!=none][height<=?$qualityHeight]/bestvideo*[vcodec!=none][ext=mp4][height<=?$qualityHeight]+bestaudio*[acodec!=none]/best[vcodec!=none][acodec!=none]"
          } else {
            "best[ext=mp4][vcodec!=none][acodec!=none]/bestvideo*[vcodec!=none][ext=mp4]+bestaudio*[acodec!=none]/best[vcodec!=none][acodec!=none]"
          },
        )
      } else if (qualityHeight > 0) {
        add("bv*[vcodec!=none][height<=?$qualityHeight]+ba[acodec!=none]/b[vcodec!=none][acodec!=none][height<=?$qualityHeight]")
      } else {
        add("bv*[vcodec!=none]+ba[acodec!=none]/b[vcodec!=none][acodec!=none]")
      }
      add("--merge-output-format")
      add("mp4")
      ffmpegExecutable?.let {
        add("--ffmpeg-location")
        add(it.absolutePath)
      }
      add("-o")
      add(outputTemplate)

      val configuredUserAgent = preferences.customUserAgent.get().trim()
      if (configuredUserAgent.isNotBlank()) {
        add("--user-agent")
        add(configuredUserAgent)
      } else {
        add("--user-agent")
        add(YtdlpOptionsBuilder.DEFAULT_USER_AGENT)
      }
      preferences.referer.get().takeIf(String::isNotBlank)?.let { referer ->
        add("--referer")
        add(referer)
      }
      if (preferences.referer.get().isBlank()) {
        Uri.parse(url).host?.lowercase()?.let { host ->
          when {
            host == "instagram.com" || host.endsWith(".instagram.com") -> "https://www.instagram.com/"
            host == "facebook.com" || host.endsWith(".facebook.com") -> "https://www.facebook.com/"
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> "https://www.tiktok.com/"
            else -> null
          }
        }?.let { referer ->
          add("--referer")
          add(referer)
        }
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

  /** Returns the ABI-matched FFmpeg executable from Android's executable native library directory. */
  private fun ensureFfmpeg(): File? {
    val abi = Build.SUPPORTED_ABIS.firstOrNull { supportedAbi ->
      supportedAbi == "arm64-v8a"
    } ?: return null
    val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
    val executable = File(nativeDirectory, "libffmpeg_exec.so")
    return executable.takeIf { it.isFile && it.length() > 0L && it.canExecute() }
      ?: run {
        Log.w(TAG, "Bundled ffmpeg is unavailable for $abi at ${executable.absolutePath}")
        null
      }
  }

  private fun startProcess(command: List<String>, ffmpegDirectory: File?): Process {
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
    env["LD_LIBRARY_PATH"] =
      listOfNotNull(ffmpegDirectory?.absolutePath, nativeLibDir).joinToString(":")
    return processBuilder.start()
  }

  private fun findNewestOutput(job: Job): String? {
    val prefix = jobFilePrefix(job)
    return File(job.directory)
      .listFiles()
      ?.filter { it.isFile && it.name.startsWith(prefix) && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
      ?.maxByOrNull { it.lastModified() }
      ?.absolutePath
  }

  /**
   * Muxes the newest video-only and audio-only files left by yt-dlp into one MP4.
   * This is a fallback for devices/builds that do not ship the ffmpeg executable.
   */
  private fun muxSeparateStreams(job: Job, ffmpegExecutable: File?): String? {
    val prefix = jobFilePrefix(job)
    val candidates =
      File(job.directory)
        .listFiles()
        ?.filter {
          it.isFile &&
            it.name.startsWith(prefix) &&
            !it.name.endsWith(".part") &&
            !it.name.endsWith(".ytdl") &&
            !it.name.endsWith(".muxing.mp4")
        }
        ?.sortedByDescending(File::lastModified)
        .orEmpty()
    if (candidates.size < 2) return null

    var videoFile: File? = null
    var audioFile: File? = null
    for (file in candidates) {
      val extractor = MediaExtractor()
      try {
        extractor.setDataSource(file.absolutePath)
        for (trackIndex in 0 until extractor.trackCount) {
          val mime = extractor.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME).orEmpty()
          if (videoFile == null && mime.startsWith("video/")) videoFile = file
          if (audioFile == null && mime.startsWith("audio/")) audioFile = file
        }
      } finally {
        extractor.release()
      }
      if (videoFile != null && audioFile != null) break
    }
    if (videoFile == null || audioFile == null || videoFile == audioFile) return null

    val output = File(job.directory, "$prefix.muxing.mp4")
    val finalOutput = File(job.directory, "$prefix.mp4")

    // Prefer FFmpeg because Android MediaMuxer rejects WebM/VP9 and other tracks that
    // cannot be represented in an MP4 container. This also handles timestamp normalization.
    ffmpegExecutable?.let { ffmpeg ->
      val directory = ffmpeg.parentFile ?: return@let
      if (ffmpeg.isFile && ffmpeg.canExecute()) {
        val processBuilder =
          ProcessBuilder(
            ffmpeg.absolutePath,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            videoFile!!.absolutePath,
            "-i",
            audioFile!!.absolutePath,
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-c",
            "copy",
            "-movflags",
            "+faststart",
            finalOutput.absolutePath,
          )
            .directory(directory)
            .redirectErrorStream(true)
        val environment = processBuilder.environment()
        environment["LD_LIBRARY_PATH"] = "${directory.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
        val process = processBuilder.start()
        val diagnostics = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        if (exitCode == 0 && finalOutput.isFile && finalOutput.length() > 0L) {
          videoFile!!.delete()
          audioFile!!.delete()
          return finalOutput.absolutePath
        }
        Log.w(TAG, "FFmpeg stream mux failed with code $exitCode: $diagnostics")
        finalOutput.delete()
      }
    }

    output.delete()
    var muxer: MediaMuxer? = null
    val extractors = mutableListOf<MediaExtractor>()
    return try {
      muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      val trackFiles = listOf(videoFile!!, audioFile!!)
      val muxerTracks = trackFiles.map { file ->
        val extractor = MediaExtractor()
        extractors += extractor
        extractor.setDataSource(file.absolutePath)
        val trackIndex = (0 until extractor.trackCount).first { index ->
          val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
          if (file == videoFile) mime.startsWith("video/") else mime.startsWith("audio/")
        }
        extractor.selectTrack(trackIndex)
        muxer!!.addTrack(extractor.getTrackFormat(trackIndex))
      }
      muxer!!.start()
      val buffer = ByteBuffer.allocate(1024 * 1024)
      val info = android.media.MediaCodec.BufferInfo()
      extractors.forEachIndexed { index, extractor ->
        while (true) {
          val sampleSize = extractor.readSampleData(buffer, 0)
          if (sampleSize < 0) break
          info.offset = 0
          info.size = sampleSize
          info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
          info.flags = extractor.sampleFlags
          muxer!!.writeSampleData(muxerTracks[index], buffer, info)
          extractor.advance()
          buffer.clear()
        }
      }
      muxer!!.stop()
      muxer!!.release()
      muxer = null
      if (!output.renameTo(finalOutput)) {
        output.copyTo(finalOutput, overwrite = true)
        output.delete()
      }
      videoFile!!.delete()
      audioFile!!.delete()
      finalOutput.absolutePath
    } finally {
      extractors.forEach { it.release() }
      muxer?.runCatching { stop() }
      muxer?.release()
      if (output.exists()) output.delete()
    }
  }

  private fun deleteJobFiles(job: Job) {
    val prefix = jobFilePrefix(job)
    File(job.directory).listFiles()?.forEach { file ->
      if (file.isFile && file.name.startsWith(prefix)) {
        runCatching { file.delete() }
      }
    }
  }

  private fun jobFilePrefix(job: Job): String =
    "${DownloadLocations.sanitizeName(job.title)}-${job.id}-"

  private fun currentJob(id: Int): Job? = _jobs.value.firstOrNull { it.id == id }

  private fun updateJob(
    id: Int,
    transform: (Job) -> Job,
  ) {
    _jobs.update { current -> current.map { if (it.id == id) transform(it) else it } }
  }

  companion object {
    private const val TAG = "YtdlpDownloadEngine"
    private const val PROGRESS_UPDATE_INTERVAL_MS = 750L

    // Example: "[download]  42.3% of ~ 123.45MiB at 2.34MiB/s ETA 01:23"
    private val PROGRESS_REGEX = Regex("""(?i)\[download]\s+([0-9.]+)%(.*)""")
    private val DESTINATION_REGEX = Regex("""(?i)\[download] Destination: (.+)""")
    private val ALREADY_DOWNLOADED_REGEX = Regex("""(?i)\[download] (.+) has already been downloaded""")

    fun parseProgressLine(line: String): Pair<Float, String>? {
      val match = PROGRESS_REGEX.find(line.replace("\r", "").trim()) ?: return null
      val percent = match.groupValues[1].toFloatOrNull() ?: return null
      return percent.coerceIn(0f, 100f) to match.groupValues[2].trim()
    }

    fun parseDestination(line: String): String? =
      DESTINATION_REGEX.find(line.trim())?.groupValues?.get(1)?.trim()
        ?: ALREADY_DOWNLOADED_REGEX.find(line.trim())?.groupValues?.get(1)?.trim()
  }
}
