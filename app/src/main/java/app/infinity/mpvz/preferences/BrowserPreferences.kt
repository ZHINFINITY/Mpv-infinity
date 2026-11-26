/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.preferences

import app.infinity.mpvz.preferences.preference.Preference
import app.infinity.mpvz.preferences.preference.PreferenceStore
import app.infinity.mpvz.preferences.preference.getEnum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

import app.infinity.mpvz.ui.browser.music.MusicSortField
import app.infinity.mpvz.ui.browser.music.MusicSortOrder
import app.infinity.mpvz.ui.browser.music.MusicThumbnailDefaults
import app.infinity.mpvz.ui.browser.music.MusicViewMode

/**
 * Preferences for the video browser (folder and video lists)
 */
class BrowserPreferences(
  preferenceStore: PreferenceStore,
  context: android.content.Context,
) {
  companion object {
    internal const val ONBOARDING_COMPLETED_KEY = "onboarding_completed"
  }

  // Folder sorting preferences
  val folderSortType = preferenceStore.getEnum("folder_sort_type", FolderSortType.Title)
  val folderSortOrder = preferenceStore.getEnum("folder_sort_order", SortOrder.Ascending)

  // Video sorting preferences
  val videoSortType = preferenceStore.getEnum("video_sort_type", VideoSortType.Title)
  val videoSortOrder = preferenceStore.getEnum("video_sort_order", SortOrder.Ascending)

  // Music view mode and sorting preferences
  val musicViewMode = preferenceStore.getEnum("music_view_mode", MusicViewMode.LIST)
  val musicSortField = preferenceStore.getEnum("music_sort_field", MusicSortField.TITLE)
  val musicSortOrder = preferenceStore.getEnum("music_sort_order", MusicSortOrder.ASCENDING)

  // Network sorting preferences
  val networkSortType = preferenceStore.getEnum("network_sort_type", NetworkSortType.Title)
  val networkSortOrder = preferenceStore.getEnum("network_sort_order", SortOrder.Ascending)
  val networkLayoutMode = preferenceStore.getEnum("network_layout_mode", MediaLayoutMode.LIST)
  val jellyfinLayoutMode = preferenceStore.getEnum("jellyfin_layout_mode", MediaLayoutMode.GRID)
  val audiobookSortType = preferenceStore.getEnum("audiobook_sort_type", AudiobookSortType.Title)
  val audiobookSortOrder = preferenceStore.getEnum("audiobook_sort_order", SortOrder.Ascending)
  val audiobookLayoutMode = preferenceStore.getEnum("audiobook_layout_mode", MediaLayoutMode.GRID)

  val folderViewMode = preferenceStore.getEnum("folder_view_mode", FolderViewMode.AlbumView)
  val dualPaneForTablet = preferenceStore.getBoolean("dual_pane_for_tablet", true)

  private val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
  val maxColumns = if (isTablet) 8 else 4

  // IPTV playlist index settings are intentionally separate from both the home browser and
  // the generic network browser. Changing one surface must never change another surface.
  val iptvLayoutMode = preferenceStore.getEnum("iptv_layout_mode", MediaLayoutMode.LIST)
  val iptvManualGridColumnsEnabled = preferenceStore.getBoolean("iptv_manual_grid_columns_enabled", false)
  private val _iptvGridColumnsPortrait = preferenceStore.getInt("iptv_grid_columns_portrait", 2)
  private val _iptvGridColumnsLandscape = preferenceStore.getInt("iptv_grid_columns_landscape", 4)
  val iptvGridColumnsPortrait: Preference<Int> = CoercedPreference(_iptvGridColumnsPortrait, maxColumns)
  val iptvGridColumnsLandscape: Preference<Int> = CoercedPreference(_iptvGridColumnsLandscape, 8)

  private val _folderGridColumnsPortrait =
    preferenceStore.getInt(
      "folder_grid_columns_portrait",
      if (isTablet) 4 else 3,
    )
  private val _folderGridColumnsLandscape = preferenceStore.getInt("folder_grid_columns_landscape", 5)

  private val _videoGridColumnsPortrait = preferenceStore.getInt("video_grid_columns_portrait", if (isTablet) 4 else 2)
  private val _videoGridColumnsLandscape = preferenceStore.getInt("video_grid_columns_landscape", 4)

  val folderGridColumnsPortrait: Preference<Int> = CoercedPreference(_folderGridColumnsPortrait, maxColumns)
  val folderGridColumnsLandscape: Preference<Int> = CoercedPreference(_folderGridColumnsLandscape, 8)

  val videoGridColumnsPortrait: Preference<Int> = CoercedPreference(_videoGridColumnsPortrait, maxColumns)
  val videoGridColumnsLandscape: Preference<Int> = CoercedPreference(_videoGridColumnsLandscape, 8)

  val showExtensionField = preferenceStore.getBoolean("show_extension_field", false)
  val showDurationField = preferenceStore.getBoolean("show_duration_field", true)

  // Visibility preferences for video card chips
  val showVideoThumbnails = preferenceStore.getBoolean("show_video_thumbnails", true)
  val thumbnailMode = preferenceStore.getEnum("thumbnail_mode", ThumbnailMode.Smart)
  val thumbnailQuality = preferenceStore.getEnum("thumbnail_quality", ThumbnailQuality.High)
  val thumbnailFramePosition = preferenceStore.getFloat("thumbnail_frame_position", 33f)
  val showSizeChip = preferenceStore.getBoolean("show_size_chip", true)

  // Metadata-dependent chips (disabled by default for better performance)
  val showResolutionChip = preferenceStore.getBoolean("show_resolution_chip", false)
  val showFramerateInResolution = preferenceStore.getBoolean("show_framerate_in_resolution", false)
  val showSubtitleIndicator = preferenceStore.getBoolean("show_subtitle_indicator", false)
  val showCodecSupportIndicator = preferenceStore.getBoolean("show_codec_support_indicator", false)
  val showProgressBar = preferenceStore.getBoolean("show_progress_bar", true)
  val centerGridTitles = preferenceStore.getBoolean("center_grid_titles", true)
  val mediaLayoutMode = preferenceStore.getEnum("media_layout_mode", MediaLayoutMode.LIST)
  val folderViewFolderLayoutMode = preferenceStore.getEnum("folder_view_folder_layout_mode", MediaLayoutMode.LIST)
  val folderViewVideoLayoutMode = preferenceStore.getEnum("folder_view_video_layout_mode", MediaLayoutMode.LIST)
  val separateFolderVideoLayout = preferenceStore.getBoolean("separate_folder_video_layout", false)
  val manualGridColumnsEnabled = preferenceStore.getBoolean("manual_grid_columns_enabled", false)
  val musicCoverArtSize = preferenceStore.getInt("music_cover_art_size", MusicThumbnailDefaults.DEFAULT_DP)

  // Visibility preferences for folder card chips
  val showTotalVideosChip = preferenceStore.getBoolean("show_total_videos_chip", true)

  // Metadata-dependent chips (disabled by default for better performance)
  val showTotalDurationChip = preferenceStore.getBoolean("show_total_duration_chip", false)
  val showTotalSizeChip = preferenceStore.getBoolean("show_total_size_chip", true)
  val showDateChip = preferenceStore.getBoolean("show_date_chip", false)
  val showFolderPath = preferenceStore.getBoolean("show_folder_path", true)
  val showFolderThumbnails = preferenceStore.getBoolean("show_folder_thumbnails", false)

  // Auto-scroll to last played media preference (like MX Player)
  val autoScrollToLastPlayed = preferenceStore.getBoolean("auto_scroll_to_last_played", false)

  // Maximum single-child folder levels skipped in one Tree View navigation step.
  val treeFlattenDepth = preferenceStore.getEnum("tree_flatten_depth", TreeFlattenDepth.Unlimited)
  val includeAudioBrowser = preferenceStore.getBoolean("include_audio_browser", false)
  val minimumAudioDurationSeconds = preferenceStore.getInt("minimum_audio_duration_seconds", 0)
  val mediaLibraryType = preferenceStore.getEnum("media_library_type", MediaLibraryType.Video)

  // Set by onboarding when the user opts into the quick tour; cleared once the tour ran.
  val demoTutorialPending = preferenceStore.getBoolean("demo_tutorial_pending", false)

  // True once the user finished (or skipped through) the first-run permission flow.
  val onboardingCompleted = preferenceStore.getBoolean(ONBOARDING_COMPLETED_KEY, false)

  // Watched threshold preference (percentage 1-100)
  val watchedThreshold = preferenceStore.getInt("watched_threshold", 95)

  // When deleting a folder, delete all files instead of only media files
  val deleteFolderAllContents = preferenceStore.getBoolean("delete_folder_all_contents", false)

  // FR-09: the snapshot library defaults to newest capture first.
  val snapshotSortType = preferenceStore.getEnum("snapshot_sort_type", SnapshotSortType.CapturedAt)
  val snapshotSortOrder = preferenceStore.getEnum("snapshot_sort_order", SortOrder.Descending)
  val snapshotFolderSortType = preferenceStore.getEnum("snapshot_folder_sort_type", SnapshotFolderSortType.Name)
  val snapshotFolderSortOrder = preferenceStore.getEnum("snapshot_folder_sort_order", SortOrder.Ascending)
  val snapshotLibraryView =
    BrowserPageViewPreferences(
      preferenceStore,
      "snapshot_library",
      folderDefaults = true,
      defaultLayoutMode = MediaLayoutMode.GRID,
      inheritGlobalLayout = false,
    )
  val snapshotItemView =
    BrowserPageViewPreferences(
      preferenceStore,
      "snapshot_item",
      defaultLayoutMode = MediaLayoutMode.GRID,
      inheritGlobalLayout = false,
    )
  val recentView = BrowserPageViewPreferences(preferenceStore, "recent", thumbnailsByDefault = true)
  val playlistView =
    BrowserPageViewPreferences(
      preferenceStore,
      "playlist_library",
      folderDefaults = true,
      thumbnailsByDefault = true,
      inheritGlobalThumbnails = false,
    )
}

