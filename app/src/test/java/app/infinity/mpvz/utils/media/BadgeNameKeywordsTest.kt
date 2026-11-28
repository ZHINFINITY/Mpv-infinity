/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.utils.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeNameKeywordsTest {
  private val markerPattern = "(?s)^(?=.*\u2063\u200c\u200c\u200c\u200d\u200d\u200c\u200c\u200d\u2064)"
  private val derived4kHevc = "2160p hevc eac3 5.1 10bit hdr "
  private val title1080p = "Some.Movie.2024.1080p.WEB-DL.DDP5.1.x264-GROUP"

  @Test
  fun detectsInvisibleMarkerPatterns() {
    assertTrue(BadgeNameKeywords.isMarkerEncoded(markerPattern))
    assertFalse(BadgeNameKeywords.isMarkerEncoded("(?i)\\b(2160p|4k)\\b"))
  }

  @Test
  fun resolutionBadgesMatchTheDerivedStreamFacts() {
    val keywords = BadgeNameKeywords.keywordsFor("4K")
    assertTrue(BadgeNameKeywords.matches(derived4kHevc, keywords))
    assertFalse(BadgeNameKeywords.matches(title1080p, keywords))
    assertTrue(BadgeNameKeywords.matches(title1080p, BadgeNameKeywords.keywordsFor("1080p")))
    assertFalse(BadgeNameKeywords.matches(derived4kHevc, BadgeNameKeywords.keywordsFor("720p")))
  }

  @Test
  fun comboBadgesRequireEveryMarkerGroup() {
    val dvHdr10Plus = BadgeNameKeywords.keywordsFor("DV · HDR10+")
    assertTrue(BadgeNameKeywords.matches("2160p hevc dv hdr10+ ", dvHdr10Plus))
    assertFalse(BadgeNameKeywords.matches("2160p hevc hdr10+ ", dvHdr10Plus))
    assertFalse(BadgeNameKeywords.matches("2160p hevc dv ", dvHdr10Plus))
  }

  @Test
  fun audioAndCodecBadgesUseTheirSpellings() {
    assertTrue(BadgeNameKeywords.matches(derived4kHevc, BadgeNameKeywords.keywordsFor("HEVC")))
    assertTrue(BadgeNameKeywords.matches("1080p h264 ac3 5.1 ", BadgeNameKeywords.keywordsFor("AVC")))
    assertTrue(BadgeNameKeywords.matches("1080p h264 eac3 5.1 ", BadgeNameKeywords.keywordsFor("DD+")))
    assertTrue(BadgeNameKeywords.matches("2160p hevc truehd ", BadgeNameKeywords.keywordsFor("ATMOS TRUEHD")))
    assertFalse(BadgeNameKeywords.matches(derived4kHevc, BadgeNameKeywords.keywordsFor("ATMOS TRUEHD")))
  }

  @Test
  fun numberedPackNamesMatchTheirBaseTag() {
    assertTrue(BadgeNameKeywords.matches("Movie.2160p.UHD.BluRay.REMUX.HEVC", BadgeNameKeywords.keywordsFor("REMUX 3")))
    assertTrue(BadgeNameKeywords.matches(title1080p, BadgeNameKeywords.keywordsFor("WEB 1")))
    assertFalse(BadgeNameKeywords.matches(title1080p, BadgeNameKeywords.keywordsFor("BLU-RAY 2")))
  }

  @Test
  fun markerBadgesFallBackToTheirNameInsteadOfNeverMatching() {
    val badge =
      MediaBadge(
        id = "r-4k",
        name = "4K",
        groupId = "gr",
        assetPath = "/tmp/none.png",
        pattern = Regex(markerPattern),
        keywords = BadgeNameKeywords.keywordsFor("4K"),
      )
    assertTrue(badge.matches(derived4kHevc))
    assertFalse(badge.matches(title1080p))
  }

  @Test
  fun plainPatternsKeepWorkingWithoutKeywords() {
    val badge =
      MediaBadge(
        id = "plain",
        name = "4K",
        groupId = "gr",
        assetPath = "/tmp/none.png",
        pattern = Regex("(?i)\\b(2160p|4k)\\b"),
      )
    assertTrue(badge.matches(derived4kHevc))
    assertFalse(badge.matches(title1080p))
  }
}
