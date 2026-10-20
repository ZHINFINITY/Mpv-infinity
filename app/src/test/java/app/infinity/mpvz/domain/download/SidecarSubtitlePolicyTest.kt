/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.domain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarSubtitlePolicyTest {
  private val extensions = setOf("srt", "ass", "vtt")

  @Test
  fun ownershipUsesExactVideoBasenameNotLoosePrefix() {
    listOf("Movie.srt", "Movie.en.srt", "Movie.en.forced.srt", "Movie.Greek.srt").forEach {
      assertTrue(it, owns(it))
    }
    listOf("Movie2.en.srt", "Movie 2.en.srt", "Movie.2.srt", "Movie.backup.srt",
      "Movie.mkv", "Movie.en.srt.part", "Other.en.srt").forEach {
      assertFalse(it, owns(it))
    }
  }

  @Test
  fun normalAndForcedTracksHaveDistinctNamesRegardlessOfInputOrder() {
    val normal = name("en", false)
    val forced = name("en", true)
    assertEquals("Movie.en.srt", normal)
    assertEquals("Movie.en.forced.srt", forced)
    assertEquals(setOf(normal, forced), listOf(true, false).map { name("en", it) }.toSet())
    assertEquals(setOf(normal, forced), listOf(false, true).map { name("en", it) }.toSet())
    assertTrue(owns(normal))
    assertTrue(owns(forced))
  }

  @Test
  fun preservesSubtitleExtensionsAndRecognizedRoleNames() {
    assertEquals("Movie.eng.forced.ass", SidecarSubtitlePolicy.sidecarFileName("Movie.mp4", "eng", "English", true, "ass"))
    assertTrue(owns("Movie.eng.forced.ass"))
    assertTrue(owns("Movie.en-US.vtt"))
    assertFalse(owns("Movie.en.normal.srt"))
  }

  @Test
  fun missingOrUnsafeLanguageUsesAnOwnedLoadableFallback() {
    assertEquals("Movie.und.srt", name(null, false))
    assertEquals("Movie.und.forced.srt", name(null, true))
    assertEquals("Movie.und.srt", name("../unsafe", false))
    assertTrue(owns(name(null, false)))
    assertTrue(owns(name(null, true)))
    assertEquals("Movie.el.srt", SidecarSubtitlePolicy.sidecarFileName("Movie.mkv", null, "Greek", false, "srt"))
  }

  private fun name(language: String?, forced: Boolean): String =
    SidecarSubtitlePolicy.sidecarFileName("Movie.mkv", language, "", forced, "srt")

  private fun owns(candidate: String): Boolean =
    SidecarSubtitlePolicy.belongsToVideo("Movie.mkv", candidate, extensions)
}
