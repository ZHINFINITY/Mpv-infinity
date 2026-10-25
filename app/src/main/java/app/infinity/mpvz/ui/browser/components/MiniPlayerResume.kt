package app.infinity.mpvz.ui.browser.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import app.infinity.mpvz.ui.icons.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.AudioPlaybackResumeSnapshot
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.preferences.components.settingsGlassContainerColor
import app.infinity.mpvz.ui.preferences.components.settingsGlassEnabled
import app.infinity.mpvz.ui.preferences.components.settingsGlassSurface
import coil3.compose.AsyncImage
import java.util.Locale

/** What the library offers when no playback service is alive but the last music session was saved. */
internal object MiniPlayerResumePolicy {
  /** Only once the service is gone, so it never flashes between tracks of a live session. */
  fun shouldOffer(
    isServiceRunning: Boolean,
    isSettingsScreen: Boolean,
    snapshot: AudioPlaybackResumeSnapshot?,
  ): Boolean = !isServiceRunning && !isSettingsScreen && snapshot?.currentItem != null

  /**
   * A plain MAIN launch without media is the one PlayerActivity restores from the saved snapshot
   * (song, queue, position, paused state). ACTION_OPEN_PLAYER would attach to a live session
   * instead, and there is none.
   */
  fun resumeIntent(context: Context): Intent =
    Intent(context, PlayerActivity::class.java).apply {
      action = Intent.ACTION_MAIN
      putExtra("is_audio", true)
      putExtra("internal_launch", true)
      putExtra("launch_source", "mini_player_resume")
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

  fun positionLabel(positionSeconds: Double): String {
    val total = positionSeconds.coerceAtLeast(0.0).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
      String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
  }
}

/** Same slot, shape and glass as the live mini player, so resuming looks like the player waiting. */
@Composable
internal fun MiniPlayerResumeCard(
  snapshot: AudioPlaybackResumeSnapshot,
  onResume: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val item = snapshot.currentItem ?: return
  val glassEnabled = settingsGlassEnabled()
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(20.dp))
      .settingsGlassSurface(cornerRadius = 20.dp)
      .clickable(onClick = onResume),
    color = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)),
    tonalElevation = if (glassEnabled) 0.dp else 8.dp,
    shadowElevation = if (glassEnabled) 0.dp else 10.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(48.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Audiotrack,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        item.artworkUri?.takeIf { it.isNotBlank() }?.let { artwork ->
          AsyncImage(
            model = artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.title?.takeIf { it.isNotBlank() } ?: item.originalUri.substringAfterLast('/'),
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val subtitle = listOfNotNull(
          item.artist?.takeIf { it.isNotBlank() },
          MiniPlayerResumePolicy.positionLabel(snapshot.positionSeconds),
        ).joinToString(" · ")
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(onClick = onResume) {
        Icon(
          imageVector = Icons.RoundedFilled.PlayArrow,
          contentDescription = stringResource(R.string.ui_resume_playback),
        )
      }
      IconButton(onClick = onDismiss) {
        Icon(
          imageVector = Icons.RoundedFilled.Close,
          contentDescription = stringResource(R.string.ui_close),
        )
      }
    }
  }
}
