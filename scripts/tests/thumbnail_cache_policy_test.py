#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/app/infinity/mpvz'


def source(path):
    return (JAVA / path).read_text()


class ThumbnailCacheContracts(unittest.TestCase):
    def test_bitmap_tiers_share_one_pressure_aware_hard_budget(self):
        policy = source('domain/thumbnail/ThumbnailCachePolicy.kt')
        repo = source('domain/thumbnail/ThumbnailRepository.kt')
        for token in ('MinBytes = 8L * 1024L * 1024L', 'MaxBytes = 64L * 1024L * 1024L', 'PreviewMaxEdge = 128', 'MaxConcurrentRequests = 8'):
            self.assertIn(token, policy)
        self.assertIn('ThumbnailCachePolicy.primaryBytes(cacheBytes)', repo)
        self.assertIn('ThumbnailCachePolicy.previewBytes(cacheBytes)', repo)
        self.assertIn('registerComponentCallbacks', repo)
        self.assertNotIn('level >= ComponentCallbacks2.', repo)

    def test_row_disposal_does_not_own_or_cancel_the_shared_decode(self):
        owner = source('domain/thumbnail/ThumbnailRequestCoalescer.kt')
        repo = source('domain/thumbnail/ThumbnailRepository.kt')
        self.assertIn('scope.async(start = CoroutineStart.LAZY)', owner)
        self.assertIn('slots.acquire()', owner)
        self.assertIn('requests.putIfAbsent', owner)
        self.assertIn('candidate.invokeOnCompletion', owner)
        self.assertIn('thumbnailRequests = ThumbnailRequestCoalescer<String, Bitmap?>(repositoryScope', repo)
        self.assertIn('thumbnailRequests.getOrLoad(key)', repo)
        self.assertIn('thumbnailRequests.cancelAll()', repo)

    def test_memory_peeks_and_previews_survive_lazy_row_recreation_without_io(self):
        repo = source('domain/thumbnail/ThumbnailRepository.kt')
        video = source('ui/browser/cards/VideoCard.kt')
        folder = source('ui/browser/cards/FolderCard.kt')
        self.assertIn('previewMemoryCache.get(previewKey(video))', repo)
        self.assertIn('ThumbnailCachePolicy.sourceIdentity(', repo)
        self.assertIn('"${memoryVideoBaseKey(video)}|$width|$height|', repo)
        self.assertIn('"${memoryVideoBaseKey(video)}|${thumbnailModeKey()}', repo)
        self.assertEqual(2, video.count('thumbnailRepository.getThumbnailFromMemory(video,'))
        self.assertIn('thumbnailRepository.getFolderThumbnailFromMemory(folder.bucketId)', folder)
        self.assertNotIn('thumbnail = null', video)
        preview = repo[repo.index('private suspend fun retainVideoPreview'):repo.index('fun clearThumbnailCache')]
        self.assertNotIn('.recycle()', preview)
        self.assertIn('Paint(Paint.FILTER_BITMAP_FLAG)', preview)

    def test_cached_preview_upgrades_when_idle_and_cache_miss_never_blanks_a_painted_row(self):
        video = source('ui/browser/cards/VideoCard.kt')
        self.assertEqual(4, video.count('if (loaded != null) thumbnail = loaded'))
        self.assertEqual(2, video.count('if (!allowThumbnailLoading || !showThumbnails) return@LaunchedEffect'))
        self.assertIn('else thumbnailRepository.getCachedThumbnail(video, resolvedThumbWidthPx, resolvedThumbHeightPx)', video)
        self.assertIn('else thumbnailRepository.getCachedThumbnail(video, thumbWidthPx, thumbHeightPx)', video)

    def test_list_branch_cannot_reference_grid_only_dimensions(self):
        video = source('ui/browser/cards/VideoCard.kt')
        start = video.index('// Audio artwork is square; video thumbnails retain their 16:9 presentation.')
        end = video.index('val listThumbnailBitmap', start)
        self.assertNotIn('resolvedThumbWidthPx', video[start:end])
        self.assertNotIn('resolvedThumbHeightPx', video[start:end])
        self.assertIn('getThumbnailFromMemory(video, thumbWidthPx, thumbHeightPx)', video[start:end])


if __name__ == '__main__':
    unittest.main()
