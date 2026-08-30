/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.preferences

import app.infinity.mpvz.BuildConfig
import app.infinity.mpvz.preferences.preference.PreferenceStore
import app.infinity.mpvz.preferences.preference.getEnum
import app.infinity.mpvz.ui.player.NotificationStyle

class AdvancedPreferences(
  preferenceStore: PreferenceStore,
) {
  val mpvConfStorageUri = preferenceStore.getString("mpv_conf_storage_location_uri")
  val mpvConf = preferenceStore.getString("mpv.conf")
  val inputConf = preferenceStore.getString("input.conf")

  val verboseLogging = preferenceStore.getBoolean("verbose_logging", BuildConfig.BUILD_TYPE != "release")

  val enabledStatisticsPage = preferenceStore.getInt("enabled_stats_page", 0)
  val showVideoFormatStatus = preferenceStore.getBoolean("show_video_format_status", true)

  val enableRecentlyPlayed = preferenceStore.getBoolean("enable_recently_played", true)

  val enableLuaScripts = preferenceStore.getBoolean("enable_lua_scripts", false)
  val selectedLuaScripts = preferenceStore.getStringSet("selected_lua_scripts", emptySet())

  val enableP2pStreaming = preferenceStore.getBoolean("enable_p2p_streaming", true)

  /** Amount of media data to download before starting torrent playback, in megabytes; 0 means auto. */
  val torrentStartupBufferMb = preferenceStore.getLong("torrent_startup_buffer_mb", 64L)

  /** Amount of data prioritized ahead of the current torrent playback position, in megabytes; 0 means auto. */
  val torrentReadAheadMb = preferenceStore.getLong("torrent_read_ahead_mb", 64L)

  /** Maximum torrent payload cache in megabytes; 0 means use the maximum safe free space. */
  val torrentCacheMb = preferenceStore.getLong("torrent_cache_mb", 0L)

  val enableHlsProxy = preferenceStore.getBoolean("enable_hls_proxy", true)

  /** Notification style for the playback service (Media vs Progress-centric on Android 16+). */
  val notificationStyle = preferenceStore.getEnum("notification_style", NotificationStyle.Media)
}
