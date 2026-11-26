/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.components

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.icons.AppIcon
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.MediaPlaybackService
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.player.controls.components.MiniAudioVisualizer
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.theme.BrowserGlassSurface
import coil3.compose.AsyncImage
import org.koin.compose.koinInject

@Composable
fun AudioMiniPlayer(modifier: Modifier = Modifier) {
  val isServiceRunning = MediaPlaybackService.isForegroundActive()
  val context = LocalContext.current
  val appearancePreferences = koinInject<AppearancePreferences>()
  val glassEnabled by appearancePreferences.liquidGlassSurfaces.collectAsState()
  val glassOpacity by appearancePreferences.glassSurfaceOpacity.collectAsState()
  val glassHighlight by appearancePreferences.glassSurfaceHighlight.collectAsState()
  val sessionState by PlaybackSession.state.collectAsStateWithLifecycle()
  val paused by PlaybackSession.propBoolean["pause"].collectAsStateWithLifecycle()
  val rawMediaTitle by PlaybackSession.propString["media-title"].collectAsStateWithLifecycle()
  val rawArtist by PlaybackSession.propString["metadata/by-key/Artist"].collectAsStateWithLifecycle()
  val rawAlbum by PlaybackSession.propString["metadata/by-key/Album"].collectAsStateWithLifecycle()
  val duration by PlaybackSession.propDouble["duration"].collectAsStateWithLifecycle()
  val position by PlaybackSession.propDouble["time-pos"].collectAsStateWithLifecycle()

  if (!isServiceRunning || sessionState.currentItem == null) return

  val isPlaying = paused == false
  val artist =
    rawArtist?.takeIf { it.isNotBlank() }
      ?: rawAlbum?.takeIf { it.isNotBlank() }
      ?: sessionState.currentItem?.artist?.takeIf { it.isNotBlank() }
      ?: context.getString(R.string.mini_player_unknown_artist)
  val title =
    sessionState.currentItem?.title?.takeIf { it.isNotBlank() }
      ?: rawMediaTitle?.takeIf { it.isNotBlank() }
      ?: "Audio Track"

  fun openPlayer() {
    val intent =
      Intent(context, PlayerActivity::class.java).apply {
        action = MediaPlaybackService.ACTION_OPEN_PLAYER
        putExtra("is_audio", true)
        putExtra("media_library_audio", true)
        putExtra("internal_launch", true)
        putExtra("launch_source", "notification")
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    context.startActivity(intent)
  }

  BrowserGlassSurface(
    enabled = glassEnabled,
    opacity = glassOpacity,
    highlight = glassHighlight,
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(24.dp))
        .pointerInput(Unit) {
          var totalDrag = 0f
          detectVerticalDragGestures(
            onVerticalDrag = { change, dragAmount ->
              totalDrag += dragAmount
              if (totalDrag < 0f) change.consume()
            },
            onDragEnd = { if (totalDrag <= -120f) openPlayer(); totalDrag = 0f },
            onDragCancel = { totalDrag = 0f },
          )
        }
        .clickable(onClick = ::openPlayer)
        .semantics {
          contentDescription = "Open full audio player"
          stateDescription = context.getString(
            if (isPlaying) R.string.notification_playing else R.string.downloads_paused,
          )
        },
  ) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val artworkUri = sessionState.currentItem?.artworkUri

    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(64.dp)
          .drawBehind {
            // mpvRx-style progress: soft tinted fill plus a sharp leading edge
            // marker at the play head, so progress reads at a glance.
            drawRect(color = trackColor.copy(alpha = 0.55f))
            val dur = duration?.toFloat() ?: 0f
            val pos = position?.toFloat() ?: 0f
            val progressFraction = if (dur > 0f) (pos / dur).coerceIn(0f, 1f) else 0f
            if (progressFraction > 0f) {
              val playedWidth = size.width * progressFraction
              drawRect(
                color = primaryColor.copy(alpha = 0.22f),
                size = Size(width = playedWidth, height = size.height),
              )
              drawRect(
                color = primaryColor.copy(alpha = 0.85f),
                topLeft = Offset(playedWidth - 1.dp.toPx(), 0f),
                size = Size(width = 2.dp.toPx(), height = size.height),
              )
            }
          }.padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Cover artwork thumbnail
      Box(
        modifier =
          Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        if (!artworkUri.isNullOrBlank()) {
          AsyncImage(
            model = artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Icon(
            imageVector = Icons.RoundedFilled.Audiotrack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
          )
        }
        if (isPlaying) {
          MiniAudioVisualizer(
            isPlaying = true,
            color = primaryColor,
            modifier =
              Modifier
                .align(Alignment.BottomEnd)
                .padding(3.dp)
                .size(width = 12.dp, height = 9.dp),
            barCount = 3,
          )
        }
      }

      Spacer(modifier = Modifier.width(10.dp))

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.Center,
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.basicMarquee(initialDelayMillis = 650),
        )
        Spacer(modifier = Modifier.height(1.dp))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = context.getString(
              if (isPlaying) R.string.notification_playing else R.string.downloads_paused,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = primaryColor,
            maxLines = 1,
          )
          Text(
            text = "·",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
          )
          Text(
            text = artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
        }
      }

      Spacer(modifier = Modifier.width(4.dp))

      MiniTransportButton(
        icon = Icons.RoundedFilled.SkipPrevious,
        contentDescription = context.getString(R.string.pref_gesture_media_previous),
        sizeDp = 22,
      ) {
        context.startService(
          Intent(context, MediaPlaybackService::class.java).setAction(
            MediaPlaybackService.ACTION_NOTIFICATION_PREVIOUS,
          ),
        )
      }

      IconButton(
        onClick = {
          context.startService(
            Intent(context, MediaPlaybackService::class.java).setAction(
              MediaPlaybackService.ACTION_NOTIFICATION_PLAY_PAUSE,
            ),
          )
        },
        modifier = Modifier.size(48.dp).clip(CircleShape),
      ) {
        AnimatedContent(
          targetState = isPlaying,
          transitionSpec = { fadeIn() togetherWith fadeOut() },
          label = "mini_play_pause",
        ) { playing ->
          Icon(
            imageVector = if (playing) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp),
          )
        }
      }

      MiniTransportButton(
        icon = Icons.RoundedFilled.SkipNext,
        contentDescription = context.getString(R.string.pref_gesture_media_next),
        sizeDp = 22,
      ) {
        context.startService(
          Intent(context, MediaPlaybackService::class.java).setAction(
            MediaPlaybackService.ACTION_NOTIFICATION_NEXT,
          ),
        )
      }

      IconButton(
        onClick = {
          context.startService(
            Intent(context, MediaPlaybackService::class.java).setAction(
              MediaPlaybackService.ACTION_NOTIFICATION_STOP,
            ),
          )
        },
        modifier = Modifier.size(40.dp).clip(CircleShape),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Close,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

@Composable
private fun MiniTransportButton(
  icon: AppIcon,
  contentDescription: String?,
  sizeDp: Int,
  onClick: () -> Unit,
) {
  IconButton(
    onClick = onClick,
    modifier = Modifier.size(40.dp).clip(CircleShape),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.size(sizeDp.dp),
    )
  }
}
