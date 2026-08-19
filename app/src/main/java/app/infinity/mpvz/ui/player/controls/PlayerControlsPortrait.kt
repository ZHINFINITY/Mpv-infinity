/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.PlayerButton
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.Panels
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.player.PlayerViewModel
import app.infinity.mpvz.ui.player.Sheets
import app.infinity.mpvz.ui.player.VideoAspect
import app.infinity.mpvz.ui.player.controls.components.ControlsButton
import app.infinity.mpvz.ui.player.controls.components.ControlsGroup
import app.infinity.mpvz.ui.player.controls.components.AnimatedPlayPauseIcon
import app.infinity.mpvz.ui.player.controls.components.PlayerGlassSurface
import app.infinity.mpvz.ui.theme.controlColor
import app.infinity.mpvz.ui.theme.spacing
import dev.vivvvek.seeker.Segment

@Composable
fun TopPlayerControlsPortrait(
  mediaTitle: String?,
  hideBackground: Boolean,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  playbackSpeed: Float = 1f,
  isTranslatingSub: Boolean = false,
  isRealtimeSubsActive: Boolean = false,
  realtimeSubsLanguage: String = "",
  translationStatus: String = "",
  translatingTrackName: String = "",
) {
  val playlistModeEnabled = viewModel.hasPlaylistSupport()
  val clickEvent = LocalPlayerButtonsClickEvent.current

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(top = MaterialTheme.spacing.medium)
        .padding(horizontal = MaterialTheme.spacing.medium),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ControlsGroup(
        modifier = Modifier.weight(1f),
        hideBackground = hideBackground,
      ) {
        ControlsButton(
          icon = Icons.RoundedFilled.ArrowBack,
          onClick = onBackPress,
          color = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(45.dp),
        )

        Column(
          modifier = Modifier.weight(1f).padding(start = 4.dp),
        ) {
          PlayerGlassSurface(
            shape = CircleShape,
            hideBackground = hideBackground,
            contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
            modifier =
              Modifier
                .height(45.dp)
                .clip(CircleShape)
                .clickable(
                  enabled = playlistModeEnabled,
                  onClick = {
                    clickEvent()
                    onOpenSheet(Sheets.Playlist)
                  },
                ),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 14.dp),
            ) {
              Text(
                mediaTitle ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false),
              )
              viewModel.getPlaylistInfo()?.let { playlistInfo ->
                Text(
                  " • $playlistInfo",
                  maxLines = 1,
                  style = MaterialTheme.typography.bodySmall,
                  color = LocalContentColor.current.copy(alpha = 0.7f),
                )
              }
            }
          }
        }

        PlayerGlassSurface(
          shape = RoundedCornerShape(24.dp),
          hideBackground = hideBackground,
          contentColor = Color.White,
          modifier = Modifier.height(45.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp),
          ) {
            Text(
              text =
                if (kotlin.math.abs(playbackSpeed - playbackSpeed.toInt()) < 0.001f) {
                  "${playbackSpeed.toInt()}x"
                } else {
                  "%.2fx".format(playbackSpeed)
                },
              style = MaterialTheme.typography.labelLarge,
              color = Color.White,
              modifier = Modifier.clickable { onOpenSheet(Sheets.PlaybackSpeed) }.padding(horizontal = 6.dp),
            )
            ControlsButton(
              icon = Icons.RoundedFilled.LockOpen,
              onClick = viewModel::lockControls,
              color = Color.White,
              modifier = Modifier.size(38.dp),
            )
            ControlsButton(
              icon = Icons.RoundedFilled.Audiotrack,
              onClick = { onOpenSheet(Sheets.AudioTracks) },
              onLongClick = { onOpenPanel(Panels.AudioDelay) },
              color = Color.White,
              modifier = Modifier.size(38.dp),
            )
          }
        }
      }
    }

    androidx.compose.animation.AnimatedVisibility(
      visible = isTranslatingSub || isRealtimeSubsActive,
      enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it },
      exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it },
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Translate,
          contentDescription = null,
          modifier = Modifier.size(14.dp),
          tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
          text =
            if (isRealtimeSubsActive) {
              "Real-time subs: ${realtimeSubsLanguage.ifBlank { "?" }} ${translationStatus.ifBlank { "" }}"
            } else {
              "Translating ${translatingTrackName.ifBlank { "subs" }} ${translationStatus.ifBlank { "" }}"
            },
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.tertiary,
        )
      }
    }

    val syncplayManager = org.koin.compose.koinInject<app.infinity.mpvz.domain.syncplay.SyncplayManager>()
    val syncplayState by syncplayManager.state.collectAsState()

    androidx.compose.animation.AnimatedVisibility(
      visible = syncplayState.isConnected,
      enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it },
      exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it },
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 14.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.CloudDownload,
          contentDescription = null,
          modifier = Modifier.size(14.dp),
          tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
          text =
            stringResource(
              R.string.syncplay_player_status,
              syncplayState.room.orEmpty(),
              syncplayState.users.size,
            ),
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.tertiary,
        )
      }
    }
  }
}

@Composable
fun BottomPlayerControlsPortrait(
  buttons: List<PlayerButton>,
  isPlaying: Boolean,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  hideBackground: Boolean,
  decoder: app.infinity.mpvz.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
) {
  val compactButtons =
    remember(buttons) {
      val preferredButtons =
        listOf(
          PlayerButton.SUBTITLES,
          PlayerButton.VIDEO_ZOOM,
          PlayerButton.SCREEN_ROTATION,
          PlayerButton.MORE_OPTIONS,
        )
      preferredButtons.filter { it in buttons }.ifEmpty { buttons.take(4) }
    }

  PlayerGlassSurface(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.medium),
    shape = RoundedCornerShape(28.dp),
    hideBackground = hideBackground,
    contentColor = Color.White,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        ControlsButton(
          icon = Icons.RoundedFilled.SkipPrevious,
          onClick = { if (viewModel.hasPrevious()) viewModel.playPrevious() },
          title = stringResource(R.string.pref_gesture_media_previous),
          modifier = Modifier.size(58.dp),
        )
          PlayerGlassSurface(
          modifier = Modifier.size(70.dp).clickable { viewModel.pauseUnpause() },
          shape = CircleShape,
          hideBackground = hideBackground,
          contentColor = Color.White,
        ) {
          AnimatedPlayPauseIcon(
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            tint = LocalContentColor.current,
          )
        }
        ControlsButton(
          icon = Icons.RoundedFilled.SkipNext,
          onClick = { if (viewModel.hasNext()) viewModel.playNext() },
          title = stringResource(R.string.pref_gesture_media_next),
          modifier = Modifier.size(58.dp),
        )
      }

      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
      ) {
        compactButtons.forEach { button ->
          Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            RenderPlayerButton(
              button = button,
              chapters = chapters,
              currentChapter = currentChapter,
              isPortrait = true,
              isSpeedNonOne = isSpeedNonOne,
              currentZoom = currentZoom,
              aspect = aspect,
              mediaTitle = mediaTitle,
              hideBackground = hideBackground,
              onBackPress = onBackPress,
              onOpenSheet = onOpenSheet,
              onOpenPanel = onOpenPanel,
              viewModel = viewModel,
              activity = activity,
              decoder = decoder,
              playbackSpeed = playbackSpeed,
              buttonSize = 42.dp,
            )
          }
        }
      }
    }
  }
}
