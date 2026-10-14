/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.presentation.crash

import android.os.Process
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal const val DEBUG_LOG_ENTRY_LIMIT = 5_000
private const val RIFE_DIAGNOSTIC_ENTRY_RESERVE = 500
private const val MPVFLOW_DIAGNOSTIC_ENTRY_RESERVE = 500
private const val NATIVE_MEDIA3_FLOW_DIAGNOSTIC_ENTRY_RESERVE = 250
private const val FLOW_STARTUP_ENTRY_PIN_COUNT = 16
private const val FLOW_RENDERER_CONFIG_PIN_COUNT = 8
private const val FLOW_FAILURE_ENTRY_PIN_COUNT = 128

internal enum class DebugLogLevel(
  val code: String,
  val label: String,
) {
  Verbose("V", "Verbose"),
  Debug("D", "Debug"),
  Info("I", "Info"),
  Warn("W", "Warn"),
  Error("E", "Error"),
}

internal data class DebugLogEntry(
  val id: String,
  val timeMillis: Long,
  val timestamp: String,
  val level: DebugLogLevel,
  val tag: String,
  val message: String,
  val pid: Int? = null,
  val tid: Int? = null,
)

internal data class DebugLogSnapshot(
  val entries: List<DebugLogEntry>,
  val source: String,
  val rawLineCount: Int,
)

internal data class DebugLogcatCapture(
  val lines: List<String>,
  val exitCode: Int,
)

/**
 * Reads the current process' logcat in a device-tolerant way.
 *
 * By default, each read consumes the device's full current (finite, circular) Logcat buffer;
 * limiting it to a recent tail can evict Flow startup diagnostics before the viewer is opened.
 * Some vendor logcat binaries either do not support --pid or emit a slightly different
 * formatting even when a format is requested. The primary path uses Android's process filter;
 * if that produces no usable entries, a second pass reads a wider snapshot and filters the
 * parsed PID in-process. Unrecognised lines from the already PID-filtered primary command are
 * still surfaced instead of being silently discarded.
 */
internal object DebugLogReader {
  // Explicit test/debug limits only; normal reads omit `-t` and request the complete Logcat ring.
  private const val PRIMARY_RAW_LIMIT = 10_000
  private const val FALLBACK_RAW_LIMIT = 20_000

  private val threadTimePattern =
    Regex(
      """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?)\s*:\s?(.*)$""",
    )
  // Xiaomi/MIUI emits `HH:mm:ss.SSS pid/tid L/Tag: message` on some builds.
  private val slashThreadTimePattern =
    Regex(
      """^(\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)/(\d+)\s+([VDIWEF])/([^:]+):\s?(.*)$""",
    )
  private val classicTimePattern =
    Regex(
      """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEF])/([^\(]+)\(\s*(\d+)\):\s?(.*)$""",
    )
  private val epochPattern =
    Regex(
      """^(\d+(?:\.\d+)?)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?)\s*:\s?(.*)$""",
    )
  private val briefPattern =
    Regex("""^([VDIWEF])/([^\(]+)\(\s*(\d+)\):\s?(.*)$""")

