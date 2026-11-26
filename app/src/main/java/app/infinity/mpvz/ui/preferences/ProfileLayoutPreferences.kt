/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.infinity.mpvz.ui.preferences

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.ProfileSection
import app.infinity.mpvz.preferences.preference.collectAsState

@Composable
internal fun ProfileLayoutPreferences(preferences: AppearancePreferences) {
  val order by preferences.profileSectionOrder.collectAsState()
  val hidden by preferences.profileHiddenSections.collectAsState()
  PreferenceSectionHeader(
    title = stringResource(R.string.profile_layout_sections),
    modifier = Modifier
      .settingsSearchTarget(R.string.profile_layout_title)
      .settingsSearchTarget(R.string.profile_layout_sections),
  )
  ExpressivePreferenceGroup {
    ReorderableTabPreferences(
      tabs = order.map { id ->
        ReorderableTabItem(
          id = id,
          title = stringResource(ProfileSection.entries.first { it.name == id }.titleRes),
          summary = "",
          enabled = id !in hidden,
        )
      },
      onEnabledChange = { id, enabled ->
        preferences.profileHiddenSections.set(if (enabled) hidden - id else hidden + id)
      },
      onMove = { id, destination ->
        val moved = order.toMutableList()
        if (moved.remove(id)) {
          moved.add(destination.coerceIn(0, moved.size), id)
          preferences.profileSectionOrder.set(moved)
        }
      },
    )
  }
}
