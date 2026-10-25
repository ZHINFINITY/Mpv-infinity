/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import android.content.ContentResolver
import android.content.Context
import android.media.AudioManager
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.infinity.mpvz.ui.player.controls.AudioPlayerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The browser's music sheet, or null outside MainActivity (PlayerActivity keeps its own path). */
val LocalBrowserMusicSheet = staticCompositionLocalOf<BrowserMusicSheetState?> { null }

/**
 * Compose side of the browser music sheet. Decisions live in [MusicSheetMotion]; this class only
 * turns them into animation frames.
 *
 * Progress is plain snapshot state that is read from `graphicsLayer` lambdas, so a drag or settle
 * redraws layers but never recomposes the player. Settles run in the sheet's own [scope], not in
 * the pointer coroutine, so a pointer handler that restarts or is cancelled mid-gesture cannot take
 * the settle down with it and leave the page frozen part-way.
 */
@Stable
class BrowserMusicSheetState internal constructor(
  private val scope: CoroutineScope,
  flingVelocityPx: Float = MusicSheetMotion.DEFAULT_FLING_VELOCITY_PX,
  private val keepContentAfterCollapseMs: Long = KEEP_CONTENT_AFTER_COLLAPSE_MS,
) {
  private val motion = MusicSheetMotion(flingVelocityPx)
  private var progressState by mutableFloatStateOf(0f)
  private var phaseState by mutableStateOf(MusicSheetPhase.Collapsed)
  private var settleJob: Job? = null
  private var releaseContentJob: Job? = null

  /** 0 = mini player, 1 = full player. Read it in draw/layer lambdas, not in composition. */
  val progress: Float get() = progressState

  val phase: MusicSheetPhase get() = phaseState

  /** Anything other than a collapsed, resting sheet. */
  val isShowing: Boolean get() = phaseState != MusicSheetPhase.Collapsed

  /** Fully open and at rest: the browser underneath can skip drawing. */
  val coversScreen: Boolean get() = phaseState == MusicSheetPhase.Expanded

  /** A live, service-owned music session the sheet can mirror. */
  var isAvailable by mutableStateOf(false)
    private set

  /**
   * Whether the full player is composed. It is created on the first touch of the mini player, so
   * the expensive first composition overlaps the finger-down and the tap only has to animate, and
   * kept for a few seconds after collapsing so a quick reopen is instant.
   */
  var isContentComposed by mutableStateOf(false)
    private set

  /** Distance between the collapsed and expanded positions (the sheet height). */
  internal var travelPx: Float = 0f

  /** Finger down on the mini player: compose the player early. */
  fun prewarm() {
    if (!isAvailable) return
    releaseContentJob?.cancel()
    isContentComposed = true
  }

  /** Opens the full player. Safe to call while a collapse is still animating; it turns around. */
  fun expand(): Boolean {
    if (!isAvailable) return false
    prewarm()
    motion.expand()
    runSettle(initialVelocity = 0f)
    return true
  }

  /** Back to the mini player. Returns false when the sheet was not showing. */
  fun collapse(): Boolean {
    if (!isShowing) return false
    motion.collapse()
    runSettle(initialVelocity = 0f)
    return true
  }

  internal fun beginDrag() {
    if (!isAvailable && !isShowing) return
    prewarm()
    // Cancelling the running settle is safe: its generation is now stale, so its cleanup no-ops.
    settleJob?.cancel()
    motion.beginDrag()
    publish()
  }

  internal fun dragBy(deltaYPx: Float) {
    motion.dragBy(deltaYPx, travelPx)
    progressState = motion.progress
  }

  internal fun release(velocityYPx: Float) {
    if (motion.phase != MusicSheetPhase.Dragging) return
    motion.release(velocityYPx)
    runSettle(MusicSheetMotion.progressVelocity(velocityYPx, travelPx))
  }

  internal fun cancelDrag() {
    if (motion.cancelDrag() == null) return
    runSettle(initialVelocity = 0f)
  }

  internal fun updateAvailability(available: Boolean) {
    if (isAvailable == available) return
    isAvailable = available
    if (!available) {
      // The music stopped or moved to PlayerActivity: drop the sheet at once, nothing to show.
      settleJob?.cancel()
      motion.snapTo(expanded = false)
      publish()
      releaseContentJob?.cancel()
      isContentComposed = false
    }
  }

  /** Suspends until the current settle, if any, has finished. */
  internal suspend fun awaitSettled() {
    settleJob?.join()
  }

  private fun runSettle(initialVelocity: Float) {
    publish()
    val destination = motion.target ?: run { onRested(); return }
    val generation = motion.settleGeneration
    val start = motion.progress
    settleJob?.cancel()
    settleJob =
      scope.launch {
        try {
          animate(
            initialValue = start,
            targetValue = destination,
            initialVelocity = initialVelocity,
            animationSpec = SettleSpring,
          ) { value, _ ->
            motion.onAnimationFrame(value)
            progressState = motion.progress
          }
        } finally {
          // Runs when the animation completes or is cut short. Only the settle that is still
          // current lands the sheet; a superseded one (drag, tap, new target) leaves it alone.
          if (motion.onSettleFinished(generation)) {
            publish()
            onRested()
          }
        }
      }
  }

  private fun onRested() {
    if (motion.phase != MusicSheetPhase.Collapsed) return
    releaseContentJob?.cancel()
    releaseContentJob =
      scope.launch {
        delay(keepContentAfterCollapseMs)
        if (motion.phase == MusicSheetPhase.Collapsed) isContentComposed = false
      }
  }

  private fun publish() {
    progressState = motion.progress
    phaseState = motion.phase
  }

  internal companion object {
    const val KEEP_CONTENT_AFTER_COLLAPSE_MS = 4_000L

    private val SettleSpring =
      spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 650f)
  }
}

