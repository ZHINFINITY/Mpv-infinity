/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.FakeAudioRenderer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.FakeVideoRenderer
import androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeMedia3EngineReloadBehaviorTest {
  @Test fun reloadRestoresSavedPositionAndAudioSubtitleSelections() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tracks = TrackGroupArray(
      TrackGroup(
        "audio",
        audioFormat("en", "English"),
        audioFormat("ja", "Japanese"),
      ),
      TrackGroup(
        "subtitles",
        subtitleFormat("en", "English captions"),
        subtitleFormat("ja", "Japanese captions"),
      ),
    )
    val fakeSourceFactory = object : MediaSource.Factory {
      override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val timeline = FakeTimeline(
          FakeTimeline.TimelineWindowDefinition.Builder()
            .setMediaItem(mediaItem)
            .setDurationUs(180_000_000L)
            .build(),
        )
        return FakeMediaSource.Builder()
          .setTimeline(timeline)
          .setTrackGroupArray(tracks)
          .setTrackDataFactory { format, _ ->
            if (format.sampleMimeType == MimeTypes.TEXT_VTT) {
              listOf(
                FakeSampleStreamItem.sample(
                  0L,
                  C.BUFFER_FLAG_KEY_FRAME,
                  "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\ncaption\n".toByteArray(),
                ),
                FakeSampleStreamItem.END_OF_STREAM_ITEM,
              )
            } else {
              listOf(
                FakeSampleStreamItem.oneByteSample(0L, C.BUFFER_FLAG_KEY_FRAME),
                FakeSampleStreamItem.END_OF_STREAM_ITEM,
              )
            }
          }
          .build()
      }

      override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

      override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
      ): MediaSource.Factory = this

      override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
      ): MediaSource.Factory = this
    }
    val testClock = FakeClock(true)
    val engine = NativeMedia3Engine(
      context = context,
      playerFactoryForTesting = { playerContext, trackSelector ->
        TestExoPlayerBuilder(playerContext)
          .setTrackSelector(trackSelector)
          .setClock(testClock)
          .setMediaSourceFactory(fakeSourceFactory)
          .setRenderersFactory(
            RenderersFactory { eventHandler, videoListener, audioListener, textOutput, _ ->
              val rendererHandler = testClock.createHandler(eventHandler.looper, null)
              arrayOf<Renderer>(
                FakeVideoRenderer(rendererHandler, videoListener),
                FakeAudioRenderer(rendererHandler, audioListener),
                // Track-selection coverage only; this test does not exercise legacy subtitle decoding.
                FakeRenderer(C.TRACK_TYPE_TEXT),
              )
            },
          )
          .build()
      },
    )
    val player = engine.currentPlayer as ExoPlayer
    val uri = Uri.parse("test://native/reload.mkv")
    val savedPositionMs = 32_000L
    val savedAudio = selectedTrack("ja", "Japanese", MimeTypes.AUDIO_AAC, channelCount = 2)
    val savedSubtitle = selectedTrack("ja", "Japanese captions", MimeTypes.TEXT_VTT)

    try {
      engine.play(uri, startPositionMs = 0L, autoplay = false)
      engine.restoreTrackSelections(savedAudio, savedSubtitle)
      TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
      player.seekTo(savedPositionMs)
      TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)
      val persistedPositionSeconds = PlaybackStatePersistence.calculateSavePosition(
        oldState = null,
        currentPosition = (requireNotNull(engine.positionForPersistenceMs()) / 1000L).toInt(),
        duration = 180,
        savePositionOnQuit = true,
      )
      val persistedPositionMs = persistedPositionSeconds.toLong() * 1000L
      assertEquals(savedPositionMs, persistedPositionMs)

      TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)
      assertEquals("ja", selectedLanguage(player, C.TRACK_TYPE_AUDIO))
      assertEquals("ja", selectedLanguage(player, C.TRACK_TYPE_TEXT))

      val persistedAudio = requireNotNull(engine.captureAudioSelection())
      val persistedSubtitle = requireNotNull(engine.captureSubtitleSelection())
      engine.play(uri, startPositionMs = persistedPositionMs, autoplay = false)
      engine.restoreTrackSelections(persistedAudio, persistedSubtitle)
      TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
      TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)

      assertEquals(persistedPositionMs, engine.positionForPersistenceMs())
      assertEquals("ja", selectedLanguage(player, C.TRACK_TYPE_AUDIO))
      assertEquals("ja", selectedLanguage(player, C.TRACK_TYPE_TEXT))
    } finally {
      engine.release()
    }
  }

  private fun audioFormat(language: String, label: String) =
    Format.Builder()
      .setId("audio-$language")
      .setLanguage(language)
      .setLabel(label)
      .setSampleMimeType(MimeTypes.AUDIO_AAC)
      .setChannelCount(2)
      .build()

  private fun subtitleFormat(language: String, label: String) =
    Format.Builder()
      .setId("text-$language")
      .setLanguage(language)
      .setLabel(label)
      .setSampleMimeType(MimeTypes.TEXT_VTT)
      .build()

  private fun selectedTrack(
    language: String,
    label: String,
    mimeType: String,
    channelCount: Int? = null,
  ): String {
    val fingerprint = requireNotNull(
      TrackSelectionFingerprint.capture(
        TrackSelectionMetadata(language, label, mimeType = mimeType, channelCount = channelCount),
        TrackSelectionState.SELECTED,
        ordinal = 1,
      ),
    )
    return PlaybackStatePersistence.encodeSelection(SharedTrackSelection.selected(fingerprint))
  }

  private fun selectedLanguage(player: ExoPlayer, type: Int): String? =
    player.currentTracks.groups
      .asSequence()
      .filter { it.type == type }
      .flatMap { group ->
        (0 until group.length).asSequence()
          .filter(group::isTrackSelected)
          .map { group.getTrackFormat(it).language }
      }
      .singleOrNull()
}