  fun readSnapshot(maxRawLines: Int? = null): DebugLogSnapshot {
    val pid = Process.myPid()
    val failures = mutableListOf<String>()
    val primaryRawLimit = maxRawLines?.coerceIn(1, PRIMARY_RAW_LIMIT)
    val fallbackRawLimit = maxRawLines?.coerceIn(primaryRawLimit ?: 1, FALLBACK_RAW_LIMIT)

    val primaryCommand = buildList {
      addAll(listOf("logcat", "--pid=$pid", "-v", "threadtime", "-d"))
      primaryRawLimit?.let { addAll(listOf("-t", it.toString())) }
    }
    val primary = runCommand(primaryCommand)

    if (primary.exitCode == 0) {
      val parsed = parseLines(primary.lines, expectedPid = pid, allowRawFallback = true)
      if (parsed.isNotEmpty()) {
        return DebugLogSnapshot(
          entries = retainDebugLogEntries(parsed),
          source = "process-filtered",
          rawLineCount = primary.lines.size,
        )
      }
    } else {
      failures += primary.errorMessage("process-filtered logcat")
    }

    val fallbackCommand = buildList {
      addAll(listOf("logcat", "-v", "threadtime", "-d"))
      fallbackRawLimit?.let { addAll(listOf("-t", it.toString())) }
    }
    val fallback = runCommand(fallbackCommand)

    if (fallback.exitCode == 0) {
      val parsed = parseLines(fallback.lines, expectedPid = pid, allowRawFallback = false)
      if (parsed.isNotEmpty()) {
        return DebugLogSnapshot(
          entries = retainDebugLogEntries(parsed),
          source = "pid-fallback",
          rawLineCount = fallback.lines.size,
        )
      }

      // A successful command with no entries is not an error. Android can legitimately expose an
      // empty current-process buffer immediately after process start or after a buffer clear.
      return DebugLogSnapshot(
        entries = emptyList(),
        source = "empty",
        rawLineCount = fallback.lines.size,
      )
    }

    failures += fallback.errorMessage("fallback logcat")
    throw IOException(failures.filter { it.isNotBlank() }.joinToString("; ").ifBlank { "Unable to read logcat" })
  }

  /** Reads every Logcat buffer the OS permits this app process to access, without viewer filters. */
  fun readAllAccessibleLogcat(): DebugLogcatCapture {
    val result = runCommand(allAccessibleLogcatCommand())
    return DebugLogcatCapture(lines = result.lines, exitCode = result.exitCode)
  }

  internal fun allAccessibleLogcatCommand(): List<String> =
    listOf("logcat", "-b", "all", "-d", "-v", "threadtime")

  internal fun parseForTesting(
    lines: List<String>,
    expectedPid: Int? = null,
    allowRawFallback: Boolean = true,
  ): List<DebugLogEntry> = parseLines(lines, expectedPid, allowRawFallback)

  private fun parseLines(
    lines: List<String>,
    expectedPid: Int?,
    allowRawFallback: Boolean,
  ): List<DebugLogEntry> {
    if (lines.isEmpty()) return emptyList()

    val now = System.currentTimeMillis()
    val parsed = ArrayList<DebugLogEntry>(minOf(lines.size, DEBUG_LOG_ENTRY_LIMIT))
    lines.forEachIndexed { index, line ->
      if (line.isBlank() || line.startsWith("--------- beginning of")) return@forEachIndexed

      val entry = parseKnownLine(line, now, index)
      if (entry != null) {
        if (expectedPid == null || entry.pid == null || entry.pid == expectedPid) {
          parsed += entry
        }
      } else if (allowRawFallback) {
        // The primary command is already --pid filtered. Preserve vendor-specific lines rather
        // than presenting an empty screen just because a vendor changed logcat formatting.
        parsed +=
          DebugLogEntry(
            id = buildEntryId(now, expectedPid, null, DebugLogLevel.Info, "logcat", line, index),
            timeMillis = now,
            timestamp = formatDisplayTime(now),
            level = DebugLogLevel.Info,
            tag = "logcat",
            message = line,
            pid = expectedPid,
          )
      }
    }
    return parsed
  }

