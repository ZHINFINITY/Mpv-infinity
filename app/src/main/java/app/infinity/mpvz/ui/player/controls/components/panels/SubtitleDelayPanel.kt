/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.panels

import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.SubtitleLane

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.SubtitlesPreferences
import app.infinity.mpvz.presentation.components.OutlinedNumericChooser
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.theme.spacing
import app.infinity.mpvz.ui.utils.currentMpvConfigOverrideOptions
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun SubtitleDelayPanel(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val preferences = koinInject<SubtitlesPreferences>()
  val configOwnedOptions = currentMpvConfigOverrideOptions()
  var selectedLane by remember { mutableStateOf(SubtitleLane.Primary) }
  val delayProperty = if (selectedLane == SubtitleLane.Primary) "sub-delay" else "secondary-sub-delay"
  val speedProperty = if (selectedLane == SubtitleLane.Primary) "sub-speed" else "secondary-sub-speed"
  val delayEnabled = delayProperty !in configOwnedOptions
  val speedEnabled = speedProperty !in configOwnedOptions
  val defaultDelayMs =
    if (selectedLane == SubtitleLane.Primary) {
      preferences.defaultSubDelay.get()
    } else {
      preferences.defaultSecondarySubDelay.get().takeUnless { it == Int.MIN_VALUE }
        ?: preferences.defaultSubDelay.get()
    }
  val defaultSpeed =
    if (selectedLane == SubtitleLane.Primary) {
      preferences.defaultSubSpeed.get()
    } else {
      preferences.defaultSecondarySubSpeed.get().takeIf { it in 0.1f..10f }
        ?: preferences.defaultSubSpeed.get()
    }

  DraggablePanel(
    modifier = modifier,
    header = {
      SubtitleDelayTitle(onClose = onDismissRequest)
    },
  ) {
    val delay by PlaybackSession.propDouble[delayProperty].collectAsState()
    val delayFloat by remember(delay, defaultDelayMs) {
      derivedStateOf { (delay ?: defaultDelayMs / 1000.0).toFloat() }
    }
    val speed by PlaybackSession.propDouble[speedProperty].collectAsState()
    val speedFloat by remember(speed, defaultSpeed) {
      derivedStateOf { (speed ?: defaultSpeed.toDouble()).toFloat() }
    }

    // We unwrap the card content here because DraggablePanel already provides the card
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller)) {
      SubtitleLaneSelector(selected = selectedLane, onSelected = { selectedLane = it })
      SubtitleDelayCardContent(
        delay = delayFloat,
        onDelayChange = { PlaybackSession.setPropertyDouble(delayProperty, it.toDouble()) },
        speed = speedFloat,
        onSpeedChange = { PlaybackSession.setPropertyDouble(speedProperty, it.toDouble()) },
        onApply = {
          if (delayEnabled) {
            val value = (delayFloat * 1000).roundToInt()
            if (selectedLane == SubtitleLane.Primary) preferences.defaultSubDelay.set(value)
            else preferences.defaultSecondarySubDelay.set(value)
          }
          if (speedEnabled && speedFloat in 0.1f..10f) {
            if (selectedLane == SubtitleLane.Primary) preferences.defaultSubSpeed.set(speedFloat)
            else preferences.defaultSecondarySubSpeed.set(speedFloat)
          }
        },
        onReset = {
          if (delayEnabled) PlaybackSession.setPropertyDouble(delayProperty, defaultDelayMs / 1000.0)
          if (speedEnabled) PlaybackSession.setPropertyDouble(speedProperty, defaultSpeed.toDouble())
        },
        delayEnabled = delayEnabled,
        speedEnabled = speedEnabled,
      )
    }
  }
}

// Extracted content to avoid nested cards since DraggablePanel has a Card
@Composable
private fun SubtitleDelayCardContent(
  delay: Float,
  onDelayChange: (Float) -> Unit,
  speed: Float,
  onSpeedChange: (Float) -> Unit,
  onApply: () -> Unit,
  onReset: () -> Unit,
  delayEnabled: Boolean,
  speedEnabled: Boolean,
) {
  DelayCardContent(
    delay = delay,
    onDelayChange = onDelayChange,
    onApply = onApply,
    onReset = onReset,
    delayType = DelayType.Subtitle,
    delayControlEnabled = delayEnabled,
    actionsEnabled = delayEnabled || speedEnabled,
    extraSettings = {
      OutlinedNumericChooser(
        label = { Text(stringResource(R.string.player_sheets_sub_delay_card_speed)) },
        value = speed,
        onChange = onSpeedChange,
        max = 10f,
        step = 0.01f,
        min = 0.1f,
        increaseIcon = Icons.RoundedFilled.Add,
        decreaseIcon = Icons.RoundedFilled.Remove,
        valueFormatter = { "%.2f".format(it) },
        enabled = speedEnabled,
      )
    },
  )
}

