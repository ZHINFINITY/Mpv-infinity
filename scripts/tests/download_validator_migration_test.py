#!/usr/bin/env python3
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE.
"""Exercise the actual 22->23 SQL without compiling or mocking a database."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "app/src/main/java/app/infinity/mpvz/di/DatabaseModule.kt"
DATABASE = ROOT / "app/src/main/java/app/infinity/mpvz/database/MpvInfinityDatabase.kt"


class DownloadValidatorMigrationTest(unittest.TestCase):
    def test_upgrade_preserves_rows_indexes_and_nullable_defaults(self):
        source = MODULE.read_text()
        migration = re.split(r"\nval \w+\s*=", source.split("val MIGRATION_22_23 =", 1)[1], maxsplit=1)[0]
        statements = re.findall(r'db\.execSQL\("([^"\n]+)"\)', migration)
        self.assertEqual(len(statements), 2)
        connection = sqlite3.connect(":memory:")
        self.addCleanup(connection.close)
        connection.row_factory = sqlite3.Row
        connection.execute("""CREATE TABLE download_items (
          id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          systemDownloadId INTEGER NOT NULL, url TEXT NOT NULL,
          dirPath TEXT NOT NULL, fileName TEXT NOT NULL, stagingPath TEXT,
          status TEXT NOT NULL, progress INTEGER NOT NULL, totalBytes INTEGER NOT NULL,
          failureReason TEXT, timeQueued INTEGER NOT NULL, source TEXT NOT NULL,
          title TEXT NOT NULL, posterUrl TEXT, sourceUrl TEXT, jellyfinServerId TEXT,
          jellyfinItemId TEXT, jellyfinSeriesName TEXT, seasonNumber INTEGER,
          episodeNumber INTEGER, isAudio INTEGER NOT NULL
        )""")
        connection.execute("CREATE INDEX index_download_items_systemDownloadId ON download_items(systemDownloadId)")
        connection.execute("CREATE INDEX index_download_items_jellyfinItemId ON download_items(jellyfinItemId)")
        connection.execute("""INSERT INTO download_items
          (id, systemDownloadId, url, dirPath, fileName, status, progress,
           totalBytes, timeQueued, source, title, isAudio)
          VALUES (7, -1, 'https://example.invalid/movie', '/downloads', 'Movie.mkv',
                  'PAUSED', 42, 1000, 123, 'link', 'Movie', 0)""")
        old_row = dict(connection.execute("SELECT * FROM download_items").fetchone())
        old_indexes = connection.execute("SELECT name FROM sqlite_master WHERE type='index' ORDER BY name").fetchall()
        for statement in statements:
            connection.execute(statement)
        new_row = dict(connection.execute("SELECT * FROM download_items").fetchone())
        self.assertEqual({key: new_row[key] for key in old_row}, old_row)
        self.assertIsNone(new_row["remoteEtag"])
        self.assertIsNone(new_row["remoteLastModified"])
        self.assertEqual(connection.execute("SELECT name FROM sqlite_master WHERE type='index' ORDER BY name").fetchall(), old_indexes)
        columns = {row[1]: row for row in connection.execute("PRAGMA table_info(download_items)")}
        for name in ("remoteEtag", "remoteLastModified"):
            self.assertEqual(columns[name][2], "TEXT")
            self.assertEqual(columns[name][3], 0)
            self.assertIsNone(columns[name][4])
        connection.execute('UPDATE download_items SET remoteEtag = ?, remoteLastModified = ? WHERE id = 7',
                           ('"version-1"', 'Tue, 15 Nov 1994 08:12:31 GMT'))
        self.assertEqual(connection.execute("SELECT remoteEtag FROM download_items WHERE id=7").fetchone()[0], '"version-1"')

    def test_migration_is_registered_for_schema_23_or_later(self):
        version = re.search(r"version\s*=\s*(\d+)\b", DATABASE.read_text())
        self.assertIsNotNone(version)
        self.assertGreaterEqual(int(version.group(1)), 23)
        registered = MODULE.read_text().split(".addMigrations(", 1)[1].split(").build()", 1)[0]
        self.assertIn("MIGRATION_22_23", registered)


if __name__ == "__main__":
    unittest.main()