  private fun parseKnownLine(
    line: String,
    now: Long,
    occurrence: Int,
  ): DebugLogEntry? {
    slashThreadTimePattern.matchEntire(line)?.let { match ->
      val timeMillis = parseClockTimestamp(match.groupValues[1], now)
      val pid = match.groupValues[2].toIntOrNull()
      val tid = match.groupValues[3].toIntOrNull()
      val level = match.groupValues[4].toDebugLogLevel() ?: return null
      val tag = match.groupValues[5].trim()
      val message = match.groupValues[6]
      return buildEntry(timeMillis, pid, tid, level, tag, message, occurrence)
    }
    threadTimePattern.matchEntire(line)?.let { match ->
      val timeMillis = parseAndroidTimestamp(match.groupValues[1], now) ?: now
      val pid = match.groupValues[2].toIntOrNull()
      val tid = match.groupValues[3].toIntOrNull()
      val level = match.groupValues[4].toDebugLogLevel() ?: return null
      val tag = match.groupValues[5].trim()
      val message = match.groupValues[6]
      return buildEntry(timeMillis, pid, tid, level, tag, message, occurrence)
    }

    classicTimePattern.matchEntire(line)?.let { match ->
      val timeMillis = parseAndroidTimestamp(match.groupValues[1], now) ?: now
      val level = match.groupValues[2].toDebugLogLevel() ?: return null
      val tag = match.groupValues[3].trim()
      val pid = match.groupValues[4].toIntOrNull()
      val message = match.groupValues[5]
      return buildEntry(timeMillis, pid, null, level, tag, message, occurrence)
    }

    epochPattern.matchEntire(line)?.let { match ->
      val timeMillis = (match.groupValues[1].toDoubleOrNull()?.times(1_000.0))?.toLong() ?: now
      val pid = match.groupValues[2].toIntOrNull()
      val tid = match.groupValues[3].toIntOrNull()
      val level = match.groupValues[4].toDebugLogLevel() ?: return null
      val tag = match.groupValues[5].trim()
      val message = match.groupValues[6]
      return buildEntry(timeMillis, pid, tid, level, tag, message, occurrence)
    }

    briefPattern.matchEntire(line)?.let { match ->
      val level = match.groupValues[1].toDebugLogLevel() ?: return null
      val tag = match.groupValues[2].trim()
      val pid = match.groupValues[3].toIntOrNull()
      val message = match.groupValues[4]
      return buildEntry(now, pid, null, level, tag, message, occurrence)
    }

    return null
  }

  private fun buildEntry(
    timeMillis: Long,
    pid: Int?,
    tid: Int?,
    level: DebugLogLevel,
    tag: String,
    message: String,
    occurrence: Int,
  ): DebugLogEntry =
    DebugLogEntry(
      id = buildEntryId(timeMillis, pid, tid, level, tag, message, occurrence),
      timeMillis = timeMillis,
      timestamp = formatDisplayTime(timeMillis),
      level = level,
      tag = tag,
      message = message,
      pid = pid,
      tid = tid,
    )

  private fun runCommand(command: List<String>): CommandResult {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    return try {
      val lines =
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
          buildList {
            reader.forEachLine { line -> add(line) }
          }
        }
      CommandResult(process.waitFor(), lines)
    } finally {
      process.destroy()
    }
  }

  private data class CommandResult(
    val exitCode: Int,
    val lines: List<String>,
  ) {
    fun errorMessage(label: String): String {
      val detail = lines.takeLast(3).joinToString(" ").trim()
      return if (detail.isBlank()) "$label exited with code $exitCode" else "$label: $detail"
    }
  }
}

internal fun formatAllAccessibleLogcatExport(
  deviceInfo: String,
  capture: DebugLogcatCapture,
): String =
  buildString {
    appendLine(deviceInfo.trimEnd())
    appendLine()
    appendLine("Logcat: all accessible buffers (logcat -b all -d -v threadtime)")
    appendLine("Scope: current circular buffers; Android may restrict access to other processes")
    appendLine("Command exit code: ${capture.exitCode}")
    appendLine("Captured raw lines: ${capture.lines.size}")
    appendLine()
    if (capture.lines.isEmpty()) {
      appendLine("No log lines were returned; the platform may restrict Logcat access or the buffers may be empty.")
    } else {
      capture.lines.forEach { appendLine(it) }
    }
  }.trimEnd()

