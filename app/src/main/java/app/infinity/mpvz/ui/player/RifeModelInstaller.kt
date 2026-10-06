/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Extracts the pinned RIFE-v4.6 weights to app-private storage for ncnn's file-based loader. */
object RifeModelInstaller {
  private const val assetDirectory = "rife-v4.6"
  private const val markerContents = "rife-ncnn-vulkan-a7532fc3-rife-v4.6"
  private val modelHashes =
    mapOf(
      "flownet.param" to "724569596bcd1e7b9fa50455c604777ebed99746d2ef40aa86e31b5725f1053c",
      "flownet.bin" to "f334ed2260149ce0188a6dcf049844e8b0cdd912e01cbcfb63553157d2508958",
    )

  fun install(context: Context): File {
    val target = File(context.noBackupFilesDir, assetDirectory)
    val marker = File(target, ".ready")
    if (marker.isFile && marker.readText() == markerContents && modelHashes.keys.all { File(target, it).isFile }) {
      return target
    }

    val staging = File(context.noBackupFilesDir, "$assetDirectory.staging")
    staging.deleteRecursively()
    check(staging.mkdirs()) { "Unable to create RIFE model staging directory" }

    try {
      for ((filename, expectedHash) in modelHashes) {
        val destination = File(staging, filename)
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open("$assetDirectory/$filename").use { input ->
          FileOutputStream(destination).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              output.write(buffer, 0, read)
              digest.update(buffer, 0, read)
            }
          }
        }
        check(digest.digest().toHex() == expectedHash) { "RIFE model asset failed SHA-256 verification: $filename" }
      }
      File(staging, ".ready").writeText(markerContents)
      target.deleteRecursively()
      check(staging.renameTo(target)) { "Unable to install verified RIFE model assets" }
      return target
    } catch (error: Throwable) {
      staging.deleteRecursively()
      throw error
    }
  }

  private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
  }
}
