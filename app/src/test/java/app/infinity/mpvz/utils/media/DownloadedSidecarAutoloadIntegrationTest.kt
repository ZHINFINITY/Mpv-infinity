/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.utils.media

import app.infinity.mpvz.domain.download.SidecarSubtitlePolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSidecarAutoloadIntegrationTest {
  @Test
  fun generatedNormalAndForcedNamesRemainAutoloadable() {
    listOf<String?>("en", "eng", "el", "en-US", null, "../unsafe").forEach { language ->
      listOf(false, true).forEach { forced ->
        val filename = SidecarSubtitlePolicy.sidecarFileName(
          videoFileName = "Movie.mkv",
          languageCode = language,
          label = "",
          forced = forced,
          extension = "srt",
        )
        assertTrue(filename, SubtitleFilenameMatcher.matches(filename, "Movie.mkv"))
        assertTrue(filename, SidecarSubtitlePolicy.belongsToVideo("Movie.mkv", filename, setOf("srt")))
      }
    }
  }
}
