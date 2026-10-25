/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.performance

import android.app.Activity
import android.os.Build
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.ceil

/** Immutable aggregate used by both the Android listener and JVM tests. */
data class FrameTimeStats(val frameCount: Int, val jankyFrames: Int, val durationsMs: List<Float>) {
  val jankyPercent: Float get() = if (frameCount == 0) 0f else jankyFrames * 100f / frameCount
  fun percentile(percent: Float): Float {
    if (durationsMs.isEmpty()) return 0f
    val sorted = durationsMs.sorted()
    val rank = ceil((percent.coerceIn(0f, 1f) * sorted.size).toDouble()).toInt().coerceAtLeast(1)
    return sorted[rank - 1].toInt().toFloat()
  }
}

internal class FrameTimeAccumulator(private val frameBudgetMs: Float) {
  private val durations = ArrayList<Float>()
  private var jank = 0
  fun add(durationNs: Long) {
    val ms = durationNs / 1_000_000f
    durations += ms
    if (ms > frameBudgetMs) jank++
  }
  fun snapshot(): FrameTimeStats = FrameTimeStats(durations.size, jank, durations.toList())
  fun reset() { durations.clear(); jank = 0 }
}

internal object FrameTimePolicy {
  fun frameBudgetMs(refreshRateHz: Float): Float = 1000f / refreshRateHz.coerceIn(1f, 240f)
  fun isJank(durationNs: Long, refreshRateHz: Float): Boolean =
    durationNs > (frameBudgetMs(refreshRateHz) * 1_000_000f).toLong()
}

class FrameTimeMetricsCollector(private val window: Window, refreshRateHz: Float) {
  private val budgetMs = FrameTimePolicy.frameBudgetMs(refreshRateHz)
  private val accumulator = FrameTimeAccumulator(budgetMs)
  private val _stats = MutableStateFlow(FrameTimeStats(0, 0, emptyList()))
  val stats = _stats.asStateFlow()
  private var lastPublishNs = 0L
  private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
    accumulator.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
    val now = System.nanoTime()
    if (now - lastPublishNs >= 500_000_000L) {
      _stats.value = accumulator.snapshot()
      accumulator.reset()
      lastPublishNs = now
    }
  }
  fun start() { if (Build.VERSION.SDK_INT >= 24) window.addOnFrameMetricsAvailableListener(listener, null) }
  fun stop() { if (Build.VERSION.SDK_INT >= 24) window.removeOnFrameMetricsAvailableListener(listener) }
}

@Composable
fun FrameTimeOverlay(activity: Activity, enabled: Boolean) {
  if (!enabled || Build.VERSION.SDK_INT < 24) return
  val collector = remember(activity) {
    FrameTimeMetricsCollector(activity.window, activity.window.decorView.display?.refreshRate ?: 60f)
  }
  val stats by collector.stats.collectAsStateWithLifecycle()
  DisposableEffect(collector) {
    collector.start()
    onDispose { collector.stop() }
  }
  Popup(alignment = androidx.compose.ui.Alignment.TopEnd) {
    Text(
      String.format(Locale.US, "Frame %s jank • p90 %.0f ms • p99 %.0f ms", "%.1f%%".format(Locale.US, stats.jankyPercent), stats.percentile(.90f), stats.percentile(.99f)),
      modifier = Modifier.zIndex(10f).background(Color.Black.copy(alpha = .78f)).padding(horizontal = 8.dp, vertical = 4.dp),
      color = Color.White,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}