/**
 * Per-page overrides of the browser card settings: every screen (recents, playlists, snapshots)
 * keeps its own layout, columns and chip visibility instead of all pages sharing one set.
 */
class BrowserPageViewPreferences internal constructor(
  private val preferenceStore: PreferenceStore,
  private val prefix: String,
  folderDefaults: Boolean = false,
  /**
   * The layout a page starts in. Snapshots pass GRID: a photo album that opens as a list gets the
   * first impression wrong, and the browser's own LIST default is about file names, not pictures.
   */
  defaultLayoutMode: MediaLayoutMode = MediaLayoutMode.LIST,
  /**
   * Whether an unset page layout is seeded from the browser's global `media_layout_mode`. Snapshots
   * opt out so their grid default is not dragged to the list by an unrelated setting.
   */
  inheritGlobalLayout: Boolean = true,
  thumbnailsByDefault: Boolean = !folderDefaults,
  inheritGlobalThumbnails: Boolean = true,
) {
  val layoutMode =
    if (inheritGlobalLayout) {
      preferenceStore
        .getEnum("${prefix}_layout_mode", defaultLayoutMode)
        .initializeFrom(preferenceStore.getEnum("media_layout_mode", defaultLayoutMode))
    } else {
      preferenceStore.getEnum("${prefix}_layout_mode", defaultLayoutMode)
    }
  val manualGridColumnsEnabled = boolean("manual_grid_columns_enabled", false)
  val gridColumnsPortrait = integer(
    "grid_columns_portrait",
    0,
    if (folderDefaults) "folder_grid_columns_portrait" else "video_grid_columns_portrait",
  )
  val gridColumnsLandscape = integer(
    "grid_columns_landscape",
    0,
    if (folderDefaults) "folder_grid_columns_landscape" else "video_grid_columns_landscape",
  )
  val showThumbnails =
    if (inheritGlobalThumbnails) {
      boolean("show_thumbnails", thumbnailsByDefault, if (folderDefaults) "show_folder_thumbnails" else "show_video_thumbnails")
    } else {
      // Playlist covers start visible independently of the folder-thumbnail setting.
      preferenceStore.getBoolean("${prefix}_show_thumbnails", thumbnailsByDefault)
    }
  val unlimitedNameLines = boolean("unlimited_name_lines", false)
  val centerGridTitles = boolean("center_grid_titles", true)
  val showExtensionField = boolean("show_extension_field", false)
  val showDurationField = boolean("show_duration_field", false)
  val showSizeChip = boolean("show_size_chip", false)
  val showResolutionChip = boolean("show_resolution_chip", false)
  val showFramerateInResolution = boolean("show_framerate_in_resolution", false)
  val showSubtitleIndicator = boolean("show_subtitle_indicator", false)
  val showCodecSupportIndicator = boolean("show_codec_support_indicator", false)
  val showProgressBar = boolean("show_progress_bar", true)
  val grayscaleWatchedThumbnails = boolean("grayscale_watched_thumbnails", true)
  val showDateChip = boolean("show_date_chip", false)
  val showItemCount = boolean("show_item_count", true, "show_total_videos_chip")
  val showLocation = boolean("show_location", true, "show_playlist_location")
  val showCategory = boolean("show_category", true, "show_playlist_category")
  val showStreamDetails = boolean("show_stream_details", true, "show_playlist_stream_details")
  val audioCoverArtSize = integer("audio_cover_art_size", 48, "music_cover_art_size")

  private fun boolean(key: String, defaultValue: Boolean, legacyKey: String = key): Preference<Boolean> =
    preferenceStore.getBoolean("${prefix}_$key", defaultValue)
      .initializeFrom(preferenceStore.getBoolean(legacyKey, defaultValue))

  private fun integer(key: String, defaultValue: Int, legacyKey: String): Preference<Int> =
    preferenceStore.getInt("${prefix}_$key", defaultValue)
      .initializeFrom(preferenceStore.getInt(legacyKey, defaultValue))

  private fun <T> Preference<T>.initializeFrom(legacy: Preference<T>): Preference<T> =
    apply {
      if (!isSet()) set(legacy.get())
    }
}

