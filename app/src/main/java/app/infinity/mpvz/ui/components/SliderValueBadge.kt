/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/** A stable value column: changing the value never changes the description's available width. */
@Composable
fun SliderValueBadge(
  valueText: String,
  minimumText: String = valueText,
  maximumText: String = valueText,
  modifier: Modifier = Modifier,
  style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
  val measurer = rememberTextMeasurer()
  val tabular = remember(style) { style.copy(fontFeatureSettings = "tnum") }
  val width = remember(tabular, minimumText, maximumText) {
    maxOf(measurer.measure(minimumText, tabular).size.width, measurer.measure(maximumText, tabular).size.width)
  }
  Box(
    modifier = modifier
      .width(with(androidx.compose.ui.platform.LocalDensity.current) { (width.toDp() + 16.dp) })
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), RoundedCornerShape(7.dp))
      .padding(horizontal = 8.dp, vertical = 2.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(valueText, style = tabular, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
  }
}
