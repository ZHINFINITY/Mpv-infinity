/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.sheets
import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.ui.preferences.components.settingsGlassSurface
import app.infinity.mpvz.ui.preferences.components.settingsGlassContainerColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AudioPropertyItem(
  val label: String,
  val value: String,
)

@Composable
fun AudioPropertiesSheet(
  properties: List<AudioPropertyItem>,
  engineLabel: String = "MPV",
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
      Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "AUDIO PROPERTIES",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          letterSpacing = 2.sp,
          fontWeight = FontWeight.SemiBold,
        )
        Surface(
          modifier = Modifier.settingsGlassSurface(cornerRadius = 8.dp),
          shape = RoundedCornerShape(8.dp),
          color = settingsGlassContainerColor(MaterialTheme.colorScheme.primaryContainer),
        ) {
          Text(
            text = "ENGINE · $engineLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      properties.forEachIndexed { index, prop ->
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .settingsGlassSurface(cornerRadius = 12.dp)
              .background(settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)))
              .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
          Text(
            text = prop.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = prop.value.ifBlank { "Unknown" },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (index < properties.lastIndex) {
          Spacer(modifier = Modifier.height(8.dp))
        }
      }
    }
  }
}
