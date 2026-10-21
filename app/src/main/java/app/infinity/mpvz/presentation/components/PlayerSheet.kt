/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

@file:Suppress("DEPRECATION")

package app.infinity.mpvz.presentation.components

import android.annotation.SuppressLint
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.theme.liquidGlassPane
import org.koin.compose.koinInject

private val sheetAnimationSpec = tween<Float>(350)

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun PlayerSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  tonalElevation: Dp = 1.dp,
  customMaxWidth: Dp? = null,
  customMaxHeight: Dp? = null,
  surfaceColor: Color? = null,
  isSwipeActive: Boolean = false,
  swipeOffset: Float = 0f,
  content: @Composable () -> Unit,
) {
  val appearancePreferences = koinInject<AppearancePreferences>()
  val liquidGlassSurfaces by appearancePreferences.liquidGlassSurfaces.collectAsState()
  val playerControlsTheme by appearancePreferences.playerControlsTheme.collectAsState()
  val sheetIsDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  val glassSheetEnabled = liquidGlassSurfaces || playerControlsTheme.name != "Classic"
  val resolvedSurfaceColor =
    surfaceColor ?: if (glassSheetEnabled) {
      if (sheetIsDark) Color.Black.copy(alpha = if (liquidGlassSurfaces) 0.30f else 0.24f)
      else Color.White.copy(alpha = if (liquidGlassSurfaces) 0.34f else 0.26f)
    } else {
      MaterialTheme.colorScheme.surface
    }
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
  val maxWidth =
    customMaxWidth
      ?: if (LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE) {
        640.dp
      } else {
        420.dp
      }
  val isImeVisible = WindowInsets.ime.getBottom(density) > 0
  val maxHeight =
    PlayerSheetLayoutPolicy.maxHeightDp(
        screenHeightDp = LocalConfiguration.current.screenHeightDp,
        portrait = LocalConfiguration.current.orientation == ORIENTATION_PORTRAIT,
        imeVisible = isImeVisible,
        customMaxHeightDp = customMaxHeight?.value,
      )
      .dp

  val screenHeightPx =
    with(density) {
      LocalConfiguration.current.screenHeightDp.dp
        .toPx()
    }
  val decayAnimationSpec = rememberSplineBasedDecay<Float>()
  val anchoredDraggableState =
    remember {
      AnchoredDraggableState(
        initialValue = 1,
        snapAnimationSpec = sheetAnimationSpec,
        decayAnimationSpec = decayAnimationSpec,
        positionalThreshold = { with(density) { 56.dp.toPx() } },
        velocityThreshold = { with(density) { 125.dp.toPx() } },
      )
    }

  val scaledSwipeOffset = swipeOffset * 2f
  val height =
    if (anchoredDraggableState.anchors.size >
      0
    ) {
      anchoredDraggableState.anchors.positionOf(1)
    } else {
      screenHeightPx
    }
  val swipeProgress = if (height > 0) (-scaledSwipeOffset / height).coerceIn(0f, 1f) else 0f
  val targetAlpha =
    if (isSwipeActive) {
      if (anchoredDraggableState.anchors.size > 0) 0.5f * swipeProgress else 0f
    } else if (anchoredDraggableState.targetValue == 0) {
      0.5f
    } else {
      0f
    }
  val alpha by animateFloatAsState(
    targetAlpha,
    animationSpec = sheetAnimationSpec,
    label = "alpha",
  )

  val internalOnDismissRequest = {
    if (anchoredDraggableState.currentValue == 0) {
      scope.launch {
        anchoredDraggableState.animateTo(1)
      }
    }
  }
  val sheetShape = MaterialTheme.shapes.extraLarge.copy(
    bottomEnd = ZeroCornerSize,
    bottomStart = ZeroCornerSize,
  )
  Box(
    modifier =
      Modifier
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = internalOnDismissRequest,
        ).fillMaxSize()
        .background(Color.Black.copy(alpha))
        .onSizeChanged {
          val anchors =
            DraggableAnchors {
              0 at 0f
              1 at it.height.toFloat()
            }
          anchoredDraggableState.updateAnchors(anchors)
        },
    contentAlignment = Alignment.BottomCenter,
  ) {
    Surface(
      modifier =
        Modifier
          // Insets belong outside the size constraint. Applying them after
          // sizeIn made the measured glass root taller than the visible sheet,
          // so its upper half was painted over the content on portrait/IME.
          .windowInsetsPadding(
            WindowInsets.systemBars
              .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
          )
          .imePadding()
          .sizeIn(maxWidth = maxWidth, maxHeight = maxHeight)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
          ).nestedScroll(
            remember(anchoredDraggableState) {
              anchoredDraggableState.preUpPostDownNestedScrollConnection()
            },
          ).then(modifier)
          .offset {
            val baseOffset =
              anchoredDraggableState.offset
                .takeIf { it.isFinite() }
                ?: screenHeightPx
            IntOffset(0, baseOffset.roundToInt())
          }.graphicsLayer {
            this.alpha = if (anchoredDraggableState.anchors.size > 0) 1f else 0f
          }.anchoredDraggable(
            state = anchoredDraggableState,
            orientation = Orientation.Vertical,
          )
            // Clip before the glass renderer so its backing cannot escape the rounded rim.
            .clip(sheetShape)
            .liquidGlassPane(
            enabled = liquidGlassSurfaces,
            opacity = 0.86f,
            highlight = 0.20f,
            cornerRadius = 24.dp,
          ),
      shape = sheetShape,
      color = if (liquidGlassSurfaces) Color.Transparent else resolvedSurfaceColor,
      contentColor = if (sheetIsDark) Color.White else Color.Black,
      tonalElevation = tonalElevation,
      content = {
        BackHandler(
          enabled = anchoredDraggableState.targetValue == 0,
          onBack = internalOnDismissRequest,
        )
        content()
      },
    )

    LaunchedEffect(scaledSwipeOffset, isSwipeActive) {
      if (isSwipeActive && anchoredDraggableState.anchors.size > 0) {
        val targetOffset = (height + scaledSwipeOffset).coerceIn(0f, screenHeightPx)
        val delta = targetOffset - anchoredDraggableState.offset
        anchoredDraggableState.dispatchRawDelta(delta)
      }
    }

    var wasSwipeActive by remember { mutableStateOf(false) }
    LaunchedEffect(anchoredDraggableState, isSwipeActive) {
      if (isSwipeActive) {
        wasSwipeActive = true
      } else {
        if (wasSwipeActive) {
          val currentOffset = anchoredDraggableState.offset
          // Settle to open (0) if the user dragged up by more than 10% of the sheet height
          val target = if (currentOffset >= height * 0.9f) 1 else 0
          anchoredDraggableState.animateTo(target)
          if (target == 1) {
            latestOnDismissRequest()
          }
          wasSwipeActive = false
        } else {
          // Title tap opening: wait for anchors to be measured before animating open (0)
          snapshotFlow { anchoredDraggableState.anchors.size }
            .filter { it > 0 }
            .collectLatest {
              anchoredDraggableState.animateTo(0)
            }
        }
      }
    }

    var wasOpened by remember { mutableStateOf(false) }
    LaunchedEffect(anchoredDraggableState) {
      snapshotFlow { anchoredDraggableState.currentValue }
        .collectLatest { value ->
          if (value == 0) {
            wasOpened = true
          } else if (value == 1 && wasOpened) {
            latestOnDismissRequest()
          }
        }
    }
  }
}

