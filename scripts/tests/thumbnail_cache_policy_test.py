from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = ROOT / "app/src/main/java/app/infinity/mpvz/domain/thumbnail/ThumbnailRepository.kt"
POLICY = ROOT / "app/src/main/java/app/infinity/mpvz/domain/thumbnail/ThumbnailCachePolicy.kt"
VIDEO_CARD = ROOT / "app/src/main/java/app/infinity/mpvz/ui/browser/cards/VideoCard.kt"
FOLDER_CARD = ROOT / "app/src/main/java/app/infinity/mpvz/ui/browser/cards/FolderCard.kt"


def test_thumbnail_cache_policy_is_bounded_and_pressure_aware():
    policy = POLICY.read_text(encoding="utf-8")
    for token in ("MinBytes = 8L * 1024L * 1024L", "MaxBytes = 64L * 1024L * 1024L", "coerceIn(MinBytes, MaxBytes)", "runningCritical"):
        assert token in policy

    repository = REPOSITORY.read_text(encoding="utf-8")
    for token in ("ThumbnailCachePolicy.maxBytes", "registerComponentCallbacks", "TRIM_MEMORY_RUNNING_CRITICAL", "ongoingOperations[key]?.let"):
        assert token in repository


def test_cards_peek_memory_before_async_thumbnail_work():
    video = VIDEO_CARD.read_text(encoding="utf-8")
    folder = FOLDER_CARD.read_text(encoding="utf-8")
    assert "thumbnailRepository.getThumbnailFromMemory(video" in video
    assert "thumbnailRepository.getFolderThumbnailFromMemory" in folder
    assert video.index("getThumbnailFromMemory(video") < video.index("getThumbnail(video")
    assert "LaunchedEffect(thumbnailRequestKey" in video


if __name__ == "__main__":
    test_thumbnail_cache_policy_is_bounded_and_pressure_aware()
    test_cards_peek_memory_before_async_thumbnail_work()
    print("Thumbnail cache contracts: 2 passed")
