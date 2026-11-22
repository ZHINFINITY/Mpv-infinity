/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.ui.player.controls.components

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
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
  val mediaPath by PlaybackSession.propString["path"].collectAsState()
  val videoHeight by PlaybackSession.propInt["video-params/h"].collectAsState()
  val videoCodec by PlaybackSession.propString["video-codec"].collectAsState()
  val audioCodec by PlaybackSession.propString["audio-codec-name"].collectAsState()
  val pixFmt by PlaybackSession.propString["video-params/pixelformat"].collectAsState()
  val primaries by PlaybackSession.propString["video-params/primaries"].collectAsState()
  val channelCount by PlaybackSession.propInt["audio-params/channel-count"].collectAsState()

  var badges by remember { mutableStateOf<List<MediaBadge>>(emptyList()) }
  LaunchedEffect(
    mediaTitle,
    rawMediaTitle,
    mediaPath,
    videoHeight,
    videoCodec,
    audioCodec,
    pixFmt,
    primaries,
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
          primaries?.lowercase()?.let { if ("2020" in it) append("hdr ") }
        }
        listOf(mediaTitle, rawMediaTitle, mediaPath, derived).flatMap { source ->
          MediaBadges.matchTitle(context, source)
        }
      } else {
        emptyList()
      }.distinctBy { it.id }
  }

  if (badges.isEmpty()) return
  val badgeHeight = 16.dp
  val badgeGap = 8.dp
  // The badge strip can be wider than the top-bar column it lives in (sized to
  // the title pill), which clipped the last badges. A custom two-pass Layout
  // shrinks the badge height until the whole strip fits the given width — and
  // avoids SubcomposeLayout, whose intrinsic measurement crashes inside the
  // player controls ConstraintLayout.
  Layout(
    content = {
      badges.forEach { badge ->
        AsyncImage(
          model = "file:///android_asset/${badge.assetPath}", // assetPath already includes "badges/"
          contentDescription = badge.name,
          contentScale = ContentScale.FillHeight,
        )
      }
    },
    modifier = modifier.padding(start = 14.dp, top = 8.dp),
  ) { measurables, constraints ->
    val gap = badgeGap.roundToPx()
    val spacing = gap * (measurables.size - 1).coerceAtLeast(0)
    fun measureAt(heightPx: Int): List<Placeable> =
      measurables.map { m ->
        val h = heightPx.coerceIn(1, constraints.maxHeight)
        m.measure(Constraints(maxHeight = h))
      }
    var placeables = measureAt(badgeHeight.roundToPx())
    val totalWidth: (List<Placeable>) -> Int = { it.sumOf { p -> p.width } + spacing }
    if (constraints.hasBoundedWidth && totalWidth(placeables) > constraints.maxWidth) {
      val scale = constraints.maxWidth.toFloat() / totalWidth(placeables)
      val shrunk = (badgeHeight.roundToPx() * scale).toInt()
      placeables = measureAt(shrunk)
    }
    val rowHeight = placeables.maxOfOrNull { it.height } ?: 0
    layout(constraints.maxWidth.coerceAtMost(totalWidth(placeables)), rowHeight) {
      var x = 0
      placeables.forEach { p ->
        p.placeRelative(x, (rowHeight - p.height) / 2)
        x += p.width + gap
      }
    }
  }
}
