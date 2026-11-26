/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.sheets

import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.ui.preferences.components.settingsGlassContainerColor
import app.infinity.mpvz.ui.preferences.components.settingsGlassSurface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.runtime.rememberUpdatedState
import app.infinity.mpvz.ui.components.themedSegmentedButtonColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import app.infinity.mpvz.ui.components.IconSwitch
import app.infinity.mpvz.ui.player.controls.components.GlassSlider

enum class EqualizerMode {
  DYNAMIC,
  MANUAL,
}

enum class EqualizerPreset(
  val displayName: String,
  val gains: List<Int>,
) {
  FLAT("Flat", listOf(0, 0, 0, 0, 0, 0, 0)),
  ACOUSTIC("Acoustic", listOf(3, 2, 1, 2, 1, 2, 2)),
  BASS_BOOST("Bass Boost", listOf(8, 6, 4, 2, 0, 0, 0)),
  BASS_CUT("Bass Cut", listOf(-8, -6, -4, 0, 0, 0, 0)),
  VOCAL("Vocal", listOf(-4, -2, 1, 4, 4, 2, 0)),
  TREBLE_BOOST("Treble Boost", listOf(0, 0, 0, 0, 2, 4, 6)),
  TREBLE_CUT("Treble Cut", listOf(0, 0, 0, 0, -2, -4, -6)),
  LOUDNESS("Loudness", listOf(7, 4, 0, -2, 0, 4, 7)),
  SPOKEN_WORD("Spoken Word", listOf(-6, -3, 2, 4, 3, 0, -2)),
  ELECTRONIC("Electronic", listOf(6, 4, 1, 0, 2, 4, 5)),
  ROCK("Rock", listOf(5, 3, -1, -3, 1, 4, 5)),
  HIP_HOP("Hip Hop", listOf(7, 5, 1, -1, 1, 2, 3)),
  JAZZ("Jazz", listOf(4, 2, 0, 1, 2, 3, 4)),
  CLASSICAL("Classical", listOf(4, 3, 2, 0, 2, 3, 4)),
  SMALL_SPEAKERS("Small Speakers", listOf(6, 4, 2, 0, -1, -2, -2)),
  LATE_NIGHT("Late Night", listOf(4, 2, 1, 0, -1, -2, -3)),
  CUSTOM("Custom", listOf(0, 0, 0, 0, 0, 0, 0)),
  ;

  companion object {
    val MUSIC =
      entries.filterNot { it == CUSTOM }

    fun matching(gains: List<Float>): EqualizerPreset =
      entries.firstOrNull { preset ->
        preset != CUSTOM &&
          preset.gains.size == gains.size &&
          preset.gains.zip(gains).all { (expected, actual) -> expected.toFloat() == actual }
      } ?: CUSTOM
  }
}

val EQ_BAND_FREQUENCIES = listOf(31, 62, 125, 250, 500, 1_000, 4_000)
val EQ_BAND_DISPLAY = listOf("31Hz", "62Hz", "125Hz", "250Hz", "500Hz", "1kHz", "4kHz")
const val EQ_MIN_DB = -15
const val EQ_MAX_DB = 15
const val EQ_TONE_STEPS = 4
const val EQ_TONE_DB_PER_STEP = 3f

data class EqualizerState(
  val isEnabled: Boolean = false,
  val mode: EqualizerMode = EqualizerMode.DYNAMIC,
  val currentPreset: EqualizerPreset = EqualizerPreset.FLAT,
  val bandGains: List<Float> = List(EQ_BAND_FREQUENCIES.size) { 0f },
  val toneX: Int = 0,
  val toneY: Int = 0,
  val toneFocused: Boolean = false,
  val volumeBoostDb: Int = 0,
)

