/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.repository.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Whole-track subtitle translation. The embedded track is extracted as ASS first so the
 * translated file keeps the original styles (size, colours, positioning); ASS/HTML tags are
 * stripped before translation so the service only ever sees plain text. Cues are deduped,
 * unique cue texts are translated with bounded concurrency and a per-request timeout, every
 * result is cached, and a failed cue keeps its original text instead of failing the track.
 */
class SubtitleTranslator(
  private val appContext: Context,
  private val translator: EmbeddedSubtitleTranslator,
) {
  /** Cross-call cache: repeated lines and re-opens translate instantly. Keyed by "<target> <text>". */
  private val cache = HashMap<String, String>()

  private data class CueTask(val lineIdxs: List<Int>, val key: String, val text: String)

  suspend fun translateTrack(
    videoPath: String,
    trackFfIndex: Int,
    targetLanguage: String,
    onStage: (String) -> Unit,
  ): Result<File> =
    try {
      withContext(Dispatchers.IO) {
        onStage("Extracting subtitles")
        val ffmpeg = File(appContext.applicationInfo.nativeLibraryDir, "libffmpeg_exec.so")
        if (!ffmpeg.isFile || !ffmpeg.canExecute()) {
          throw SubtitleTranslationException("Built-in ffmpeg is unavailable")
        }
        val workDir = File(appContext.cacheDir, "subtitle_translation").apply { mkdirs() }

        // Preferred path: ASS output preserves the source styles, so the translated
        // subtitles render at the original size with the original positioning.
        val assFile = File(workDir, "track_${trackFfIndex}_$targetLanguage.ass")
        assFile.delete()
        val assOutput = extract(ffmpeg, videoPath, trackFfIndex, "ass", assFile)
        val assContent = if (assFile.isFile) assFile.readText() else ""
        if (assContent.contains("Dialogue:")) {
          val translated =
            translateAss(assContent, targetLanguage) { done ->
              onStage("Translating ${(done * 100).toInt()}%")
            }
          val outFile = File(workDir, "track_${trackFfIndex}_$targetLanguage.translated.ass")
          outFile.writeText(translated)
          return@withContext Result.success(outFile)
        }

        // Fallback: text-based tracks that do not convert to ASS (or an extraction
        // failure) go through the SRT path with the same cue-level algorithm.
        val srtFile = File(workDir, "track_${trackFfIndex}_$targetLanguage.srt")
        srtFile.delete()
        val srtOutput = extract(ffmpeg, videoPath, trackFfIndex, "srt", srtFile)
        val content = if (srtFile.isFile) srtFile.readText() else ""
        // Last resort for image-based tracks (PGS/DVB): copy the raw bitstream into a Matroska
        // container (the bundled ffmpeg has no .sup muxer), then OCR the bitmaps and translate
        // the recognized text through the same cue-level algorithm.
        if (content.isBlank()) {
          val supFile = File(workDir, "track_$trackFfIndex.mkv")
          supFile.delete()
          extract(ffmpeg, videoPath, trackFfIndex, "copy", supFile)
          val supCues: List<String> =
            if (supFile.isFile && supFile.length() > 0) {
              withContext(Dispatchers.Default) {
                PgsSupDecoder.decodeToCues(supFile).map { cue ->
                  "${srtTimestamp(cue.startMs)} --> ${srtTimestamp(cue.endMs)}\n${cue.text}\n"
                }
              }
            } else emptyList()
          if (supCues.isNotEmpty()) {
            onStage("Translating")
            val translated = translateSrt(supCues.joinToString("\n"), targetLanguage) { done ->
              onStage("Translating ${(done * 100).toInt()}%")
            }
            val supOut = File(workDir, "track_${trackFfIndex}_$targetLanguage.translated.srt")
            supOut.writeText(translated)
            return@withContext Result.success(supOut)
          }
          throw SubtitleTranslationException(assOutput.ifBlank { srtOutput }.ifBlank {
            "Selected track is not text-based (e.g. image subtitles)"
          })
        }

        onStage("Translating")
        val translated = translateSrt(content, targetLanguage) { done ->
          onStage("Translating ${(done * 100).toInt()}%")
        }
        val outFile = File(workDir, "track_${trackFfIndex}_$targetLanguage.translated.srt")
        outFile.writeText(translated)
        Result.success(outFile)
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(
        if (e is SubtitleTranslationException) e else SubtitleTranslationException(e.message ?: "Translation failed"),
      )
    }

  private fun srtTimestamp(ms: Long): String {
    val h = ms / 3_600_000
    val m = ms % 3_600_000 / 60_000
    val s = ms % 60_000 / 1000
    val millis = ms % 1000
    return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
  }

  /** Returns an empty string on success, otherwise a short user-facing failure reason. */
  private fun extract(
    ffmpeg: File,
    videoPath: String,
    trackFfIndex: Int,
    format: String,
    output: File,
  ): String {
    val process =
      ProcessBuilder(
        listOf(
          ffmpeg.absolutePath,
          "-y",
          "-i",
          videoPath,
          "-map",
          "0:$trackFfIndex",
          "-c:s",
          format,
          output.absolutePath,
        ),
      ).redirectErrorStream(true).start()
    val outputText = process.inputStream.readBytes().decodeToString()
    if (process.waitFor() != 0 || !output.isFile || output.length() == 0L) {
      return friendlyFailure(outputText)
    }
    return ""
  }

  /** The last meaningful ffmpeg error line, or a plain hint for image-based tracks. */
  private fun friendlyFailure(raw: String): String =
    raw.lineSequence()
      .filter { it.isNotBlank() }
      .lastOrNull()
      ?.take(120)
      ?.ifBlank { null }
      ?: "Selected track is not text-based (e.g. image subtitles)"

  /**
   * Translates an ASS subtitle: Dialogue text is cleaned of override blocks, translated as a
   * whole cue and written back into the untouched Dialogue line, so styles and timing survive.
   */
  suspend fun translateAss(
    content: String,
    targetLanguage: String,
    onProgress: (Float) -> Unit,
  ): String {
    val lines = content.lines()
    val tasks = mutableListOf<CueTask>()

    lines.forEachIndexed { idx, line ->
      if (!line.startsWith("Dialogue:")) return@forEachIndexed
      val plain = dialogueText(line)
      if (plain.isEmpty()) return@forEachIndexed
      tasks.add(CueTask(listOf(idx), "$targetLanguage:${plain.lowercase()}", plain))
    }
    if (tasks.isEmpty()) throw SubtitleTranslationException("No text cues found to translate")

    val unique = LinkedHashMap<String, CueTask>()
    tasks.forEach { task -> unique.putIfAbsent(task.key, task) }
    val results = HashMap<Int, String>(tasks.size)
    translateUnique(unique, results, targetLanguage, onProgress)

    return lines.mapIndexed { idx, original ->
      val replacement = results[idx] ?: return@mapIndexed original
      val textStart = nthCommaIndex(original, 9)
      if (textStart < 0) original else original.substring(0, textStart + 1) + replacement.replace("\n", "\\N")
    }.joinToString("\n")
  }

  /** The SRT fallback: parse cues, dedupe cue texts (all cue lines in one request), rebuild. */
  suspend fun translateSrt(
    content: String,
    targetLanguage: String,
    onProgress: (Float) -> Unit,
  ): String {
    val lines = content.lines()
    val tasks = mutableListOf<CueTask>()

    var i = 0
    while (i < lines.size) {
      val trimmed = lines[i].trim()
      if (trimmed.isNotEmpty() && trimmed.all { it.isDigit() } &&
        i + 1 < lines.size && lines[i + 1].contains("-->")
      ) {
        var j = i + 2
        val body = mutableListOf<String>()
        while (j < lines.size && lines[j].trim().isNotEmpty()) {
          if (lines[j].isNotBlank()) body.add(lines[j])
          j++
        }
        // One request per cue (all its lines together): half the requests of a per-line
        // approach and better context for the translator.
        val clean = body.joinToString("\n").replace(TAG_REGEX, "").replace(ASS_BLOCK_REGEX, "").trim()
        if (clean.isNotEmpty()) {
          val idxs = (j - body.size until j).filter { lines[it].isNotBlank() }
          tasks.add(CueTask(idxs, "$targetLanguage:${clean.lowercase()}", clean))
        }
        i = j
      } else {
        i++
      }
    }
    if (tasks.isEmpty()) throw SubtitleTranslationException("No SRT cues found to translate")

    val unique = LinkedHashMap<String, CueTask>()
    tasks.forEach { task -> unique.putIfAbsent(task.key, task) }
    val results = HashMap<Int, String>(tasks.size)
    translateUnique(unique, results, targetLanguage, onProgress)

    val out = lines.toMutableList()
    tasks.forEach { task ->
      val replacement = results[task.lineIdxs.first()] ?: return@forEach
      // Index/timestamp lines stay untouched; the first cue line carries the translation.
      out[task.lineIdxs.first()] = replacement
      task.lineIdxs.drop(1).forEach { out[it] = "" }
    }
    return out.joinToString("\n")
  }

  /** Bounded concurrent translation of the unique cue texts; a failed cue keeps its original. */
  private suspend fun translateUnique(
    unique: LinkedHashMap<String, CueTask>,
    results: HashMap<Int, String>,
    targetLanguage: String,
    onProgress: (Float) -> Unit,
  ) {
    var completed = 0
    val total = unique.size
    // Match the reference implementation: 6 workers, a hard per-request timeout and a single
    // quick retry. Aggressive retry loops trip the free endpoint's rate limit and stall runs.
    val gate = Semaphore(CONCURRENCY)

    coroutineScope {
      unique.values
        .map { task ->
          async {
            gate.acquire()
            try {
              val cached = synchronized(cache) { cache[task.key] }
              if (cached != null) {
                synchronized(results) { results[task.lineIdxs.first()] = cached }
              } else {
                var translated: String? = null
                for (attempt in 0 until 2) {
                  try {
                    translated =
                      withTimeout(REQUEST_TIMEOUT_MS) {
                        translator.translate(task.text, targetLanguage).getOrThrow()
                      }
                    break
                  } catch (failure: Throwable) {
                    // A per-request timeout must not cancel the whole run; a real job
                    // cancellation still rethrows via ensureActive().
                    coroutineContext.ensureActive()
                    if (attempt < 1) delay(400)
                  }
                }
                // Best-effort per cue: a line that will not translate keeps its original
                // text, so the track is never lost and no cue is left empty.
                translated?.let { value ->
                  synchronized(cache) { cache[task.key] = value }
                  synchronized(results) { results[task.lineIdxs.first()] = value }
                }
              }
              val done = synchronized(unique) { ++completed }
              onProgress(done.toFloat() / total)
            } finally {
              gate.release()
            }
          }
        }.awaitAll()
    }
  }

  /** Strips ASS override blocks and HTML tags from a Dialogue line, returning the plain text. */
  private fun dialogueText(line: String): String {
    val rest = line.substringAfter("Dialogue:", "")
    val textStart = nthCommaIndex(rest, 9)
    if (textStart < 0) return ""
    return rest.substring(textStart + 1)
      .replace(ASS_BLOCK_REGEX, "")
      .replace(TAG_REGEX, "")
      .replace("\\h", " ")
      .replace(Regex("\\\\N", RegexOption.IGNORE_CASE), "\n")
      .trim()
  }

  /** Index of the [n]th comma (1-based) in [value], or -1. */
  private fun nthCommaIndex(value: String, n: Int): Int {
    var seen = 0
    for (i in value.indices) {
      if (value[i] == ',') {
        seen++
        if (seen == n) return i
      }
    }
    return -1
  }

  private companion object {
    val TAG_REGEX = Regex("<[^>]+>")
    val ASS_BLOCK_REGEX = Regex("\\{[^}]*\\}")
    const val CONCURRENCY = 6
    const val REQUEST_TIMEOUT_MS = 12_000L
  }
}