@Suppress("LambdaParameterInRestartableEffect")
@Composable
fun DelayCardContent( // Renamed from DelayCard and removed the Card wrapper
  delay: Float,
  onDelayChange: (Float) -> Unit,
  onApply: () -> Unit,
  onReset: () -> Unit,
  delayType: DelayType,
  delayControlEnabled: Boolean = true,
  actionsEnabled: Boolean = true,
  extraSettings: @Composable ColumnScope.() -> Unit = {},
) {
  // Note: verticalScroll is now handled by DraggablePanel
  Column(
    Modifier.padding(MaterialTheme.spacing.medium),
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    OutlinedNumericChooser(
      label = { Text(stringResource(R.string.player_sheets_sub_delay_card_delay)) },
      value = delay,
      onChange = onDelayChange,
      step = 0.1f,
      min = Float.NEGATIVE_INFINITY,
      max = Float.POSITIVE_INFINITY,
      suffix = {
        Text(
          androidx.compose.ui.res
            .stringResource(app.infinity.mpvz.R.string.ui_s),
        )
      },
      increaseIcon = Icons.RoundedFilled.Add,
      decreaseIcon = Icons.RoundedFilled.Remove,
      valueFormatter = { "%.1f".format(it) },
      enabled = delayControlEnabled,
    )
    Column(
      modifier = Modifier.animateContentSize(),
    ) { extraSettings() }
    // true (heard -> spotted), false (spotted -> heard)
    var isDirectionPositive by remember { mutableStateOf<Boolean?>(null) }
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      var timerStart by remember { mutableStateOf<Long?>(null) }
      var finalDelay by remember { mutableStateOf(delay) }
      LaunchedEffect(isDirectionPositive) {
        if (isDirectionPositive == null) {
          onDelayChange(finalDelay)
          return@LaunchedEffect
        }
        finalDelay = delay
        val startTime = System.currentTimeMillis()
        timerStart = startTime
        val startingDelay: Float = finalDelay
        while (isDirectionPositive != null && timerStart != null) {
          val elapsed = System.currentTimeMillis() - startTime
          val direction = isDirectionPositive ?: break
          finalDelay = startingDelay + (if (direction) elapsed / 1000f else -elapsed / 1000f)
          // Arbitrary delay of 20ms
          delay(20)
        }
      }
      Button(
        onClick = {
          isDirectionPositive = if (isDirectionPositive == null) delayType == DelayType.Audio else null
        },
        modifier = Modifier.weight(1f),
        enabled = delayControlEnabled && isDirectionPositive != (delayType == DelayType.Audio),
      ) {
        Text(
          stringResource(
            if (delayType == DelayType.Audio) {
              R.string.player_sheets_sub_delay_audio_sound_heard
            } else {
              R.string.player_sheets_sub_delay_subtitle_voice_heard
            },
          ),
        )
      }
      Button(
        onClick = {
          isDirectionPositive = if (isDirectionPositive == null) delayType != DelayType.Audio else null
        },
        modifier = Modifier.weight(1f),
        enabled = delayControlEnabled && isDirectionPositive != (delayType == DelayType.Subtitle),
      ) {
        Text(
          stringResource(
            if (delayType == DelayType.Audio) {
              R.string.player_sheets_sub_delay_sound_sound_spotted
            } else {
              R.string.player_sheets_sub_delay_subtitle_text_seen
            },
          ),
        )
      }
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      Button(
        onClick = onApply,
        modifier = Modifier.weight(1f),
        enabled = actionsEnabled && isDirectionPositive == null,
      ) {
        Text(stringResource(R.string.player_sheets_delay_set_as_default))
      }
      FilledIconButton(
        onClick = onReset,
        enabled = actionsEnabled && isDirectionPositive == null,
      ) {
        Icon(Icons.RoundedFilled.Refresh, null)
      }
    }
  }
}

@Composable
fun SubtitleDelayTitle(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.medium)
        .padding(top = MaterialTheme.spacing.small),
  ) {
    Text(
      stringResource(R.string.player_sheets_sub_delay_card_title),
      style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.weight(1f))
    IconButton(onClick = onClose) {
      Icon(Icons.RoundedFilled.Close, null, modifier = Modifier.size(32.dp))
    }
  }
}

enum class DelayType {
  Audio,
  Subtitle,
}
