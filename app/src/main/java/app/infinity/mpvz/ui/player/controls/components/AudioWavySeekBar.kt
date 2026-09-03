/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.SeekbarStyle
import app.gyrolet.mpvrx.ui.player.controls.LocalPlayerButtonsClickEvent
import app.gyrolet.mpvrx.ui.theme.AppMotion
import dev.vivvvek.seeker.Segment
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

/**
 * Shifts lightness and saturation of a Compose Color to produce
 * rich, dynamic tonal variations within the Material Design 3 theme palette.
 */
fun Color.shiftTonal(lightnessDelta: Float, saturationScale: Float = 1.0f): Color {
  val hsv = FloatArray(3)
  android.graphics.Color.RGBToHSV(
    (red * 255).toInt().coerceIn(0, 255),
    (green * 255).toInt().coerceIn(0, 255),
    (blue * 255).toInt().coerceIn(0, 255),
    hsv,
  )
  hsv[1] = (hsv[1] * saturationScale).coerceIn(0.15f, 1.0f)
  hsv[2] = (hsv[2] + lightnessDelta).coerceIn(0.15f, 1.0f)
  val rgb = android.graphics.Color.HSVToColor(hsv)
  return Color(rgb)
}

/**
 * Advanced Dynamic Counter-Gradient Frosted Glass Waveform Visualizer Overlay
 * (Incorporating PR #9 improvements from LastWave):
 * - Multi-depth liquid gradient body fills (Layer 1, 2, 3 vertical & horizontal gradients)
 * - Multi-stage luminous glow halos along the wave crests
 * - Crisp contour line strokes (1.0dp, 1.2dp, 1.8dp)
 * - 4.5dp sampling step optimization for low-end device performance
 * - Pre-allocated gradient color lists to prevent garbage collection churn during 60fps draw loop
 * - Smooth 280ms amplitude dampening when paused or dragging
 * - Dynamic thickness adaptation: vertical baseline position, wave amplitude, and end-of-wave thumb
 *   offset automatically adjust based on the seekbar thickness and style (Thick, Standard, Normal, Slim, Wavy).
 * - Floats strictly ABOVE the seekbar track: never draws into the track, never covers the thumb/tip,
 *   and naturally tapers smoothly to 0 height before the seekbar tip with zero flat/rounded cutoff box.
 */
