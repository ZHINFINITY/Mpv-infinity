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
import app.infinity.mpvz.utils.media.MediaBadge
import app.infinity.mpvz.utils.media.MediaBadges
import coil3.compose.AsyncImage
import org.koin.compose.koinInject

/**
 * Transparent PNG media badges (Elite-Badges), matched the way mpvRx does it:
 * the release-tag patterns run over every title/name source we have (display
 * title, raw media-title, full path/URL) *and* over a synthetic string derived
 * from the live mpv stream properties (resolution, codecs, channels, bit depth,
 * dynamic range). Streaming titles carry no release tags, so without the
 * property-derived part badges never appear for them.
 */
@Composable
fun MediaBadgesRow(
  mediaTitle: String?,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val preferences = koinInject<AppearancePreferences>()
  val showMediaBadges by preferences.showMediaBadges.collectAsState()

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

  var badges by remember { mutableStateOf<List<MediaBadge>>(emptyList()) }
  LaunchedEffect(
    mediaTitle,
    rawMediaTitle,
    mediaPath,
    videoHeight,
    videoCodec,
    audioCodec,
    pixFmt,
    gamma,
    channelCount,
    showMediaBadges,
  ) {
    badges =
      if (showMediaBadges) {
        val derived = buildString {
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
        listOf(mediaTitle, rawMediaTitle, mediaPath, derived).flatMap { source ->
          MediaBadges.matchTitle(context, source)
        }
      } else {
        emptyList()
      }.distinctBy { it.id }
  }

  if (badges.isEmpty()) return
  val badgeHeight = 28.dp
  val badgeGap = 8.dp
  // Badges keep a fixed height in every video. The strip can be wider than the
  // top-bar column it lives in (sized to the title pill), so it scrolls
  // horizontally instead of shrinking — one line, full size, never cut off.
  // A plain Row (no SubcomposeLayout) keeps the intrinsic-measurement crash of
  // the player controls ConstraintLayout away.
  Row(
    modifier =
      modifier
        .padding(start = 14.dp, top = 8.dp)
        .height(badgeHeight)
        .horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(badgeGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    badges.forEach { badge ->
      AsyncImage(
        model = "file:///android_asset/${badge.assetPath}", // assetPath already includes "badges/"
        contentDescription = badge.name,
        contentScale = ContentScale.FillHeight,
        modifier = Modifier.fillMaxHeight(),
      )
    }
  }
}
