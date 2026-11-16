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
import org.json.JSONArray
import org.json.JSONObject
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
  private const val REPORT_PREFIX = "mpv-infinity-jelly-probe-"
  private const val LEGACY_REPORT_PREFIX = "jelly-probe-"
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
  fun awaitFinalized(
    context: Context,
    report: File,
    nativeFinalizeResult: Int,
    nativeArmOptionResult: Int,
  ) {
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

        val fallback = writeNoSampleReport(report, nativeFinalizeResult, nativeArmOptionResult)
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
    (file.name.startsWith(REPORT_PREFIX) || file.name.startsWith(LEGACY_REPORT_PREFIX)) &&
      (file.name.endsWith(".json") || file.name.endsWith(".no-sample.json"))

  private fun isComplete(file: File): Boolean = file.isFile && file.length() > 0L

  private fun fallbackFile(report: File): File =
    File(report.parentFile, "${report.nameWithoutExtension}.no-sample.json")

  private fun writeNoSampleReport(
    report: File,
    nativeFinalizeResult: Int,
    nativeArmOptionResult: Int,
  ): File? {
    if (isComplete(report)) return report
    val fallback = fallbackFile(report)
    if (isComplete(fallback)) return fallback
    val parent = fallback.parentFile ?: return null
    if (!parent.exists() && !parent.mkdirs()) return null
    val temporary = File(parent, "${fallback.name}.tmp")
    val status = if (nativeFinalizeResult == 0) "no_sample" else "finalization_error"
    val reason = if (nativeFinalizeResult == 0) {
      "The native disarm option call returned zero, but no native report arrived before this app fallback; native stage outcomes were not observed."
    } else {
      "The native disarm option call returned $nativeFinalizeResult; no native report arrived before this app fallback, so capture outcomes were not observed."
    }
    val unknown = JSONObject.NULL
    val target = JSONObject()
      .put("media_hint", "current_playback")
      .put("current_file_verified", false)
      .put("pts_window_seconds", unknown)
      .put("pair_rule", "probe every eligible frame pair; PTS is recorded for context")
      .put("reference_pixel", JSONArray().put(1352).put(224))
      .put("reference_frame_size", JSONArray().put(1920).put(1080))
      .put("sample_position_fraction", JSONArray().put(1352.0 / 1920.0).put(224.0 / 1080.0))
      .put("pixel", unknown)
      .put("eligible_alpha_range", JSONArray().put(0.35).put(0.65))
      .put(
        "fallback_rule",
        "Continue across every eligible pair until the first Jelly activation; if none activates, finalization saves the last evaluated skip.",
      )
    val sourceBounds = JSONObject()
      .put("status", "not_observed")
      .put("width", unknown)
      .put("height", unknown)
      .put("pixel_in_bounds", unknown)
    val stages = JSONObject()
      .put(
        "arm_setup",
        JSONObject()
          .put("status", "unknown")
          .put("app_arm_option_result", nativeArmOptionResult)
          .put("native_renderer_setup", "not_observed")
          .put("native_error_code", unknown)
          .put("report_path", report.absolutePath)
          .put("meaning", "The MPV option result does not prove renderer resources were initialized."),
      )
      .put(
        "target_pixel",
        JSONObject()
          .put("status", "not_observed")
          .put("evaluated", unknown)
          .put("source_bounds", sourceBounds),
      )
      .put(
        "eligible_frame_pairs",
        JSONObject()
          .put("status", "not_observed")
          .put("eligible_count", unknown)
          .put("last_pts", unknown)
          .put("last_alpha", unknown),
      )
      .put(
        "render_callbacks",
        JSONObject()
          .put("status", "not_observed")
          .put("count", unknown)
          .put("while_armed", unknown)
          .put("out_of_bounds_count", unknown)
          .put("alpha_below_range_count", unknown)
          .put("alpha_above_range_count", unknown)
          .put("alpha_invalid_count", unknown)
          .put("pending_readback_count", unknown)
          .put("rejection_mask_bits", "1=target_pixel_out_of_bounds,2=alpha_below_range,4=alpha_above_range,8=alpha_not_finite")
          .put("last_candidate", unknown),
      )
      .put(
        "motion_view_path",
        JSONObject()
          .put("status", "not_observed")
          .put("motion_views_enabled", unknown)
          .put("render_source_mix_callbacks", unknown)
          .put("pair_count", unknown)
          .put("supported_pair_count", unknown)
          .put("unsupported_pair_count", unknown)
          .put("mixer_callback_count", unknown)
          .put("shader_callback_count", unknown)
          .put("texture_callback_count", unknown)
          .put("probe_output_path", "offscreen_compute_only")
          .put("probe_dispatch_count", unknown),
      )
      .put(
        "jelly",
        JSONObject()
          .put("status", "not_observed")
          .put("activation", unknown)
          .put("reason_code", unknown)
          .put("reason", "not_observed"),
      )
      .put(
        "gpu_readback",
        JSONObject()
          .put("status", "not_observed")
          .put("submitted_count", unknown)
          .put("poll_count", unknown)
          .put("last_poll", "not_observed"),
      )
      .put(
        "writer",
        JSONObject()
          .put("native_status", "not_observed")
          .put("native_file_result", "not_observed")
          .put("native_error_code", unknown)
          .put("native_report_path", report.absolutePath)
          .put("fallback_status", "complete")
          .put("fallback_file_result", "success")
          .put("fallback_error_code", 0)
          .put("fallback_report_path", fallback.absolutePath),
      )
    val json = JSONObject()
      .put("report_type", "mpv-infinity-jelly-probe-report")
      .put("schema_version", 5)
      .put("status", status)
      .put("reason", reason)
      .put("stage_snapshot", "not_observed")
      .put("native_arm_option_result", nativeArmOptionResult)
      .put("native_finalize_result", nativeFinalizeResult)
      .put(
        "native_finalize_result_semantics",
        "The disarm-option update result only; zero means the property call succeeded, not that a capture was made.",
      )
      .put("report_path", report.absolutePath)
      .put("fallback_report_path", fallback.absolutePath)
      .put("target", target)
      .put("stages", stages)
      .put("created_at_epoch_ms", System.currentTimeMillis())
      .toString() + "\n"

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
