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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
        TonePad(
          toneX = state.toneX,
          toneY = state.toneY,
          isEnabled = state.isEnabled,
          toneFocused = state.toneFocused,
          onToneChanged = onToneChanged,
          onToneFocusChanged = onToneFocusChanged,
          modifier =
            Modifier
              .fillMaxWidth()
              .height(220.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "WARM",
            style = MaterialTheme.typography.labelSmall,
            color = if (state.isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
          )
          Text(
            text = if (state.toneFocused) "Focused" else "Broad",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (state.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier =
              Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = state.isEnabled) { onToneFocusChanged(!state.toneFocused) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
          )
          Text(
            text = "BRIGHT",
            style = MaterialTheme.typography.labelSmall,
            color = if (state.isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
          )
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
  toneX: Int,
  toneY: Int,
  toneFocused: Boolean,
  isEnabled: Boolean,
  onToneChanged: (Int, Int) -> Unit,
  onToneFocusChanged: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  var padSize by remember { mutableFloatStateOf(0f) }
  var lastEmittedX by remember { mutableIntStateOf(Int.MIN_VALUE) }
  var lastEmittedY by remember { mutableIntStateOf(Int.MIN_VALUE) }

  fun pointToStep(offset: Float, sizePx: Float): Int {
    if (sizePx <= 0f) return 0
    val normalized = ((offset / sizePx) * 2f - 1f).coerceIn(-1f, 1f)
    return (normalized * EQ_TONE_STEPS).roundToInt().coerceIn(-EQ_TONE_STEPS, EQ_TONE_STEPS)
  }

  fun emit(x: Int, y: Int) {
    if (x == lastEmittedX && y == lastEmittedY) return
    lastEmittedX = x
    lastEmittedY = y
    onToneChanged(x, y)
  }

  Box(
    modifier =
      modifier
        .alpha(if (isEnabled) 1f else 0.38f)
        .clip(RoundedCornerShape(20.dp))
        .settingsGlassSurface(cornerRadius = 20.dp)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f))
        .border(
          BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
          RoundedCornerShape(20.dp),
        ).pointerInput(isEnabled, toneFocused) {
          if (!isEnabled) return@pointerInput
          detectTapGestures(
            onTap = { offset ->
              padSize = size.width.toFloat()
              emit(pointToStep(offset.x, size.width.toFloat()), pointToStep(size.height.toFloat() - offset.y, size.height.toFloat()))
              onToneFocusChanged(toneFocused)
            },
          )
        }.pointerInput(isEnabled) {
          if (!isEnabled) return@pointerInput
          detectDragGestures(
            onDragStart = { offset ->
              padSize = size.width.toFloat()
              emit(pointToStep(offset.x, size.width.toFloat()), pointToStep(size.height.toFloat() - offset.y, size.height.toFloat()))
            },
          ) { change, _ ->
            change.consume()
            emit(pointToStep(change.position.x, size.width.toFloat()), pointToStep(size.height.toFloat() - change.position.y, size.height.toFloat()))
          }
        }.onSizeChanged { size: IntSize -> padSize = size.width.toFloat() },
    contentAlignment = Alignment.Center,
  ) {
    val handleFractionX =
      if (padSize > 0f) (toneX / EQ_TONE_STEPS.toFloat() + 1f) / 2f else 0.5f
    val handleX = padSize * handleFractionX
    val handleFractionY = (toneY / EQ_TONE_STEPS.toFloat() + 1f) / 2f
    val handleY = padSize * (1f - handleFractionY)

    Box(
      modifier =
        Modifier
          .padding(16.dp)
          .fillMaxSize(),
    ) {
      // mpvRx-style tone pad: a dot matrix grid (no heavy lines), a stronger
      // center cross, axis labels, and a soft crosshair on the handle.
      val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
      val crosshairColor = MaterialTheme.colorScheme.primary
      Canvas(Modifier.fillMaxSize()) {
        val cols = EQ_TONE_STEPS * 2
        val gapX = size.width / cols
        val gapY = size.height / cols
        for (i in 0..cols) {
          for (j in 0..cols) {
            val cx = i * gapX
            val cy = size.height - j * gapY
            val isCenter = i * 2 == cols || j * 2 == cols
            drawCircle(
              color = dotColor.copy(alpha = if (isCenter) 0.38f else 0.18f),
              radius = if (isCenter) 1.6.dp.toPx() / 2 else 1.2.dp.toPx() / 2,
              center = Offset(cx, cy),
            )
          }
        }
        if (padSize > 0f) {
          val hx = handleFractionX * size.width
          val hy = (1f - handleFractionY) * size.height
          drawLine(
            color = crosshairColor.copy(alpha = 0.30f),
            start = Offset(hx, 0f),
            end = Offset(hx, size.height),
            strokeWidth = 1.dp.toPx(),
          )
          drawLine(
            color = crosshairColor.copy(alpha = 0.30f),
            start = Offset(0f, hy),
            end = Offset(size.width, hy),
            strokeWidth = 1.dp.toPx(),
          )
        }
      }
      Text(
        text = "WARM",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp),
      )
      Text(
        text = "BRIGHT",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
      )
      Text(
        text = "FOCUSED",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
      )
      Text(
        text = "BROAD",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
      )

      Box(
        modifier =
          Modifier
            .size(22.dp)
            .graphicsLayer {
              translationX = handleX - 11.dp.toPx()
              translationY = handleY - 11.dp.toPx()
            }.clip(CircleShape)
            .background(
              if (toneFocused) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
              },
            ),
      )
    }

    Text(
      text = "Drag to shape the tone",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
    )
  }
}

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
