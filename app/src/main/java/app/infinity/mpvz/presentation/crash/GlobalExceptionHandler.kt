/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.presentation.crash

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Debug
import kotlin.system.exitProcess

class GlobalExceptionHandler(
  private val context: Context,
  private val activity: Class<*>,
) : Thread.UncaughtExceptionHandler {
  override fun uncaughtException(
    t: Thread,
    e: Throwable,
  ) {
    val memorySnapshot = captureMemorySnapshot(context)
    val preCrashLogcat =
      runCatching { CrashActivity.collectPreCrashLogcat() }
        .getOrElse { error ->
          "Pre-crash logcat capture failed: ${error.javaClass.simpleName}: ${error.message}"
        }
    val snapshotSaved =
      runCatching {
        CrashLogSnapshotStore.save(context.noBackupFilesDir, "$preCrashLogcat\n\n$memorySnapshot")
      }.getOrDefault(false)
    val intent = Intent(context, activity)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    intent.putExtra(
      "exception",
      runCatching { e.stackTraceToString() }
        .getOrElse { "${e.javaClass.name}: uncaught exception (stack trace unavailable)" },
    )
    if (snapshotSaved) {
      intent.putExtra(CrashLogSnapshotStore.EXTRA_PRECRASH_SNAPSHOT_AVAILABLE, true)
    }
    context.startActivity(intent)
    exitProcess(0)
  }

  private fun captureMemorySnapshot(context: Context): String =
    try {
      val runtime = Runtime.getRuntime()
      val processMemory = Debug.MemoryInfo()
      Debug.getMemoryInfo(processMemory)
      val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
      val systemMemory =
        activityManager?.let { manager ->
          ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        }
      buildString {
        appendLine("Crash-time memory snapshot:")
        appendLine("java_heap_used_bytes=${runtime.totalMemory() - runtime.freeMemory()}")
        appendLine("java_heap_max_bytes=${runtime.maxMemory()}")
        appendLine("process_total_pss_kib=${processMemory.totalPss}")
        appendLine("native_heap_allocated_bytes=${Debug.getNativeHeapAllocatedSize()}")
        if (systemMemory != null) {
          appendLine("system_available_bytes=${systemMemory.availMem}")
          appendLine("system_low_memory=${systemMemory.lowMemory}")
          appendLine("system_low_memory_threshold_bytes=${systemMemory.threshold}")
        } else {
          appendLine("system_memory_snapshot=unavailable")
        }
        appendLine("vulkan_device_memory=not_exposed_by_this_snapshot")
      }
    } catch (error: Throwable) {
      "Crash-time memory snapshot unavailable: ${error.javaClass.simpleName}"
    }
}