@Composable
fun rememberBrowserMusicSheetState(): BrowserMusicSheetState {
  val scope = rememberCoroutineScope()
  val flingVelocityPx = with(LocalDensity.current) { 700.dp.toPx() }
  return remember(scope) { BrowserMusicSheetState(scope, flingVelocityPx) }
}

/**
 * Vertical drag that moves the sheet. On the mini player ([fromMiniPlayer]) only an upward drag
 * is claimed; on the full player only a downward one, so lists and lyrics keep their own scrolling.
 * Positions are measured in window space because the full player moves with the finger.
 *
 * Every exit path ends in [BrowserMusicSheetState.release] or [BrowserMusicSheetState.cancelDrag],
 * including the pointer coroutine being cancelled, so a drag can never be abandoned half-way.
 */
fun Modifier.browserMusicSheetDrag(
  state: BrowserMusicSheetState,
  fromMiniPlayer: Boolean,
  enabled: Boolean = true,
  onDown: () -> Unit = {},
): Modifier {
  if (!enabled) return this
  val coordinatesHolder = arrayOfNulls<LayoutCoordinates>(1)
  return this
    .onGloballyPositioned { coordinatesHolder[0] = it }
    .pointerInput(state, fromMiniPlayer) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onDown()
        val tracker = VelocityTracker()
        fun windowY(local: androidx.compose.ui.geometry.Offset): Float =
          coordinatesHolder[0]?.takeIf { it.isAttached }?.localToWindow(local)?.y ?: local.y
        var lastWindowY = windowY(down.position)
        tracker.addPosition(down.uptimeMillis, androidx.compose.ui.geometry.Offset(0f, lastWindowY))
        var claimed = false
        val afterSlop =
          awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
            val wanted = if (fromMiniPlayer) overSlop < 0f else overSlop > 0f
            if (wanted) {
              change.consume()
              claimed = true
            }
          }
        if (afterSlop == null || !claimed) return@awaitEachGesture
        state.beginDrag()
        var completed = false
        try {
          val y = windowY(afterSlop.position)
          state.dragBy(y - lastWindowY)
          lastWindowY = y
          tracker.addPosition(afterSlop.uptimeMillis, androidx.compose.ui.geometry.Offset(0f, y))
          completed =
            verticalDrag(afterSlop.id) { change ->
              val current = windowY(change.position)
              state.dragBy(current - lastWindowY)
              lastWindowY = current
              tracker.addPosition(change.uptimeMillis, androidx.compose.ui.geometry.Offset(0f, current))
              change.consume()
            }
        } finally {
          if (completed) state.release(tracker.calculateVelocity().y) else state.cancelDrag()
        }
      }
    }
}

/** Fades the mini player out as the sheet rises over it, without recomposing it. */
fun Modifier.fadeUnderBrowserMusicSheet(state: BrowserMusicSheetState?): Modifier =
  if (state == null) this
  else graphicsLayer { alpha = (1f - state.progress * 3f).coerceIn(0f, 1f) }

/** Skips drawing the browser while the open sheet covers it completely. */
fun Modifier.hiddenUnderBrowserMusicSheet(state: BrowserMusicSheetState): Modifier =
  drawWithContent { if (!state.coversScreen) drawContent() }

/**
 * Hosts the full music player above the browser. The page only slides; it is never scaled, so
 * artwork cannot be stretched mid-transition, and there is no MPV surface here at all, so there is
 * no second copy of the cover to leak through.
 */
