/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import java.io.File
import java.io.IOException
import java.util.UUID

/** Filesystem transaction for an MPV asset preparation. Provider reads happen before publish. */
internal class MpvAssetSetPublisher(
  private val liveRoot: File,
  private val stagingParent: File,
  private val move: (File, File) -> Unit = { from, to ->
    to.parentFile?.let { parent ->
      if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create ${parent.path}")
    }
    if (!from.renameTo(to)) throw IOException("Unable to move ${from.path} to ${to.path}")
  },
) {
  companion object {
    /** All files whose contents may be consulted while MPV starts. */
    val assetPaths = listOf(
      "mpv.conf",
      "input.conf",
      "subfont.ttf",
      "cacert.pem",
      "scripts",
      "script-modules",
      "script-opts",
      "shaders",
      "fonts",
    )

    private val publicationLock = Any()
    private val publishedRevisions = mutableMapOf<String, Long>()
    private val stagedRevisions = mutableMapOf<String, Map<String, Long>>()

    /** Serializes direct live-asset writes with stage publication and fences older snapshots. */
    fun <T> withLivePathMutation(liveRoot: File, paths: List<String>, operation: () -> T): T =
      synchronized(publicationLock) {
        require(paths.isNotEmpty() && paths.distinct().size == paths.size && paths.all { it in assetPaths }) {
          "Only unique managed MPV asset paths may be mutated"
        }
        try {
          operation()
        } finally {
          paths.forEach { path ->
            val key = File(liveRoot, path).absolutePath
            publishedRevisions[key] = (publishedRevisions[key] ?: 0L) + 1L
          }
        }
      }
  }

  /** Allocates a per-owner/per-generation root and copies the currently published set into it. */
  fun newStage(ownerToken: Long, generation: Long): File = synchronized(publicationLock) {
    stagedRevisions.keys.removeAll { !File(it).exists() }
    if (!stagingParent.exists() && !stagingParent.mkdirs()) {
      throw IOException("Unable to create MPV staging parent: $stagingParent")
    }
    val root = File(stagingParent, "mpv-assets-owner-$ownerToken-generation-$generation-${UUID.randomUUID()}")
    if (!root.mkdir()) throw IOException("Unable to create MPV asset stage: $root")
    try {
      for (path in assetPaths) copyIfPresent(File(liveRoot, path), File(root, path))
      stagedRevisions[root.absolutePath] = assetPaths.associateWith(::currentRevision)
      root
    } catch (failure: Exception) {
      root.deleteRecursively()
      throw failure
    }
  }

  /**
   * Swaps the requested managed paths with rollback on failure. Call inside
   * PlaybackActivityOwner.runIfOwner; this method performs no provider I/O.
   */
  fun publish(
    stageRoot: File,
    isCurrent: () -> Boolean,
    paths: List<String> = assetPaths,
  ): Boolean = synchronized(publicationLock) {
    require(stageRoot.parentFile == stagingParent) { "Stage must be created by this publisher" }
    require(paths.distinct().size == paths.size && paths.all { it in assetPaths }) {
      "Only unique managed MPV asset paths may be published"
    }
    if (!isCurrent()) return@synchronized false
    val stageRevision = stagedRevisions[stageRoot.absolutePath]
      ?: throw IllegalArgumentException("Stage snapshot is no longer available")
    if (paths.any { stageRevision[it] != currentRevision(it) }) return@synchronized false

    val backupRoot = File(stageRoot, ".rollback")
    if (!backupRoot.mkdir()) throw IOException("Unable to create rollback directory: $backupRoot")
    val installed = mutableListOf<String>()
    val backedUp = mutableListOf<String>()
    try {
      for (path in paths) {
        if (!isCurrent()) throw StalePreparation()
        val staged = File(stageRoot, path)
        val live = File(liveRoot, path)
        val backup = File(backupRoot, path)
        if (live.exists()) {
          move(live, backup)
          backedUp += path
        }
        if (staged.exists()) {
          move(staged, live)
          installed += path
        }
      }
      if (!isCurrent()) throw StalePreparation()
      paths.forEach { path ->
        val key = livePathKey(path)
        publishedRevisions[key] = currentRevision(path) + 1L
      }
      stagedRevisions.remove(stageRoot.absolutePath)
      true
    } catch (stale: StalePreparation) {
      rollbackOrRetain(installed, backedUp, backupRoot, stale)
      false
    } catch (failure: Exception) {
      rollbackOrRetain(installed, backedUp, backupRoot, failure)
      throw MpvAssetSetPublishException(retainStage = false, cause = failure)
    }
  }

  private fun rollbackOrRetain(
    installed: List<String>,
    backedUp: List<String>,
    backupRoot: File,
    originalFailure: Throwable,
  ) {
    val rollbackFailure = runCatching { rollback(installed, backedUp, backupRoot) }.exceptionOrNull()
    if (rollbackFailure != null) {
      originalFailure.addSuppressed(rollbackFailure)
      (installed + backedUp).distinct().forEach { path ->
        val key = livePathKey(path)
        publishedRevisions[key] = currentRevision(path) + 1L
      }
      throw MpvAssetSetPublishException(retainStage = true, cause = originalFailure)
    }
  }

  private fun rollback(installed: List<String>, backedUp: List<String>, backupRoot: File) {
    val failures = mutableListOf<Exception>()
    installed.asReversed().forEach { path ->
      try {
        val live = File(liveRoot, path)
        if (live.exists() && !live.deleteRecursively() && live.exists()) {
          throw IOException("Unable to remove partially published asset: $live")
        }
      } catch (failure: Exception) {
        failures += failure
      }
    }
    backedUp.asReversed().forEach { path ->
      try {
        val backup = File(backupRoot, path)
        if (backup.exists()) move(backup, File(liveRoot, path))
      } catch (failure: Exception) {
        failures += failure
      }
    }
    if (failures.isNotEmpty()) {
      val rollbackFailure = IOException("One or more MPV assets could not be restored")
      failures.forEach(rollbackFailure::addSuppressed)
      throw rollbackFailure
    }
  }

  private fun livePathKey(path: String): String = File(liveRoot, path).absolutePath

  private fun currentRevision(path: String): Long = publishedRevisions[livePathKey(path)] ?: 0L

  private fun copyIfPresent(source: File, destination: File) {
    if (!source.exists()) return
    if (source.isDirectory) {
      if (!destination.exists() && !destination.mkdirs()) {
        throw IOException("Unable to create staged directory: $destination")
      }
      val children = source.listFiles() ?: throw IOException("Unable to list MPV asset directory: $source")
      children.forEach { child -> copyIfPresent(child, File(destination, child.name)) }
    } else {
      destination.parentFile?.let { parent ->
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create staged parent: $parent")
      }
      source.copyTo(destination, overwrite = true)
      source.lastModified().takeIf { it > 0L }?.let(destination::setLastModified)
    }
  }
}

internal class MpvAssetSetPublishException(
  val retainStage: Boolean,
  cause: Throwable,
) : IOException(
  if (retainStage) "MPV asset publish failed and rollback was incomplete; retain stage for recovery"
  else "MPV asset publish failed; previous set restored",
  cause,
)

private class StalePreparation : RuntimeException()
