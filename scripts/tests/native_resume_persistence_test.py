#!/usr/bin/env python3
"""Native resume integration and non-destructive playback-history upgrade checks."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
PLAYER = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player"


class NativeResumeIntegrationTest(unittest.TestCase):
    def test_close_captures_before_native_stop_clears_clock(self):
        source = (PLAYER / "PlayerActivity.kt").read_text()
        start = source.index("  private fun silenceAudioOnClose()")
        close = source[start:source.index("  override fun onPause()", start)]
        self.assertLess(close.index("saveVideoPlaybackState(fileName, immediate = true)"),
                        close.index("nativeEngine.stop()"))
        self.assertIn("(playbackWasInitialized || nativeWasActive) && ownsPlaybackSession", source)

    def test_save_uses_pending_native_seek_target_not_stale_live_clock(self):
        source = (PLAYER / "PlayerActivity.kt").read_text()
        capture = source[source.index("  private fun capturePlaybackStateSnapshot("):
                         source.index("  private data class SharedMpvTrack")]
        self.assertIn("nativeEngine.positionForPersistenceMs()", capture)
        self.assertIn("nativeEngine.captureAudioSelection()", capture)
        self.assertIn("nativeEngine.captureSubtitleSelection()", capture)

    def test_native_drag_sends_scene_preview_before_release(self):
        controls = (PLAYER / "controls/PlayerControls.kt").read_text()
        start = controls.index("nativeSeekPreviewPosition = it")
        self.assertIn("viewModel.seekPreviewTo(it)", controls[start:start + 160])
        model = (PLAYER / "PlayerViewModel.kt").read_text()
        start = model.index("  fun seekPreviewTo(")
        preview = model[start:model.index("  private suspend fun runSeekPreviewLoop", start)]
        self.assertIn("host.nativeSeekTo(", preview)
        self.assertIn("beginNativeScrub()", preview)

    def test_room_upgrade_preserves_existing_position_and_adds_nullable_selections(self):
        schema = json.loads((ROOT / "app/schemas/app.gyrolet.mpv_infinity.database.MpvInfinityDatabase/16.json").read_text())
        entity = next(item for item in schema["database"]["entities"] if item["tableName"] == "PlaybackStateEntity")
        database = sqlite3.connect(":memory:")
        database.execute(entity["createSql"].replace("${TABLE_NAME}", "PlaybackStateEntity"))
        module = (ROOT / "app/src/main/java/app/infinity/mpvz/di/DatabaseModule.kt").read_text()
        for migration in ("MIGRATION_18_19", "MIGRATION_23_24"):
            start = module.index("val " + migration + " =")
            end = module.find("\nval ", start + 1)
            block = module[start:end if end >= 0 else None]
            statements = re.findall(r'db\.execSQL\("(ALTER TABLE `PlaybackStateEntity`[^"\n]*)"\)', block)
            self.assertEqual(2, len(statements), migration)
            if migration == "MIGRATION_18_19":
                for statement in statements:
                    database.execute(statement)
                columns = database.execute("PRAGMA table_info(PlaybackStateEntity)").fetchall()
                values = {column[1]: ("" if column[2] == "TEXT" else 0) for column in columns}
                values.update(mediaTitle="media:v2:movie", lastPosition=600, playbackSpeed=1.5,
                              sid=2, aid=3, externalSubtitles="content://subs/english.srt",
                              videoAspect="Crop", customAspectRatio=-1.0)
                names = list(values)
                database.execute("INSERT INTO PlaybackStateEntity (" + ",".join(names) + ") VALUES (" +
                                 ",".join("?" for _ in names) + ")", [values[name] for name in names])
            else:
                for statement in statements:
                    database.execute(statement)
        row = database.execute("SELECT lastPosition,playbackSpeed,sid,aid,externalSubtitles,videoAspect,audioSelection,subtitleSelection FROM PlaybackStateEntity").fetchone()
        self.assertEqual((600, 1.5, 2, 3, "content://subs/english.srt", "Crop", None, None), row)
        self.assertIn("MIGRATION_23_24,", module)
        database.close()


if __name__ == "__main__":
    unittest.main()
