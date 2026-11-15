/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.infinity.mpvz.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps debug-only Jelly probe reports reachable after PlayerActivity has been destroyed. */
internal object JellyProbeReportStore {
  private const val REPORT_DIRECTORY = "jelly-probe-reports"
  private const val REPORT_PREFIX = "jelly-probe-"
  private const val FINALIZATION_POLL_COUNT = 20
  private const val FINALIZATION_POLL_MILLIS = 250L

  private val finalizationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val finalizationJobs = ConcurrentHashMap<String, Job>()
  private val mutableLatestReport = MutableStateFlow<File?>(null)
  val latestReport: StateFlow<File?> = mutableLatestReport.asStateFlow()

  fun newReportFile(context: Context): File {
    val directory = ensureReportDirectory(context)
    val captureId = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
    return File(directory, "$REPORT_PREFIX$captureId.json")
  }

  /** Refreshes the latest complete report from durable app-private storage. */
  fun refresh(context: Context): File? {
    val directory = runCatching { ensureReportDirectory(context) }.getOrNull()
      ?: return mutableLatestReport.value?.takeIf(::isComplete)
    migrateLegacyCacheReport(context, directory)
    val latest = latestCompleteReport(directory)
    mutableLatestReport.value = latest
    return latest
  }

  /** Marks a just-written native report as the latest shareable report. */
  fun publish(report: File) {
    completedReportForCapture(report)?.let { mutableLatestReport.value = it }
  }

  /** Returns this capture's native report, or its fallback status report once saved. */
  fun completedReportForCapture(report: File): File? {
    if (isComplete(report)) return report
    val fallback = fallbackFile(report)
    return fallback.takeIf(::isComplete)
  }

  /**
   * Waits independently of the player Activity for the native writer to finish. If it emitted no
   * sample, save an explicit status JSON so stop/EOF/exit still leaves something useful to share.
   */
  fun awaitFinalized(context: Context, report: File, nativeFinalizeResult: Int) {
    val reportKey = report.absolutePath
    if (finalizationJobs[reportKey]?.isActive == true) return
    val appContext = context.applicationContext
    finalizationJobs[reportKey] = finalizationScope.launch {
      try {
        repeat(FINALIZATION_POLL_COUNT) {
          completedReportForCapture(report)?.let {
            mutableLatestReport.value = it
            return@launch
          }
          delay(FINALIZATION_POLL_MILLIS)
        }

        val existing = completedReportForCapture(report)
        if (existing != null) {
          mutableLatestReport.value = existing
          return@launch
        }

        val fallback = writeNoSampleReport(report, nativeFinalizeResult)
        fallback?.let { mutableLatestReport.value = it }

        // A queued native write may finish just after the no-sample status was saved. Prefer it
        // if it arrives shortly afterward; the fallback remains available if the writer had none.
        repeat(FINALIZATION_POLL_COUNT) {
          if (isComplete(report)) {
            mutableLatestReport.value = report
            return@launch
          }
          delay(FINALIZATION_POLL_MILLIS)
        }
      } finally {
        finalizationJobs.remove(reportKey)
        // A process restart or an unusually late write should still be discoverable from disk.
        if (mutableLatestReport.value == null) refresh(appContext)
      }
    }
  }

  /** Opens the standard Android share chooser for the latest report or a preferred capture. */
  fun share(context: Context, preferredCapture: File? = null): Boolean {
    if (!BuildConfig.DEBUG || !BuildConfig.MPV_HAS_JELLY_PROBE) return false
    val report = preferredCapture?.let(::completedReportForCapture) ?: latestReport(context)
    val canonicalReport = report?.canonicalFile ?: return false
    if (!isComplete(canonicalReport) || canonicalReport.parentFile != reportDirectory(context).canonicalFile) {
      return false
    }

    return runCatching {
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", canonicalReport)
      val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      val chooser = Intent.createChooser(sendIntent, "Share Jelly probe report")
      if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(chooser)
      true
    }.getOrDefault(false)
  }

