/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.preferences

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.infinity.mpvz.preferences.PlayerButton
import app.infinity.mpvz.preferences.PlaybackControlsPosition
import app.infinity.mpvz.ui.icons.AppIcon
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.controls.PlayerControlsGeometry
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlayerLayoutPreview(
  topRightButtons: List<PlayerButton>,
  bottomLeftButtons: List<PlayerButton>,
  bottomRightButtons: List<PlayerButton>,
  position: PlaybackControlsPosition,
  activeRegion: ControlRegion? = null,
  portraitBottomButtons: List<PlayerButton> = emptyList(),
  modifier: Modifier = Modifier,
  onPreviewButtonTap: ((PlayerButton) -> Unit)? = null,
  onPreviewReorder: ((PlayerButton, PlayerButton) -> Unit)? = null,
) {
  val configuration = LocalConfiguration.current
  val isPortraitPreview = activeRegion == ControlRegion.PORTRAIT_BOTTOM
  val shortScreenEdge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(1)
  val longScreenEdge = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(1)
  val referenceWidth = if (isPortraitPreview) shortScreenEdge else longScreenEdge
  val landscapeAspect = (longScreenEdge.toFloat() / shortScreenEdge).coerceIn(1.65f, 1.95f)
  val frameShape = RoundedCornerShape(18.dp)
  val previewProgress = remember { mutableFloatStateOf(0.38f) }
  var simulatedPlaying by remember { mutableStateOf(false) }
  var simulatedActiveButtons by remember { mutableStateOf(emptySet<PlayerButton>()) }

  BoxWithConstraints(
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    contentAlignment = Alignment.Center,
  ) {
    val frameWidth = minOf(maxWidth, if (isPortraitPreview) 190.dp else 540.dp)
    val frameScale = (frameWidth.value / referenceWidth.toFloat()).coerceIn(0.32f, 0.72f)
    val frameAspect = if (isPortraitPreview) 0.58f else landscapeAspect
    val iconTouchSize = maxOf(44.dp, 46.dp * frameScale)
    val iconVisualSize = maxOf(18.dp, 25.dp * frameScale)
    val skipVisualSize = maxOf(20.dp, PlayerControlsGeometry.PlaylistSkipButtonSize * frameScale)
    val playVisualSize = maxOf(24.dp, PlayerControlsGeometry.PlayPauseButtonSize * frameScale)
    val transportTouchSize = maxOf(44.dp, playVisualSize + 10.dp)
    val activeTint = Color(0xFFB9D9F1)

    Box(
      modifier =
        Modifier
          .width(frameWidth)
          .aspectRatio(frameAspect)
          .clip(frameShape)
          .background(Brush.verticalGradient(listOf(Color(0xFF17212A), Color(0xFF090C10))))
          .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), frameShape),
    ) {
      Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 9.dp * frameScale, vertical = 7.dp * frameScale),
        verticalArrangement = Arrangement.spacedBy(3.dp * frameScale),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp * frameScale),
          ) {
            Text("‹", color = Color.White, fontSize = 20.sp * frameScale, lineHeight = 20.sp * frameScale)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp * frameScale)) {
              Text(
                text = "VIDEO PLAYER",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = maxSp(6f, 7f * frameScale),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp * frameScale,
                maxLines = 1,
              )
              Text(
                text = position.displayName.uppercase(),
                color = Color.White.copy(alpha = 0.88f),
                fontSize = maxSp(6f, 8f * frameScale),
                maxLines = 1,
              )
            }
          }
          if (!isPortraitPreview) {
            PreviewIconStrip(
              buttons = topRightButtons,
              modifier = Modifier.widthIn(max = frameWidth * 0.38f),
              reverseScrolling = true,
              highlighted = activeRegion == ControlRegion.TOP_RIGHT,
              editable = activeRegion == ControlRegion.TOP_RIGHT && onPreviewButtonTap != null,
              touchSize = iconTouchSize,
              visualSize = iconVisualSize,
              simulatedActiveButtons = simulatedActiveButtons,
              onButtonTap = { button, editable ->
                if (editable) {
                  onPreviewButtonTap?.invoke(button)
                } else {
                  simulatedActiveButtons = togglePreviewButton(simulatedActiveButtons, button)
                }
              },
              onReorder = onPreviewReorder,
            )
          }
        }

        Spacer(Modifier.weight(1f))

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
        ) {
          PreviewTransportButton(
            imageVector = Icons.RoundedFilled.SkipPrevious,
            visualSize = skipVisualSize,
            touchSize = transportTouchSize,
            description = "Previous in simulated preview",
            onClick = { previewProgress.floatValue = (previewProgress.floatValue - 0.12f).coerceAtLeast(0f) },
          )
          Spacer(Modifier.width(PlayerControlsGeometry.PlaylistButtonGap * frameScale))
          PreviewTransportButton(
            imageVector = if (simulatedPlaying) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
            visualSize = playVisualSize,
            touchSize = transportTouchSize,
            description = "Toggle simulated playback",
            emphasized = true,
            onClick = { simulatedPlaying = !simulatedPlaying },
          )
          Spacer(Modifier.width(PlayerControlsGeometry.PlaylistButtonGap * frameScale))
          PreviewTransportButton(
            imageVector = Icons.RoundedFilled.SkipNext,
            visualSize = skipVisualSize,
            touchSize = transportTouchSize,
            description = "Next in simulated preview",
            onClick = { previewProgress.floatValue = (previewProgress.floatValue + 0.12f).coerceAtMost(1f) },
          )
        }

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(
          visible = position == PlaybackControlsPosition.AboveSeekbar,
          enter =
            fadeIn(tween(150)) +
              expandVertically(animationSpec = tween(150), expandFrom = Alignment.Bottom) +
              slideInVertically(animationSpec = tween(150)) { it / 5 },
          exit =
            fadeOut(tween(120)) +
              shrinkVertically(animationSpec = tween(120), shrinkTowards = Alignment.Top) +
              slideOutVertically(animationSpec = tween(120)) { -it / 5 },
        ) {
          PreviewShortcutRows(
            leftButtons = bottomLeftButtons,
            rightButtons = bottomRightButtons,
            portraitButtons = portraitBottomButtons,
            activeRegion = activeRegion,
            iconTouchSize = iconTouchSize,
            iconVisualSize = iconVisualSize,
            simulatedActiveButtons = simulatedActiveButtons,
            onButtonTap = { button, editable ->
              if (editable) {
                onPreviewButtonTap?.invoke(button)
              } else {
                simulatedActiveButtons = togglePreviewButton(simulatedActiveButtons, button)
              }
            },
            onReorder = onPreviewReorder,
          )
        }

        PreviewSeekbar(
          progress = previewProgress,
          frameScale = frameScale,
        )

        AnimatedVisibility(
          visible = position == PlaybackControlsPosition.BelowSeekbar,
          enter =
            fadeIn(tween(150)) +
              expandVertically(animationSpec = tween(150), expandFrom = Alignment.Top) +
              slideInVertically(animationSpec = tween(150)) { -it / 5 },
          exit =
            fadeOut(tween(120)) +
              shrinkVertically(animationSpec = tween(120), shrinkTowards = Alignment.Bottom) +
              slideOutVertically(animationSpec = tween(120)) { it / 5 },
        ) {
          PreviewShortcutRows(
            leftButtons = bottomLeftButtons,
            rightButtons = bottomRightButtons,
            portraitButtons = portraitBottomButtons,
            activeRegion = activeRegion,
            iconTouchSize = iconTouchSize,
            iconVisualSize = iconVisualSize,
            simulatedActiveButtons = simulatedActiveButtons,
            onButtonTap = { button, editable ->
              if (editable) {
                onPreviewButtonTap?.invoke(button)
              } else {
                simulatedActiveButtons = togglePreviewButton(simulatedActiveButtons, button)
              }
            },
            onReorder = onPreviewReorder,
          )
        }
      }
    }
  }
}

