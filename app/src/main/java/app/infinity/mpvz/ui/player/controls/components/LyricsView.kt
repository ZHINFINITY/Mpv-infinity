/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.lyrics.LyricsLanguageOptions
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.domain.lyrics.SyncedWord
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.LyricsTranslationDisplayMode
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.LyricsTranslateDialog
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricsView(
  viewModel: PlayerViewModel,
  modifier: Modifier = Modifier,
  showTitleHeader: Boolean = false,
  isLyricsFullscreen: Boolean = false,
  onTap: (() -> Unit)? = null,
) {
  val audioPreferences = koinInject<AudioPreferences>()
  val translationDisplayMode by audioPreferences.lyricsTranslationDisplayMode.collectAsState()
  val state by viewModel.lyricsUiState.collectAsState()
  val precisePosition by viewModel.precisePosition.collectAsState()
  val listState = rememberLazyListState()
  val density = LocalDensity.current
  var lyricsViewportPx by remember { mutableIntStateOf(0) }
  var showTranslateDialog by remember { mutableStateOf(false) }

  val currentPosMs = remember(precisePosition, state.syncOffsetMs) {
    (precisePosition * 1000).toLong() + state.syncOffsetMs
  }
  val paused by PlaybackSession.propBoolean["pause"].collectAsState()
  val playbackSpeed by PlaybackSession.propFloat["speed"].collectAsState()
  // Position polls arrive every 50-500ms; per-letter animation needs a per-frame clock.
  val smoothPositionMs = rememberSmoothedPositionMs(currentPosMs, paused == false, playbackSpeed ?: 1f)

  // Autoscroll: scroll current active line to the top
  LaunchedEffect(state.activeLineIndex, isLyricsFullscreen, lyricsViewportPx) {
    val target = state.activeLineIndex
    if (target < 0) return@LaunchedEffect
    runCatching {
      if (target == 0) {
        listState.animateScrollToItem(0)
      } else {
        val topOffset = with(density) { 16.dp.roundToPx() }
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        if (item != null) {
          listState.animateScrollBy(
            (item.offset - topOffset).toFloat(),
            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
          )
        } else {
          listState.animateScrollToItem(target, scrollOffset = 0)
        }
      }
    }
  }

  val hasEmbedded = state.embeddedLyrics != null && state.embeddedLyrics?.isValid() == true

  Surface(
    modifier = modifier
      .fillMaxSize()
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
      ) { onTap?.invoke() },
    color = Color.Transparent,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 4.dp, vertical = 4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Optional Header
      if (showTitleHeader) {
        val mediaTitle by PlaybackSession.propString["media-title"].collectAsState()
        val artistName by PlaybackSession.propString["metadata/by-key/Artist"].collectAsState()
        val displayTitle = mediaTitle?.takeIf { it.isNotBlank() } ?: "Current Track"
        val displayArtist = artistName?.takeIf { it.isNotBlank() } ?: ""

        Text(
          text = displayTitle,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.ExtraBold,
          fontFamily = FontFamily.SansSerif,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
        )
        if (displayArtist.isNotBlank()) {
          Text(
            text = displayArtist,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
      }

      // Source Switcher Row (Show ONLY IF embedded/local lyrics are present)
      if (hasEmbedded) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          FilterChip(
            selected = state.selectedSource == LyricsSourceType.EMBEDDED || state.selectedSource == LyricsSourceType.LOCAL,
            onClick = { viewModel.switchLyricsSource(LyricsSourceType.EMBEDDED) },
            label = {
              Text(
                if (state.embeddedLyrics?.sourceType == LyricsSourceType.LOCAL) stringResource(R.string.lyrics_source_local) else stringResource(R.string.lyrics_source_embedded),
                fontWeight = FontWeight.Bold,
              )
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          )

          FilterChip(
            selected = state.selectedSource == LyricsSourceType.ONLINE,
            onClick = { viewModel.switchLyricsSource(LyricsSourceType.ONLINE) },
            label = {
              Text(stringResource(R.string.lyrics_source_online), fontWeight = FontWeight.Bold)
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          )

          if (state.isLoading) {
            Spacer(modifier = Modifier.width(6.dp))
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
        Spacer(modifier = Modifier.height(14.dp))
      }

      // Edge-to-Edge Synced Lyrics Scroll Area with bottom 33% gradient fade
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .onSizeChanged { lyricsViewportPx = it.height },
        contentAlignment = Alignment.Center,
      ) {
        val activeLyrics = state.lyrics
        when {
          state.isLoading && activeLyrics == null -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.height(12.dp))
              Text(
                text = "Fetching synced lyrics...",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          activeLyrics != null && !activeLyrics.synced.isNullOrEmpty() -> {
            LazyColumn(
              state = listState,
              modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                  drawContent()
                  drawRect(
                    brush = Brush.verticalGradient(
                      0.0f to Color.Black,
                      0.50f to Color.Black,
                      1.0f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                  )
                },
              verticalArrangement = Arrangement.spacedBy(18.dp),
              contentPadding = PaddingValues(top = 16.dp, bottom = 220.dp),
            ) {
              itemsIndexed(
                items = activeLyrics.synced,
                key = { index, line -> "${line.time}_${index}" },
                contentType = { _, _ -> "lyric_synced_line" },
              ) { index, line ->
                val isActiveLine = index == state.activeLineIndex
                val (ogText, transText) = remember(line.line, line.translation, translationDisplayMode) {
                  val rawTrans = line.translation?.trim()
                  if (translationDisplayMode == LyricsTranslationDisplayMode.Replace && !rawTrans.isNullOrBlank()) {
                    Pair(rawTrans, null)
                  } else if (!rawTrans.isNullOrBlank() && !rawTrans.equals(line.line.trim(), ignoreCase = true)) {
                    Pair(line.line.trim(), rawTrans)
                  } else if (line.line.contains("\n")) {
                    val parts = line.line.split("\n", limit = 2)
                    Pair(parts[0].trim(), parts.getOrNull(1)?.trim())
                  } else if (line.line.contains(" / ")) {
                    val parts = line.line.split(" / ", limit = 2)
                    Pair(parts[0].trim(), parts.getOrNull(1)?.trim())
                  } else if (line.line.contains(" | ")) {
                    val parts = line.line.split(" | ", limit = 2)
                    Pair(parts[0].trim(), parts.getOrNull(1)?.trim())
                  } else {
                    Pair(line.line.trim(), null)
                  }
                }

                val isBlankLine = ogText.isBlank()
                val displayText = if (isBlankLine) ". . ." else ogText
                val hasTranslation = !transText.isNullOrBlank()

                val distanceFromActive =
                  if (state.activeLineIndex >= 0) kotlin.math.abs(index - state.activeLineIndex) else 0

                val lineAlpha by animateFloatAsState(
                  targetValue =
                    when {
                      isActiveLine -> 1.0f
                      distanceFromActive == 1 -> 0.70f
                      distanceFromActive == 2 -> 0.50f
                      else -> 0.38f
                    },
                  animationSpec = tween(durationMillis = if (isActiveLine) 250 else 400, easing = FastOutSlowInEasing),
                  label = "LineAlpha",
                )

                val lineScale by animateFloatAsState(
                  targetValue = if (isActiveLine) 1.02f else 1.0f,
                  animationSpec = spring(dampingRatio = 0.80f, stiffness = 280f),
                  label = "LineScale",
                )

                val activeColor = Color.White
                val inactiveColor = Color.White.copy(alpha = 0.55f)

                val lineColor by animateColorAsState(
                  targetValue = if (isActiveLine) activeColor else inactiveColor,
                  animationSpec = tween(durationMillis = 250),
                  label = "LineColor",
                )

                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                      alpha = lineAlpha
                      scaleX = lineScale
                      scaleY = lineScale
                      transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                      onTap?.invoke()
                      if (!isLyricsFullscreen) {
                        val targetSeconds = line.time / 1000f
                        PlaybackSession.command("seek", targetSeconds.toString(), "absolute+exact")
                      }
                    }
                    .padding(vertical = 4.dp, horizontal = 6.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  if (isActiveLine && !isBlankLine && !line.words.isNullOrEmpty()) {
                    FlowRow(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.Center,
                      verticalArrangement = Arrangement.Center,
                    ) {
                      line.words.forEachIndexed { wordIndex, word ->
                        val wordEndMs =
                          line.words.getOrNull(wordIndex + 1)?.time?.toLong()
                            ?: activeLyrics.synced.getOrNull(index + 1)?.time?.toLong()
                              ?.coerceAtMost(line.time.toLong() + 8_000L)
                            ?: (word.time + 600).toLong()
                        AnimatedLyricWord(
                          word = word,
                          endTimeMs = wordEndMs,
                          positionMs = smoothPositionMs,
                          activeColor = activeColor,
                          inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                          fontSize = if (isLyricsFullscreen) 30.sp else 26.sp,
                        )
                      }
                    }
                  } else {
                    Text(
                      text = displayText,
                      color = lineColor,
                      fontSize = when {
                        isActiveLine && isLyricsFullscreen -> 30.sp
                        isActiveLine -> 26.sp
                        isLyricsFullscreen -> 24.sp
                        else -> 22.sp
                      },
                      fontWeight = if (isActiveLine) FontWeight.Black else FontWeight.ExtraBold,
                      fontFamily = FontFamily.SansSerif,
                      textAlign = TextAlign.Center,
                      modifier = Modifier.fillMaxWidth(),
                    )
                  }

                  // Render Translation if present (Smaller font size, highlighted together with original when active)
                  if (hasTranslation) {
                    val translationColor by animateColorAsState(
                      targetValue = if (isActiveLine) activeColor.copy(alpha = 0.85f) else inactiveColor.copy(alpha = 0.70f),
                      animationSpec = tween(durationMillis = 250),
                      label = "TranslationColor",
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                      text = transText.orEmpty(),
                      color = translationColor,
                      fontSize = if (isActiveLine) 18.sp else 16.sp,
                      fontWeight = FontWeight.Bold,
                      fontFamily = FontFamily.SansSerif,
                      textAlign = TextAlign.Center,
                      modifier = Modifier.fillMaxWidth(),
                    )
                  }
                }
              }
            }
          }

          activeLyrics != null && !activeLyrics.plain.isNullOrEmpty() -> {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                  drawContent()
                  drawRect(
                    brush = Brush.verticalGradient(
                      0.0f to Color.Black,
                      0.50f to Color.Black,
                      1.0f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                  )
                },
              verticalArrangement = Arrangement.spacedBy(14.dp),
              contentPadding = PaddingValues(top = 16.dp, bottom = 220.dp),
            ) {
              itemsIndexed(
                items = activeLyrics.plain,
                key = { index, _ -> index },
                contentType = { _, _ -> "lyric_plain_line" },
              ) { _, lineText ->
                val textToDisplay = if (lineText.isBlank()) ". . ." else lineText
                Text(
                  text = textToDisplay,
                  color = MaterialTheme.colorScheme.onSurface,
                  fontSize = 24.sp,
                  fontWeight = FontWeight.Black,
                  fontFamily = FontFamily.SansSerif,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }

          else -> {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center,
            ) {
              Text(
                text = "No lyrics available for this track.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(8.dp))
              TextButton(onClick = { viewModel.loadLyricsForCurrentTrack(forceRefresh = true) }) {
                Text("Search Online", fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }

      // Bottom Bar: Translate Button & Sync Timing Adjustments (Only visible when synced lyrics are present)
      AnimatedVisibility(visible = state.lyrics?.synced?.isNotEmpty() == true) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        ) {
          // Medium size Translate button (Square with rounded corners)
          Surface(
            onClick = { showTranslateDialog = true },
            shape = RoundedCornerShape(12.dp),
            color = if (state.isTranslationActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(40.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              if (state.isTranslating) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  strokeWidth = 2.5.dp,
                  color = MaterialTheme.colorScheme.primary,
                )
              } else {
                Icon(
                  imageVector = Icons.RoundedFilled.Translate,
                  contentDescription = stringResource(R.string.lyrics_translate_title),
                  tint = if (state.isTranslationActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(22.dp),
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          // Sync offset pill: equal-weight buttons that always fit the available width, with the
          // current offset shown (and reset) via the center segment.
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              SyncOffsetButton(
                label = "-0.5s",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.adjustLyricsSyncOffset(-500) },
              )
              SyncOffsetButton(
                label = "-0.1s",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.adjustLyricsSyncOffset(-100) },
              )
              SyncOffsetButton(
                label = "${if (state.syncOffsetMs >= 0) "+" else ""}${state.syncOffsetMs / 1000f}s",
                modifier = Modifier.weight(1.2f),
                emphasized = true,
                onClick = { viewModel.resetLyricsSyncOffset() },
              )
              SyncOffsetButton(
                label = "+0.1s",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.adjustLyricsSyncOffset(100) },
              )
              SyncOffsetButton(
                label = "+0.5s",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.adjustLyricsSyncOffset(500) },
              )
            }
          }
        }
      }
    }
  }

  if (showTranslateDialog) {
    LyricsTranslateDialog(
      viewModel = viewModel,
      onDismiss = { showTranslateDialog = false },
    )
  }
}

// One equal-weight segment of the sync offset pill; `emphasized` marks the center reset/readout segment.
@Composable
private fun SyncOffsetButton(
  label: String,
  modifier: Modifier = Modifier,
  emphasized: Boolean = false,
  onClick: () -> Unit,
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(vertical = 8.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * Interpolates the polled playback position with the display frame clock so letter reveals stay
 * fluid between position updates. Backward jumps (seeks) snap; forward extrapolation is capped so
 * a stalled poll cannot run ahead.
 */
@Composable
private fun rememberSmoothedPositionMs(
  rawPositionMs: Long,
  isPlaying: Boolean,
  speed: Float,
): State<Long> {
  val smoothed = remember { mutableLongStateOf(rawPositionMs) }
  LaunchedEffect(rawPositionMs, isPlaying, speed) {
    smoothed.longValue =
      if (rawPositionMs < smoothed.longValue || rawPositionMs > smoothed.longValue + 1_000L) {
        rawPositionMs
      } else {
        maxOf(smoothed.longValue, rawPositionMs)
      }
    if (!isPlaying) return@LaunchedEffect
    val anchorMs = smoothed.longValue
    var anchorFrameNanos = -1L
    while (true) {
      withFrameNanos { frameNanos ->
        if (anchorFrameNanos < 0L) anchorFrameNanos = frameNanos
        val elapsedMs = (frameNanos - anchorFrameNanos) / 1_000_000f
        smoothed.longValue = anchorMs + (elapsedMs * speed.coerceIn(0.1f, 8f)).toLong().coerceAtMost(800L)
      }
    }
  }
  return smoothed
}

/** Smooth karaoke fill: a glowing active layer is revealed continuously from left to right. */
@Composable
private fun AnimatedLyricWord(
  word: SyncedWord,
  endTimeMs: Long,
  positionMs: State<Long>,
  activeColor: Color,
  inactiveColor: Color,
  fontSize: TextUnit = 26.sp,
) {
  val text = "${word.word} "
  val textStyle =
    MaterialTheme.typography.headlineSmall.copy(
      fontSize = fontSize,
      fontWeight = FontWeight.Black,
      fontFamily = FontFamily.SansSerif,
    )
  if (text.isEmpty()) {
    Text(text = " ", style = textStyle)
    return
  }

  val startTimeMs = word.time.toLong()
  val durationMs = (endTimeMs - startTimeMs).coerceAtLeast(1L)
  val currentPositionMs by positionMs
  val fillProgress = ((currentPositionMs - startTimeMs).toFloat() / durationMs).coerceIn(0f, 1f)
  val activeStyle =
    textStyle.copy(
      shadow =
        Shadow(
          color = activeColor.copy(alpha = if (fillProgress in 0.001f..0.999f) 0.55f else 0.24f),
          offset = Offset.Zero,
          blurRadius = 10f,
        ),
    )

  Box(contentAlignment = Alignment.CenterStart) {
    Text(text = text, color = inactiveColor, style = textStyle)
    Text(
      text = text,
      color = activeColor,
      style = activeStyle,
      modifier =
        Modifier.drawWithContent {
          clipRect(right = size.width * fillProgress) {
            this@drawWithContent.drawContent()
          }
        },
    )
  }
}
