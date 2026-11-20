/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.music

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity-side controller for the independent Music MediaSession. */
internal class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {
  val playbackState: StateFlow<MusicPlaybackSnapshot> = MusicPlaybackSession.state
  private val mutableController = MutableStateFlow<MediaController?>(null)
  val controller: StateFlow<MediaController?> = mutableController.asStateFlow()

  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var pendingLaunch: MusicQueueLaunch? = null
  private var appliedLaunchToken: String? = null
  private var restoreRequestGeneration = 0L

  init {
    connectToMusicService()
  }

  fun openLaunch(launch: MusicQueueLaunch) {
    restoreRequestGeneration++
    if (appliedLaunchToken == launch.token) return
    appliedLaunchToken = launch.token
    pendingLaunch = launch
    mutableController.value?.let { applyLaunch(it, launch) }
  }

  fun restoreLaunch(requestedToken: String?, fallback: MusicQueueLaunch?) {
    val generation = ++restoreRequestGeneration
    val app = getApplication<Application>()
    viewModelScope.launch {
      val restored =
        withContext(Dispatchers.IO) {
          restoreMusicPlaybackLaunch(
            snapshot = MusicPlaybackResumeStore.load(app),
            requestedToken = requestedToken,
            fallback = fallback,
            isUriAccessible = { uri -> isMusicPlaybackUriAccessible(app, uri) },
          )
        }
      if (generation != restoreRequestGeneration) return@launch
      restored?.let(::openLaunch)
    }
  }

  private fun connectToMusicService() {
    val app = getApplication<Application>()
    val token = SessionToken(app, ComponentName(app, MusicPlaybackService::class.java))
    val future = MediaController.Builder(app, token).buildAsync()
    controllerFuture = future
    future.addListener(
      {
        val connectedController = runCatching { future.get() }.getOrNull() ?: return@addListener
        mutableController.value = connectedController
        pendingLaunch?.let { applyLaunch(connectedController, it) }
      },
      ContextCompat.getMainExecutor(app),
    )
  }

  private fun applyLaunch(
    controller: MediaController,
    launch: MusicQueueLaunch,
  ) {
    if (launch.songs.isEmpty()) return
    val items =
      launch.songs.map { song ->
        song.toMusicMediaItem(launchToken = launch.token, launchSource = launch.launchSource)
      }
    val selectedIndex = launch.currentIndex.coerceIn(items.indices)
    val firstBatch = items.take(MAX_ITEMS_PER_TRANSACTION)
    val startPositionMs = launch.positionMs.takeIf { it > 0L } ?: C.TIME_UNSET
    controller.setMediaItems(
      firstBatch,
      selectedIndex.coerceAtMost(firstBatch.lastIndex),
      startPositionMs,
    )
    items.drop(firstBatch.size).chunked(MAX_ITEMS_PER_TRANSACTION).forEach { batch ->
      controller.addMediaItems(batch)
    }
    if (selectedIndex >= firstBatch.size) {
      controller.seekTo(selectedIndex, startPositionMs)
    }
    controller.prepare()
    controller.play()
    pendingLaunch = null
  }

  override fun onCleared() {
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controllerFuture = null
    mutableController.value = null
    super.onCleared()
  }

  private companion object {
    const val MAX_ITEMS_PER_TRANSACTION = 100
  }
}