internal fun retainDebugLogEntries(entries: List<DebugLogEntry>): List<DebugLogEntry> {
  if (entries.size <= DEBUG_LOG_ENTRY_LIMIT) return entries

  val rifeDiagnostics =
    entries
      .filter(DebugLogEntry::isRifeDiagnostic)
      .takeLast(RIFE_DIAGNOSTIC_ENTRY_RESERVE)
  val mpvFlowDiagnostics =
    retainPinnedFlowDiagnostics(entries.filter(DebugLogEntry::isMpvFlowDiagnostic), MPVFLOW_DIAGNOSTIC_ENTRY_RESERVE)
  val nativeMedia3FlowDiagnostics =
    retainPinnedFlowDiagnostics(
      entries.filter(DebugLogEntry::isNativeMedia3FlowDiagnostic),
      NATIVE_MEDIA3_FLOW_DIAGNOSTIC_ENTRY_RESERVE,
    )
  val diagnostics = (rifeDiagnostics + mpvFlowDiagnostics + nativeMedia3FlowDiagnostics).distinctBy(DebugLogEntry::id)
  val recentCapacity = (DEBUG_LOG_ENTRY_LIMIT - diagnostics.size).coerceAtLeast(0)
  return (entries.takeLast(recentCapacity) + diagnostics)
    .distinctBy(DebugLogEntry::id)
    .sortedBy(DebugLogEntry::timeMillis)
}

internal fun DebugLogEntry.isRifeDiagnostic(): Boolean =
  tag.contains("rife", ignoreCase = true) ||
    message.contains("rife", ignoreCase = true) ||
    message.contains("vf_rife", ignoreCase = true)

internal fun DebugLogEntry.isMpvFlowDiagnostic(): Boolean =
  tag.contains("vf_mpvflow", ignoreCase = true) ||
    tag.contains("MpvInfinityFlowVk", ignoreCase = true) ||
    message.contains("MPVFLOW_DIAGNOSTIC", ignoreCase = true) ||
    message.contains("MPVFLOW_GPU_DIAGNOSTIC", ignoreCase = true) ||
    message.contains("vf_mpvflow", ignoreCase = true)

internal fun DebugLogEntry.isNativeMedia3FlowDiagnostic(): Boolean =
  tag.contains("MpvInfinityFlowVk", ignoreCase = true) ||
    tag.contains("Mpv∞-Media3Flow", ignoreCase = true) ||
    message.contains("component=native-media3", ignoreCase = true) ||
    message.contains("media3_flow_route", ignoreCase = true) ||
    message.contains("flow_summary", ignoreCase = true)