class NativeInterpolationRuntimePolicyTest {
  @Test fun flowCanStartBeforeInitializationOnlyForEligibleFormatsAndCanAlwaysBeDisabled() {
    fun canApply(
      flowResourcesAttempted: Boolean,
      flowAvailable: Boolean,
      currentEnabled: Boolean,
      requestedEnabled: Boolean,
      formatEligible: Boolean,
      disposed: Boolean = false,
      preflightAvailable: Boolean = true,
      allowRetryAfterResourceFailure: Boolean = false,
    ) = NativeInterpolationRuntimePolicy.canApplyRuntimeConfiguration(
      disposed = disposed,
      preflightAvailable = preflightAvailable,
      flowResourcesAttempted = flowResourcesAttempted,
      flowAvailable = flowAvailable,
      currentInterpolationEnabled = currentEnabled,
      requestedInterpolationEnabled = requestedEnabled,
      requestedFormatEligible = formatEligible,
      allowRetryAfterResourceFailure = allowRetryAfterResourceFailure,
    )

    assertTrue(canApply(false, false, false, true, true))
    assertFalse(canApply(false, false, false, true, false))
    assertFalse(canApply(true, false, false, true, true))
    assertTrue(canApply(true, false, false, true, true, allowRetryAfterResourceFailure = true))
    assertFalse(canApply(true, false, false, true, false, allowRetryAfterResourceFailure = true))
    assertTrue(canApply(true, false, true, false, false))
    assertTrue(canApply(true, true, false, true, true))
    assertFalse(canApply(false, false, false, true, true, disposed = true))
    assertFalse(canApply(false, false, false, true, true, preflightAvailable = false))
    assertTrue(canApply(true, false, true, false, false, preflightAvailable = false))
    assertFalse(canApply(true, false, false, true, true, preflightAvailable = false, allowRetryAfterResourceFailure = true))
  }

