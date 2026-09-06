/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.controls.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.theme.spacing

private val tabularFigures = "tnum"

@Composable
fun PlayerUpdate(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit = {},
) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border =
      BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
      ),
    modifier =
      modifier
        .height(45.dp)
        .animateContentSize(),
  ) {
    Box(
      modifier =
        Modifier.padding(
          vertical = MaterialTheme.spacing.small,
          horizontal = MaterialTheme.spacing.medium,
        ),
      contentAlignment = Alignment.Center,
    ) {
      content()
    }
  }
}

@Composable
fun TextPlayerUpdate(
  text: String,
  modifier: Modifier = Modifier,
) {
  val stableTextStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = tabularFigures)
  PlayerUpdate(modifier) {
    Text(
      text = text,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurface,
      style = stableTextStyle,
    )
  }
}

@Composable
fun MultipleSpeedPlayerUpdate(
  currentSpeed: Float,
  modifier: Modifier = Modifier,
) {
  TextPlayerUpdate(text = "${currentSpeed.formatSpeed()}x", modifier = modifier)
}

@Composable
@Preview
private fun PreviewMultipleSpeedPlayerUpdate() {
  MultipleSpeedPlayerUpdate(currentSpeed = 2f)
}

private fun Float.formatSpeed(): String =
  if (this % 1.0f == 0.0f) {
    this.toInt().toString()
  } else {
    String.format("%.1f", this)
  }

@Composable
fun SeekPlayerUpdate(
  currentTime: String,
  seekDelta: String,
  modifier: Modifier = Modifier,
) {
  val stableTextStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = tabularFigures)
  PlayerUpdate(modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = currentTime,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        style = stableTextStyle,
      )

      Text(
        text = " $seekDelta",
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center,
        style = stableTextStyle,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      )
    }
  }
}


@Composable
fun TranslatedSubtitleText(
  text: String,
  modifier: Modifier = Modifier,
  fontSize: androidx.compose.ui.unit.TextUnit = MaterialTheme.typography.bodyLarge.fontSize,
  textColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White,
  backgroundColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
  outlineColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Black,
  outlineWidth: Float = 0f,
  shadowOffset: Float = 0f,
  bold: Boolean = false,
  italic: Boolean = false,
  textAlign: TextAlign = TextAlign.Center,
) {
  Text(
    text = text,
    modifier = modifier
      .background(
        color = backgroundColor,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
      )
      .padding(horizontal = 10.dp, vertical = 4.dp),
    color = textColor,
    fontSize = fontSize,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
    textAlign = textAlign,
    style = androidx.compose.ui.text.TextStyle(
      fontSize = fontSize,
      lineHeight = fontSize,
      shadow = androidx.compose.ui.graphics.Shadow(
        color = outlineColor,
        blurRadius = (8f + outlineWidth * 2f).coerceAtLeast(1f),
        offset = androidx.compose.ui.geometry.Offset(0f, shadowOffset),
      ),
    ),
  )
}
