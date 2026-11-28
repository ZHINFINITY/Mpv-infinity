/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.infinity.mpvz.domain.media.model.Video
import app.infinity.mpvz.domain.playbackstate.repository.PlaybackStateRepository
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.BrowserPreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.browser.videolist.buildVideoWithPlaybackInfo
import app.infinity.mpvz.ui.browser.videolist.videoPlaybackIdentifiers
import app.infinity.mpvz.ui.browser.videolist.VideoWithPlaybackInfo
import app.infinity.mpvz.utils.media.PlaybackStateEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Playback state for a page of cards: watched flags and progress bars without each card polling
 * the database. Playback saves arrive every few seconds while anything plays and name a single
 * item, so the map rebuilds per changed identifier instead of for every save.
 */
@Composable
internal fun rememberSwipePlaybackInfo(videos: List<Video>): Map<String, VideoWithPlaybackInfo> {
  val preferences = koinInject<BrowserPreferences>()
  val appearance = koinInject<AppearancePreferences>()
  val repository = koinInject<PlaybackStateRepository>()
  val threshold by preferences.watchedThreshold.collectAsState()
  val newDays by appearance.unplayedOldVideoDays.collectAsState()
  var playbackInfo by remember { mutableStateOf(emptyMap<String, VideoWithPlaybackInfo>()) }
  LaunchedEffect(videos, threshold, newDays) {
    var videosByIdentifier: Map<String, List<Video>>? = null
    PlaybackStateEvents.changes.onStart { emit("") }.collectLatest { identifier ->
      val index = videosByIdentifier
      if (identifier.isNotBlank() && index != null) {
        val affected = index[identifier] ?: return@collectLatest
        val current = playbackInfo
        playbackInfo =
          withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            current +
              affected.associate { video ->
                video.path to
                  buildVideoWithPlaybackInfo(
                    video = video,
                    playbackState = videoPlaybackIdentifiers(video).firstNotNullOfOrNull { repository.getVideoDataByTitle(it) },
                    currentTimeMillis = now,
                    newLabelDays = newDays,
                    watchedThreshold = threshold,
                  )
              }
          }
        return@collectLatest
      }
      val (rebuiltInfo, rebuiltIndex) =
        withContext(Dispatchers.IO) {
          val states = repository.getAllPlaybackStates().associateBy { it.mediaTitle }
          val now = System.currentTimeMillis()
          val byIdentifier = HashMap<String, MutableList<Video>>()
          val info =
            videos.associate { video ->
              val identifiers = videoPlaybackIdentifiers(video)
              identifiers.forEach { byIdentifier.getOrPut(it) { ArrayList(1) }.add(video) }
              video.path to
                buildVideoWithPlaybackInfo(
                  video = video,
                  playbackState = identifiers.firstNotNullOfOrNull(states::get),
                  currentTimeMillis = now,
                  newLabelDays = newDays,
                  watchedThreshold = threshold,
                )
            }
          info to byIdentifier
        }
      videosByIdentifier = rebuiltIndex
      playbackInfo = rebuiltInfo
    }
  }
  return playbackInfo
}
