/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
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
  val mpvConfOverrides = preferenceStore.getStringSet("mpv_conf_overrides", emptySet())

  val verboseLogging = preferenceStore.getBoolean("verbose_logging", BuildConfig.BUILD_TYPE != "release")
  val showFrameTimeOverlay = preferenceStore.getBoolean("show_frame_time_overlay", false)

  val enabledStatisticsPage = preferenceStore.getInt("enabled_stats_page", 0)

  val enableRecentlyPlayed = preferenceStore.getBoolean("enable_recently_played", true)

  val enableLuaScripts = preferenceStore.getBoolean("enable_lua_scripts", false)
  val selectedLuaScripts = preferenceStore.getStringSet("selected_lua_scripts", emptySet())

  val enableP2pStreaming = preferenceStore.getBoolean("enable_p2p_streaming", true)

  val enableHlsProxy = preferenceStore.getBoolean("enable_hls_proxy", true)

  val enableSmartCollections = preferenceStore.getBoolean("enable_smart_collections", false)
  val enableWatchStatistics = preferenceStore.getBoolean("enable_watch_statistics", false)
  val enablePlaybackDiagnostics = preferenceStore.getBoolean("enable_playback_diagnostics", false)
  val enableLibraryHealthReport = preferenceStore.getBoolean("enable_library_health_report", false)
  val enableMediaQualityFilters = preferenceStore.getBoolean("enable_media_quality_filters", false)
  val enableLibraryActivityLog = preferenceStore.getBoolean("enable_library_activity_log", false)

  /** Notification style for the playback service (Media vs Progress-centric on Android 16+). */
  val notificationStyle = preferenceStore.getEnum("notification_style", NotificationStyle.Media)
}