private fun <T> AnchoredDraggableState<T>.preUpPostDownNestedScrollConnection() =
  object : NestedScrollConnection {
    override fun onPreScroll(
      available: Offset,
      source: NestedScrollSource,
    ): Offset {
      val delta = available.toFloat()
      return if (delta < 0 && source == NestedScrollSource.UserInput) {
        dispatchRawDelta(delta).toOffset()
      } else {
        Offset.Zero
      }
    }

    override fun onPostScroll(
      consumed: Offset,
      available: Offset,
      source: NestedScrollSource,
    ): Offset =
      if (source == NestedScrollSource.UserInput) {
        dispatchRawDelta(available.toFloat()).toOffset()
      } else {
        Offset.Zero
      }

    override suspend fun onPreFling(available: Velocity): Velocity {
      val toFling = available.toFloat()
      return if (toFling < 0 && offset > anchors.minPosition()) {
        settle(toFling)
        available
      } else {
        Velocity.Zero
      }
    }

    override suspend fun onPostFling(
      consumed: Velocity,
      available: Velocity,
    ): Velocity {
      val toFling = available.toFloat()
      return if (toFling > 0) {
        settle(toFling)
        available
      } else {
        Velocity.Zero
      }
    }

    private fun Float.toOffset(): Offset = Offset(0f, this)

    @JvmName("velocityToFloat")
    private fun Velocity.toFloat() = y

    private fun Offset.toFloat(): Float = y
  }
