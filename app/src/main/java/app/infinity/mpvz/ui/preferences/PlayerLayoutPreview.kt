/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.preferences

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.infinity.mpvz.preferences.PlayerButton
import app.infinity.mpvz.preferences.PlaybackControlsPosition
import app.infinity.mpvz.ui.icons.Icon

@Composable
fun PlayerLayoutPreview(
  topRightButtons: List<PlayerButton>,
  bottomLeftButtons: List<PlayerButton>,
  bottomRightButtons: List<PlayerButton>,
  position: PlaybackControlsPosition,
  activeRegion: ControlRegion? = null,
  portraitBottomButtons: List<PlayerButton> = emptyList(),
  modifier: Modifier = Modifier,
) {
  val cardShape = RoundedCornerShape(24.dp)
  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 10.dp)
      .clip(cardShape)
      .background(
        Brush.linearGradient(
          listOf(Color(0xFF06111F), Color(0xFF101D38), Color(0xFF15102F)),
        ),
      ).border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), cardShape)
      .height(206.dp),
  ) {
    Canvas(Modifier.fillMaxSize()) {
      drawRect(
        Brush.radialGradient(
          colors = listOf(Color(0xFF347CE5).copy(alpha = 0.34f), Color.Transparent),
          center = Offset(size.width * 0.52f, size.height * 0.53f),
          radius = size.minDimension * 0.62f,
        ),
      )
      val center = Offset(size.width * 0.52f, size.height * 0.53f)
      val loopWidth = size.width * 0.19f
      val loopHeight = size.height * 0.31f
      val infinity = Path().apply {
        moveTo(center.x, center.y)
        cubicTo(center.x - loopWidth, center.y - loopHeight, center.x - loopWidth, center.y + loopHeight, center.x, center.y)
        cubicTo(center.x + loopWidth, center.y - loopHeight, center.x + loopWidth, center.y + loopHeight, center.x, center.y)
      }
      drawPath(
        path = infinity,
        color = Color(0xFF59B8FF).copy(alpha = 0.13f),
        style = Stroke(width = size.minDimension * 0.16f, cap = StrokeCap.Round),
      )
      drawPath(
        path = infinity,
        brush = Brush.linearGradient(
          colors = listOf(Color(0xFF7BE4FF).copy(alpha = 0.62f), Color(0xFF8C76FF).copy(alpha = 0.60f)),
          start = Offset(center.x - loopWidth, center.y - loopHeight),
          end = Offset(center.x + loopWidth, center.y + loopHeight),
        ),
        style = Stroke(width = size.minDimension * 0.024f, cap = StrokeCap.Round),
      )
    }

    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column {
          Text("LIVE PLAYER PREVIEW", color = Color.White.copy(alpha = 0.78f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
          Text("Infinity layout", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(position.displayName, color = Color.White.copy(alpha = 0.84f), fontSize = 10.sp)
          PreviewIconStrip(
            buttons = topRightButtons,
            modifier = Modifier.widthIn(max = 88.dp),
            reverseScrolling = true,
            scrollable = true,
            highlighted = activeRegion == ControlRegion.TOP_RIGHT,
          )
        }
      }

      Spacer(Modifier.weight(1f))

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        PreviewTransportControl("⟲")
        Surface(
          modifier = Modifier.padding(horizontal = 12.dp).size(38.dp),
          shape = CircleShape,
          color = Color.White.copy(alpha = 0.23f),
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.42f)),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text("▶", color = Color.White, fontSize = 16.sp)
          }
        }
        PreviewTransportControl("⟳")
      }

      Spacer(Modifier.weight(1f))

      AnimatedVisibility(
        visible = position == PlaybackControlsPosition.AboveSeekbar,
        enter =
          fadeIn(tween(190)) +
            expandVertically(animationSpec = tween(190), expandFrom = Alignment.Bottom) +
            slideInVertically(animationSpec = tween(190)) { it / 4 },
        exit =
          fadeOut(tween(140)) +
            shrinkVertically(animationSpec = tween(140), shrinkTowards = Alignment.Top) +
            slideOutVertically(animationSpec = tween(140)) { -it / 4 },
      ) {
        PreviewShortcutRows(
          leftButtons = bottomLeftButtons,
          rightButtons = bottomRightButtons,
          portraitButtons = portraitBottomButtons,
          activeRegion = activeRegion,
          scrollable = false,
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("0:42", color = Color.White.copy(alpha = 0.82f), fontSize = 9.sp)
        Box(
          modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.32f)),
        ) {
          Box(
            modifier = Modifier.fillMaxWidth(0.38f).fillMaxSize().clip(CircleShape)
              .background(Brush.horizontalGradient(listOf(Color(0xFF8FE7FF), Color(0xFF9C8BFF)))),
          )
        }
        Text("1:58", color = Color.White.copy(alpha = 0.82f), fontSize = 9.sp)
      }
      AnimatedVisibility(
        visible = position == PlaybackControlsPosition.BelowSeekbar,
        enter =
          fadeIn(tween(190)) +
            expandVertically(animationSpec = tween(190), expandFrom = Alignment.Top) +
            slideInVertically(animationSpec = tween(190)) { -it / 4 },
        exit =
          fadeOut(tween(140)) +
            shrinkVertically(animationSpec = tween(140), shrinkTowards = Alignment.Bottom) +
            slideOutVertically(animationSpec = tween(140)) { it / 4 },
      ) {
        PreviewShortcutRows(
          leftButtons = bottomLeftButtons,
          rightButtons = bottomRightButtons,
          portraitButtons = portraitBottomButtons,
          activeRegion = activeRegion,
          scrollable = true,
        )
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
  scrollable: Boolean,
) {
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val halfMaxWidth = maxWidth / 2
    if (activeRegion == ControlRegion.PORTRAIT_BOTTOM) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
      ) {
        PreviewIconStrip(
          buttons = portraitButtons,
          modifier = Modifier.widthIn(max = maxWidth),
          reverseScrolling = false,
          scrollable = scrollable,
          highlighted = true,
        )
      }
    } else {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        if (leftButtons.isNotEmpty()) {
          PreviewIconStrip(
            buttons = leftButtons,
            modifier = Modifier.widthIn(max = halfMaxWidth),
            reverseScrolling = false,
            scrollable = scrollable,
            highlighted = activeRegion == ControlRegion.BOTTOM_LEFT,
          )
        }
        if (rightButtons.isNotEmpty()) {
          PreviewIconStrip(
            buttons = rightButtons,
            modifier = Modifier.widthIn(max = halfMaxWidth),
            reverseScrolling = true,
            scrollable = scrollable,
            highlighted = activeRegion == ControlRegion.BOTTOM_RIGHT,
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewIconStrip(
  buttons: List<PlayerButton>,
  modifier: Modifier = Modifier,
  reverseScrolling: Boolean,
  scrollable: Boolean,
  highlighted: Boolean = false,
) {
  if (buttons.isEmpty()) return
  if (scrollable) {
    Row(
      modifier = modifier.height(30.dp).horizontalScroll(rememberScrollState(), reverseScrolling).animateContentSize(tween(180)),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      buttons.forEach { button ->
        PreviewIconButton(button, highlighted)
      }
    }
  } else {
    androidx.compose.foundation.layout.FlowRow(
      modifier = modifier.animateContentSize(tween(180)),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      buttons.forEach { button ->
        PreviewIconButton(button, highlighted)
      }
    }
  }
}

@Composable
private fun PreviewIconButton(button: PlayerButton, highlighted: Boolean) {
  val activeTint = Color(0xFF75D9FF)
  Surface(
    modifier = Modifier.size(28.dp),
    shape = CircleShape,
    color = if (highlighted) activeTint.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.16f),
    border = BorderStroke(0.8.dp, if (highlighted) activeTint.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.28f)),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(imageVector = button.icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
    }
  }
}

@Composable
private fun PreviewTransportControl(symbol: String) {
  Surface(
    modifier = Modifier.size(28.dp),
    shape = CircleShape,
    color = Color.White.copy(alpha = 0.16f),
    border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.22f)),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(symbol, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
    }
  }
}