private fun retainPinnedFlowDiagnostics(
  entries: List<DebugLogEntry>,
  reserve: Int,
): List<DebugLogEntry> {
  if (entries.size <= reserve) return entries
  val startup = entries.filter(DebugLogEntry::isFlowStartupRecord).take(FLOW_STARTUP_ENTRY_PIN_COUNT)
  val latestRendererConfigs =
    entries
      .filter { it.message.contains("event=renderer_config", ignoreCase = true) }
      .takeLast(FLOW_RENDERER_CONFIG_PIN_COUNT)
  val failures = entries.filter(DebugLogEntry::isFlowFailureRecord).takeLast(FLOW_FAILURE_ENTRY_PIN_COUNT)
  val latestPresentMode = entries.filter { it.message.contains("event=present_mode", ignoreCase = true) }.takeLast(1)
  val latestWsiToggle = entries.filter { it.message.contains("event=wsi_diagnostics_toggle", ignoreCase = true) }.takeLast(1)
  val wsiSwapchains = entries.filter { it.message.contains("event=wsi_swapchain_created", ignoreCase = true) }
  val wsiSwapchainEvidence = (wsiSwapchains.take(1) + wsiSwapchains.takeLast(1)).distinctBy(DebugLogEntry::id)
  val wsiTimingEntries =
    entries
      .filter { it.message.contains("event=wsi_timing", ignoreCase = true) }
      .mapNotNull { entry -> entry.flowWsiTimingStage()?.let { it to entry } }
      .groupBy({ it.first }, { it.second })
      .values
      .mapNotNull { it.lastOrNull() }
  val latestQcomCapabilities = entries.filter { it.message.contains("event=qcom_gpu_timing", ignoreCase = true) }.takeLast(1)
  val latestQcomDispatchTiming = entries.filter { it.message.contains("event=qcom_dispatch_timing", ignoreCase = true) }.takeLast(1)
  val latestQcomBlockMatchCapability =
    entries.filter { it.message.contains("event=qcom_block_match_capability", ignoreCase = true) }.takeLast(1)
  val latestQcomBlockMatchInit =
    entries.filter { it.message.contains("event=qcom_block_match_init", ignoreCase = true) }.takeLast(1)
  val qcomBlockMatchImages =
    entries
      .mapNotNull { entry -> entry.flowQcomCreatedImageName()?.let { it to entry } }
      .groupBy({ it.first }, { it.second })
      .values
      .mapNotNull { it.lastOrNull() }
  val latestQcomBlockMatchImageState =
    entries
      .filter {
        it.message.contains("event=qcom_block_match_image", ignoreCase = true) &&
          !it.message.contains("state=created", ignoreCase = true)
      }
      .takeLast(1)
  val latestQcomBlockMatchDispatchState =
    entries.filter { it.message.contains("event=qcom_block_match_dispatch", ignoreCase = true) }.takeLast(1)
  val pinned =
    (startup + latestRendererConfigs + failures + latestPresentMode + latestWsiToggle +
      wsiSwapchainEvidence + wsiTimingEntries + latestQcomCapabilities + latestQcomDispatchTiming +
      latestQcomBlockMatchCapability + latestQcomBlockMatchInit + qcomBlockMatchImages +
      latestQcomBlockMatchImageState + latestQcomBlockMatchDispatchState)
      .distinctBy(DebugLogEntry::id)
  val tailCapacity = (reserve - pinned.size).coerceAtLeast(0)
  return (pinned + entries.takeLast(tailCapacity))
    .distinctBy(DebugLogEntry::id)
    .sortedBy(DebugLogEntry::timeMillis)
}

private fun DebugLogEntry.flowWsiTimingStage(): String? {
  if (!message.contains("event=wsi_timing", ignoreCase = true)) return null
  return Regex("(?:^|\\s)stage=([^\\s]+)").find(message)?.groupValues?.get(1)
}

private fun DebugLogEntry.flowQcomCreatedImageName(): String? {
  if (!message.contains("event=qcom_block_match_image", ignoreCase = true)) return null
  if (!message.contains("state=created", ignoreCase = true)) return null
  return Regex("(?:^|\\s)name=([^\\s]+)").find(message)?.groupValues?.get(1)
}

private fun DebugLogEntry.isFlowStartupRecord(): Boolean =
  message.contains("event=config", ignoreCase = true) ||
    message.contains("event=logger_config", ignoreCase = true) ||
    message.contains("event=renderer_config", ignoreCase = true) ||
    message.contains("event=renderer_init", ignoreCase = true) ||
    message.contains("event=compute_capabilities", ignoreCase = true) ||
    message.contains("event=vulkan_context", ignoreCase = true) ||
    message.contains("event=queue_state", ignoreCase = true) ||
    message.contains("event=preflight", ignoreCase = true) ||
    message.contains("media3_flow_route", ignoreCase = true)

private fun DebugLogEntry.isFlowFailureRecord(): Boolean =
  message.contains("state=failed", ignoreCase = true) ||
    message.contains("state=unavailable", ignoreCase = true) ||
    message.contains("state=passthrough", ignoreCase = true) ||
    message.contains("state=source_fallback", ignoreCase = true) ||
    message.contains("event=auto_fallback", ignoreCase = true)

/** Curated export section; the unfiltered Logcat text is separately kept intact. */
internal fun selectMpvFlowDiagnosticLines(logcat: String): String =
  logcat.lineSequence()
    .filter { line ->
      listOf(
        "MPVFLOW_DIAGNOSTIC",
        "MPVFLOW_GPU_DIAGNOSTIC",
        "vf_mpvflow",
        "MpvInfinityFlowVk",
      ).any { marker -> line.contains(marker, ignoreCase = true) }
    }
    .joinToString("\n")

