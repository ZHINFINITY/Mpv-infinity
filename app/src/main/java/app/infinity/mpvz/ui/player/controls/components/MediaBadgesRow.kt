/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.ui.player.controls.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.player.NativeMediaMetadata
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.controls.AudioBadgeStyle
import app.infinity.mpvz.ui.player.controls.MediaBadgeImage
import app.infinity.mpvz.utils.media.MediaBadge
import app.infinity.mpvz.utils.media.MediaBadges
import app.infinity.mpvz.utils.media.RemoteBadgePacks
import coil3.compose.AsyncImage
import org.koin.compose.koinInject

/**
 * Video-player badge strip. With "Text pill" selected it renders the built-in
 * asset badges; with any image pack selected it renders EVERY badge of that
 * pack (resolution, bit depth, HDR/DV, video/audio codecs, editions, ...) that
 * matches the release tags of the title plus the live mpv stream properties —
 * the pack's own pattern list decides what appears. Pack art is downloaded and
 * cached once (RemoteBadgePacks) and rendered at 24 dp height.
 */
@Composable
fun MediaBadgesRow(
  mediaTitle: String?,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val preferences = koinInject<AppearancePreferences>()
  val showMediaBadges by preferences.showMediaBadges.collectAsState()
  val liquidGlassSurfaces by preferences.liquidGlassSurfaces.collectAsState()
  val videoBadgeStyle by preferences.videoBadgeStyle.collectAsState()
  val badgeStyle = AudioBadgeStyle.fromId(videoBadgeStyle)

  val rawMediaTitle by PlaybackSession.propString["media-title"].collectAsState()
  val videoHeightProp by PlaybackSession.propInt["video-params/h"].collectAsState()
  val videoCodecProp by PlaybackSession.propString["video-codec"].collectAsState()
  val audioCodecProp by PlaybackSession.propString["audio-codec-name"].collectAsState()
  val channelCountProp by PlaybackSession.propInt["audio-params/channel-count"].collectAsState()
  // Native (Media3) engine never publishes mpv properties; without this fallback the badge row
  // shows nothing after switching engines.
  val nativeMetadata by NativeMediaMetadata.snapshot.collectAsState()
  val mediaPath by PlaybackSession.propString["path"].collectAsState()
  val videoHeight = videoHeightProp ?: nativeMetadata.videoHeight
  val videoCodec = videoCodecProp ?: nativeMetadata.videoCodec
  val audioCodec = audioCodecProp ?: nativeMetadata.audioCodec
  val pixFmt by PlaybackSession.propString["video-params/pixelformat"].collectAsState()
  val gamma by PlaybackSession.propString["video-params/gamma"].collectAsState()
  val channelCount = channelCountProp ?: nativeMetadata.channelCount

  // One match string per source, exactly as the release-tag matcher expects.
  val derived =
    remember(videoHeight, videoCodec, audioCodec, pixFmt, gamma, channelCount) {
      buildString {
        videoHeight?.takeIf { it > 0 }?.let { append("${it}p ") }
        videoCodec?.let { append("$it ") }
        audioCodec?.let { append("$it ") }
        when (channelCount) {
          6 -> append("5.1 ")
          8 -> append("7.1 ")
        }
        pixFmt?.lowercase()?.let { if ("10" in it) append("10bit ") }
        // HDR is about the transfer curve (PQ/HLG), not the color gamut —
        // a BT.2020-primaries SDR stream must not be labeled HDR.
        gamma?.lowercase()?.let { if ("pq" in it || "hlg" in it) append("hdr ") }
      }
    }

  var badges by remember { mutableStateOf<List<MediaBadge>>(emptyList()) }
  LaunchedEffect(mediaTitle, rawMediaTitle, mediaPath, derived, showMediaBadges, badgeStyle.id) {
    if (!showMediaBadges) {
      badges = emptyList()
      return@LaunchedEffect
    }
    badges =
      if (badgeStyle == AudioBadgeStyle.Text) {
        listOf(mediaTitle, rawMediaTitle, mediaPath, derived).flatMap { source ->
          MediaBadges.matchTitle(context, source)
        }.distinctBy { it.id }
      } else {
        // Full pack: match every badge in the selected pack's JSON.
        val pack = RemoteBadgePacks.load(context, badgeStyle.id)
        pack.filter { badge ->
          listOf(mediaTitle, rawMediaTitle, mediaPath, derived).any { source ->
            try {
              source != null && badge.pattern.containsMatchIn(source)
            } catch (_: Exception) {
              false
            }
          }
        }
      }
  }

  if (badges.isEmpty()) return
  // Badge height: 24 dp.
  val badgeHeight = 24.dp
  val badgeGap = 6.dp
  // The strip can be wider than the top-bar column it lives in (sized to the
  // title pill), so it scrolls horizontally instead of shrinking — one line,
  // full size, never cut off. A plain Row (no SubcomposeLayout) keeps the
  // intrinsic-measurement crash of the player controls ConstraintLayout away.
  Row(
    modifier =
      modifier
        .padding(start = 14.dp, top = 8.dp)
        .height(badgeHeight)
        .horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(badgeGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (badgeStyle == AudioBadgeStyle.Text) {
      badges.forEach { badge ->
        AsyncImage(
          model = "file:///android_asset/${badge.assetPath}", // assetPath already includes "badges/"
          contentDescription = badge.name,
          contentScale = ContentScale.FillHeight,
          modifier = Modifier.fillMaxHeight(),
        )
      }
    } else {
      badges.forEach { badge ->
        MediaBadgeImage(
          badge = app.infinity.mpvz.ui.player.controls.MediaBadgeProxy(badge),
          liquidGlass = liquidGlassSurfaces,
          modifier = Modifier.height(badgeHeight),
        )
      }
    }
  }
}