@Composable
fun SeekbarWavyVisualizerOverlay(
  positionProvider: () -> Float,
  duration: Float,
  isPaused: Boolean,
  isScrubbing: Boolean,
  trackHeight: Dp = 8.dp,
  seekbarStyle: SeekbarStyle = SeekbarStyle.Standard,
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary
  val secondaryColor = MaterialTheme.colorScheme.secondary
  val tertiaryColor = MaterialTheme.colorScheme.tertiary

  // Pre-calculated tonal palette (zero allocation in draw loop)
  val layer1Light = remember(tertiaryColor) { tertiaryColor.shiftTonal(lightnessDelta = +0.18f, saturationScale = 0.85f) }
  val layer1Dark = remember(tertiaryColor) { tertiaryColor.shiftTonal(lightnessDelta = -0.15f, saturationScale = 1.30f) }
  val layer2Dark = remember(secondaryColor) { secondaryColor.shiftTonal(lightnessDelta = -0.16f, saturationScale = 1.30f) }
  val layer2Light = remember(secondaryColor) { secondaryColor.shiftTonal(lightnessDelta = +0.18f, saturationScale = 0.85f) }
  val layer3Light = remember(primaryColor) { primaryColor.shiftTonal(lightnessDelta = +0.20f, saturationScale = 0.90f) }
  val layer3Dark = remember(primaryColor) { primaryColor.shiftTonal(lightnessDelta = -0.14f, saturationScale = 1.35f) }

  // Pre-allocated gradient color lists to eliminate garbage collection churn during frame rendering (PR #9)
  val gradient1Colors = remember(layer1Light, layer1Dark) {
    listOf(layer1Light.copy(alpha = 0.30f), layer1Dark.copy(alpha = 0.08f))
  }
  val gradient2Colors = remember(layer2Light, layer2Dark) {
    listOf(layer2Light.copy(alpha = 0.38f), layer2Dark.copy(alpha = 0.12f))
  }
  val gradient3Colors = remember(layer3Light, layer3Dark) {
    listOf(layer3Light.copy(alpha = 0.56f), layer3Dark.copy(alpha = 0.22f))
  }
  val gradient3HorizontalColors = remember(layer3Light, layer3Dark, secondaryColor) {
    listOf(layer3Light.copy(alpha = 0.32f), secondaryColor.copy(alpha = 0.22f), layer3Dark.copy(alpha = 0.38f))
  }

  val contour1Colors = remember(layer1Light, layer1Dark) {
    listOf(layer1Light.copy(alpha = 0.18f), layer1Dark.copy(alpha = 0.12f))
  }
  val contour2Colors = remember(layer2Light, layer2Dark) {
    listOf(layer2Dark.copy(alpha = 0.24f), layer2Light.copy(alpha = 0.18f))
  }
  val contour3OuterColors = remember(layer3Light, layer3Dark) {
    listOf(layer3Light.copy(alpha = 0.32f), layer3Dark.copy(alpha = 0.22f))
  }
  val contour3InnerColors = remember(layer3Light, layer3Dark) {
    listOf(layer3Light.copy(alpha = 0.52f), layer3Dark.copy(alpha = 0.40f))
  }

  val crispContour1Colors = remember(layer1Light, layer1Dark) {
    listOf(layer1Light.copy(alpha = 0.45f), layer1Dark.copy(alpha = 0.32f))
  }
  val crispContour2Colors = remember(layer2Light, layer2Dark) {
    listOf(layer2Dark.copy(alpha = 0.72f), layer2Light.copy(alpha = 0.62f))
  }
  val crispContour3Colors = remember(layer3Light, layer3Dark) {
    listOf(layer3Light.copy(alpha = 0.98f), layer3Dark.copy(alpha = 0.92f))
  }

  // Dynamic seekbar track thickness that tracks the underlying seekbar's exact height
  val effectiveTrackHeightDp = when (seekbarStyle) {
    SeekbarStyle.Thick -> if (isPaused || isScrubbing) 11.2.dp else 16.dp
    SeekbarStyle.Standard -> if (isPaused || isScrubbing) 5.6.dp else 8.dp
    SeekbarStyle.Normal -> if (isScrubbing) 6.dp else 4.dp
    SeekbarStyle.Slim -> when {
      isScrubbing -> 15.dp
      isPaused -> 6.dp
      else -> 8.dp
    }
    SeekbarStyle.Wavy -> 8.dp
  }

  val animatedTrackHeight by animateDpAsState(
    targetValue = effectiveTrackHeightDp,
    animationSpec = spring(
      dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
      stiffness = AppMotion.Spatial.Expressive.stiffness,
    ),
    label = "wavy_overlay_track_height",
  )

  // Continuous running phase accumulator that smoothly freezes when paused without snapping
  var phase1 by remember { mutableFloatStateOf(2.2f) }
  var phase2 by remember { mutableFloatStateOf(1.2f) }
  var phase3 by remember { mutableFloatStateOf(0f) }

  val wavesActive = !isPaused && !isScrubbing

  LaunchedEffect(wavesActive) {
    if (!wavesActive) return@LaunchedEffect
    var lastFrameTime = withFrameMillis { it }
    val speed1 = (2 * PI / 2.4).toFloat()
    val speed2 = (2 * PI / 1.8).toFloat()
    val speed3 = (2 * PI / 1.3).toFloat()
    val twoPi = (2 * PI).toFloat()
    while (isActive) {
      withFrameMillis { frameTimeMillis ->
        val dt = ((frameTimeMillis - lastFrameTime) / 1000f).coerceIn(0f, 0.1f)
        phase1 = (phase1 + dt * speed1) % twoPi
        phase2 = (phase2 + dt * speed2) % twoPi
        phase3 = (phase3 + dt * speed3) % twoPi
        lastFrameTime = frameTimeMillis
      }
    }
  }

  // 3-Tier Amplitudes scaled proportionally with seekbar thickness, with smooth 280ms dampening on pause/scrub
  val density = LocalDensity.current
  val thicknessScale = (effectiveTrackHeightDp.value / 12f).coerceIn(0.75f, 1.25f)
  val baseAmp1Px = with(density) { 13.0.dp.toPx() } * thicknessScale
  val baseAmp2Px = with(density) { 10.0.dp.toPx() } * thicknessScale
  val baseAmp3Px = with(density) { 7.5.dp.toPx() } * thicknessScale

  val amp1 by animateFloatAsState(
    targetValue = if (!wavesActive) 0f else baseAmp1Px,
    animationSpec = tween(durationMillis = 280),
    label = "WavyOverlay_Amp1",
  )
  val amp2 by animateFloatAsState(
    targetValue = if (!wavesActive) 0f else baseAmp2Px,
    animationSpec = tween(durationMillis = 280),
    label = "WavyOverlay_Amp2",
  )
  val amp3 by animateFloatAsState(
    targetValue = if (!wavesActive) 0f else baseAmp3Px,
    animationSpec = tween(durationMillis = 280),
    label = "WavyOverlay_Amp3",
  )

  // Broad wavelengths for smooth, elegant rolling waves
  val waveLength1Px = with(density) { 160.dp.toPx() }
  val waveLength2Px = with(density) { 125.dp.toPx() }
  val waveLength3Px = with(density) { 95.dp.toPx() }

  val transitionLengthPx = with(density) { 44.dp.toPx() }
  val waveSampleStepPx = with(density) { 4.5.dp.toPx() } // Optimized for lower end devices (PR #9)

  // Reusable Path caches to eliminate GC allocations during frame drawing
  val pathFilled1 = remember { Path() }
  val pathContour1 = remember { Path() }
  val pathFilled2 = remember { Path() }
  val pathContour2 = remember { Path() }
  val pathFilled3 = remember { Path() }
  val pathContour3 = remember { Path() }

  Canvas(modifier = modifier) {
    val width = size.width
    val height = size.height
    val centerY = height / 2f
    val currentPosition = positionProvider()
    val safeDuration = duration.takeIf { it.isFinite() && it > 0f } ?: 0f
    val fraction = if (safeDuration > 0f) (currentPosition / safeDuration).coerceIn(0f, 1f) else 0f
    val thumbX = (fraction * width).coerceIn(0f, width)
    val halfThickness = animatedTrackHeight.toPx() / 2f
    val topBaselineY = centerY - halfThickness

    // Thumb clearance offset adjusted per seekbar style so wave stops right before the thumb tip
    val thumbOffsetPx = when (seekbarStyle) {
      SeekbarStyle.Thick -> if (isScrubbing) 5.dp.toPx() else 7.dp.toPx()
      SeekbarStyle.Standard -> if (isScrubbing) 5.dp.toPx() else 7.5.dp.toPx()
      SeekbarStyle.Normal -> if (isScrubbing) 9.dp.toPx() else 6.5.dp.toPx()
      SeekbarStyle.Slim -> halfThickness
      SeekbarStyle.Wavy -> 4.dp.toPx()
    }

    // Wave stops gracefully just before the thumb tip so it never overlaps or covers the seekbar thumb
    val waveEndX = (thumbX - thumbOffsetPx).coerceAtLeast(0f)

    // If nothing has played past the thumb yet, don't draw
    if (waveEndX <= 0.5f || width <= 0f) return@Canvas

    // Strictly contain waves above the track baseline: never bleed into the track or past horizontal bounds
    clipRect(left = 0f, top = 0f, right = width, bottom = topBaselineY + 1.dp.toPx()) {
      fun smootherstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * c * (c * (c * 6f - 15f) + 10f)
      }

      fun populateWave(
        filled: Path,
        contour: Path,
        wavelength: Float,
        amplitude: Float,
        phase: Float,
      ) {
        filled.reset()
        contour.reset()
        filled.moveTo(0f, topBaselineY)
        contour.moveTo(0f, topBaselineY)

        var x = 0f
        val effectiveTransition = minOf(transitionLengthPx, waveEndX * 0.48f)
        val invTransition = if (effectiveTransition > 0f) 1f / effectiveTransition else 0f
        val invWavelength2Pi = (2 * PI / wavelength).toFloat()
        while (x < waveEndX) {
          val startEnv = if (invTransition > 0f) smootherstep(x * invTransition) else 1f
          val endEnv = if (invTransition > 0f) smootherstep((waveEndX - x) * invTransition) else 1f
          val envelope = startEnv * endEnv

          val angle = x * invWavelength2Pi - phase
          val waveHeight = (0.5f + 0.5f * sin(angle)) * amplitude * envelope
          val y = topBaselineY - waveHeight

          filled.lineTo(x, y)
          contour.lineTo(x, y)
          x += waveSampleStepPx
        }
        val endY = topBaselineY
        filled.lineTo(waveEndX, endY)
        contour.lineTo(waveEndX, endY)

        // Close filled path along topBaselineY so it floats purely above the track
        filled.close()
      }

      populateWave(pathFilled1, pathContour1, waveLength1Px, amp1, phase1)
      populateWave(pathFilled2, pathContour2, waveLength2Px, amp2, phase2)
      populateWave(pathFilled3, pathContour3, waveLength3Px, amp3, phase3)

      val activeWidth = waveEndX.coerceAtLeast(1f)
      val maxAmp = maxOf(amp1, maxOf(amp2, amp3))
      val topWaveY = topBaselineY - maxAmp

      // 1. Layer 1 Filled Wave (Ambient deep vertical gradient body — PR #9)
      if (amp1 > 0.05f) {
        drawPath(
          path = pathFilled1,
          brush = Brush.verticalGradient(
            colors = gradient1Colors,
            startY = topWaveY,
            endY = topBaselineY,
          ),
        )
      }

      // 2. Layer 2 Filled Wave (Middle harmonic translucent gradient body — PR #9)
      if (amp2 > 0.05f) {
        drawPath(
          path = pathFilled2,
          brush = Brush.verticalGradient(
            colors = gradient2Colors,
            startY = topWaveY,
            endY = topBaselineY,
          ),
        )
      }

      // 3. Layer 3 Filled Wave (Primary vibrant vertical + horizontal liquid gradient body — PR #9)
      if (amp3 > 0.05f) {
        drawPath(
          path = pathFilled3,
          brush = Brush.verticalGradient(
            colors = gradient3Colors,
            startY = topWaveY,
            endY = topBaselineY,
          ),
        )
        drawPath(
          path = pathFilled3,
          brush = Brush.horizontalGradient(
            colors = gradient3HorizontalColors,
            startX = 0f,
            endX = activeWidth,
          ),
        )
      }

      // ── Gradient Glow Halos (Luminous bloom along wave crests — PR #9) ──
      if (maxAmp > 0.05f) {
        // Layer 1 Ambient Glow
        drawPath(
          path = pathContour1,
          brush = Brush.horizontalGradient(
            colors = contour1Colors,
            startX = 0f,
            endX = activeWidth,
          ),
          style = Stroke(width = 4.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Layer 2 Harmonic Glow
        drawPath(
          path = pathContour2,
          brush = Brush.horizontalGradient(
            colors = contour2Colors,
            startX = 0f,
            endX = activeWidth,
          ),
          style = Stroke(width = 5.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Layer 3 Primary Outer Soft Glow
        drawPath(
          path = pathContour3,
          brush = Brush.horizontalGradient(
            colors = contour3OuterColors,
            startX = 0f,
            endX = activeWidth,
          ),
          style = Stroke(width = 6.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Layer 3 Primary Inner Focused Halo
        drawPath(
          path = pathContour3,
          brush = Brush.horizontalGradient(
            colors = contour3InnerColors,
            startX = 0f,
            endX = activeWidth,
          ),
          style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
      }

      // ── Crisp Contour Line Strokes ──

      // Layer 1: Soft organic contour stroke
      drawPath(
        path = pathContour1,
        brush = Brush.horizontalGradient(
          colors = crispContour1Colors,
          startX = 0f,
          endX = activeWidth,
        ),
        style = Stroke(width = 1.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
      )

      // Layer 2: Medium harmonic contour stroke
      drawPath(
        path = pathContour2,
        brush = Brush.horizontalGradient(
          colors = crispContour2Colors,
          startX = 0f,
          endX = activeWidth,
        ),
        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
      )

      // Layer 3: Vibrant primary contour stroke (1.8dp in PR #9)
      drawPath(
        path = pathContour3,
        brush = Brush.horizontalGradient(
          colors = crispContour3Colors,
          startX = 0f,
          endX = activeWidth,
        ),
        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
      )
    }
  }
}

/**
 * Standalone AudioWavySeekBar component with integrated track and timers.
 */
@Composable
fun AudioWavySeekBar(
  position: Float,
  duration: Float,
  remaining: Float,
  isPlaying: Boolean,
  onSeek: (Float) -> Unit,
  onSeekFinished: (Float) -> Unit,
  timersInverted: Pair<Boolean, Boolean>,
  positionTimerOnClick: () -> Unit,
  durationTimerOnClick: () -> Unit,
  chapters: ImmutableList<Segment> = persistentListOf(),
  timerTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  modifier: Modifier = Modifier,
) {
  val clickEvent = LocalPlayerButtonsClickEvent.current
  val interactionSource = remember { MutableInteractionSource() }
  val dragging by interactionSource.collectIsDraggedAsState()
  var dragPosition by remember { mutableFloatStateOf(0f) }

  val boundedDuration = duration.coerceAtLeast(0f)
  val boundedPosition = if (boundedDuration > 0f) position.coerceIn(0f, boundedDuration) else 0f
  val shownPosition = if (dragging) dragPosition.coerceIn(0f, boundedDuration) else boundedPosition

  val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
  val chapterDividerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)
  val density = LocalDensity.current
  val baseTrackThicknessPx = with(density) { 4.5.dp.toPx() }

  Column(modifier = modifier.fillMaxWidth()) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(50.dp),
    ) {
      Canvas(modifier = Modifier.matchParentSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f + 7.dp.toPx()
        val halfTrack = baseTrackThicknessPx / 2f

        // 1. Inactive background track: Full smooth capsule bar with exact bounds [0f, width]
        drawRoundRect(
          color = inactiveColor,
          topLeft = Offset(0f, centerY - halfTrack),
          size = Size(width, baseTrackThicknessPx),
          cornerRadius = CornerRadius(halfTrack, halfTrack),
        )

        // Chapter markers along the inactive track
        if (chapters.isNotEmpty() && boundedDuration > 0f) {
          val tickHalfH = baseTrackThicknessPx * 0.75f
          for (chapter in chapters) {
            val frac = chapter.start / boundedDuration
            if (frac > 0.005f && frac < 0.995f) {
              val chX = frac * width
              drawLine(
                color = chapterDividerColor,
                start = Offset(chX, centerY - tickHalfH),
                end = Offset(chX, centerY + tickHalfH),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
              )
            }
          }
        }
      }

      SeekbarWavyVisualizerOverlay(
        positionProvider = { shownPosition },
        duration = boundedDuration,
        isPaused = !isPlaying,
        isScrubbing = dragging,
        trackHeight = 4.5.dp,
        modifier = Modifier.matchParentSize(),
      )

      Slider(
        value = shownPosition,
        onValueChange = {
          dragPosition = it
          onSeek(it)
        },
        onValueChangeFinished = {
          onSeekFinished(dragPosition.coerceIn(0f, boundedDuration))
        },
        valueRange = 0f..boundedDuration.coerceAtLeast(1f),
        enabled = boundedDuration > 0f,
        interactionSource = interactionSource,
        colors = SliderDefaults.colors(
          thumbColor = Color.Transparent,
          activeTrackColor = Color.Transparent,
          inactiveTrackColor = Color.Transparent,
        ),
        modifier = Modifier
          .matchParentSize()
          .alpha(0f),
      )
    }

    Spacer(Modifier.height(2.dp))

    // Time labels with inverted duration support
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      VideoTimer(
        value = shownPosition,
        isInverted = timersInverted.first,
        textColor = timerTextColor,
        onClick = {
          clickEvent()
          positionTimerOnClick()
        },
      )

      VideoTimer(
        value = if (timersInverted.second) -remaining else duration,
        isInverted = timersInverted.second,
        textColor = timerTextColor,
        onClick = {
          clickEvent()
          durationTimerOnClick()
        },
      )
    }
  }
}
