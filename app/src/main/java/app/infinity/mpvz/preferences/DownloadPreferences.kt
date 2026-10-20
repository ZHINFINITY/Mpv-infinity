/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
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