@Composable
fun EqualizerSheet(
  state: EqualizerState,
  onEnabledChanged: (Boolean) -> Unit,
  onModeChanged: (EqualizerMode) -> Unit,
  onPresetSelected: (EqualizerPreset) -> Unit,
  onBandChanged: (bandIndex: Int, gainDb: Float) -> Unit,
  onToneChanged: (x: Int, y: Int) -> Unit,
  onToneFocusChanged: (Boolean) -> Unit,
  onVolumeBoostChanged: (Int) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  PlayerSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 48.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "EQUALIZER",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          letterSpacing = 2.sp,
          fontWeight = FontWeight.SemiBold,
        )
        IconSwitch(
          checked = state.isEnabled,
          onCheckedChange = onEnabledChanged,
          modifier = Modifier.scale(0.8f),
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
          selected = state.mode == EqualizerMode.DYNAMIC,
          onClick = { onModeChanged(EqualizerMode.DYNAMIC) },
          shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          enabled = state.isEnabled,
          label = { Text("Tone") },
        )
        SegmentedButton(
          selected = state.mode == EqualizerMode.MANUAL,
          onClick = { onModeChanged(EqualizerMode.MANUAL) },
          shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          enabled = state.isEnabled,
          label = { Text("Bands") },
        )
      }

      Spacer(modifier = Modifier.height(20.dp))

      if (state.mode == EqualizerMode.DYNAMIC) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "WARM",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "FORWARD",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "BRIGHT",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TonePad(
          x = state.toneX,
          y = state.toneY,
          enabled = state.isEnabled,
          onChange = onToneChanged,
        )

        Text(
          text = "Tilt ${signed(state.toneX)}  •  Contour ${signed(state.toneY)}",
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
          color =
            if (state.isEnabled) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          listOf(true, false).forEachIndexed { index, focused ->
            SegmentedButton(
              selected = state.toneFocused == focused,
              onClick = { onToneFocusChanged(focused) },
              enabled = state.isEnabled,
              shape = SegmentedButtonDefaults.itemShape(index, 2),
              colors = themedSegmentedButtonColors(),
            ) {
              Text(if (focused) "Focused" else "Broad")
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "PRESETS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = "Reset",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (state.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier =
              Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = state.isEnabled) {
                  onPresetSelected(EqualizerPreset.FLAT)
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        val presetsToShow =
          if (state.currentPreset == EqualizerPreset.CUSTOM) {
            listOf(EqualizerPreset.CUSTOM) + EqualizerPreset.MUSIC
          } else {
            EqualizerPreset.MUSIC
          }

        LazyRow(
          contentPadding = PaddingValues(horizontal = 0.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          items(presetsToShow, key = { it.name }) { preset ->
            PresetChip(
              preset = preset,
              isSelected = preset == state.currentPreset,
              isEnabled = state.isEnabled,
              onClick =
                if (preset != EqualizerPreset.CUSTOM) {
                  { onPresetSelected(preset) }
                } else {
                  null
                },
            )
          }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(220.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          state.bandGains.forEachIndexed { index, gain ->
            BandColumn(
              label = EQ_BAND_DISPLAY.getOrElse(index) { "" },
              gainDb = gain,
              isEnabled = state.isEnabled,
              onGainChanged = { db -> onBandChanged(index, db) },
              modifier = Modifier.weight(1f),
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))
      }

      HorizontalDivider(
        modifier = Modifier.padding(vertical = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "VOLUME BOOST",
          style = MaterialTheme.typography.labelMedium,
          color =
            if (state.isEnabled) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
          letterSpacing = 2.sp,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = if (state.volumeBoostDb > 0) "+${state.volumeBoostDb} dB" else "Off",
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
          color =
            if (state.isEnabled) {
              if (state.volumeBoostDb > 0) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              }
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      var volumeBoostValue by remember(state.volumeBoostDb) { mutableFloatStateOf(state.volumeBoostDb.toFloat()) }
      GlassSlider(
        value = volumeBoostValue,
        onValueChange = { newValue ->
          volumeBoostValue = newValue
          onVolumeBoostChanged(newValue.roundToInt())
        },
        valueRange = 0f..10f,
        enabled = state.isEnabled,
        modifier = Modifier.fillMaxWidth(),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "0 dB",
          style = MaterialTheme.typography.labelSmall,
          color =
            if (state.isEnabled) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
        Text(
          text = "+10 dB",
          style = MaterialTheme.typography.labelSmall,
          color =
            if (state.isEnabled) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
      }
    }
  }
}

@Composable
private fun TonePad(
  x: Int,
  y: Int,
  enabled: Boolean,
  onChange: (Int, Int) -> Unit,
) {
  val latestX by rememberUpdatedState(x)
  val latestY by rememberUpdatedState(y)
  val animatedX by
    animateFloatAsState(
      targetValue = x.toFloat(),
      animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessHigh),
      label = "equalizer_tone_x",
    )
  val animatedY by
    animateFloatAsState(
      targetValue = y.toFloat(),
      animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessHigh),
      label = "equalizer_tone_y",
    )
  val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
  val puckColor = MaterialTheme.colorScheme.primary
  val puckMarkColor = MaterialTheme.colorScheme.onPrimary
  val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest

  Canvas(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(190.dp)
        .padding(horizontal = 12.dp, vertical = 14.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(surfaceColor.copy(alpha = if (enabled) 0.55f else 0.25f))
        .pointerInput(enabled) {
          if (!enabled) return@pointerInput
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            var lastX = latestX
            var lastY = latestY
            fun report(position: Offset) {
              val inset = 18.dp.toPx()
              val width = (size.width - inset * 2f).coerceAtLeast(1f)
              val height = (size.height - inset * 2f).coerceAtLeast(1f)
              val nextX =
                (((position.x - inset) / width).coerceIn(0f, 1f) * EQ_TONE_STEPS * 2f).roundToInt() - EQ_TONE_STEPS
              val nextY =
                EQ_TONE_STEPS - (((position.y - inset) / height).coerceIn(0f, 1f) * EQ_TONE_STEPS * 2f).roundToInt()
              if (nextX != lastX || nextY != lastY) {
                lastX = nextX
                lastY = nextY
                onChange(nextX, nextY)
              }
            }
            report(down.position)
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) {
                change.consume()
                break
              }
              if (change.positionChanged()) {
                change.consume()
                report(change.position)
              }
            }
          }
        },
  ) {
    val inset = 18.dp.toPx()
    val usableWidth = size.width - inset * 2f
    val usableHeight = size.height - inset * 2f
    val divisions = EQ_TONE_STEPS * 2
    for (column in 0..divisions) {
      for (row in 0..divisions) {
        val onAxis = column == EQ_TONE_STEPS || row == EQ_TONE_STEPS
        drawCircle(
          color = dotColor.copy(alpha = if (onAxis) 0.55f else 0.26f),
          radius = if (onAxis) 2.dp.toPx() else 1.5.dp.toPx(),
          center =
            Offset(
              x = inset + usableWidth * column / divisions,
              y = inset + usableHeight * row / divisions,
            ),
        )
      }
    }
    val puckCenter =
      Offset(
        x = inset + usableWidth * (animatedX + EQ_TONE_STEPS) / divisions,
        y = inset + usableHeight * (EQ_TONE_STEPS - animatedY) / divisions,
      )
    drawCircle(
      color = Color.Black.copy(alpha = 0.18f),
      radius = 15.dp.toPx(),
      center = puckCenter.copy(y = puckCenter.y + 2.dp.toPx()),
    )
    drawCircle(
      color = puckColor.copy(alpha = if (enabled) 1f else 0.38f),
      radius = 13.dp.toPx(),
      center = puckCenter,
    )
    drawCircle(color = puckMarkColor, radius = 4.dp.toPx(), center = puckCenter)
  }
}

private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

@Composable
private fun PresetChip(
  preset: EqualizerPreset,
  isSelected: Boolean,
  isEnabled: Boolean,
  onClick: (() -> Unit)?,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      Modifier
        .alpha(if (isEnabled) 1f else 0.38f)
        .clip(RoundedCornerShape(50))
        .settingsGlassSurface(cornerRadius = 999.dp)
        .background(
          if (isSelected) {
            settingsGlassContainerColor(MaterialTheme.colorScheme.primaryContainer)
          } else {
            Color.Transparent
          },
        ).border(
          BorderStroke(
            1.dp,
            if (isSelected) {
              MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            } else {
              MaterialTheme.colorScheme.outlineVariant
            },
          ),
          RoundedCornerShape(50),
        ).then(if (isEnabled && onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Text(
      text = preset.displayName,
      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
      color =
        if (isSelected) {
          MaterialTheme.colorScheme.onPrimaryContainer
        } else {
          MaterialTheme.colorScheme.onSurface
        },
    )
  }
}

@Composable
private fun BandColumn(
  label: String,
  gainDb: Float,
  isEnabled: Boolean,
  onGainChanged: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  var sliderValue by remember(gainDb) { mutableFloatStateOf(gainDb) }

  Column(
    modifier = modifier.fillMaxHeight(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    val displayGain = sliderValue.roundToInt()
    val gainText = if (displayGain > 0) "+$displayGain" else "$displayGain"
    Text(
      text = gainText,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
      color =
        if (isEnabled) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      textAlign = TextAlign.Center,
    )

    GlassSlider(
      value = sliderValue,
      onValueChange = { newValue ->
        sliderValue = newValue
        onGainChanged(newValue)
      },
      valueRange = EQ_MIN_DB.toFloat()..EQ_MAX_DB.toFloat(),
      enabled = isEnabled,
      modifier =
        Modifier
          .weight(1f)
          .padding(vertical = 12.dp)
          .layout { measurable, constraints ->
            val placeable =
              measurable.measure(
                Constraints(
                  minWidth = constraints.minHeight,
                  maxWidth = constraints.maxHeight,
                  minHeight = constraints.minWidth,
                  maxHeight = constraints.maxWidth,
                ),
              )
            layout(placeable.height, placeable.width) {
              placeable.place(
                x = -(placeable.width / 2 - placeable.height / 2),
                y = -(placeable.height / 2 - placeable.width / 2),
              )
            }
          }.graphicsLayer {
            rotationZ = -90f
            transformOrigin = TransformOrigin.Center
          },
    )

    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}
