/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.preferences

import app.infinity.mpvz.preferences.preference.PreferenceStore

class DownloadPreferences(
  preferenceStore: PreferenceStore,
) {
  /** Persisted SAF tree URI of the user-picked download folder; blank = app-private default. */
  val downloadLocationTreeUri = preferenceStore.getString("download_location_tree_uri", "")

  /** Resolved filesystem path of the picked folder, cached for display and fast reuse. */
  val downloadLocationPath = preferenceStore.getString("download_location_path", "")
}