internal data class MpvFlowRuntimeCadenceSample(
  val synthesizedSubmitFps: Double,
  val targetFps: Int?,
  val swapSubmitFps: Double?,
  val synthesizedSubmits: Long?,
  val windowMillis: Double?,
  val timeMillis: Long,
)

internal fun latestMpvFlowRuntimeCadenceSample(
  entries: List<DebugLogEntry>,
): MpvFlowRuntimeCadenceSample? {
  val entry =
    entries.lastOrNull { candidate ->
      candidate.isMpvFlowDiagnostic() && candidate.message.contains("event=runtime_cadence")
    } ?: return null
  val fields = Regex("""([A-Za-z_][A-Za-z0-9_]*)=([^\s]+)""")
    .findAll(entry.message)
    .associate { it.groupValues[1] to it.groupValues[2] }
  if (fields["metric"] != "successful_pl_swapchain_submit_not_surfaceflinger_scanout") return null

  fun number(name: String): Double? = fields[name]?.toDoubleOrNull()?.takeIf { it.isFinite() }

  val windowMillis = number("window_ms")?.takeIf { it > 0.0 }
  val synthesizedSubmits = fields["synthesized_submits"]?.toLongOrNull()?.takeIf { it >= 0L }
  val synthesizedSubmitFps =
    number("synthesized_submit_fps")?.takeIf { it >= 0.0 }
      ?: if (synthesizedSubmits != null && windowMillis != null) {
        synthesizedSubmits * 1_000.0 / windowMillis
      } else {
        null
      }
      ?: return null

  return MpvFlowRuntimeCadenceSample(
    synthesizedSubmitFps = synthesizedSubmitFps,
    targetFps = fields["target_fps"]?.toIntOrNull()?.takeIf { it in 1..240 },
    swapSubmitFps = number("swap_submit_fps")?.takeIf { it >= 0.0 },
    synthesizedSubmits = synthesizedSubmits,
    windowMillis = windowMillis,
    timeMillis = entry.timeMillis,
  )
}

internal fun isMpvFlowRuntimeCadenceSampleFresh(
  sample: MpvFlowRuntimeCadenceSample,
  nowMillis: Long,
  maxAgeMillis: Long = 12_000L,
): Boolean {
  val ageMillis = nowMillis - sample.timeMillis
  return ageMillis in 0..maxAgeMillis
}

internal class MpvFlowRuntimeCadenceSampleCache {
  private var lastFreshSample: MpvFlowRuntimeCadenceSample? = null

  fun update(
    candidate: MpvFlowRuntimeCadenceSample?,
    nowMillis: Long,
  ): MpvFlowRuntimeCadenceSample? {
    if (candidate != null && isMpvFlowRuntimeCadenceSampleFresh(candidate, nowMillis)) {
      lastFreshSample = candidate
    }
    val retained = lastFreshSample
    if (retained != null && isMpvFlowRuntimeCadenceSampleFresh(retained, nowMillis)) {
      return retained
    }
    lastFreshSample = null
    return null
  }
}