@Composable
private fun PreviewShortcutRows(
  leftButtons: List<PlayerButton>,
  rightButtons: List<PlayerButton>,
  portraitButtons: List<PlayerButton>,
  activeRegion: ControlRegion?,
  iconTouchSize: Dp,
  iconVisualSize: Dp,
  simulatedActiveButtons: Set<PlayerButton>,
  onButtonTap: (PlayerButton, Boolean) -> Unit,
  onReorder: ((PlayerButton, PlayerButton) -> Unit)?,
) {
  if (activeRegion == ControlRegion.PORTRAIT_BOTTOM) {
    PreviewIconStrip(
      buttons = portraitButtons,
      modifier = Modifier.fillMaxWidth(),
      reverseScrolling = false,
      highlighted = true,
      editable = onReorder != null,
      touchSize = iconTouchSize,
      visualSize = iconVisualSize,
      simulatedActiveButtons = simulatedActiveButtons,
      onButtonTap = onButtonTap,
      onReorder = onReorder,
    )
  } else {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      if (leftButtons.isNotEmpty()) {
        PreviewIconStrip(
          buttons = leftButtons,
          modifier = Modifier.weight(1f),
          reverseScrolling = false,
          highlighted = activeRegion == ControlRegion.BOTTOM_LEFT,
          editable = activeRegion == ControlRegion.BOTTOM_LEFT && onReorder != null,
          touchSize = iconTouchSize,
          visualSize = iconVisualSize,
          simulatedActiveButtons = simulatedActiveButtons,
          onButtonTap = onButtonTap,
          onReorder = onReorder,
        )
      }
      if (rightButtons.isNotEmpty()) {
        PreviewIconStrip(
          buttons = rightButtons,
          modifier = Modifier.weight(1f),
          reverseScrolling = true,
          highlighted = activeRegion == ControlRegion.BOTTOM_RIGHT,
          editable = activeRegion == ControlRegion.BOTTOM_RIGHT && onReorder != null,
          touchSize = iconTouchSize,
          visualSize = iconVisualSize,
          simulatedActiveButtons = simulatedActiveButtons,
          onButtonTap = onButtonTap,
          onReorder = onReorder,
        )
      }
    }
  }
}

