/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.presentation.components.pullrefresh

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A reusable Box that wraps content with pull-to-refresh functionality.
 *
 * @param isRefreshing Tracks refresh state.
 * @param onRefresh Invoked when refresh is triggered.
 * @param modifier Box modifier.
 * @param enabled Toggles pull-to-refresh.
 * @param listState Unused. Reserved for top-scroll checks.
 * @param refreshingOffset Unused. Reserved for offset customization.
 * @param refreshThreshold Pull distance required to trigger refresh.
 * @param delayAfterRefresh Delay (ms) to keep indicator visible after completion.
 * @param content Content displayed inside the Box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshBox(
  isRefreshing: MutableState<Boolean>,
  onRefresh: suspend () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  @Suppress("UNUSED_PARAMETER") listState: LazyListState? = null,
  @Suppress("UNUSED_PARAMETER") refreshingOffset: Dp = 80.dp,
  refreshThreshold: Dp = 80.dp,
  delayAfterRefresh: Long = 800L,
  content: @Composable BoxScope.() -> Unit,
) {
  val coroutineScope = rememberCoroutineScope()
  val state = rememberPullToRefreshState()
  val density = LocalDensity.current

  val maxTranslationPx = with(density) { refreshThreshold.toPx() }

  val activeJob = remember { mutableStateOf<Job?>(null) }
  val infiniteTransition = rememberInfiniteTransition(label = "refreshRotation")
  val infinityDashOffset by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 256.589f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 2_000, easing = LinearEasing),
        ),
      label = "infinityDashOffset",
    )
  val infinityPath = remember {
    Path().apply {
      moveTo(24.3f, 30f)
      cubicTo(11.4f, 30f, 5f, 43.3f, 5f, 50f)
      cubicTo(5f, 56.7f, 11.4f, 70f, 24.3f, 70f)
      cubicTo(43.6f, 70f, 56.4f, 30f, 75.7f, 30f)
      cubicTo(88.6f, 30f, 95f, 43.3f, 95f, 50f)
      cubicTo(95f, 56.7f, 88.6f, 70f, 75.7f, 70f)
      cubicTo(56.4f, 70f, 43.6f, 30f, 24.3f, 30f)
      close()
    }
  }

  val targetTranslationY =
    if (isRefreshing.value) {
      maxTranslationPx
    } else {
      (state.distanceFraction * maxTranslationPx).coerceAtLeast(0f)
    }

  val animatedTranslationY by animateFloatAsState(
    targetValue = targetTranslationY,
    animationSpec =
      spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
      ),
    label = "content_translationY",
  )

  val indicatorScale by animateFloatAsState(
    targetValue = if (isRefreshing.value) 1f else state.distanceFraction.coerceIn(0f, 1f),
    animationSpec =
      spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMedium,
      ),
    label = "indicator_scale",
  )

  val indicatorSize = 56.dp
  val indicatorSizePx = remember(density) { with(density) { indicatorSize.toPx() } }

  Box(
    modifier =
      modifier.pullToRefresh(
        isRefreshing = isRefreshing.value,
        state = state,
        enabled = enabled,
        onRefresh = {
          activeJob.value?.cancel()
          isRefreshing.value = true
          activeJob.value =
            coroutineScope.launch {
              try {
                onRefresh()
                delay(delayAfterRefresh)
              } finally {
                isRefreshing.value = false
              }
            }
        },
      ),
  ) {
    Box(
      modifier =
        Modifier
          .matchParentSize()
          .graphicsLayer { translationY = animatedTranslationY },
    ) {
      content()
    }

    Box(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .graphicsLayer {
            translationY = (animatedTranslationY / 2f) - (indicatorSizePx / 2f)
            scaleX = indicatorScale
            scaleY = indicatorScale
            alpha = indicatorScale
          }.shadow(elevation = 4.dp, shape = CircleShape, clip = false)
          .size(indicatorSize)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surfaceContainerHigh)
          .padding(6.dp),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val pathScale = minOf(size.width, size.height) / 100f * 0.8f
        withTransform({
          translate(size.width / 2f, size.height / 2f)
          scale(pathScale, pathScale, pivot = Offset.Zero)
          translate(-50f, -50f)
        }) {
          drawPath(
            path = infinityPath,
            color = MaterialTheme.colorScheme.primary,
            style =
              Stroke(
                width = 10f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect =
                  PathEffect.dashPathEffect(
                    intervals = floatArrayOf(205.271f, 51.318f),
                    phase = infinityDashOffset,
                  ),
              ),
          )
        }
      }
    }
  }
}