  private fun latestReport(context: Context): File? {
    return mutableLatestReport.value?.takeIf(::isComplete) ?: refresh(context)
  }

  private fun reportDirectory(context: Context): File = File(context.filesDir, REPORT_DIRECTORY)

  private fun ensureReportDirectory(context: Context): File {
    val directory = reportDirectory(context)
    if (!directory.exists() && !directory.mkdirs()) {
      throw IOException("Could not create Jelly probe report directory")
    }
    if (!directory.isDirectory) throw IOException("Jelly probe report path is not a directory")
    return directory
  }

  /** Keeps the report from the prior diagnostic build reachable after updating the app. */
  private fun migrateLegacyCacheReport(context: Context, directory: File) {
    val legacy = File(context.cacheDir, "jelly-probe-report.json")
    if (!isComplete(legacy)) return
    val migrated = File(directory, "${REPORT_PREFIX}legacy-${legacy.lastModified()}-${legacy.length()}.json")
    if (isComplete(migrated)) return
    val temporary = File(directory, "${migrated.name}.tmp")
    runCatching {
      legacy.copyTo(temporary, overwrite = true)
      if (!temporary.renameTo(migrated) && !isComplete(migrated)) {
        temporary.copyTo(migrated, overwrite = true)
      }
      temporary.delete()
      if (isComplete(migrated)) migrated.setLastModified(legacy.lastModified())
    }.onFailure {
      temporary.delete()
    }
  }

  private fun latestCompleteReport(directory: File): File? {
    val reports = directory.listFiles()?.filter(::isProbeReportFile).orEmpty()
    val nativeReports = reports.filter { !it.name.endsWith(".no-sample.json") && isComplete(it) }
    val fallbackReports = reports.filter { it.name.endsWith(".no-sample.json") && isComplete(it) }
      .filter { fallback ->
        val nativeName = fallback.name.removeSuffix(".no-sample.json") + ".json"
        nativeReports.none { it.name == nativeName }
      }
    return (nativeReports + fallbackReports).maxByOrNull(File::lastModified)
  }

  private fun isProbeReportFile(file: File): Boolean =
    file.name.startsWith(REPORT_PREFIX) &&
      (file.name.endsWith(".json") || file.name.endsWith(".no-sample.json"))

  private fun isComplete(file: File): Boolean = file.isFile && file.length() > 0L

  private fun fallbackFile(report: File): File =
    File(report.parentFile, "${report.nameWithoutExtension}.no-sample.json")

  private fun writeNoSampleReport(report: File, nativeFinalizeResult: Int): File? {
    if (isComplete(report)) return report
    val fallback = fallbackFile(report)
    if (isComplete(fallback)) return fallback
    val parent = fallback.parentFile ?: return null
    if (!parent.exists() && !parent.mkdirs()) return null
    val temporary = File(parent, "${fallback.name}.tmp")
    val status = if (nativeFinalizeResult == 0) "no_sample" else "finalization_error"
    val reason = if (nativeFinalizeResult == 0) {
      "Native finalization completed without emitting a Jelly probe sample."
    } else {
      "Native Jelly probe finalization returned an error; no native sample report was available."
    }
    val json = """
      {"report_type":"mpvrx-jelly-probe-finalization-status","status":"$status","reason":"$reason","native_finalize_result":$nativeFinalizeResult,"created_at_epoch_ms":${System.currentTimeMillis()}}
    """.trimIndent() + "\n"

    return runCatching {
      FileOutputStream(temporary).use { output ->
        output.write(json.toByteArray(Charsets.UTF_8))
        output.fd.sync()
      }
      if (!temporary.renameTo(fallback)) {
        if (!fallback.exists()) temporary.copyTo(fallback, overwrite = false)
        temporary.delete()
      }
      fallback.takeIf(::isComplete)
    }.getOrNull()
  }
}
