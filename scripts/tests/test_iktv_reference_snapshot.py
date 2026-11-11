import csv
import gzip
import importlib.util
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "build_iktv_reference_snapshot.py"
SPEC = importlib.util.spec_from_file_location("build_iktv_reference_snapshot", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class IktvReferenceSnapshotTests(unittest.TestCase):
    def setUp(self):
        temp_parent = ROOT / "backend" / "target" / "iktv-remediation" / "fixtures"
        temp_parent.mkdir(parents=True, exist_ok=True)
        self.temp_dir = tempfile.TemporaryDirectory(dir=temp_parent)
        self.root = Path(self.temp_dir.name)
        self.database = self.root / "song.db"
        self.special_csv = self.root / "special.csv"
        self.snapshot = self.root / "reference.jsonl.gz"
        self.manifest = self.root / "reference.manifest.json"
        self._create_database()
        self._create_special_csv()

    def tearDown(self):
        self.temp_dir.cleanup()

    def _create_database(self):
        connection = sqlite3.connect(self.database)
        try:
            connection.executescript(
                """
                CREATE TABLE song(
                    id INTEGER PRIMARY KEY,
                    number INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    singer_names TEXT NOT NULL,
                    edition TEXT,
                    language_id INTEGER,
                    type_id INTEGER
                );
                CREATE TABLE song_language(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
                CREATE TABLE song_type(id INTEGER PRIMARY KEY, name TEXT, category_id INTEGER NOT NULL);
                CREATE TABLE song_category(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
                INSERT INTO song_language VALUES (1, '国语');
                INSERT INTO song_category VALUES (1, '流行');
                INSERT INTO song_type VALUES (1, '热歌', 1);
                INSERT INTO song VALUES (1, 12345678, '甲,乙', '歌手甲', 'MV', 1, 1);
                INSERT INTO song VALUES (2, 12345679, '重复歌名', '歌手乙', 'MV', 1, 1);
                INSERT INTO song VALUES (3, 12345680, '重复歌名', '歌手乙', '现场版', 1, 1);
                """
            )
        finally:
            connection.close()

    def _create_special_csv(self):
        with self.special_csv.open("w", encoding="utf-8", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=("title", "artist", "tags", "edition"))
            writer.writeheader()
            writer.writerows(
                (
                    {"title": "甲,乙", "artist": "歌手甲", "tags": "抖音神曲", "edition": "MV"},
                    {"title": "重复歌名", "artist": "歌手乙", "tags": "精选专区", "edition": "MV"},
                    {"title": "重复歌名", "artist": "歌手乙", "tags": "精选专区", "edition": "MV"},
                    {"title": "重复歌名", "artist": "歌手乙", "tags": "抖音神曲", "edition": "现场版"},
                )
            )

    def test_snapshot_preserves_quoted_titles_and_exact_edition_specific_tags(self):
        result = MODULE.build_snapshot(self.database, self.special_csv, self.snapshot, self.manifest)

        with gzip.open(self.snapshot, "rt", encoding="utf-8") as source:
            rows = {str(row["reference_song_id"]): row for row in map(json.loads, source)}

        self.assertEqual(result.song_count, 3)
        self.assertEqual(rows["1"]["title"], "甲,乙")
        self.assertEqual(rows["1"]["special_tags"], ["抖音神曲"])
        self.assertEqual(rows["2"]["special_tags"], ["精选专区"])
        self.assertEqual(rows["3"]["special_tags"], ["抖音神曲"])
        self.assertEqual(rows["3"]["edition"], "现场版")

    def test_conflicting_tag_rows_are_withheld_and_marked(self):
        with self.special_csv.open("a", encoding="utf-8", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=("title", "artist", "tags", "edition"))
            writer.writerow({"title": "甲,乙", "artist": "歌手甲", "tags": "精选专区", "edition": "MV"})

        MODULE.build_snapshot(self.database, self.special_csv, self.snapshot, self.manifest)

        with gzip.open(self.snapshot, "rt", encoding="utf-8") as source:
            rows = {str(row["reference_song_id"]): row for row in map(json.loads, source)}

        self.assertEqual(rows["1"]["special_tags"], [])
        self.assertIs(rows["1"]["special_tag_conflict"], True)

    def test_source_database_is_opened_read_only(self):
        connection = MODULE.open_source_database(self.database)
        try:
            with self.assertRaises(sqlite3.OperationalError):
                connection.execute("CREATE TABLE forbidden(value TEXT)")
        finally:
            connection.close()

    def test_failed_snapshot_write_removes_temporary_part_file(self):
        with patch.object(MODULE, "_write_snapshot", side_effect=RuntimeError("fixture failure")):
            with self.assertRaisesRegex(RuntimeError, "fixture failure"):
                MODULE.build_snapshot(self.database, self.special_csv, self.snapshot, self.manifest)

        self.assertEqual(list(self.root.glob("iktv-reference-*.part")), [])


if __name__ == "__main__":
    unittest.main()
