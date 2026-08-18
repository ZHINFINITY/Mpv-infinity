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
import android.util.Log

/**
 * Keeps important app diagnostics in-process so the in-app log viewer does not depend entirely on
 * vendor-specific Logcat filtering. Logcat remains enabled for normal Android diagnostics; this
 * buffer is merged into the same viewer/export stream by [DebugLogReader].
 */
internal object DebugLogBuffer {
  private val lock = Any()
  private val entries = ArrayDeque<DebugLogEntry>()
  private var nextId = 0L

  fun append(
    level: DebugLogLevel,
    tag: String,
    message: String,
    timeMillis: Long = System.currentTimeMillis(),
  ) {
    synchronized(lock) {
      while (entries.size >= DEBUG_LOG_ENTRY_LIMIT) {
        entries.removeFirst()
      }
      entries.addLast(
        DebugLogEntry(
          id = "in-app-${nextId++}",
          timeMillis = timeMillis,
          timestamp = formatDisplayTime(timeMillis),
          level = level,
          tag = tag,
          message = message,
          pid = Process.myPid(),
          tid = null,
        ),
      )
    }
  }

  fun snapshot(): List<DebugLogEntry> = synchronized(lock) { entries.toList() }
}

/** Writes an event to Android Logcat and the same in-app log/export stream. */
internal object AppDebugLog {
  fun verbose(tag: String, message: String) {
    Log.v(tag, message)
    DebugLogBuffer.append(DebugLogLevel.Verbose, tag, message)
  }

  fun debug(tag: String, message: String) {
    Log.d(tag, message)
    DebugLogBuffer.append(DebugLogLevel.Debug, tag, message)
  }

  fun info(tag: String, message: String) {
    Log.i(tag, message)
    DebugLogBuffer.append(DebugLogLevel.Info, tag, message)
  }

  fun warn(tag: String, message: String) {
    Log.w(tag, message)
    DebugLogBuffer.append(DebugLogLevel.Warn, tag, message)
  }

  fun error(tag: String, message: String, throwable: Throwable? = null) {
    if (throwable == null) {
      Log.e(tag, message)
    } else {
      Log.e(tag, message, throwable)
    }
    DebugLogBuffer.append(
      DebugLogLevel.Error,
      tag,
      if (throwable == null) message else "$message ${throwable.stackTraceToString()}",
    )
  }
}
