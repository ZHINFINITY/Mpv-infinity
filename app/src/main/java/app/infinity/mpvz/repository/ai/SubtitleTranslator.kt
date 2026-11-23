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
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Whole-track subtitle translation: the embedded track is extracted to SRT,
 * cues are deduped, unique lines are translated with bounded concurrency,
 * every result is cached, and a complete translated SRT is rebuilt.
 */
class SubtitleTranslator(
  private val appContext: Context,
  private val translator: EmbeddedSubtitleTranslator,
) {
  /** Cross-call cache: repeated lines and re-opens translate instantly. Keyed by "<target> <text>". */
  private val cache = HashMap<String, String>()

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
        val extracted = File(workDir, "track_${trackFfIndex}_$targetLanguage.srt")
        extracted.delete()
        val process = ProcessBuilder(
          listOf(
            ffmpeg.absolutePath,
            "-y",
            "-i",
            videoPath,
            "-map",
            "0:$trackFfIndex",
            "-c:s",
            "srt",
            extracted.absolutePath,
          ),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        if (process.waitFor() != 0 || !extracted.isFile || extracted.length() == 0L) {
          throw SubtitleTranslationException(output.lineSequence().lastOrNull()?.take(120) ?: "ffmpeg failed")
        }
        val content = extracted.readText()
        if (content.isBlank()) {
          throw SubtitleTranslationException("Selected track is not text-based (e.g. image subtitles)")
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

  /** The algorithm: parse SRT cues, dedupe text lines, translate each unique text once. */
  suspend fun translateSrt(
    content: String,
    targetLanguage: String,
    onProgress: (Float) -> Unit,
  ): String {
    val lines = content.lines()
    data class Task(val lineIdx: Int, val key: String, val original: String)

    val tasks = mutableListOf<Task>()
    var i = 0
    while (i < lines.size) {
      val trimmed = lines[i].trim()
      if (trimmed.isNotEmpty() && trimmed.all { it.isDigit() } &&
        i + 1 < lines.size && lines[i + 1].contains("-->")
      ) {
        var j = i + 2
        while (j < lines.size && lines[j].trim().isNotEmpty()) {
          val line = lines[j]
          if (line.isNotBlank()) {
            val clean = line.replace(TAG_REGEX, "").trim()
            tasks.add(Task(j, "$targetLanguage:${clean.lowercase()}", line))
          }
          j++
        }
        i = j
      } else {
        i++
      }
    }
    if (tasks.isEmpty()) throw SubtitleTranslationException("No SRT cues found to translate")

    val unique = LinkedHashMap<String, Task>()
    tasks.forEach { task -> unique.putIfAbsent(task.key, task) }
    val results = HashMap<Int, String>(tasks.size)
    var completed = 0
    val total = unique.size
    // Bounded concurrency: firing every line at once trips the free endpoint's
    // rate limit and lines come back untranslated. 4 at a time with retries.
    val gate = kotlinx.coroutines.sync.Semaphore(4)

    coroutineScope {
      unique.values
        .map { task ->
          async {
            gate.acquire()
            try {
              val cached = synchronized(cache) { cache[task.key] }
              if (cached != null) {
                synchronized(results) { results[task.lineIdx] = cached }
              } else {
                var translated: String? = null
                for (attempt in 0 until 4) {
                  val result = translator.translate(task.original, targetLanguage)
                  if (result.isSuccess) {
                    translated = result.getOrThrow()
                    break
                  }
                  if (attempt < 3) delay(500L * (1L shl attempt))
                }
                translated?.let { value ->
                  synchronized(cache) { cache[task.key] = value }
                  synchronized(results) { results[task.lineIdx] = value }
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

    return lines.mapIndexed { idx, original ->
      val replacement = results[idx] ?: return@mapIndexed original
      // Preserve a leading inline tag pair like <i>…</i> around the translated text.
      val openStart = original.indexOf('<')
      val openEnd = original.indexOf('>')
      if (openStart == 0 && openEnd > 1) {
        val open = original.substring(0, openEnd + 1)
        val closeIdx = original.lastIndexOf("</")
        val close = if (closeIdx > openEnd) original.substring(closeIdx) else ""
        open + replacement + close
      } else {
        replacement
      }
    }.joinToString("\n")
  }

  private companion object {
    val TAG_REGEX = Regex("<[^>]+>")
  }
}