/** Sort fields offered on the Snapshots tab. */
enum class SnapshotSortType {
  CapturedAt,
  VideoTitle,
  Position,
}

/** Sort fields offered on the snapshot library's folder list. */
enum class SnapshotFolderSortType {
  Name,
  CreatedAt,
  ItemCount,
}

/**
 * Sort order options
 */
enum class SortOrder {
  Ascending,
  Descending,
  ;

  val isAscending: Boolean
    get() = this == Ascending
}

/**
 * Folder sorting options
 */
enum class FolderSortType {
  Title,
  Date,
  Size,
  VideoCount,
  ;

  val displayName: String
    get() =
      when (this) {
        Title -> "Title"
        Date -> "Date"
        Size -> "Size"
        VideoCount -> "Count"
      }
}

/**
 * Video sorting options
 */
enum class VideoSortType {
  Title,
  Duration,
  Date,
  Size,
  ;

  val displayName: String
    get() =
      when (this) {
        Title -> "Title"
        Duration -> "Duration"
        Date -> "Date"
        Size -> "Size"
      }
}

/**
 * Network sorting options
 */
enum class NetworkSortType {
  Title,
  Date,
  Size,
  ;

  val displayName: String
    get() =
      when (this) {
        Title -> "Title"
        Date -> "Date"
        Size -> "Size"
      }
}

