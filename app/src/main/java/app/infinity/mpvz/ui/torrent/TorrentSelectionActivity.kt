/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.torrent

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.infinity.mpvz.database.repository.NetworkStreamEntryRepository
import app.infinity.mpvz.domain.torrent.TorrentStreamingEngine
import app.infinity.mpvz.repository.wyzie.WyzieSearchRepository
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.theme.MpvInfinityTheme
import app.infinity.mpvz.utils.media.MediaUtils
import org.koin.android.ext.android.inject

class TorrentSelectionActivity : AppCompatActivity() {
  private companion object { const val DIAG_TAG = "MpvCatalogDiag" }
  private val torrentStreamingEngine: TorrentStreamingEngine by inject()
  private val streamEntryRepository: NetworkStreamEntryRepository by inject()
  private val wyzieSearchRepository: WyzieSearchRepository by inject()
  private val viewModel: TorrentSelectionViewModel by viewModels {
    TorrentSelectionViewModel.factory(
      torrentStreamingEngine = torrentStreamingEngine,
      streamEntryRepository = streamEntryRepository,
      wyzieSearchRepository = wyzieSearchRepository,
    )
  }

  private var playerLaunched = false

  override fun onResume() {
    super.onResume()
    if (playerLaunched) {
      playerLaunched = false
      viewModel.onPlayerReturned()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val source = extractTorrentSource(intent)
    Log.i(DIAG_TAG, "torrent selection entry sourceKind=${source?.let { if (it.startsWith("http")) "http" else if (it.startsWith("magnet")) "magnet" else "other" } ?: "none"}")
    if (source.isDirectPlayableUrl()) {
      Log.i(DIAG_TAG, "direct playback dispatch urlHost=${runCatching { Uri.parse(source).host }.getOrNull()}")
      startActivity(Intent(this, PlayerActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(source)
        putExtra(Intent.EXTRA_STREAM, Uri.parse(source))
        putExtra(MediaUtils.EXTRA_MEDIA_TITLE, intent.getStringExtra(MediaUtils.EXTRA_MEDIA_TITLE).orEmpty())
        putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, source)
      })
      finishWithoutAnimation()
      return
    }
    if (source.isNullOrBlank()) {
      finishWithoutAnimation()
      return
    }

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = closePicker()
      },
    )

    viewModel.initialize(torrentInput(source, intent))

    setContent {
      val state by viewModel.uiState.collectAsState()
      LaunchedEffect(viewModel) { viewModel.launches.collect(::openPlayer) }
      MpvInfinityTheme {
        TorrentSelectionScreen(
          state = state,
          onBack = ::closePicker,
          onRetry = viewModel::retry,
          onSelect = viewModel::select,
        )
      }
    }
  }

  private fun torrentInput(
    source: String,
    intent: Intent,
    season: Int? = intent.getIntExtra("episode_season", -1).takeIf { it >= 0 },
    episode: Int? = intent.getIntExtra("episode_number", -1).takeIf { it >= 0 },
  ): TorrentSelectionInput {
    val title = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_TITLE)
    val description = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION)
    return TorrentSelectionInput(
      source = source,
      title = title ?: intent.getStringExtra("title") ?: intent.getStringExtra("introdb_title"),
      description = description ?: intent.getStringExtra("description") ?: intent.getStringExtra("overview"),
      posterUrl = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL),
      backdropUrl = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL),
      season = season,
      episode = episode,
      episodeTitle = intent.getStringExtra("episode_title"),
      episodeOverview = intent.getStringExtra("episode_overview"),
      episodeThumbnail = intent.getStringExtra("episode_thumbnail"),
      seasonsJson = intent.getStringExtra("seasons_json"),
      fileIndex = intent.getIntExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, -1).takeIf { it >= 0 },
    )
  }

  private fun openPlayer(request: TorrentSelectionLaunch) {
    if (playerLaunched || isFinishing) return
    playerLaunched = true
    if (request.isExternal) {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.source)))
      finishWithoutAnimation()
      return
    }
    val playbackIntent = Intent(intent).apply {
      action = Intent.ACTION_VIEW
      data = Uri.parse(request.source)
      setClass(this@TorrentSelectionActivity, PlayerActivity::class.java)
      addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
      putExtra("title", request.file.name)
      putExtra(MediaUtils.EXTRA_MEDIA_TITLE, request.file.name)
      putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, request.source)
      putExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, request.file.index)
      putExtra(MediaUtils.EXTRA_TORRENT_PREPARATION_ID, request.preparationId)
      putExtra("is_audio", request.file.mimeType.startsWith("audio/"))
      if (request.headers.isNotEmpty()) {
        putExtra("headers", request.headers.entries.flatMap { listOf(it.key, it.value) }.toTypedArray())
      }
    }
    startActivity(playbackIntent)
    // Keep this picker underneath the player so Back returns to the episode list instead of
    // dropping all the way to the stream home screen.
  }

  private fun closePicker() {
    if (!playerLaunched) viewModel.cancel()
    finishWithoutAnimation()
  }

  @Suppress("DEPRECATION")
  private fun finishWithoutAnimation() {
    finish()
    overridePendingTransition(0, 0)
  }

  private fun extractTorrentSource(intent: Intent?): String? {
    intent ?: return null
    intent.getStringExtra(MediaUtils.EXTRA_TORRENT_SOURCE)?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    intent.dataString?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    if (intent.action == Intent.ACTION_SEND) {
      val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
      }
      stream?.toString()?.takeIf(String::isNotBlank)?.let { return it }
      intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    }
    return null
  }
}

private fun String?.isDirectPlayableUrl(): Boolean {
  val value = this?.trim().orEmpty()
  if (!value.startsWith("http://") && !value.startsWith("https://")) return false
  val path = value.substringBefore('?').substringBefore('#').lowercase()
  return !path.endsWith(".torrent")
}
