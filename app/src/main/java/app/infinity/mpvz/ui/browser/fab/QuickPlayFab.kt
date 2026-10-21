/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.fab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.utils.history.RecentlyPlayedOps
import app.infinity.mpvz.utils.media.MediaUtils
import app.infinity.mpvz.ui.preferences.components.settingsGlassContainerColor
import app.infinity.mpvz.ui.preferences.components.settingsGlassSurface
import kotlinx.coroutines.launch

import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import org.koin.compose.koinInject

/** Shared surface used by both the standalone and menu Quick Play FABs. */
@Composable
fun Modifier.quickPlayFabSurface(): Modifier =
  settingsGlassSurface(cornerRadius = 20.dp)

/** Resolve composition locals now; the FAB invokes its progress callback outside composition. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun quickPlayFabContainerColor(): (Float) -> Color {
  val appearance = koinInject<AppearancePreferences>()
  val glass by appearance.liquidGlassSurfaces.collectAsState()
  val original = ToggleFloatingActionButtonDefaults.containerColor()
  return if (glass) { _: Float -> Color.Transparent } else original
}

/**
 * A Quick Play Floating Action Button that appears on the main screen.
 * When tapped, it immediately resumes playback of the most recently played video.
 * The FAB is only visible when there is a recently played video available and enabled in settings.
 *
 * @param visible Controls overall visibility (e.g., hidden during selection mode or when permission is denied)
 * @param bottomPadding Bottom padding to position above the navigation bar
 * @param modifier Modifier for the FAB
 */
@Composable
fun QuickPlayFab(
  visible: Boolean,
  bottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val showQuickPlayFab by appearancePreferences.showQuickPlayFab.collectAsState()
  val glass by appearancePreferences.liquidGlassSurfaces.collectAsState()
  val lastPlayedEntity by RecentlyPlayedOps.observeLastPlayedEntity().collectAsState(initial = null)
  val hasRecentlyPlayed = lastPlayedEntity != null
  var isPressed by remember { mutableStateOf(false) }

  // Pulse animation scale
  val scale by animateFloatAsState(
    targetValue = if (isPressed) 0.9f else 1f,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioMediumBouncy,
      stiffness = Spring.StiffnessMedium,
    ),
    label = "quick_play_scale",
  )

  AnimatedVisibility(
    visible = visible && hasRecentlyPlayed && showQuickPlayFab,
    enter = scaleIn(
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
      ),
    ) + fadeIn(),
    exit = scaleOut(
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
      ),
    ) + fadeOut(),
    modifier = modifier,
  ) {
    FloatingActionButton(
      onClick = {
        isPressed = true
        coroutineScope.launch {
          val validEntity = RecentlyPlayedOps.getLastPlayedEntity()
          if (validEntity != null) {
            MediaUtils.playFile(
              source = validEntity.filePath,
              context = context,
              launchSource = "quick_play_fab",
              title = validEntity.videoTitle?.takeIf { it.isNotBlank() }
                ?: validEntity.fileName.takeIf { it.isNotBlank() },
            )
          } else {
            android.widget.Toast.makeText(
              context,
              R.string.toast_file_not_found,
              android.widget.Toast.LENGTH_SHORT,
            ).show()
          }
          isPressed = false
        }
      },
      modifier = Modifier
        .padding(bottom = bottomPadding)
        .quickPlayFabSurface()
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        },
      containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.primaryContainer),
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      elevation = FloatingActionButtonDefaults.elevation(
        defaultElevation = if (glass) 0.dp else 6.dp,
        pressedElevation = if (glass) 0.dp else 12.dp,
        focusedElevation = if (glass) 0.dp else 6.dp,
        hoveredElevation = if (glass) 0.dp else 8.dp,
      ),
    ) {
      Icon(
        Icons.RoundedFilled.PlayArrow,
        contentDescription = stringResource(R.string.ui_quick_play),
        modifier = Modifier.size(28.dp),
      )
    }
  }
}