  @Test fun flowEglSurfaceIsReleasedOnlyForFlowToNativePassthroughTransition() {
    fun shouldRelease(previous: Boolean, current: Boolean, nativePassthrough: Boolean) =
      NativeInterpolationRuntimePolicy.shouldReleaseFlowOutputSurfaceBeforeNativeRendererReselect(
        previousInterpolationEnabled = previous,
        interpolationEnabled = current,
        requiresNativePassthrough = nativePassthrough,
      )

    assertTrue(shouldRelease(previous = true, current = false, nativePassthrough = true))
    assertFalse(shouldRelease(previous = true, current = false, nativePassthrough = false))
    assertFalse(shouldRelease(previous = false, current = false, nativePassthrough = true))
    assertFalse(shouldRelease(previous = true, current = true, nativePassthrough = true))
    assertFalse(shouldRelease(previous = false, current = true, nativePassthrough = true))
  }

  @Test fun restartFrameWaitAcceptsOnlyTheMatchingCurrentItemSignal() {
    val expected = NativePlaybackErrorToken(mediaItemEpoch = 8L, playbackGeneration = 3L)
    val stale = NativePlaybackErrorToken(mediaItemEpoch = 7L, playbackGeneration = 3L)
    assertEquals(
      NativeRestartFrameOutcome.RENDERED,
      nativeRestartFrameOutcome(expected, null, expected, currentEpoch = 8L, currentPlaybackGeneration = 3L),
    )
    assertEquals(
      NativeRestartFrameOutcome.ERROR,
      nativeRestartFrameOutcome(null, expected, expected, currentEpoch = 8L, currentPlaybackGeneration = 3L),
    )
    assertEquals(
      NativeRestartFrameOutcome.WAITING,
      nativeRestartFrameOutcome(stale, null, expected, currentEpoch = 8L, currentPlaybackGeneration = 3L),
    )
    assertEquals(
      NativeRestartFrameOutcome.WAITING,
      nativeRestartFrameOutcome(null, stale, expected, currentEpoch = 8L, currentPlaybackGeneration = 3L),
    )
  }

  @Test fun actualSinkConstructionAndAcknowledgementUsePersistedStateAndEligibility() {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val base = File(root, "app/src/main/java/app/infinity/mpvz/ui/player")
    val engine = File(base, "NativeMedia3Engine.kt").readText()
    val sink = File(base, "Media3FlowVideoSink.kt").readText()
    assertTrue(engine.contains("Media3FlowVideoSink.deviceSupportsVulkanFlow(appContext)"))
    assertTrue(!engine.contains("enableMedia3Flow && Media3FlowVideoSink.deviceSupportsVulkanFlow"))
    assertTrue(sink.contains("interpolationEnabled = initialInterpolationEnabled"))
    assertTrue(sink.contains("NativeInterpolationRuntimePolicy.canApplyRuntimeConfiguration"))
    assertTrue(sink.contains("flowResourcesAttempted = flowResourcesAttempted"))
    assertTrue(sink.contains("flowResourcesAttempted = true"))
    assertTrue(sink.contains("requestedFormatEligible = requestedFormatEligible"))
    assertTrue(sink.contains("allowRetryAfterResourceFailure = allowRetryAfterResourceFailure"))
    assertTrue(engine.contains("Media3FlowVideoSink.supportsFormat(it, enabled)"))
    val apply = sink.substringAfter("suspend fun applyRuntimeInterpolation").substringBefore("private val maxDimension")
    assertTrue(apply.indexOf("glHandler.post") < apply.indexOf("interpolationEnabled = enabled"))
    assertTrue(apply.indexOf("setOutputFrameRate()") < apply.indexOf("continuation.resume(true)"))
    assertTrue(apply.contains("continuation.resume(false)"))
  }