/**
 * Folder view mode options
 */
enum class FolderViewMode {
  AlbumView,
  FileManager,
  MediaLibrary,
  ;

  val displayName: String
    get() =
      when (this) {
        AlbumView -> "Folder View"
        FileManager -> "Tree View"
        MediaLibrary -> "Media Library"
      }
}

enum class TreeFlattenDepth(
  val maxLevels: Int,
  val displayName: String,
) {
  Off(0, "Off (show every folder)"),
  One(1, "1 level"),
  Two(2, "2 levels"),
  Three(3, "3 levels"),
  Four(4, "4 levels"),
  Five(5, "5 levels"),
  Unlimited(-1, "Unlimited"),
}

enum class MediaLayoutMode {
  LIST,
  GRID,

  /**
   * Photo-wall layout: every image keeps its own aspect ratio and a wide frame spans two columns.
   * Only the snapshots' item view offers it — the enum is shared, the choices are not.
   */
  MOSAIC,
  ;

  val displayName: String
    get() =
      when (this) {
        LIST -> "List"
        GRID -> "Grid"
        MOSAIC -> "Mosaic"
      }
}

enum class AudiobookSortType {
  Title,
  Author,
  Duration,
  Progress,
  LastPlayed,
  DateAdded,
  ;

  val displayName: String
    get() = when (this) {
      Title -> "Title"
      Author -> "Author"
      Duration -> "Duration"
      Progress -> "Progress"
      LastPlayed -> "Recent"
      DateAdded -> "Date Added"
    }
}

enum class MediaLibraryType {
  Video,
  Audio,
}

enum class ThumbnailMode {
  Smart,
  FirstFrame,
  FrameAtPosition,
  EmbeddedThumbnail,
  ;

  val displayName: String
    get() =
      when (this) {
        Smart -> "Smart (embedded + 33%)"
        FirstFrame -> "First frame"
        FrameAtPosition -> "Frame position"
        EmbeddedThumbnail -> "Embedded thumbnail"
      }
}

enum class ThumbnailQuality(
  val maxSizePx: Int,
  val displayName: String,
) {
  Balanced(720, "Balanced (720p)"),
  High(1080, "High (1080p)"),
  Ultra(1440, "Ultra (1440p)"),
}

internal class CoercedPreference(
  private val delegate: Preference<Int>,
  private val maxVal: Int,
) : Preference<Int> {
  override fun key(): String = delegate.key()

  override fun get(): Int = delegate.get().coerceIn(1, maxVal)

  override fun set(value: Int) = delegate.set(value.coerceIn(1, maxVal))

  override fun isSet(): Boolean = delegate.isSet()

  override fun delete() = delegate.delete()

  override fun defaultValue(): Int = delegate.defaultValue().coerceIn(1, maxVal)

  override fun changes(): Flow<Int> = delegate.changes().map { it.coerceIn(1, maxVal) }

  override fun stateIn(scope: kotlinx.coroutines.CoroutineScope): StateFlow<Int> =
    changes().stateIn(scope, SharingStarted.Eagerly, get())
}
