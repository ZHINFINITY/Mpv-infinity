/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.infinity.mpvz.ui.preferences

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.domain.recentlyplayed.repository.RecentlyPlayedRepository
import app.infinity.mpvz.preferences.AdvancedPreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.preferences.components.SwitchPreference
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.LocalShowSettingsBackArrow
import app.infinity.mpvz.ui.utils.popSafely
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.koin.compose.koinInject
import java.io.File

@Serializable
object ExtraFeaturesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val preferences = koinInject<AdvancedPreferences>()
    val recentlyPlayedRepository = koinInject<RecentlyPlayedRepository>()
    val backStack = LocalBackStack.current
    val smartCollections by preferences.enableSmartCollections.collectAsState()
    val watchStatistics by preferences.enableWatchStatistics.collectAsState()
    val playbackDiagnostics by preferences.enablePlaybackDiagnostics.collectAsState()
    val libraryHealth by preferences.enableLibraryHealthReport.collectAsState()
    val qualityFilters by preferences.enableMediaQualityFilters.collectAsState()
    val activityLog by preferences.enableLibraryActivityLog.collectAsState()
    val showExtraData = smartCollections || watchStatistics || playbackDiagnostics || libraryHealth || qualityFilters || activityLog
    val recentEntries by produceState(emptyList(), showExtraData) {
      value =
        if (showExtraData) {
          withContext(Dispatchers.IO) { recentlyPlayedRepository.getRecentlyPlayed(200) }
        } else {
          emptyList()
        }
    }
    val recentlyPlayedCount = recentEntries.size
    val partiallyWatchedCount = recentEntries.count { it.duration > 0L }
    val missingFileCount by produceState(0, recentEntries, libraryHealth) {
      value =
        if (libraryHealth) {
          withContext(Dispatchers.IO) {
            recentEntries.count { it.filePath.startsWith("/") && !File(it.filePath).exists() }
          }
        } else {
          0
        }
    }
    val highQualityCount = recentEntries.count { it.width >= 1920 || it.height >= 1080 }
    val mediaCandidateCount = recentEntries.count { it.fileName.substringAfterLast('.', "").lowercase() in setOf("mkv", "mp4", "webm", "avi") }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(stringResource(R.string.extra_features_title)) },
          navigationIcon = {
            if (LocalShowSettingsBackArrow.current) {
              IconButton(onClick = { backStack.popSafely() }) {
                Icon(Icons.RoundedFilled.ArrowBack, contentDescription = stringResource(R.string.back))
              }
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.extra_features_section_safe),
              modifier = Modifier.padding(top = 8.dp),
            )
          }
          item {
            Text(
              text = stringResource(R.string.extra_features_intro),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
          item {
            SwitchPreference(
              value = smartCollections,
              onValueChange = { preferences.enableSmartCollections.set(it) },
              title = { Text(stringResource(R.string.extra_features_smart_collections)) },
              summary = { Text(stringResource(R.string.extra_features_smart_collections_summary)) },
            )
          }
          if (smartCollections) {
            item { ExtraFeatureStatus(stringResource(R.string.extra_features_smart_collections_count, recentlyPlayedCount, partiallyWatchedCount)) }
          }
          item {
            SwitchPreference(
              value = watchStatistics,
              onValueChange = { preferences.enableWatchStatistics.set(it) },
              title = { Text(stringResource(R.string.extra_features_watch_statistics)) },
              summary = { Text(stringResource(R.string.extra_features_watch_statistics_summary)) },
            )
          }
          if (watchStatistics) {
            item { ExtraFeatureStatus(stringResource(R.string.extra_features_watch_statistics_count, recentlyPlayedCount)) }
          }
          item {
            SwitchPreference(
              value = playbackDiagnostics,
              onValueChange = { preferences.enablePlaybackDiagnostics.set(it) },
              title = { Text(stringResource(R.string.extra_features_playback_diagnostics)) },
              summary = { Text(stringResource(R.string.extra_features_playback_diagnostics_summary)) },
            )
          }
          if (playbackDiagnostics) {
            item { ExtraFeatureStatus(stringResource(R.string.extra_features_playback_diagnostics_status)) }
          }
          item {
            SwitchPreference(
              value = libraryHealth,
              onValueChange = { preferences.enableLibraryHealthReport.set(it) },
              title = { Text(stringResource(R.string.extra_features_library_health)) },
              summary = { Text(stringResource(R.string.extra_features_library_health_summary)) },
            )
          }
          if (libraryHealth) {
            item {
              ExtraFeatureStatus(
                text = stringResource(R.string.extra_features_library_health_count, missingFileCount),
                isWarning = missingFileCount > 0,
              )
            }
          }
          item {
            SwitchPreference(
              value = qualityFilters,
              onValueChange = { preferences.enableMediaQualityFilters.set(it) },
              title = { Text(stringResource(R.string.extra_features_quality_filters)) },
              summary = { Text(stringResource(R.string.extra_features_quality_filters_summary)) },
            )
          }
          if (qualityFilters) {
            item { ExtraFeatureStatus(stringResource(R.string.extra_features_quality_filters_count, highQualityCount, mediaCandidateCount)) }
          }
          item {
            SwitchPreference(
              value = activityLog,
              onValueChange = { preferences.enableLibraryActivityLog.set(it) },
              title = { Text(stringResource(R.string.extra_features_activity_log)) },
              summary = { Text(stringResource(R.string.extra_features_activity_log_summary)) },
            )
          }
          if (activityLog) {
            items(recentEntries.take(5), key = { it.id }) { entry ->
              Text(
                text = stringResource(R.string.extra_features_activity_log_entry, entry.fileName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 3.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ExtraFeatureStatus(
  text: String,
  isWarning: Boolean = false,
) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
  )
}