  @Test fun hdrRendererSelectionUsesEffectiveRuntimeFlagAndKeepsTrackParameters() {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val base = File(root, "app/src/main/java/app/infinity/mpvz/ui/player")
    val engine = File(base, "NativeMedia3Engine.kt").readText()
    val sink = File(base, "Media3FlowVideoSink.kt").readText()
    val renderers = File(base, "Media3FlowRenderersFactory.java").readText()
    assertTrue(sink.contains("fun isInterpolationEnabled(): Boolean = interpolationEnabled"))
    assertTrue(renderers.contains("Media3FlowVideoSink.supportsFormat(format, interpolationEnabled)"))
    assertTrue(renderers.contains("flowSink.isInterpolationEnabled()"))
    assertTrue(!renderers.contains("Media3FlowVideoSink.supportsFormat(format)"))
    assertTrue(engine.contains(".setTrackSelector(nativeTrackSelector)"))
    assertTrue(engine.contains("fun invalidateRendererEligibility()"))
    assertTrue(engine.contains("invalidate(null)"))
    assertTrue(engine.contains("nativeTrackSelector.invalidateRendererEligibility()"))
    assertTrue(engine.contains("Media3FlowFormatPolicy.shouldReevaluateNativeRenderer("))
    assertTrue(engine.contains("NativeInterpolationRuntimePolicy.shouldReleaseFlowOutputSurfaceBeforeNativeRendererReselect("))
    val apply = engine.substringAfter("suspend fun applyInterpolationRuntime").substringBefore("\n  /**")
    val releaseIndex = apply.indexOf("flowSink.clearOutputSurfaceInfoAndAwait()")
    val reselectIndex = apply.indexOf("nativeTrackSelector.invalidateRendererEligibility()")
    assertTrue(releaseIndex >= 0 && reselectIndex >= 0 && releaseIndex < reselectIndex)
    assertTrue(apply.contains("withContext(Dispatchers.IO) { flowSink.clearOutputSurfaceInfoAndAwait() }"))
    val failedRelease = apply.indexOf("if (!withContext(Dispatchers.IO)")
    val failedReturn = apply.indexOf(
      "return NativeInterpolationRuntimeApplyOutcome.OUTPUT_SURFACE_RELEASE_FAILED",
      failedRelease,
    )
    assertTrue(failedRelease >= 0 && failedReturn > failedRelease && failedReturn < reselectIndex)
    assertTrue(engine.contains("combine(_renderedFirstFrameToken, _currentPlaybackErrorToken)"))
    assertTrue(engine.contains("_renderedFirstFrameToken.value = itemToken"))
    val clearSurface = sink.substringAfter("internal fun clearOutputSurfaceInfoAndAwait()").substringBefore("\n  override fun")
    assertTrue(clearSurface.contains("destroyWindowSurface { releaseFence.countDown() }"))
    assertTrue(clearSurface.contains("releaseFence.await(GL_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)"))
  }

  @Test fun nativeSnapshotsMarkKnownEmptyChaptersSoStaleMpvChaptersAreCleared() {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val base = File(root, "app/src/main/java/app/infinity/mpvz/ui/player")
    val engine = File(base, "NativeMedia3Engine.kt").readText()
    val viewModel = File(base, "PlayerViewModel.kt").readText()
    assertTrue(engine.contains("chaptersKnown = true"))
    assertTrue(viewModel.contains("if (snapshot.chaptersKnown)"))
    assertTrue(viewModel.contains("nativeChapters.value = snapshot.chapters.map"))
  }

  @Test fun startupUsesPersistedInterpolationStateBeforeRendererSelection() {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val base = File(root, "app/src/main/java/app/infinity/mpvz/ui/player")
    val activity = File(base, "PlayerActivity.kt").readText()
    val engine = File(base, "NativeMedia3Engine.kt").readText()
    val renderers = File(base, "Media3FlowRenderersFactory.java").readText()
    val sinkCreation = engine.indexOf("private val media3FlowSink")
    val playerCreation = engine.indexOf("private val player =")
    assertTrue(activity.contains("enableMedia3Flow = decoderPreferences.mpvFlowFrameInterpolation.get()"))
    assertTrue(sinkCreation >= 0 && sinkCreation < playerCreation)
    assertTrue(engine.contains("      enableMedia3Flow,\n      ::publishFlowDiagnostics,"))
    assertTrue(renderers.contains("Media3FlowVideoSink.supportsFormat(format, flowSink.isInterpolationEnabled())"))
  }
}