@Composable
fun BrowserMusicSheet(state: BrowserMusicSheetState) {
  val session by PlaybackSession.state.collectAsStateWithLifecycle()
  val serviceActive by MediaPlaybackService.foregroundActive.collectAsStateWithLifecycle()
  val available = serviceActive && session.phase in SheetPhases
  LaunchedEffect(available) { state.updateAvailability(available) }

  BackHandler(enabled = state.isShowing) { state.collapse() }

  if (!state.isContentComposed) return
  val cornerPx = with(LocalDensity.current) { 28.dp.toPx() }
  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .onSizeChanged { state.travelPx = it.height.toFloat() }
        .graphicsLayer {
          val p = state.progress
          translationY = (1f - p) * size.height
          // A resting collapsed sheet is offscreen and invisible; alpha 0 also skips its draw.
          alpha = if (p <= 0f) 0f else 1f
          shape = RoundedCornerShape(cornerPx * (1f - p))
          clip = p < 1f
        }
        .then(if (state.isShowing) Modifier else Modifier.clearAndSetSemantics {}),
  ) {
    BrowserMusicSheetContent(state)
  }
}

@Composable
private fun BrowserMusicSheetContent(state: BrowserMusicSheetState) {
  val activity = LocalActivity.current as? ComponentActivity ?: return
  // A private store: the view model lives exactly as long as the composed sheet.
  val owner =
    remember {
      object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
      }
    }
  val viewModel =
    remember(owner) {
      ViewModelProvider.create(
        owner.viewModelStore,
        viewModelFactory { initializer { PlayerViewModel(hostedInBrowser = true) } },
      )[PlayerViewModel::class]
    }
  val host = remember(activity, state) { BrowserMusicPlayerHost(activity, state) }
  DisposableEffect(viewModel, host) {
    viewModel.attachHost(host)
    viewModel.attachToDetachedPlayback()
    onDispose {
      MediaPlaybackService.setInAppPlayerVisible(false)
      viewModel.detachFromDetachedPlayback()
      owner.viewModelStore.clear()
    }
  }
  LaunchedEffect(state) {
    snapshotFlow { state.isShowing }.collect(MediaPlaybackService::setInAppPlayerVisible)
  }
  CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
    AudioPlayerScreen(
      viewModel = viewModel,
      onBackPress = { state.collapse() },
      inAppSheet = state,
    )
  }
}

private val SheetPhases = setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)

/**
 * [PlayerHost] for the browser sheet. [MediaPlaybackService] keeps owning playback, audio focus
 * and the queue; the sheet only forwards user actions to it.
 */
internal class BrowserMusicPlayerHost(
  private val activity: ComponentActivity,
  private val sheet: BrowserMusicSheetState,
) : PlayerHost {
  override val context: Context get() = activity
  override val windowInsetsController: WindowInsetsControllerCompat
    get() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
  override val audioManager: AudioManager
    get() = activity.getSystemService(AudioManager::class.java)
  override val hostWindow: Window get() = activity.window
  override val hostWindowManager: WindowManager get() = activity.windowManager
  override val hostContentResolver: ContentResolver get() = activity.contentResolver

  // The browser keeps its own orientation; music never rotates it.
  override var hostRequestedOrientation: Int
    get() = activity.requestedOrientation
    set(_) = Unit

  // The service already holds focus for the session the sheet mirrors.
  override fun requestAudioFocus(): Boolean = true

  override fun abandonAudioFocus() = Unit

  override fun minimizeToMiniPlayer(): Boolean = sheet.collapse()

  override fun isCurrentMediaKnownAudio(): Boolean = true

  override fun currentThumbnailSource(): String? = PlaybackSession.state.value.currentItem?.artworkUri

  override fun playQueueItem(index: Int) {
    MediaPlaybackService.playQueueItemFromApp(index)
  }

  override fun reorderQueueItem(from: Int, to: Int) {
    if (from != to) PlaybackSession.moveQueueItem(from, to)
  }

  override fun hasNextQueueItem(): Boolean = PlaybackSession.hasNext()

  override fun hasPreviousQueueItem(): Boolean = PlaybackSession.hasPrevious()

  override fun playNextQueueItem() {
    MediaPlaybackService.skipToNextFromApp()
  }

  override fun playPreviousQueueItem() {
    MediaPlaybackService.skipToPreviousFromApp()
  }

  override fun onQueueShuffleChanged(enabled: Boolean) {
    PlaybackSession.setShuffleEnabled(enabled)
  }
}
