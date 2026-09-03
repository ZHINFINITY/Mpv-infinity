/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

private data class RibbonTones(
  val bass: Color,
  val mid: Color,
  val treble: Color,
)

private fun ribbonTones(palette: VisualizerPalette): RibbonTones =
  RibbonTones(
    bass = Color(palette.secondary),
    mid = Color(palette.tertiary),
    treble = Color(palette.primary),
  )

/**
 * One UI 6 media-notification style visualizer: a solid ribbon grows above the elapsed
 * track, breathes with the music, and tapers back into the seekbar at the playhead.
 */
@Composable
internal fun WaveVisualizerOverlay(
  palette: VisualizerPalette,
  isSheetOpen: Boolean,
  volumeScale: Float,
  features: AudioFeatures,
  isPlaying: Boolean,
  progressProvider: () -> Float,
  trackHeight: Dp,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val realAnalyzerActive = remember(features) { AtomicBoolean(false) }
  var hasRecordPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  val recordPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasRecordPermission = granted
    }

  LaunchedEffect(volumeScale) {
    features.volumeScale = volumeScale
  }
  LaunchedEffect(hasRecordPermission) {
    if (!hasRecordPermission) recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
  }

  // Audio capture stays scoped to the visible visualizer so album-art-only playback
  // never holds the Visualizer effect or polls capture freshness in the background.
  DisposableEffect(hasRecordPermission, features, isSheetOpen) {
    val analyzer = if (hasRecordPermission && !isSheetOpen) AudioSpectrumAnalyzer(features) else null
    val job =
      scope.launch(Dispatchers.Default) {
        while (isActive && analyzer != null) {
          val captureFresh = features.active && features.hasRecentCapture(1_500_000_000L)
          if (!realAnalyzerActive.get() || !captureFresh) {
            realAnalyzerActive.set(analyzer.start(0).isSuccess)
          }
          delay(if (realAnalyzerActive.get()) 1_500L else 400L)
        }
      }
    onDispose {
      job.cancel()
      realAnalyzerActive.set(false)
      analyzer?.stop(resetFeatures = false)
    }
  }

  // Each band owns its phase and envelope so the ribbons do not move as one shape.
  var frameNanos by remember { mutableLongStateOf(0L) }
  var lowPhase by remember { mutableFloatStateOf(0f) }
  var midPhase by remember { mutableFloatStateOf(1.8f) }
  var highPhase by remember { mutableFloatStateOf(3.6f) }
  var lowLevel by remember { mutableFloatStateOf(0f) }
  var midLevel by remember { mutableFloatStateOf(0f) }
  var highLevel by remember { mutableFloatStateOf(0f) }
  var loudness by remember { mutableFloatStateOf(0f) }
  LaunchedEffect(isPlaying, isSheetOpen) {
    if (!isPlaying || isSheetOpen) return@LaunchedEffect
    var previous = 0L
    while (true) {
      withFrameNanos { now ->
        val dt = if (previous == 0L) 0f else (now - previous) / 1_000_000_000f
        previous = now
        val lowTarget =
          (
            features.scaledSubBass() * 2.8f +
              features.scaledBass() * 2.2f +
              features.scaledBeat() * 1.1f +
              features.scaledEnergy() * 0.4f
          ).times(3.6f).coerceIn(0f, 1f)
        val midTarget =
          (
            features.scaledLowMid() * 2.2f +
              features.scaledMid() * 2.5f +
              features.scaledEnergy() * 0.7f +
              features.scaledSpectralFlux() * 0.5f
          ).times(3.8f).coerceIn(0f, 1f)
        val highTarget =
          (
            features.scaledHighMid() * 1.8f +
              features.scaledTreble() * 2.8f +
              features.scaledSpectralFlux() * 1.3f +
              features.scaledBeat() * 0.25f
          ).times(4.2f).coerceIn(0f, 1f)
        val loudnessTarget =
          (
            features.scaledEnergy() * 5.5f +
              features.scaledBass() * 1.8f +
              features.scaledMid() * 1.1f +
              features.scaledTreble() * 0.6f
          ).coerceIn(0f, 1f)

        lowLevel += (lowTarget - lowLevel) * min(1f, dt * (if (lowTarget > lowLevel) 9f else 2.6f))
        midLevel += (midTarget - midLevel) * min(1f, dt * (if (midTarget > midLevel) 12f else 4f))
        highLevel += (highTarget - highLevel) * min(1f, dt * (if (highTarget > highLevel) 18f else 7f))
        loudness +=
          (loudnessTarget - loudness) *
            min(1f, dt * (if (loudnessTarget > loudness) 14f else 3.5f))
        lowPhase += dt * 1.15f
        midPhase += dt * 2f
        highPhase += dt * 3.4f
        frameNanos = now
      }
    }
  }

  // Wave collapses to a flat line while paused, exactly like the One UI notification.
  val amplitudeFraction by animateFloatAsState(
    targetValue = if (isPlaying) 1f else 0f,
    animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
    label = "wave_amplitude",
  )

  val lowRibbonPath = remember { Path() }
  val midRibbonPath = remember { Path() }
  val highRibbonPath = remember { Path() }
  val tones = remember(palette) { ribbonTones(palette) }

  Canvas(modifier = modifier) {
    @Suppress("UNUSED_EXPRESSION")
    frameNanos // Reading the frame clock keeps the canvas invalidating per frame.

    val width = size.width
    val centerY = size.height / 2f
    if (width <= 0f) return@Canvas

    val trackHalf = trackHeight.toPx() / 2f
    val trackTop = centerY - trackHalf
    val progress = progressProvider().coerceIn(0f, 1f)
    val waveEnd = (width * progress).coerceIn(0f, width)
    val twoPi = (2.0 * PI).toFloat()

    fun smoothstep(value: Float): Float {
      val clamped = value.coerceIn(0f, 1f)
      return clamped * clamped * (3f - 2f * clamped)
    }

    fun drawRibbon(
      path: Path,
      color: Color,
      wavelength: Float,
      baseLift: Float,
      phase: Float,
      liftScale: Float,
    ) {
      val lift = baseLift * amplitudeFraction * liftScale
      if (waveEnd <= trackHeight.toPx() || lift <= 0.25f) return

      fun ribbonTop(x: Float): Float {
        val edgeIn = smoothstep(x / (wavelength * 0.5f))
        val edgeOut = smoothstep((waveEnd - x) / (wavelength * 0.55f))
        val envelope = edgeIn * edgeOut
        val undulation = 0.64f + 0.36f * sin((x / wavelength) * twoPi - phase)
        return trackTop - lift * undulation * envelope
      }

      val step = 2.dp.toPx().coerceAtLeast(1f)
      path.reset()
      path.moveTo(0f, trackTop)
      path.lineTo(0f, ribbonTop(0f))
      var x = 0f
      while (x < waveEnd) {
        x = min(x + step, waveEnd)
        path.lineTo(x, ribbonTop(x))
      }
      path.lineTo(waveEnd, trackTop)
      path.close()
      drawPath(path = path, color = color)
    }

    drawRibbon(
      path = lowRibbonPath,
      color = tones.bass.copy(alpha = 0.82f),
      wavelength = 96.dp.toPx(),
      baseLift = 12.dp.toPx(),
      phase = lowPhase,
      liftScale = 0.62f + loudness * 0.50f + lowLevel * 0.23f,
    )
    drawRibbon(
      path = midRibbonPath,
      color = tones.mid.copy(alpha = 0.88f),
      wavelength = 58.dp.toPx(),
      baseLift = 8.5.dp.toPx(),
      phase = midPhase,
      liftScale = 0.50f + loudness * 0.45f + midLevel * 0.25f,
    )
    drawRibbon(
      path = highRibbonPath,
      color = tones.treble.copy(alpha = 0.94f),
      wavelength = 34.dp.toPx(),
      baseLift = 5.5.dp.toPx(),
      phase = highPhase,
      liftScale = 0.40f + loudness * 0.35f + highLevel * 0.25f,
    )
  }
}
