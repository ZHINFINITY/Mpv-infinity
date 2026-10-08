/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.presentation.crash

import java.io.File

/** Keeps the most recent pre-exit crash logcat private to the app and out of device backup. */
internal object CrashLogSnapshotStore {
  const val EXTRA_PRECRASH_SNAPSHOT_AVAILABLE = "pre_crash_snapshot_available"
  internal const val MAX_SNAPSHOT_CHARS = 512 * 1024

  private const val FILE_NAME = "last_crash_logcat.txt"
  private const val TEMP_FILE_NAME = "$FILE_NAME.tmp"

  fun save(
    directory: File,
    snapshot: String,
  ): Boolean =
    try {
      if (!directory.exists() && !directory.mkdirs()) return false

      val target = File(directory, FILE_NAME)
      val temporary = File(directory, TEMP_FILE_NAME)
      temporary.writeText(snapshot.takeLast(MAX_SNAPSHOT_CHARS), Charsets.UTF_8)
      if (target.exists() && !target.delete()) {
        temporary.delete()
        return false
      }
      if (!temporary.renameTo(target)) {
        temporary.copyTo(target, overwrite = true)
        temporary.delete()
      }
      true
    } catch (_: Exception) {
      File(directory, TEMP_FILE_NAME).delete()
      false
    }

  fun read(directory: File): String? =
    try {
      File(directory, FILE_NAME)
        .takeIf { it.isFile && it.length() <= MAX_SNAPSHOT_CHARS * 4L }
        ?.readText(Charsets.UTF_8)
        ?.takeLast(MAX_SNAPSHOT_CHARS)
    } catch (_: Exception) {
      null
    }
}