@Composable
private fun PreviewIconStrip(
  buttons: List<PlayerButton>,
  modifier: Modifier = Modifier,
  reverseScrolling: Boolean,
  highlighted: Boolean,
  editable: Boolean,
  touchSize: Dp,
  visualSize: Dp,
  simulatedActiveButtons: Set<PlayerButton>,
  onButtonTap: (PlayerButton, Boolean) -> Unit,
  onReorder: ((PlayerButton, PlayerButton) -> Unit)?,
) {
  if (buttons.isEmpty()) return
  val listState = rememberLazyListState()
  val currentButtons by rememberUpdatedState(buttons)
  val currentOnReorder by rememberUpdatedState(onReorder)
  val currentEditable by rememberUpdatedState(editable)
  val reorderState =
    rememberReorderableLazyListState(listState) { from, to ->
      val latestButtons = currentButtons
      val reorder = currentOnReorder
      if (currentEditable && reorder != null) {
        val fromButton = latestButtons.getOrNull(from.index)
        val toButton = latestButtons.getOrNull(to.index)
        if (fromButton != null && toButton != null && fromButton != toButton) {
          reorder(fromButton, toButton)
        }
      }
    }
  val stripShape = RoundedCornerShape(8.dp)

  LazyRow(
    state = listState,
    reverseLayout = reverseScrolling,
    modifier =
      modifier
        .heightIn(min = touchSize)
        .clip(stripShape)
        .previewScrollHints(listState, reverseLayout = reverseScrolling)
        .then(
          if (highlighted) {
            Modifier.border(BorderStroke(1.dp, Color(0xFFB9D9F1).copy(alpha = 0.54f)), stripShape)
          } else {
            Modifier
          },
        ),
    horizontalArrangement =
      Arrangement.spacedBy(
        3.dp,
        if (reverseScrolling) Alignment.End else Alignment.Start,
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    items(buttons, key = { it }) { button ->
      val shouldReorder = editable && onReorder != null && buttons.size > 1
      if (shouldReorder) {
        ReorderableItem(reorderState, key = button) { isDragging ->
          PreviewIconButton(
            button = button,
            touchSize = touchSize,
            visualSize = visualSize,
            highlighted = highlighted,
            simulatedActive = button in simulatedActiveButtons,
            isDragging = isDragging,
            editable = editable,
            modifier = Modifier.draggableHandle(),
            onClick = { onButtonTap(button, editable) },
          )
        }
      } else {
        PreviewIconButton(
          button = button,
          touchSize = touchSize,
          visualSize = visualSize,
          highlighted = highlighted,
          simulatedActive = button in simulatedActiveButtons,
          isDragging = false,
          editable = editable,
          onClick = { onButtonTap(button, editable) },
        )
      }
    }
  }
}

@Composable
private fun PreviewIconButton(
  button: PlayerButton,
  touchSize: Dp,
  visualSize: Dp,
  highlighted: Boolean,
  simulatedActive: Boolean,
  isDragging: Boolean,
  editable: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  val activeTint = Color(0xFFB9D9F1)
  val label = button.name.lowercase().replace('_', ' ')
  val actionLabel = if (editable) "Remove $label from layout preview" else "Simulate $label control"
  Box(
    modifier =
      modifier
        .size(touchSize)
        .clickable(
          onClickLabel = actionLabel,
          role = Role.Button,
          onClick = onClick,
        ),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      modifier = Modifier.size(if (isDragging) visualSize * 1.12f else visualSize),
      shape = CircleShape,
      color =
        when {
          isDragging -> activeTint.copy(alpha = 0.58f)
          highlighted -> activeTint.copy(alpha = 0.22f)
          simulatedActive -> activeTint.copy(alpha = 0.40f)
          else -> Color.White.copy(alpha = 0.10f)
        },
      border =
        BorderStroke(
          0.8.dp,
          if (highlighted || simulatedActive || isDragging) activeTint.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.22f),
        ),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = button.icon,
          contentDescription = label,
          modifier = Modifier.size(maxOf(10.dp, visualSize * 0.52f)),
          tint = Color.White,
        )
      }
    }
  }
}