internal fun formatMpvFlowTimingSummary(entries: List<DebugLogEntry>): String {
  val fieldPattern = Regex("""([A-Za-z_][A-Za-z0-9_]*)=([^\s]+)""")
  fun fieldsFor(event: String): Map<String, String>? =
    entries.lastOrNull { entry ->
      entry.isMpvFlowDiagnostic() && entry.message.contains("event=$event")
    }?.let { entry ->
      fieldPattern.findAll(entry.message)
        .associate { it.groupValues[1] to it.groupValues[2] }
    }
  fun number(fields: Map<String, String>, name: String): Double? =
    fields[name]?.toDoubleOrNull()?.takeIf { it.isFinite() }
  fun compact(value: Double?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "--"

  val synthesizedOutput = latestMpvFlowRuntimeCadenceSample(entries)?.let { sample ->
    buildString {
      append("Successful synthesized submits ${compact(sample.synthesizedSubmitFps)} fps")
      sample.targetFps?.let { append(" · target $it fps") }
      if (sample.synthesizedSubmits != null && sample.windowMillis != null) {
        append(" · ${sample.synthesizedSubmits} in ${compact(sample.windowMillis / 1_000.0)} s")
      }
      sample.swapSubmitFps?.let { append(" · all swap submits ${compact(it)} fps") }
      append(" · not scanout")
    }
  }

  val pairFields = fieldsFor("source_pair_synthesis")
  val pairSummary = pairFields?.let { fields ->
    val processingMs = number(fields, "processing_ms")
    val budgetMs = number(fields, "pair_budget_ms")
    val budgetRatio = number(fields, "budget_ratio")
      ?: if (processingMs != null && budgetMs != null && budgetMs > 0.0) processingMs / budgetMs else null
    val percent = budgetRatio?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "--"
    val dimensions =
      if (fields["process_width"] != null && fields["process_height"] != null) {
        "${fields["process_width"]}×${fields["process_height"]}"
      } else {
        "--"
      }
    val frames = fields["frames"] ?: "?"
    val targetFps = fields["target_fps"] ?: "?"
    "Last pair: $frames generated · ${compact(processingMs)}/${compact(budgetMs)} ms ($percent) · $dimensions · target $targetFps fps"
  }

  return when {
    synthesizedOutput != null && pairSummary != null -> "$synthesizedOutput · $pairSummary"
    synthesizedOutput != null -> synthesizedOutput
    pairSummary != null -> "Synthesized submit rate not captured · $pairSummary"
    entries.any(DebugLogEntry::isMpvFlowDiagnostic) -> "MPVFlow active; synthesized submit rate not captured yet"
    else -> "No MPVFlow output telemetry captured"
  }
}

private fun String.toDebugLogLevel(): DebugLogLevel? =
  when (this) {
    "V" -> DebugLogLevel.Verbose
    "D" -> DebugLogLevel.Debug
    "I" -> DebugLogLevel.Info
    "W" -> DebugLogLevel.Warn
    "E", "F" -> DebugLogLevel.Error
    else -> null
  }

private fun buildEntryId(
  timeMillis: Long,
  pid: Int?,
  tid: Int?,
  level: DebugLogLevel,
  tag: String,
  message: String,
  occurrence: Int,
): String =
  buildString {
    append(timeMillis)
    append(':')
    append(pid ?: -1)
    append(':')
    append(tid ?: -1)
    append(':')
    append(level.code)
    append(':')
    append(tag.hashCode())
    append(':')
    append(message.hashCode())
    append(':')
    append(occurrence)
  }

private fun parseClockTimestamp(
  timestamp: String,
  now: Long,
): Long {
  val parsed = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).parse(timestamp) ?: return now
  val today = Calendar.getInstance().apply { timeInMillis = now }
  return Calendar.getInstance().apply {
    time = parsed
    set(Calendar.YEAR, today.get(Calendar.YEAR))
    set(Calendar.MONTH, today.get(Calendar.MONTH))
    set(Calendar.DAY_OF_MONTH, today.get(Calendar.DAY_OF_MONTH))
  }.timeInMillis
}

private fun parseAndroidTimestamp(
  timestamp: String,
  now: Long,
): Long? =
  runCatching {
    val formatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    val parsed = formatter.parse(timestamp) ?: return@runCatching null
    val currentCalendar = Calendar.getInstance().apply { timeInMillis = now }
    val parsedCalendar =
      Calendar.getInstance().apply {
        time = parsed
        set(Calendar.YEAR, currentCalendar.get(Calendar.YEAR))
      }

    // A log buffer can cross New Year. If assigning the current year makes the log appear more
    // than a day in the future, it belongs to the previous year.
    if (parsedCalendar.timeInMillis > now + 24L * 60L * 60L * 1_000L) {
      parsedCalendar.add(Calendar.YEAR, -1)
    }
    parsedCalendar.timeInMillis
  }.getOrNull()

private fun formatDisplayTime(timeMillis: Long): String =
  SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timeMillis))
