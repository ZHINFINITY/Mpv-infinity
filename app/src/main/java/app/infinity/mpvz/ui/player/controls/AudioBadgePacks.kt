/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for project-specific terms.
 */
package app.infinity.mpvz.ui.player.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.player.controls.components.playerGlassPane
import coil3.compose.AsyncImage

/** Selectable media-badge styles for the Now Playing screen. */
enum class AudioBadgeStyle(val id: String) {
  Text("text"),
  GlassVibrant("glass_vibrant"),
  Neon("neon"),
  Solid("solid"),
  Frost("frost"),
  Liquid("liquid"),
  Prism("prism"),
  ;

  companion object {
    fun fromId(value: String?): AudioBadgeStyle = entries.firstOrNull { it.id == value } ?: Text
  }
}

@Stable
internal data class MediaBadge(
  val name: String,
  val pattern: Regex,
  val imageUrl: String,
)

/** Bridge so a cached local pack badge renders through the pack image composable. */
internal fun MediaBadgeProxy(badge: app.infinity.mpvz.utils.media.MediaBadge): MediaBadge =
  MediaBadge(
    name = badge.name,
    pattern = badge.pattern,
    imageUrl = java.io.File(badge.assetPath).let { if (it.isFile) it.toURI().toString() else badge.remoteUrl ?: badge.assetPath },
  )

/**
 * Image media badges from the open FR-Badges and Nuvio badge kits. Each style resolves to a
 * pack; a badge matches the track's format string and renders the pack's artwork.
 */
internal object AudioBadgePacks {
  /** Priority order: the first matching badge wins, so specific DTS variants precede plain DTS. */
  private val specs: List<Triple<String, String, String>> =
    listOf(
      Triple("Dolby Atmos", """(?i)(?<![a-z0-9])atmos(?![a-z])""", "atmos"),
      Triple("DTS:X", """(?i)\bdts[\s._:-]?x\b""", "dtsx"),
      Triple("DTS-HD MA", """(?i)\bdts[\s._-]?hd[\s._-]?(ma|master[\s._-]?audio)\b""", "dtshd-ma"),
      Triple("DTS-HD HRA", """(?i)\bdts[\s._-]?hd[\s._-]?hra\b""", "dtshd-hra"),
      Triple("DTS-HD", """(?i)\bdts[\s._-]?hd\b""", "dtshd"),
      Triple("DTS-ES", """(?i)\bdts[\s._-]?es\b""", "dtses"),
      Triple("DTS", """(?i)\bdts\b""", "dts"),
      Triple("FLAC", """(?i)\bflac\b""", "flac"),
      Triple("ALAC", """(?i)\balac\b""", "alac"),
      Triple("PCM", """(?i)\bl?pcm\b""", "pcm"),
      Triple("AAC", """(?i)\baac\b""", "aac"),
      Triple("Opus", """(?i)\bopus\b""", "opus"),
      Triple("Vorbis", """(?i)\b(vorbis|ogg)\b""", "vorbis"),
      Triple("MP3", """(?i)\bmp3\b""", "mp3"),
    )

  private val frPacks: Map<String, List<MediaBadge>> =
    mapOf(
      "glass_vibrant" to badges("https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/Badges-Glass-Vibrant/", "png"),
      "neon" to badges("https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/Badges-Neon/", "png"),
      "solid" to badges("https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/Badges-Solid/", "png"),
      "frost" to badges("https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/Badges-Frost/", "png"),
    )

  private val nuvioPacks: Map<String, List<MediaBadge>> =
    mapOf(
      "liquid" to nuvioBadges("https://raw.githubusercontent.com/jyotiprakash101/nuvio-badge-kit/main/v2/Liquid/", "webp"),
      "prism" to nuvioBadges("https://raw.githubusercontent.com/jyotiprakash101/nuvio-badge-kit/main/v2/Prism/", "webp"),
    )

  private fun badges(baseUrl: String, ext: String): List<MediaBadge> =
    specs.mapNotNull { (name, pattern, file) ->
      try {
        MediaBadge(name.lowercase(), Regex(pattern), baseUrl + file + "." + ext)
      } catch (_: Exception) {
        null
      }
    }

  /** Nuvio packs have no ALAC or Vorbis artwork; those badges are simply absent there. */
  private fun nuvioBadges(baseUrl: String, ext: String): List<MediaBadge> =
    badges(baseUrl, ext).filterNot { it.name == "alac" || it.name == "vorbis" }

  fun forStyle(style: AudioBadgeStyle): List<MediaBadge> = when (style) {
    AudioBadgeStyle.GlassVibrant -> frPacks["glass_vibrant"].orEmpty()
    AudioBadgeStyle.Neon -> frPacks["neon"].orEmpty()
    AudioBadgeStyle.Solid -> frPacks["solid"].orEmpty()
    AudioBadgeStyle.Frost -> frPacks["frost"].orEmpty()
    AudioBadgeStyle.Liquid -> nuvioPacks["liquid"].orEmpty()
    AudioBadgeStyle.Prism -> nuvioPacks["prism"].orEmpty()
    AudioBadgeStyle.Text -> emptyList()
  }

  /** First badge whose pattern matches the track's format/title string, or null. */
  fun match(style: AudioBadgeStyle, formatString: String): MediaBadge? =
    if (formatString.isBlank()) null else forStyle(style).firstOrNull { it.pattern.containsMatchIn(formatString) }
}

/** Badge artwork rendered from the selected pack; optionally inside the liquid-glass surface. */
@Composable
internal fun MediaBadgeImage(
  badge: MediaBadge,
  liquidGlass: Boolean,
  modifier: Modifier = Modifier,
  contentDescription: String = badge.name,
) {
  val image = @Composable {
    AsyncImage(
      model = badge.imageUrl,
      contentDescription = contentDescription,
      contentScale = ContentScale.Fit,
      modifier = Modifier.requiredSizeIn(maxWidth = 72.dp, maxHeight = 24.dp),
    )
  }
  if (liquidGlass) {
    Box(
      modifier
        .playerGlassPane(enabled = true, cornerRadius = 999.dp)
        .padding(horizontal = 8.dp, vertical = 3.dp),
      contentAlignment = Alignment.Center,
    ) { image() }
  } else {
    Box(modifier, contentAlignment = Alignment.Center) { image() }
  }
}
