/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.repository.subtitlehub

object MpvInfinitySubtitleHubSources {
  const val BETASERIES_KEY = "betaseries"
  const val JIMAKU_KEY = "jimaku"
  const val SUBDL_KEY = "subdl_com"
  const val SUBSOURCE_KEY = "subsource_net"
  const val SUBS_RO_KEY = "subs_ro"
  const val SUBX_KEY = "subx"

  val ALL =
    linkedMapOf(
      "all" to "All sources without an API key",
      "subtitlecat_com" to "SubtitleCat",
      "moviesubtitles_org" to "MovieSubtitles.org",
      "moviesubtitlesrt_com" to "MovieSubtitlesRT",
      "my_subs_co" to "My Subs",
      "tvsubtitles_net" to "TVSubtitles",
      BETASERIES_KEY to "BetaSeries",
      JIMAKU_KEY to "Jimaku",
      SUBDL_KEY to "SubDL.com",
      SUBSOURCE_KEY to "SubSource.net",
      SUBS_RO_KEY to "Subs.ro",
      SUBX_KEY to "SubX",
    )

  val DEFAULT = setOf("all")

  val ANDROID_SUPPORTED =
    setOf(
      "subdl_com",
      "subtitlecat_com",
      "moviesubtitles_org",
      "moviesubtitlesrt_com",
      "my_subs_co",
      "tvsubtitles_net",
      BETASERIES_KEY,
      JIMAKU_KEY,
      SUBDL_KEY,
      SUBSOURCE_KEY,
      SUBS_RO_KEY,
      SUBX_KEY,
    )

  val AUTHENTICATED_SOURCES =
    setOf(
      BETASERIES_KEY,
      JIMAKU_KEY,
      SUBDL_KEY,
      SUBSOURCE_KEY,
      SUBS_RO_KEY,
      SUBX_KEY,
    )

  private val KEYLESS_SOURCES = ANDROID_SUPPORTED - AUTHENTICATED_SOURCES

  fun resolveSelected(selected: Set<String>): Set<String> =
    if (selected.isEmpty() || selected.contains("all")) {
      KEYLESS_SOURCES + selected.intersect(AUTHENTICATED_SOURCES)
    } else {
      selected.intersect(ANDROID_SUPPORTED)
    }
}
