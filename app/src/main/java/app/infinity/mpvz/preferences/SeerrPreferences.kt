/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore

class SeerrPreferences(
  preferenceStore: PreferenceStore,
) {
  val serverUrl = preferenceStore.getString("seerr_server_url", "")
  val apiKey = preferenceStore.getString("seerr_api_key", "")
  val useJellyfinAuth = preferenceStore.getBoolean("seerr_use_jellyfin_auth", true)
  val userEmail = preferenceStore.getString("seerr_user_email", "")
  val userDisplayName = preferenceStore.getString("seerr_user_display_name", "")
  val username = preferenceStore.getString("seerr_username", "")
  val userAvatar = preferenceStore.getString("seerr_user_avatar", "")
  val userId = preferenceStore.getInt("seerr_user_id", -1)
  val userPermissions = preferenceStore.getLong("seerr_user_permissions", 0L)
  val isLoggedIn = preferenceStore.getBoolean("seerr_is_logged_in", false)

  fun clearSession() {
    isLoggedIn.set(false)
    userId.set(-1)
    userDisplayName.set("")
    username.set("")
    userEmail.set("")
    userAvatar.set("")
    userPermissions.set(0L)
    apiKey.set("")
  }
}
