/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.infinity.mpvz.preferences.DecoderPreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.presentation.components.ExpandableCard
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.VideoFxFilter
import app.infinity.mpvz.ui.player.controls.CARDS_MAX_WIDTH
import app.infinity.mpvz.ui.player.controls.panelCardsColors
import app.infinity.mpvz.ui.theme.spacing
import app.infinity.mpvz.ui.utils.currentMpvConfigOverrideOptions
import org.koin.compose.koinInject

/**
 * Stackable on-video effects (sharpen, clarity, denoise, pop), the app's counterpart of the
 * mpv Effects shader pack's Enhance stage. Toggling one adds/removes a named vf filter live
 * and is remembered for the next video.
 */
@Composable
fun VideoSettingsFxCard(modifier: Modifier = Modifier) {
  val decoderPreferences = koinInject<DecoderPreferences>()
  val configOwned = currentMpvConfigOverrideOptions().contains("vf")
  val enabledIds: String by decoderPreferences.videoFxFilters.collectAsState()
  var isExpanded by remember { mutableStateOf(true) }

  ExpandableCard(
    isExpanded,
    title = {
      Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
        Icon(Icons.RoundedFilled.AutoFixHigh, null)
        Text("Effects")
      }
    },
    onExpand = { isExpanded = !isExpanded },
    modifier.widthIn(max = CARDS_MAX_WIDTH),
    colors = panelCardsColors(),
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(start = MaterialTheme.spacing.extraSmall, end = MaterialTheme.spacing.medium),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
      VideoFxFilter.entries.forEach { filter ->
        val active = enabledIds.split(',').any { it.trim() == filter.id }
        FilterChip(
          selected = active,
          enabled = !configOwned,
          onClick = {
            val next =
              if (active) {
                VideoFxFilter.fromIds(enabledIds) - filter
              } else {
                VideoFxFilter.fromIds(enabledIds) + filter
              }
            decoderPreferences.videoFxFilters.set(VideoFxFilter.idsOf(next))
            VideoFxFilter.setEnabled(filter, filter in next)
          },
          label = { Text(filter.label) },
        )
      }
    }
  }
}
