#!/usr/bin/env python3
"""Static contracts ensuring all shared MPV asset mutations are staged."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLAYER = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt").read_text()
VIEW_MODEL = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerViewModel.kt").read_text()


def section(start, end):
    a = PLAYER.index(start)
    b = PLAYER.index(end, a + len(start))
    return PLAYER[a:b]


assert "private val mpvAssetPreparationGeneration = AtomicLong(0L)" in PLAYER
assert PLAYER.count("private val deferredUserMpvAssetRefreshStarted = AtomicBoolean(false)") == 1
assert "publisher.newStage(ownerToken, generation)" in PLAYER
publish = section("  private fun publishMpvAssetStage(", "  private fun isMpvAssetPreparationCurrent(")
assert "PlaybackActivityOwner.runIfOwner(ownerToken, false)" in publish
assert "val published = publisher.publish(" in publish and "          stageRoot," in publish
assert publish.index("val published = publisher.publish(") < publish.index("commitPendingMpvAssetPreferenceUpdates(pending)")
assert "pending.userAssetDirectoryUri == advancedPreferences.mpvConfStorageUri.get()" in publish
startup_user_assets = section(
    "  private fun prepareUserMpvAssetsForStartup(", "  private fun currentUserMpvAssetSelection("
)
assert "val mpvConfStorageUri = advancedPreferences.mpvConfStorageUri.get()" in startup_user_assets
assert "syncFromUserMpvDirectory(assetRoot, pending, mpvConfStorageUri)" in startup_user_assets
assert "mpvConfStorageUri = mpvConfStorageUri" in startup_user_assets
deferred_user_assets = section(
    "  private fun scheduleDeferredUserMpvAssetRefresh()", "  private fun syncSubtitleFontsFromPreferenceFolder(assetRoot: File)"
)
assert "pending.userAssetDirectoryUri = mpvConfStorageUri" in deferred_user_assets
assert "override fun getFilesDir(): File = assetRoot" in section(
    "  private fun syncBundledAssetsIfNeeded(", "  private fun scheduleDeferredSubtitleFontsSync()"
)

for start, end in [
    ("  private fun syncFromUserMpvDirectory(", "  // ==================== Config Files Sync"),
    ("  private fun syncConfigFiles(", "  // ==================== Scripts Sync"),
    ("  private fun syncScripts(", "  /**\n   * Syncs helper folders from scripts/"),
    ("  private fun syncScriptSupportDirectories(", "  // ==================== Script Options Sync"),
    ("  private fun syncScriptOpts(", "  // ==================== Shaders Sync"),
    ("  private fun syncShaders(", "  // ==================== Fonts Sync"),
    ("  private fun syncFonts(", "  private fun runStagedMpvAssetUpdate("),
    ("  private fun copyMPVConfigFromPreferences(", "  private fun clearDirectoryContents(directory: File)"),
]:
    block = section(start, end)
    assert "assetRoot" in block, start
    assert "File(filesDir," not in block, start

assert "pending.mpvConf = content" in section(
    "  private fun syncConfigFiles(", "  // ==================== Scripts Sync"
)
assert "pending.inputConf = content" in section(
    "  private fun syncConfigFiles(", "  // ==================== Scripts Sync"
)
assert "advancedPreferences.mpvConf.set(content)" not in section(
    "  private fun syncConfigFiles(", "  // ==================== Scripts Sync"
)
assert "runStagedMpvAssetUpdate(generation)" in section(
    "  private fun prepareMpvAssetsForStartup(", "  private fun startMpvAssetPreparation()"
)
font_sync = section(
    "  private fun scheduleDeferredSubtitleFontsSync()", "  private fun scheduleDeferredUserMpvAssetRefresh()"
)
assert "runStagedMpvAssetUpdate(" in font_sync
assert "generationState = subtitleFontAssetGeneration" in font_sync
assert "runStagedMpvAssetUpdate(generation)" in section(
    "  private fun scheduleDeferredUserMpvAssetRefresh()", "  private fun syncSubtitleFontsFromPreferenceFolder(assetRoot: File)"
)
assert "runStagedMpvAssetUpdate(" in section(
    "  private fun loadScriptsAtRuntime(scriptNames: List<String>)", "  // ==================== Helpers ===================="
)
assert "MpvAssetSetPublisher.withLivePathMutation(appContext.filesDir, listOf(\"scripts\"))" in VIEW_MODEL
assert "syncScriptSupportDirectories(scriptsSubdir, assetRoot)" in PLAYER
assert "syncScriptOpts(tree, rootChildren, assetRoot)" in PLAYER
print("MPV staged asset contracts passed")
