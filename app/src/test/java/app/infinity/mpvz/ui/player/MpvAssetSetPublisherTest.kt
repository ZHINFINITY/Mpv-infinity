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
}
