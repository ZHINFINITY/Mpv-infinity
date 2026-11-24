/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.music

import android.content.res.Configuration
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.presentation.components.RemoteImageLoader
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.controls.components.MiniAudioVisualizer
import app.infinity.mpvz.utils.media.MediaInfoOps
import java.util.Locale
import kotlin.math.roundToLong

private enum class MusicPlayerSheet {
  QUEUE,
  SOURCE_INFO,
}

private data class LoadedSourceAudioInfo(
  val codec: String,
  val format: MusicSourceAudioFormat?,
  val channels: Int? = null,
  val bitRateBps: Int? = null,
)

@Composable
internal fun MusicPlayerScreen(
  viewModel: MusicPlayerViewModel,
  onBackPress: () -> Unit,
) {
  val snapshot by viewModel.playbackState.collectAsStateWithLifecycle()
  val controller by viewModel.controller.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
  var loadedSourceInfo by remember(snapshot.currentUri) { mutableStateOf<LoadedSourceAudioInfo?>(null) }
  var openSheet by remember { mutableStateOf<MusicPlayerSheet?>(null) }
  val sourceInfoCache = remember { mutableMapOf<String, LoadedSourceAudioInfo>() }

  LaunchedEffect(snapshot.currentUri, snapshot.title) {
    val uriText = snapshot.currentUri ?: return@LaunchedEffect
    sourceInfoCache[uriText]?.let {
      loadedSourceInfo = it
      return@LaunchedEffect
    }
    loadedSourceInfo = null
    val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return@LaunchedEffect
    // Stream URLs (Jellyfin "stream?static=true", Navidrome "stream.view") have no useful
    // lastPathSegment; the queue item's own extension is the better MediaInfo hint.
    val fileName =
      snapshot.sourceExtension?.let { "audio.$it" }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
        ?: "audio"
    val audio =
      MediaInfoOps.getMediaInfo(context, uri, fileName)
        .getOrNull()
        ?.audioStreams
        ?.firstOrNull()
    val extension =
      snapshot.sourceExtension ?: fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val sourceCodec =
      listOfNotNull(audio?.format?.takeIf { it.isNotBlank() }, extension.takeIf { it.isNotBlank() })
        .joinToString(" ")
    val sourceFormat =
      audio?.let {
        MusicSourceAudioFormatPolicy.fromSourceMetadata(
          bitDepthText = it.bitDepth,
          sampleRateText = it.samplingRate,
          formatProfileText = it.formatProfile,
        )
      }
    val channels = audio?.channels?.trim()?.removeSuffix("ch")?.trim()?.toIntOrNull()
    val bitRateBps = audio?.bitRate?.trim()?.toIntOrNull()?.times(1_000)
    LoadedSourceAudioInfo(sourceCodec, sourceFormat, channels, bitRateBps)
      .also { cached ->
        loadedSourceInfo = cached
        sourceInfoCache[uriText] = cached
      }
  }

  val losslessLabel =
    MusicSourceAudioFormatPolicy.headerLabel(
      codecOrExtension =
        loadedSourceInfo?.codec?.takeIf { it.isNotBlank() }
          ?: snapshot.sourceExtension?.takeIf { it.isNotBlank() },
      sourceFormat = loadedSourceInfo?.format,
    )

  // Ambience backdrop: dominant album-art colors behind the player, for both portrait and
  // landscape layouts. Mirrors the MPV video ambience so both engines get the treatment.
  var ambienceColors by remember(snapshot.artworkUri) {
    mutableStateOf<AmbienceColors?>(null)
  }
  LaunchedEffect(snapshot.artworkUri) {
    val art = snapshot.artworkUri?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
    val bitmap =
      RemoteImageLoader.getFromMemory(art)
        ?: RemoteImageLoader.load(context, OkHttpClient(), art)
    val colors = bitmap?.let { withContext(Dispatchers.Default) { AmbienceColors.from(it) } }
    if (colors != null) ambienceColors = colors
  }

  BackHandler(onBack = onBackPress)
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.surface,
  ) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    Box(
      Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors =
              listOf(
                ambienceColors?.base?.let(::Color) ?: surfaceColor,
                ambienceColors?.accent?.let { Color(it).copy(alpha = 0.55f) } ?: surfaceColor,
                surfaceColor,
              ),
          ),
        ),
    )
    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      if (landscape && maxWidth > maxHeight) {
        Row(
          modifier = Modifier.fillMaxSize(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          MusicArtwork(
            artworkUri = snapshot.artworkUri,
            isPlaying = snapshot.isPlaying,
            modifier = Modifier
              .weight(0.36f)
              .fillMaxHeight(0.88f)
              .aspectRatio(1f)
              .padding(18.dp),
          )
          Column(
            modifier = Modifier
              .weight(0.64f)
              .fillMaxHeight()
              .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            MusicHeader(
              landscape = true,
              losslessLabel = losslessLabel,
              onBackPress = onBackPress,
              onInfo = { openSheet = MusicPlayerSheet.SOURCE_INFO },
              modifier = Modifier.fillMaxWidth(),
            )
            MusicTrackInfo(snapshot = snapshot, modifier = Modifier.fillMaxWidth())
            MusicTimeline(snapshot = snapshot, controller = controller, modifier = Modifier.fillMaxWidth())
            MusicTransport(snapshot = snapshot, controller = controller, modifier = Modifier.fillMaxWidth())
            MusicSecondaryControls(
              snapshot = snapshot,
              controller = controller,
              onQueue = { openSheet = MusicPlayerSheet.QUEUE },
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      } else {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          MusicHeader(
            landscape = false,
            losslessLabel = losslessLabel,
            onBackPress = onBackPress,
            onInfo = { openSheet = MusicPlayerSheet.SOURCE_INFO },
            modifier = Modifier.fillMaxWidth(),
          )
          MusicArtwork(
            artworkUri = snapshot.artworkUri,
            isPlaying = snapshot.isPlaying,
            modifier = Modifier
              .fillMaxWidth(0.88f)
              .sizeIn(maxWidth = 340.dp, maxHeight = 340.dp)
              .aspectRatio(1f)
              .padding(top = 14.dp),
          )
          MusicTrackInfo(snapshot = snapshot, modifier = Modifier.fillMaxWidth())
          MusicTimeline(snapshot = snapshot, controller = controller, modifier = Modifier.fillMaxWidth())
          MusicTransport(snapshot = snapshot, controller = controller, modifier = Modifier.fillMaxWidth())
          MusicSecondaryControls(
            snapshot = snapshot,
            controller = controller,
            onQueue = { openSheet = MusicPlayerSheet.QUEUE },
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
  }

  when (openSheet) {
    MusicPlayerSheet.QUEUE ->
      ModalBottomSheet(onDismissRequest = { openSheet = null }) {
        Text(
          text = "Queue",
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
          itemsIndexed(snapshot.queue, key = { index, item -> "$index:${item.mediaId}" }) { index, item ->
            TextButton(
              onClick = {
                controller?.seekTo(index, 0L)
                controller?.play()
                openSheet = null
              },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = item.title.ifBlank { "Audio Track" },
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  color = if (index == snapshot.currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                  fontWeight = if (index == snapshot.currentIndex) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                  text = item.artist,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              IconButton(onClick = { controller?.removeMediaItem(index) }) {
                Icon(
                  Icons.RoundedFilled.Close,
                  contentDescription = "Remove from queue",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    MusicPlayerSheet.SOURCE_INFO ->
      ModalBottomSheet(onDismissRequest = { openSheet = null }) {
        SourceInfoContent(
          codec = loadedSourceInfo?.codec?.takeIf { it.isNotBlank() } ?: snapshot.sourceExtension,
          sourceFormat = loadedSourceInfo?.format,
          channels = loadedSourceInfo?.channels,
          bitRateBps = loadedSourceInfo?.bitRateBps,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        )
      }
    null -> Unit
  }
}

@Composable
private fun MusicHeader(
  landscape: Boolean,
  losslessLabel: String?,
  onBackPress: () -> Unit,
  onInfo: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (landscape) {
    Box(modifier = modifier.height(74.dp)) {
      Column(
        modifier = Modifier.align(Alignment.CenterStart),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
      ) {
        IconButton(onClick = onBackPress, modifier = Modifier.size(30.dp)) {
          Icon(
            imageVector = Icons.RoundedFilled.ExpandMore,
            contentDescription = "Minimize player",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
        if (losslessLabel != null) LosslessBadge(label = losslessLabel)
      }
      Row(
        modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Spacer(modifier = Modifier.weight(0.25f))
        Text(
          text = "Now Playing",
          modifier = Modifier.weight(0.5f),
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(0.25f))
      }
      IconButton(onClick = onInfo, modifier = Modifier.align(Alignment.CenterEnd)) {
        Icon(
          imageVector = Icons.RoundedFilled.Info,
          contentDescription = "Source audio information",
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  } else {
    Column(modifier = modifier) {
      Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onBackPress) {
          Icon(
            imageVector = Icons.RoundedFilled.ExpandMore,
            contentDescription = "Minimize player",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
        Text(
          text = "Now Playing",
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onInfo) {
          Icon(
            imageVector = Icons.RoundedFilled.Info,
            contentDescription = "Source audio information",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
      if (losslessLabel != null) {
        LosslessBadge(label = losslessLabel, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
      }
    }
  }
}

@Composable
private fun LosslessBadge(
  label: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(7.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
  ) {
    Text(
      text = label,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      style = MaterialTheme.typography.labelSmall,
      fontSize = 10.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun MusicArtwork(
  artworkUri: String?,
  isPlaying: Boolean,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(24.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    val parsedUri = artworkUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    if (parsedUri != null) {
      AsyncImage(
        model = parsedUri,
        contentDescription = "Album artwork",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Icon(
        imageVector = Icons.RoundedFilled.Audiotrack,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(72.dp),
      )
    }
    if (isPlaying) {
      MiniAudioVisualizer(
        isPlaying = true,
        color = Color.White,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).size(width = 38.dp, height = 24.dp),
      )
    }
  }
}

@Composable
private fun MusicTrackInfo(
  snapshot: MusicPlaybackSnapshot,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = snapshot.title?.takeIf { it.isNotBlank() } ?: "Nothing Playing",
      style = MaterialTheme.typography.headlineSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Text(
      text = snapshot.artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
      style = MaterialTheme.typography.titleMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (snapshot.queue.isNotEmpty()) {
      Spacer(Modifier.height(8.dp))
      Text(
        text = "Track ${snapshot.currentIndex + 1}/${snapshot.queue.size}  ·  ${snapshot.playbackSpeed.formatSpeed()}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun MusicTimeline(
  snapshot: MusicPlaybackSnapshot,
  controller: MediaController?,
  modifier: Modifier = Modifier,
) {
  val duration = snapshot.durationMs
  var isScrubbing by remember(snapshot.currentUri) { mutableStateOf(false) }
  var scrubValue by remember(snapshot.currentUri) { mutableFloatStateOf(0f) }
  val currentProgress = if (duration > 0L) (snapshot.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
  val progress = if (isScrubbing) scrubValue else currentProgress
  Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
    Slider(
      value = progress,
      onValueChange = {
        isScrubbing = true
        scrubValue = it.coerceIn(0f, 1f)
      },
      onValueChangeFinished = {
        if (duration > 0L) controller?.seekTo((scrubValue * duration).roundToLong())
        isScrubbing = false
      },
      enabled = duration > 0L,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(formatTime((progress * duration).toLong()), style = MaterialTheme.typography.labelMedium)
      Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
    }
  }
}

@Composable
private fun MusicTransport(
  snapshot: MusicPlaybackSnapshot,
  controller: MediaController?,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(
      onClick = {
        if (snapshot.positionMs > PREVIOUS_RESTART_THRESHOLD_MS) controller?.seekTo(0L)
        else controller?.seekToPreviousMediaItem()
      },
    ) {
      Icon(Icons.RoundedFilled.SkipPrevious, contentDescription = "Previous track", tint = MaterialTheme.colorScheme.onSurface)
    }
    IconButton(onClick = { controller?.seekTo((snapshot.positionMs - SEEK_STEP_MS).coerceAtLeast(0L)) }) {
      Icon(Icons.RoundedFilled.FastRewind, contentDescription = "Rewind", tint = MaterialTheme.colorScheme.onSurface)
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
      IconButton(onClick = { if (snapshot.isPlaying) controller?.pause() else controller?.play() }, modifier = Modifier.size(76.dp)) {
        Icon(
          imageVector = if (snapshot.isPlaying) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
          contentDescription = if (snapshot.isPlaying) "Pause" else "Play",
          tint = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.size(38.dp),
        )
      }
    }
    IconButton(onClick = { controller?.seekTo((snapshot.positionMs + SEEK_STEP_MS).coerceAtMost(snapshot.durationMs)) }) {
      Icon(Icons.RoundedFilled.FastForward, contentDescription = "Forward", tint = MaterialTheme.colorScheme.onSurface)
    }
    IconButton(onClick = { controller?.seekToNextMediaItem() }) {
      Icon(Icons.RoundedFilled.SkipNext, contentDescription = "Next track", tint = MaterialTheme.colorScheme.onSurface)
    }
  }
}

@Composable
private fun MusicSecondaryControls(
  snapshot: MusicPlaybackSnapshot,
  controller: MediaController?,
  onQueue: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.padding(top = 4.dp, bottom = 8.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = { controller?.shuffleModeEnabled = !snapshot.shuffleEnabled }) {
      Icon(
        imageVector = if (snapshot.shuffleEnabled) Icons.RoundedFilled.ShuffleOn else Icons.RoundedFilled.Shuffle,
        contentDescription = if (snapshot.shuffleEnabled) "Turn shuffle off" else "Turn shuffle on",
        tint = if (snapshot.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = {
      controller?.repeatMode =
        when (snapshot.repeatMode) {
          Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
          Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
          else -> Player.REPEAT_MODE_OFF
        }
    }) {
      Icon(
        imageVector = if (snapshot.repeatMode == Player.REPEAT_MODE_ONE) Icons.RoundedFilled.RepeatOne else Icons.RoundedFilled.Repeat,
        contentDescription = when (snapshot.repeatMode) {
          Player.REPEAT_MODE_ONE -> "Repeat one"
          Player.REPEAT_MODE_ALL -> "Repeat queue"
          else -> "Repeat off"
        },
        tint = if (snapshot.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    TextButton(onClick = {
      val nextSpeed = if (snapshot.playbackSpeed < 1.1f) 1.25f else 1f
      controller?.setPlaybackSpeed(nextSpeed)
    }) {
      Text(snapshot.playbackSpeed.formatSpeed(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    IconButton(onClick = onQueue) {
      Icon(Icons.RoundedFilled.QueueMusic, contentDescription = "Open queue", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun SourceInfoContent(
  codec: String?,
  sourceFormat: MusicSourceAudioFormat?,
  channels: Int? = null,
  bitRateBps: Int? = null,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("Source audio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text("Codec: ${codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: "Not reported"}")
    Text("Source bit depth: ${sourceFormat?.bitDepth?.let { if (sourceFormat.isFloat) "$it-bit float" else "$it-bit" } ?: "Not reported"}")
    Text("Source sample rate: ${sourceFormat?.sampleRateHz?.let(::formatSampleRate) ?: "Not reported"}")
    Text("Channels: ${channels?.let { "$it" } ?: "Not reported"}")
    Text("Bit rate: ${bitRateBps?.let { "${it / 1_000} kbps" } ?: "Not reported"}")
    if (codec.isNullOrBlank() && sourceFormat == null) {
      Text("Source metadata is not available yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

private fun Float.formatSpeed(): String {
  val formatted = String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
  return "${formatted}x"
}

private fun formatTime(milliseconds: Long): String =
  DateUtils.formatElapsedTime((milliseconds.coerceAtLeast(0L) / 1_000L))

private fun formatSampleRate(sampleRateHz: Int): String {
  val khz = sampleRateHz / 1_000.0
  return if (sampleRateHz % 1_000 == 0) "${sampleRateHz / 1_000} kHz" else String.format(Locale.US, "%.1f kHz", khz)
}

private const val SEEK_STEP_MS = 10_000L
private const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
