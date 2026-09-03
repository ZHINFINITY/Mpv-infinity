/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.lyrics.LyricsLanguageOptions
import app.gyrolet.mpvrx.data.lyrics.SupportedLanguage
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.LyricsTranslationDisplayMode
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import org.koin.compose.koinInject

@Composable
fun LyricsTranslateDialog(
  viewModel: PlayerViewModel,
  onDismiss: () -> Unit,
) {
  val audioPreferences = koinInject<AudioPreferences>()
  val displayMode by audioPreferences.lyricsTranslationDisplayMode.collectAsState()
  val state by viewModel.lyricsUiState.collectAsState()
  var searchQuery by remember { mutableStateOf("") }

  val filteredLanguages = remember(searchQuery) {
    if (searchQuery.isBlank()) {
      LyricsLanguageOptions.ALL_LANGUAGES
    } else {
      LyricsLanguageOptions.ALL_LANGUAGES.filter {
        it.displayName.contains(searchQuery, ignoreCase = true) || it.code.contains(searchQuery, ignoreCase = true)
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.RoundedFilled.Translate,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
          )
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = stringResource(R.string.lyrics_translate_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
        }
        if (state.isTranslating) {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 440.dp),
      ) {
        // Option to toggle off (original lyrics)
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
              audioPreferences.lyricsAutoTranslate.set(false)
              if (state.isTranslationActive) {
                viewModel.toggleLyricsTranslation()
              }
              onDismiss()
            },
          color = if (!state.isTranslationActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(12.dp),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = !state.isTranslationActive,
              onClick = null,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Text(
                text = stringResource(R.string.lyrics_translation_off),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (!state.isTranslationActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "Show original language without translation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Display Mode Selector (Dual-Line vs Replace)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          FilterChip(
            selected = displayMode == LyricsTranslationDisplayMode.DualLine,
            onClick = { audioPreferences.lyricsTranslationDisplayMode.set(LyricsTranslationDisplayMode.DualLine) },
            label = { Text("Dual-Line", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
            modifier = Modifier.weight(1f),
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          )
          FilterChip(
            selected = displayMode == LyricsTranslationDisplayMode.Replace,
            onClick = { audioPreferences.lyricsTranslationDisplayMode.set(LyricsTranslationDisplayMode.Replace) },
            label = { Text("Replace Original", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
            modifier = Modifier.weight(1f),
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Filter
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          placeholder = { Text("Search language...") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Language List
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          items(filteredLanguages, key = { it.code }) { lang ->
            val isSelected = state.isTranslationActive && state.targetLanguage.equals(lang.code, ignoreCase = true)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                  audioPreferences.lyricsTargetLanguage.set(lang.code)
                  viewModel.translateLyrics(lang.code)
                  onDismiss()
                }
                .background(
                  if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                  else MaterialTheme.colorScheme.surface,
                )
                .padding(horizontal = 10.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(
                selected = isSelected,
                onClick = null,
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = lang.displayName,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                  color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (lang.isRomanization) {
                  Text(
                    text = lang.subtitle ?: "Pronunciation / Romanized",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                  )
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Done", fontWeight = FontWeight.Bold)
      }
    },
  )
}
