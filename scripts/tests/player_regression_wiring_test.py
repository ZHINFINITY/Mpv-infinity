#!/usr/bin/env python3
"""Source wiring guards complement behavioral JVM tests; these are not UI/device tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/app/infinity/mpvz'


def source(relative):
    return (JAVA / relative).read_text()


class PlayerRegressionWiringTest(unittest.TestCase):
    def test_playlist_action_uses_modal_state_and_measured_content_optics(self):
        text = source('ui/browser/sheets/PlaylistActionSheet.kt')
        self.assertIn('rememberModalBottomSheetState(skipPartiallyExpanded = true)', text)
        self.assertNotIn('rememberBottomSheetState(', text)
        sheet_arguments, content = text.split('ModalBottomSheet(', 1)[1].split('Column(', 1)
        self.assertNotIn('.settingsGlassSurface(', sheet_arguments)
        self.assertIn('.settingsGlassSurface(', content)

    def test_sheet_insets_are_outside_its_constrained_glass_content(self):
        text = source('presentation/components/PlayerSheet.kt')
        surface = text.split('Surface(', 1)[1]
        self.assertLess(surface.index('.windowInsetsPadding('), surface.index('.sizeIn('))
        self.assertLess(surface.index('.imePadding()'), surface.index('.sizeIn('))
        self.assertIn('PlayerSheetLayoutPolicy.maxHeightDp(', text)

    def test_episode_playlist_has_glass_and_material_fallback(self):
        text = source('ui/player/controls/components/sheets/PlaylistSheet.kt')
        self.assertIn('liquidGlassSurfaces', text)
        self.assertIn('Color.Transparent', text)
        self.assertIn('MaterialTheme.colorScheme.', text)

    def test_more_drawer_has_one_pane_per_tile_and_a_bounded_scroll_viewport(self):
        drawer = source('ui/player/controls/PlayerControlDrawer.kt')
        panel = source('ui/player/controls/components/panels/DraggablePanel.kt')
        controls = source('ui/player/controls/components/GlassControls.kt')
        self.assertIn('LocalPlayerGlassSurfaceOwner provides liquidGlassSurfaces', drawer)
        self.assertIn('if (LocalPlayerGlassSurfaceOwner.current) return this', controls)
        self.assertIn('border = if (glass) null else border', panel)
        self.assertIn('.weight(1f, fill = false).fillMaxWidth().verticalScroll(', panel)

    def test_native_custom_ratio_reaches_viewport_and_survives_source_size_callbacks(self):
        engine = source('ui/player/NativeMedia3Engine.kt')
        model = source('ui/player/PlayerViewModel.kt')
        self.assertIn('host.nativeSetCustomAspectRatio(ratio)', model)
        self.assertIn('content?.setAspectRatio(it)', engine)
        self.assertIn('media3OutputAspect(videoAspect.aspect(), customAspectRatio)', engine)
        callback = engine.split('override fun onVideoSizeChanged(', 1)[1].split('override fun onRenderedFirstFrame', 1)[0]
        self.assertIn('view.post', callback)
        self.assertIn('applyVideoViewport(view)', callback)

    def test_thumbnail_http_decoding_is_sampled_and_not_bounded_by_video_source_size(self):
        text = source('domain/thumbnail/ThumbnailRepository.kt')
        http = text.split('private suspend fun fetchHttpImage(', 1)[1].split('private fun youtubePosterUrl', 1)[0]
        self.assertIn('ThumbnailDecodePolicy.MaxEncodedImageBytes', http)
        self.assertIn('ThumbnailDecodePolicy.readEncodedImage(stream)', http)
        self.assertIn('inJustDecodeBounds = true', http)
        self.assertIn('inSampleSize = ThumbnailDecodePolicy.sampleSize(', http)
        self.assertIn('targetMaxEdge(widthPx, heightPx, thumbnailMaxSize())', http)
        self.assertIn('connection.disconnect()', http)
        self.assertNotIn('MAX_NETWORK_FRAME_THUMBNAIL_FILE_SIZE', http)

    def test_activity_artwork_key_comes_from_queue_not_disposed_activity_state(self):
        text = source('ui/player/PlayerActivity.kt')
        key = text.split('private fun buildBackgroundThumbnailKey(): String {', 1)[1].split('\n  /**', 1)[0]
        self.assertIn('PlaybackSession.queue.value', key)
        self.assertIn('MediaSessionItemIdentity(item.stableId, item.originalUri)', key)
        self.assertIn('.artworkKey(queue.currentIndex, item.artworkUri)', key)
        sync = text.split('private fun syncBackgroundPlaybackService(', 1)[1].split('private fun buildBackgroundThumbnailKey', 1)[0]
        self.assertIn('val durableMediaUri = currentQueueItem?.originalUri', sync)
        self.assertEqual(sync.count('uri = durableMediaUri'), 2)
        self.assertNotIn('uri = currentDurableMediaUri()', sync)

    def test_music_artwork_does_not_use_a_stale_video_frame(self):
        text = source('ui/player/PlayerActivity.kt')
        sync = text.split('private fun syncBackgroundPlaybackService(', 1)[1].split('private fun buildBackgroundThumbnailKey', 1)[0]
        self.assertIn('if (!audioArtwork)', sync)
        self.assertIn('EmbeddedArtworkResolver.decodeEmbeddedArtwork', sync)
        self.assertIn('backgroundArtworkRequestGuard.begin(thumbnailKey, sourceReady)', sync)

    def test_service_validates_source_before_replacing_artwork_or_metadata(self):
        text = source('ui/player/MediaPlaybackService.kt')
        method = text.split('fun setMediaInfo(', 1)[1].split('private fun replaceOwnedThumbnail', 1)[0]
        self.assertIn('.acceptsMetadata(resolvedIdentifier, uri)', method)
        self.assertLess(method.index('.acceptsMetadata('), method.index('thumbnail?.let(::replaceOwnedThumbnail)'))
        self.assertLess(method.index('.acceptsMetadata('), method.index('mediaTitle = resolvedTitle'))
        self.assertIn('mediaIdentityTracker.isCurrent(', text)


if __name__ == '__main__':
    unittest.main()