@Composable
private fun PreviewTransportButton(
  imageVector: AppIcon,
  visualSize: Dp,
  touchSize: Dp,
  description: String,
  emphasized: Boolean = false,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .size(touchSize)
        .clickable(
          onClickLabel = description,
          role = Role.Button,
          onClick = onClick,
        ),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      modifier = Modifier.size(visualSize),
      shape = CircleShape,
      color = if (emphasized) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.10f),
      border = BorderStroke(0.9.dp, Color.White.copy(alpha = if (emphasized) 0.48f else 0.28f)),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = imageVector,
          contentDescription = description,
          modifier = Modifier.size(maxOf(11.dp, visualSize * 0.50f)),
          tint = Color.White,
        )
      }
    }
  }
}

@Composable
private fun PreviewSeekbar(
  progress: androidx.compose.runtime.MutableFloatState,
  frameScale: Float,
) {
  val thumbSize = maxOf(6.dp, 8.dp * frameScale)
  var widthPx by remember { mutableFloatStateOf(1f) }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp * frameScale),
  ) {
    Text(
      text = formatPreviewTime(progress.floatValue),
      color = Color.White.copy(alpha = 0.86f),
      fontSize = maxSp(6f, 8f * frameScale),
      maxLines = 1,
    )
    BoxWithConstraints(
      modifier =
        Modifier
          .weight(1f)
          .height(maxOf(18.dp, 24.dp * frameScale))
          .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
          .semantics {
            contentDescription = "Simulated playback position"
            stateDescription = "${(progress.floatValue * 100f).roundToInt()} percent"
            progressBarRangeInfo = ProgressBarRangeInfo(progress.floatValue, 0f..1f)
          }
          .pointerInput(widthPx) {
            detectTapGestures { position ->
              progress.floatValue = (position.x / widthPx).coerceIn(0f, 1f)
            }
          }
          .pointerInput(widthPx) {
            detectDragGestures(
              onDragStart = { position ->
                progress.floatValue = (position.x / widthPx).coerceIn(0f, 1f)
              },
              onDrag = { change, _ ->
                progress.floatValue = (change.position.x / widthPx).coerceIn(0f, 1f)
                change.consume()
              },
            )
          },
      contentAlignment = Alignment.CenterStart,
    ) {
      val progressWidth = maxWidth * progress.floatValue
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(maxOf(2.dp, 3.dp * frameScale))
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.28f)),
      )
      Box(
        modifier =
          Modifier
            .width(progressWidth.coerceAtLeast(0.dp))
            .height(maxOf(2.dp, 3.dp * frameScale))
            .clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(Color(0xFFB8D9F2), Color(0xFFECF3FA)))),
      )
      Box(
        modifier =
          Modifier
            .offset(x = (maxWidth - thumbSize).coerceAtLeast(0.dp) * progress.floatValue)
            .size(thumbSize)
            .background(Color.White, CircleShape),
      )
    }
    Text(
      text = "1:58",
      color = Color.White.copy(alpha = 0.86f),
      fontSize = maxSp(6f, 8f * frameScale),
      maxLines = 1,
    )
  }
}

private fun Modifier.previewScrollHints(
  state: LazyListState,
  reverseLayout: Boolean,
): Modifier =
  drawWithContent {
    drawContent()
    val fadeWidth = 9.dp.toPx().coerceAtMost(size.width * 0.22f)
    if (fadeWidth <= 0f || size.height <= 0f) return@drawWithContent
    val sceneEdge = Color(0xFF0B0F14)
    val canFadeLeft = if (reverseLayout) state.canScrollForward else state.canScrollBackward
    val canFadeRight = if (reverseLayout) state.canScrollBackward else state.canScrollForward
    if (canFadeLeft) {
      drawRect(
        brush = Brush.horizontalGradient(listOf(sceneEdge, Color.Transparent), startX = 0f, endX = fadeWidth),
        size = Size(fadeWidth, size.height),
      )
    }
    if (canFadeRight) {
      drawRect(
        brush =
          Brush.horizontalGradient(
            listOf(Color.Transparent, sceneEdge),
            startX = size.width - fadeWidth,
            endX = size.width,
          ),
        topLeft = Offset(size.width - fadeWidth, 0f),
        size = Size(fadeWidth, size.height),
      )
    }
  }

private fun togglePreviewButton(
  active: Set<PlayerButton>,
  button: PlayerButton,
): Set<PlayerButton> =
  if (button in active) active - button else active + button

private fun maxSp(minimumSp: Float, candidateSp: Float) = maxOf(minimumSp, candidateSp).sp

private fun formatPreviewTime(progress: Float): String {
  val seconds = (progress.coerceIn(0f, 1f) * 118f).roundToInt()
  return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
