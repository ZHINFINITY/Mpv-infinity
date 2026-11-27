/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.utils.media

import java.util.concurrent.ConcurrentHashMap

/**
 * Fallback matching for packs whose regexes encode the tag as an invisible marker sequence.
 *
 * Several popular Nuvio packs (e.g. GOAT) write patterns like
 * `(?s)^(?=.*\u2063\u200c\u200c\u200c\u200d\u200d\u200c\u200c\u200d\u2064)`: the lookahead demands a
 * run of zero-width characters that only the pack's own tagging tool ever inserts, so such a badge
 * can never match a plain title, path or ffprobe-derived string. For those badges the readable
 * `name` ("4K", "DV · HDR10+", "DTS-HD MA") carries the meaning, so the name is turned into an
 * ordered list of AND-groups of OR-keywords and matched against the same text instead.
 */
internal object BadgeNameKeywords {
  private val INVISIBLE = Regex("[\\u200B-\\u200D\\u2060-\\u2064\\uFEFF]")
  private val TRAILING_INDEX = Regex("\\s+\\d+$")
  private val PARENTHETICAL = Regex("\\([^)]*\\)")
  private val RESOLUTION = Regex("^\\d{3,4}p$")
  private val CHANNELS = Regex("^\\d\\.\\d$")

  /** Two-letter names that would match far too much text ("AI") are left unmatched. */
  private val TOO_GENERIC = setOf("ai", "hd")

  private val explicit: Map<String, List<String>> =
    mapOf(
      "4k" to listOf("2160p", "4k", "uhd"),
      "2k" to listOf("1440p", "2k"),
      "8k" to listOf("4320p", "8k"),
      "dv" to listOf("dv", "dolby vision", "dovi"),
      "dolby vision" to listOf("dv", "dolby vision", "dovi"),
      "hdr10+" to listOf("hdr10+", "hdr10p"),
      "hdr10" to listOf("hdr10"),
      "hdr" to listOf("hdr"),
      "hlg" to listOf("hlg"),
      "sdr" to listOf("sdr"),
      "10bit" to listOf("10bit", "10-bit"),
      "8bit" to listOf("8bit", "8-bit"),
      "hevc" to listOf("hevc", "x265", "h.265", "h265"),
      "x265" to listOf("x265", "hevc", "h.265", "h265"),
      "h.265" to listOf("h.265", "h265", "x265", "hevc"),
      "avc" to listOf("avc", "x264", "h.264", "h264"),
      "x264" to listOf("x264", "avc", "h.264", "h264"),
      "h.264" to listOf("h.264", "h264", "x264", "avc"),
      "xvid" to listOf("xvid"),
      "divx" to listOf("divx"),
      "av1" to listOf("av1"),
      "atmos" to listOf("atmos"),
      "truehd" to listOf("truehd"),
      "atmos truehd" to listOf("atmos", "truehd"),
      "atmos digital+" to listOf("atmos", "ddp", "eac3", "dd+"),
      "dts:x hd ma" to listOf("dts:x", "dts-hd", "dts"),
      "dts:x hd" to listOf("dts:x", "dts-hd", "dts"),
      "dts:x" to listOf("dts:x", "dts-x", "dtsx"),
      "dts-hd ma" to listOf("dts-hd", "dtshd", "dts"),
      "dts-hd" to listOf("dts-hd", "dtshd", "dts"),
      "dts-es" to listOf("dts-es", "dtses", "dts"),
      "dts" to listOf("dts"),
      "dd+" to listOf("ddp", "eac3", "dd+"),
      "ddp" to listOf("ddp", "eac3", "dd+"),
      "dd" to listOf("dd", "ac3"),
      "ac3" to listOf("ac3", "dd"),
      "aac" to listOf("aac"),
      "flac" to listOf("flac"),
      "opus" to listOf("opus"),
      "mp3" to listOf("mp3"),
      "remux" to listOf("remux"),
      "blu-ray" to listOf("blu-ray", "bluray", "bdrip", "brrip"),
      "bluray" to listOf("bluray", "blu-ray", "bdrip", "brrip"),
      "web-dl" to listOf("web-dl", "webdl", "web"),
      "web" to listOf("web-dl", "webdl", "webrip", "web"),
      "webrip" to listOf("webrip", "web-rip", "web"),
      "hdrip" to listOf("hdrip"),
      "hc hdrip" to listOf("hc", "hdrip"),
      "dvdrip" to listOf("dvdrip", "dvd"),
      "dvd" to listOf("dvdrip", "dvd"),
      "hdtv" to listOf("hdtv"),
      "scr" to listOf("scr", "screener"),
      "tc" to listOf("tc", "telesync"),
      "ts" to listOf("ts", "telesync"),
      "cam" to listOf("cam", "camrip"),
      "hybrid" to listOf("hybrid"),
      "criterion" to listOf("criterion"),
      "proper" to listOf("proper"),
      "repack" to listOf("repack"),
      "remastered" to listOf("remastered"),
      "open matte" to listOf("open matte", "openmatte"),
      "regraded" to listOf("regraded"),
      "dir cut" to listOf("dir cut", "director", "dc"),
      "director's cut" to listOf("dir cut", "director", "dc"),
      "extended" to listOf("extended"),
      "uncut" to listOf("uncut"),
      "uncensored" to listOf("uncensored"),
      "imax" to listOf("imax"),
      "imax enhanced" to listOf("imax"),
      "multi" to listOf("multi", "dual audio"),
    )

  private val regexCache = ConcurrentHashMap<String, Regex>()

  fun isMarkerEncoded(pattern: String): Boolean = INVISIBLE.containsMatchIn(pattern)

  /** Outer list is AND-ed; each inner list is a set of OR-ed spellings. */
  fun keywordsFor(name: String): List<List<String>> {
    val cleaned = INVISIBLE.replace(name, " ").lowercase().trim().replace(Regex("\\s+"), " ")
    if (cleaned.isEmpty()) return emptyList()
    val withoutIndex = TRAILING_INDEX.replace(cleaned, "").trim().ifEmpty { cleaned }
    val languageOnly = withoutIndex.removeSuffix(" subtitles").trim()
    val parts = if (languageOnly != withoutIndex) listOf(languageOnly) else withoutIndex.split("·").map { it.trim() }
    return parts.mapNotNull { alternatives(it) }
  }

  fun matches(source: String?, keywords: List<List<String>>): Boolean {
    if (source.isNullOrBlank() || keywords.isEmpty()) return false
    return keywords.all { spellings -> spellings.any { tokenRegex(it).containsMatchIn(source) } }
  }

  private fun alternatives(part: String): List<String>? {
    if (part.isBlank()) return null
    explicit[part]?.let { return it }
    if (RESOLUTION.matches(part)) {
      return if (part == "2160p") listOf("2160p", "4k", "uhd") else listOf(part)
    }
    if (CHANNELS.matches(part)) return listOf(part)
    val stripped = PARENTHETICAL.replace(part, " ").replace(Regex("\\s+"), " ").trim()
    if (stripped.isEmpty() || stripped in TOO_GENERIC) return null
    return listOf(stripped)
  }

  private fun tokenRegex(token: String): Regex =
    regexCache.getOrPut(token) {
      val core =
        token.trim().split(' ').filter { it.isNotEmpty() }
          .joinToString("[\\s._-]*") { Regex.escape(it) }
      Regex("(?<![a-z0-9])$core(?![a-z0-9])", RegexOption.IGNORE_CASE)
    }
}
