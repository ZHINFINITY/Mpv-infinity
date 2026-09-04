/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.browser.cards

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.theme.AppMotion
import app.infinity.mpvz.ui.theme.AppShapeScale
import app.infinity.mpvz.ui.theme.LocalMotionPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SwipeableFolderActions(
  itemKey: String,
  enabled: Boolean,
  isWatched: Boolean,
  onWatchedChange: (Boolean) -> Unit,
  content: @Composable () -> Unit,
) {
  val density = LocalDensity.current
  val revealPx = with(density) { 88.dp.toPx() }
  val thresholdPx = with(density) { 56.dp.toPx() }
  val scope = rememberCoroutineScope()
  val reduceMotion = LocalMotionPolicy.current.reduceMotion
  val currentIsWatched by rememberUpdatedState(isWatched)
  val currentOnWatchedChange by rememberUpdatedState(onWatchedChange)
  var offsetX by remember(itemKey) { mutableFloatStateOf(0f) }
  var settleJob by remember(itemKey) { mutableStateOf<Job?>(null) }

  fun settle(target: Float, action: (() -> Unit)? = null) {
    settleJob?.cancel()
    action?.invoke()
    if (reduceMotion) {
      offsetX = target
      return
    }
    settleJob = scope.launch {
      animate(
        initialValue = offsetX,
        targetValue = target,
        animationSpec = AppMotion.Spatial.StandardDefault,
      ) { value, _ -> offsetX = value }
    }
  }

  LaunchedEffect(enabled) {
    if (!enabled) {
      settleJob?.cancel()
      offsetX = 0f
    }
  }

  val shape = AppShapeScale.large
  Box(
    modifier = Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface),
  ) {
    if (offsetX > 0f) {
      val progress = (offsetX / revealPx).coerceIn(0f, 1f)
      Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) {
        val iconScale by androidx.compose.animation.core.animateFloatAsState(
          targetValue = if (progress > 0.5f) 1f else 0.6f,
          label = "folderSwipeIconScale",
        )
        val alpha by androidx.compose.animation.core.animateFloatAsState(
          targetValue = progress.coerceIn(0.3f, 1f),
          label = "folderSwipeAlpha",
        )
        Box(
          modifier = Modifier.padding(8.dp).size(72.dp).graphicsLayer {
            scaleX = iconScale
            scaleY = iconScale
            this.alpha = alpha
          }.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            if (isWatched) Icons.RoundedFilled.RemoveCircle else Icons.RoundedFilled.CheckCircle,
            contentDescription = if (isWatched) "Mark folder unwatched" else "Mark folder watched",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(28.dp),
          )
        }
      }
    }

    Box(
      modifier = Modifier.fillMaxWidth().offset { IntOffset(offsetX.roundToInt(), 0) }.background(MaterialTheme.colorScheme.surface)
        .then(
          if (enabled) {
            Modifier.pointerInput(itemKey, revealPx) {
              detectHorizontalDragGestures(
                onDragStart = { settleJob?.cancel() },
                onHorizontalDrag = { change, dragAmount ->
                  change.consume()
                  offsetX = (offsetX + dragAmount).coerceIn(0f, revealPx)
                },
                onDragEnd = {
                  if (offsetX >= thresholdPx) {
                    settle(0f) { currentOnWatchedChange(!currentIsWatched) }
                  } else {
                    settle(0f)
                  }
                },
                onDragCancel = { settle(0f) },
              )
            }
          } else {
            Modifier
          },
        ),
    ) {
      content()
    }
  }
}
