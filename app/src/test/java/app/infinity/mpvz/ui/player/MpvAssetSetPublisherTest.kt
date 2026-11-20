/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvAssetSetPublisherTest {
  @Test
  fun staleAndSupersededStagesDoNotReplaceTheCurrentAssetSet() {
    val root = Files.createTempDirectory("mpv-assets-test").toFile()
    try {
      val live = File(root, "live").apply { mkdirs() }
      val publisher = MpvAssetSetPublisher(live, File(root, "stages"))
      File(live, "mpv.conf").writeText("old")
      File(live, "scripts/old.lua").apply { parentFile.mkdirs(); writeText("old-script") }

      val stale = publisher.newStage(ownerToken = 41L, generation = 1L)
      val current = publisher.newStage(ownerToken = 41L, generation = 2L)
      File(stale, "mpv.conf").writeText("stale")
      File(current, "mpv.conf").writeText("current")

      assertFalse(publisher.publish(stale, isCurrent = { false }))
      assertEquals("old", File(live, "mpv.conf").readText())
      assertTrue(publisher.publish(current, isCurrent = { true }))
      assertEquals("current", File(live, "mpv.conf").readText())
      assertTrue(File(live, "scripts/old.lua").isFile)

      val superseded = publisher.newStage(ownerToken = 41L, generation = 3L)
      File(superseded, "mpv.conf").writeText("must-not-stick")
      var currentChecks = 0
      assertFalse(publisher.publish(superseded, isCurrent = { ++currentChecks <= 2 }))
      assertEquals("current", File(live, "mpv.conf").readText())
      assertTrue(File(live, "scripts/old.lua").isFile)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun failedMoveRollsBackThePreviouslyPublishedSet() {
    val root = Files.createTempDirectory("mpv-assets-rollback-test").toFile()
    try {
      val live = File(root, "live").apply { mkdirs() }
      File(live, "mpv.conf").writeText("current")
      File(live, "scripts/old.lua").apply { parentFile.mkdirs(); writeText("old-script") }
      var moves = 0
      val publisher = MpvAssetSetPublisher(live, File(root, "stages")) { from, to ->
        moves++
        if (moves == 2) throw IOException("injected move failure")
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw IOException("unable to move $from to $to")
      }
      val stage = publisher.newStage(ownerToken = 42L, generation = 1L)
      File(stage, "mpv.conf").writeText("must-not-stick")
      File(stage, "input.conf").writeText("next")

      try {
        publisher.publish(stage, isCurrent = { true })
        throw AssertionError("expected transactional publish failure")
      } catch (expected: MpvAssetSetPublishException) {
        assertFalse(expected.retainStage)
      }
      assertEquals("current", File(live, "mpv.conf").readText())
      assertTrue(File(live, "scripts/old.lua").isFile)
      assertFalse(File(live, "input.conf").exists())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun stalePathCannotOverwriteNewerAssetsButUnchangedPathsMayStillPublish() {
    val root = Files.createTempDirectory("mpv-assets-revision-test").toFile()
    try {
      val live = File(root, "live").apply { mkdirs() }
      File(live, "scripts/current.lua").apply { parentFile.mkdirs(); writeText("old") }
      val publisher = MpvAssetSetPublisher(live, File(root, "stages"))
      val stale = publisher.newStage(ownerToken = 43L, generation = 1L)
      File(stale, "scripts/current.lua").writeText("stale")
      File(stale, "input.conf").writeText("unrelated")
      val current = publisher.newStage(ownerToken = 43L, generation = 2L)
      File(current, "scripts/current.lua").writeText("newer")

      assertTrue(publisher.publish(current, isCurrent = { true }, paths = listOf("scripts")))
      assertFalse(publisher.publish(stale, isCurrent = { true }, paths = listOf("scripts")))
      assertTrue(publisher.publish(stale, isCurrent = { true }, paths = listOf("input.conf")))
      assertEquals("newer", File(live, "scripts/current.lua").readText())
      assertEquals("unrelated", File(live, "input.conf").readText())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun directManagedPathMutationFencesOlderStagesWithoutBlockingUnrelatedPaths() {
    val root = Files.createTempDirectory("mpv-assets-direct-mutation-test").toFile()
    try {
      val live = File(root, "live").apply { mkdirs() }
      File(live, "scripts/current.lua").apply { parentFile.mkdirs(); writeText("old") }
      val publisher = MpvAssetSetPublisher(live, File(root, "stages"))
      val stale = publisher.newStage(ownerToken = 45L, generation = 1L)
      File(stale, "scripts/current.lua").writeText("stale")
      File(stale, "input.conf").writeText("unrelated")

      MpvAssetSetPublisher.withLivePathMutation(live, listOf("scripts")) {
        File(live, "scripts/current.lua").writeText("custom-button")
      }

      assertFalse(publisher.publish(stale, isCurrent = { true }, paths = listOf("scripts")))
      assertTrue(publisher.publish(stale, isCurrent = { true }, paths = listOf("input.conf")))
      assertEquals("custom-button", File(live, "scripts/current.lua").readText())
      assertEquals("unrelated", File(live, "input.conf").readText())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun rollbackAttemptsEveryBackupRestoreAfterOneRestoreFails() {
    val root = Files.createTempDirectory("mpv-assets-rollback-all-test").toFile()
    try {
      val live = File(root, "live").apply { mkdirs() }
      File(live, "mpv.conf").writeText("old-mpv")
      File(live, "input.conf").writeText("old-input")
      val publisher = MpvAssetSetPublisher(live, File(root, "stages")) { from, to ->
        val isStageInputInstall = from.name == "input.conf" &&
          from.parentFile?.name?.startsWith("mpv-assets-owner-") == true &&
          to == File(live, "input.conf")
        val isMpvRestore = from.parentFile?.name == ".rollback" &&
          from.name == "mpv.conf" && to == File(live, "mpv.conf")
        if (isStageInputInstall) throw IOException("injected install failure")
        if (isMpvRestore) throw IOException("injected restore failure")
        to.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (!from.renameTo(to)) throw IOException("unable to move $from to $to")
      }
      val stage = publisher.newStage(ownerToken = 44L, generation = 1L)
      File(stage, "mpv.conf").writeText("new-mpv")
      File(stage, "input.conf").writeText("new-input")

      try {
        publisher.publish(stage, isCurrent = { true }, paths = listOf("mpv.conf", "input.conf"))
        throw AssertionError("expected incomplete rollback")
      } catch (expected: MpvAssetSetPublishException) {
        assertTrue(expected.retainStage)
      }

      assertEquals("old-input", File(live, "input.conf").readText())
      assertFalse(File(live, "mpv.conf").exists())
      assertTrue(File(stage, ".rollback/mpv.conf").isFile)
    } finally {
      root.deleteRecursively()
    }
  }
}
