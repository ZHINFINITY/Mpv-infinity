/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedQueueAutoEngineContractTest {
  @Test
  fun preparedQueueLaunchHdrMetadataRoutesAutoToNativeAndSdrRemainsMpv() {
    val staged =
      PlaybackItem(
        stableId = "tab-video",
        originalUri = "content://media/external/video/1",
        title = "Episode 1",
        mimeType = "video/mp4",
      )
    val hdrItem =
      mergePlaybackLaunchMetadata(
        item = staged,
        videoWidth = 3840,
        videoHeight = 2160,
        hdrMetadata = true,
      )
    val sdrItem = mergePlaybackLaunchMetadata(staged, videoWidth = 1920, videoHeight = 1080, hdrMetadata = null)

    assertEquals(3840, hdrItem.videoWidth)
    assertEquals(2160, hdrItem.videoHeight)
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, hdrItem))
    assertEquals(PlaybackEngineMode.MPV, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, sdrItem))
  }

  @Test
  fun commonVideoTabLaunchesForwardDolbyVisionCodecMetadata() {
    val hdrSignal = isHdrOrDolbyVisionMetadata("video/dolby-vision")
    val item =
      PlaybackItem.fromUri(
        uri = "content://media/external/video/dolby-vision",
        title = "Episode 1",
        mimeType = "video/mp4",
        hdrMetadata = hdrSignal,
      )
    assertEquals(PlaybackEngineMode.NATIVE, resolvePlaybackEngineMode(PlaybackEngineMode.AUTO, item))

    val mediaUtils = mediaUtilsSource()
    assertTrue(mediaUtils.contains("hdrMetadata ="))
    assertTrue(mediaUtils.contains("video.videoCodecMimeType"))
    assertTrue(mediaUtils.contains("selected.videoCodecMimeType"))
    assertTrue(mediaUtils.contains("source.videoCodecMimeType"))
    assertTrue(mediaUtils.contains("\"is_hdr\""))
    assertFalse(mediaUtils.contains("video.displayName,\n              video.path,\n              video.videoCodec"))
    assertFalse(mediaUtils.contains("selected.displayName,\n            selected.path,\n            selected.videoCodec"))
    assertFalse(mediaUtils.contains("source.displayName,\n              source.path,\n              source.videoCodec"))
  }

  @Test
  fun preparedQueueRestorationMergesIntentMetadataBeforePublishingSharedQueue() {
    val activity = playerActivitySource()
    val restore =
      activity.substringAfter("private fun restorePreparedPlaybackQueue")
        .substringBefore("private fun acceptPreparedPlaybackLaunch")

    val mergeIndex = restore.indexOf("mergePlaybackLaunchMetadata(")
    val replaceQueueIndex = restore.indexOf("PlaybackSession.replaceQueue(")
    assertTrue("selected tab item must be enriched before shared queue publication", mergeIndex >= 0 && replaceQueueIndex > mergeIndex)
    assertTrue(restore.contains("hdrMetadata = intentHdrMetadata(sourceIntent)"))
    assertTrue(restore.contains("items = restoredItems"))
  }

  @Test
  fun loadAndPreferenceHandoffUseTheSharedAutoResolver() {
    val activity = playerActivitySource()
    assertTrue(activity.contains("val currentNativeVideoHint = currentQueueItem?.stableId == nativeVideoHintMediaId"))
    assertTrue(activity.contains("currentQueueItem != null -> resolvePlaybackEngineMode(engine, currentQueueItem, videoHint = currentNativeVideoHint)"))
    assertTrue(activity.contains("sourceIntent.getBooleanExtra(\"stream_direct_media\", false)"))

    val load =
      activity.substringAfter("val selectedEngine =")
        .substringBefore("val nativeResolvedUri =")
    assertTrue(load.contains("resolvePlaybackEngineMode(configuredEngine, playbackItem, videoHint = nativeVideoHint)"))
    assertTrue(load.contains("playbackItem.requiresWebPageResolutionBeforeNative(videoHint = nativeVideoHint)"))
    val nativeEligibility = activity.substringAfter("val canUseNative =").substringBefore("Log.i(")
    assertTrue(nativeEligibility.contains("nativeItem.declaredMediaKind(videoHint = nativeVideoHint) == DeclaredPlaybackMediaKind.VIDEO"))
  }

  @Test
  fun removingStreamTabKeepsSharedOnlinePlaybackAvailable() {
    val streamScreenFiles =
      sequenceOf(
        File("src/main/java/app/infinity/mpvz/ui/browser/catalog/StreamScreen.kt"),
        File("app/src/main/java/app/infinity/mpvz/ui/browser/catalog/StreamScreen.kt"),
      )
    val mainScreen = mainScreenSource()
    val activity = playerActivitySource()

    assertFalse(streamScreenFiles.any(File::exists))
    assertFalse(mainScreen.contains("MainTab.STREAMS"))
    assertFalse(mainScreen.contains("StreamScreen"))
    assertTrue(activity.contains("YtdlpManager.requiresYtdlp"))
    assertTrue(activity.contains("YtdlpManager.resolveDirectMediaUrl"))
  }

  private fun playerActivitySource(): String =
    sequenceOf(
      File("src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt"),
      File("app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt"),
    ).first(File::exists).readText()

  private fun mediaUtilsSource(): String =
    sequenceOf(
      File("src/main/java/app/infinity/mpvz/utils/media/MediaUtils.kt"),
      File("app/src/main/java/app/infinity/mpvz/utils/media/MediaUtils.kt"),
    ).first(File::exists).readText()

  private fun mainScreenSource(): String =
    sequenceOf(
      File("src/main/java/app/infinity/mpvz/ui/browser/MainScreen.kt"),
      File("app/src/main/java/app/infinity/mpvz/ui/browser/MainScreen.kt"),
    ).first(File::exists).readText()
}
