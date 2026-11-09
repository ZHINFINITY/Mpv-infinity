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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.infinity.mpvz.preferences.PlayerButton
import app.infinity.mpvz.preferences.PlaybackControlsPosition
import app.infinity.mpvz.ui.components.horizontalScrollFadeEdges
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
  val cardShape = RoundedCornerShape(18.dp)
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .height(172.dp)
        .clip(cardShape)
        .background(Brush.verticalGradient(listOf(Color(0xFF17212A), Color(0xFF090C10))))
        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)), cardShape),
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          Text("‹", color = Color.White, fontSize = 20.sp, lineHeight = 20.sp)
          Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
              text = "VIDEO PLAYER",
              color = Color.White.copy(alpha = 0.62f),
              fontSize = 7.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 0.7.sp,
              maxLines = 1,
            )
            Text(
              text = position.displayName.uppercase(),
              color = Color.White.copy(alpha = 0.82f),
              fontSize = 8.sp,
              maxLines = 1,
            )
          }
        }
        PreviewIconStrip(
          buttons = topRightButtons,
          modifier = Modifier.widthIn(max = 78.dp),
          reverseScrolling = true,
          highlighted = activeRegion == ControlRegion.TOP_RIGHT,
        )
      }

      Spacer(Modifier.weight(1f))

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        PreviewTransportControl("↶")
        Surface(
          modifier = Modifier.padding(horizontal = 10.dp).size(28.dp),
          shape = CircleShape,
          color = Color.White.copy(alpha = 0.16f),
          border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.34f)),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text("▶", color = Color.White, fontSize = 11.sp, lineHeight = 12.sp)
          }
        }
        PreviewTransportControl("↷")
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
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text("0:42", color = Color.White.copy(alpha = 0.80f), fontSize = 8.sp, maxLines = 1)
        Box(
          modifier =
            Modifier
              .weight(1f)
              .height(2.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.30f)),
        ) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth(0.38f)
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(Color(0xFFB8D9F2), Color(0xFFECF3FA)))),
          )
        }
        Text("1:58", color = Color.White.copy(alpha = 0.80f), fontSize = 8.sp, maxLines = 1)
      }

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
) {
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val availableWidth = maxWidth
    val halfMaxWidth = availableWidth / 2
    if (activeRegion == ControlRegion.PORTRAIT_BOTTOM) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
      ) {
        PreviewIconStrip(
          buttons = portraitButtons,
          modifier = Modifier.widthIn(max = availableWidth),
          reverseScrolling = false,
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
            highlighted = activeRegion == ControlRegion.BOTTOM_LEFT,
          )
        }
        if (rightButtons.isNotEmpty()) {
          PreviewIconStrip(
            buttons = rightButtons,
            modifier = Modifier.widthIn(max = halfMaxWidth),
            reverseScrolling = true,
            highlighted = activeRegion == ControlRegion.BOTTOM_RIGHT,
          )
        }
      }
    }
  }
}

@Composable
private fun PreviewIconStrip(
  buttons: List<PlayerButton>,
  modifier: Modifier = Modifier,
  reverseScrolling: Boolean,
  highlighted: Boolean = false,
) {
  if (buttons.isEmpty()) return
  val scrollState = rememberScrollState()
  Row(
    modifier =
      modifier
        .height(22.dp)
        .horizontalScrollFadeEdges(scrollState, reverseScrolling = reverseScrolling, fadeWidth = 10.dp)
        .horizontalScroll(scrollState, reverseScrolling),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    buttons.forEach { button ->
      PreviewIconButton(button, highlighted)
    }
  }
}

@Composable
private fun PreviewIconButton(button: PlayerButton, highlighted: Boolean) {
  val activeTint = Color(0xFFB5DBF4)
  Surface(
    modifier = Modifier.size(18.dp),
    shape = CircleShape,
    color = if (highlighted) activeTint.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.10f),
    border = BorderStroke(0.6.dp, if (highlighted) activeTint.copy(alpha = 0.76f) else Color.White.copy(alpha = 0.18f)),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(imageVector = button.icon, contentDescription = null, modifier = Modifier.size(10.dp), tint = Color.White)
    }
  }
}

@Composable
private fun PreviewTransportControl(symbol: String) {
  Surface(
    modifier = Modifier.size(20.dp),
    shape = CircleShape,
    color = Color.White.copy(alpha = 0.09f),
    border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.20f)),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(symbol, color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, lineHeight = 10.sp)
    }
  }
}
