#!/usr/bin/env python3
"""Build a deterministic, read-only-derived IKTV reference catalog resource."""

from __future__ import annotations

import csv
import gzip
import hashlib
import io
import json
import os
import re
import sqlite3
import tempfile
import unicodedata
from pathlib import Path


FORMAT_VERSION = 1
SPECIAL_FIELDS = ("title", "artist", "tags", "edition")


class SnapshotResult:
    def __init__(self, song_count: int, special_row_count: int, matched_special_keys: int,
                 special_tag_conflict_count: int, duplicate_catalog_number_groups: int):
        self.song_count = song_count
        self.special_row_count = special_row_count
        self.matched_special_keys = matched_special_keys
        self.special_tag_conflict_count = special_tag_conflict_count
        self.duplicate_catalog_number_groups = duplicate_catalog_number_groups


def normalize_key(value: str | None) -> str:
    normalized = unicodedata.normalize("NFKC", value or "").lower()
    return " ".join(normalized.split())


def open_source_database(path: Path) -> sqlite3.Connection:
    database_uri = Path(path).resolve().as_uri() + "?mode=ro"
    connection = sqlite3.connect(database_uri, uri=True)
    connection.execute("PRAGMA query_only = ON")
    return connection


def read_special_songs(path: Path) -> tuple[dict[tuple[str, str, str], set[tuple[str, ...]]], int]:
    indexed: dict[tuple[str, str, str], set[tuple[str, ...]]] = {}
    row_count = 0
    with Path(path).open("r", encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None or not set(SPECIAL_FIELDS).issubset(reader.fieldnames):
            raise ValueError("IKTV special-song CSV is missing required columns")
        for row in reader:
            row_count += 1
            key = (
                normalize_key(row["title"]),
                normalize_key(row["artist"]),
                normalize_key(row["edition"]),
            )
            tags = tuple(sorted({tag.strip() for tag in (row["tags"] or "").split("|") if tag.strip()}))
            indexed.setdefault(key, set()).add(tags)
    return indexed, row_count


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_snapshot(connection: sqlite3.Connection, special_index, output: Path) -> tuple[int, set, int]:
    query = """
        SELECT s.id, CAST(s.number AS TEXT), s.name, s.singer_names,
               COALESCE(s.edition, ''), COALESCE(language.name, ''),
               COALESCE(category.name, ''), COALESCE(song_type.name, '')
          FROM song s
          LEFT JOIN song_language language ON language.id = s.language_id
          LEFT JOIN song_type ON song_type.id = s.type_id
          LEFT JOIN song_category category ON category.id = song_type.category_id
         ORDER BY s.id
    """
    special_keys_found = set()
    song_count = 0
    special_tag_conflict_count = 0
    with output.open("wb") as raw_output:
        compressed = gzip.GzipFile(fileobj=raw_output, mode="wb", mtime=0)
        with io.TextIOWrapper(compressed, encoding="utf-8", newline="") as text_output:
            for row in connection.execute(query):
                reference_id, raw_number, title, artist, edition, language, category, song_type = row
                title = title or ""
                artist = artist or ""
                edition = edition or ""
                key = (normalize_key(title), normalize_key(artist), normalize_key(edition))
                tag_sets = special_index.get(key, set())
                if tag_sets:
                    special_keys_found.add(key)
                tag_conflict = len(tag_sets) > 1
                if tag_conflict:
                    special_tag_conflict_count += 1
                tags = () if tag_conflict or not tag_sets else next(iter(tag_sets))
                number = str(raw_number or "")
                if not re.fullmatch(r"[0-9]{8,10}", number):
                    number = ""
                snapshot_row = {
                    "reference_song_id": reference_id,
                    "catalog_number": number,
                    "title": title,
                    "artist": artist,
                    "edition": edition,
                    "language": language or "",
                    "category": category or "",
                    "type": song_type or "",
                    "normalized_title": key[0],
                    "normalized_artist": key[1],
                    "normalized_edition": key[2],
                    "special_tags": list(tags),
                    "special_tag_conflict": tag_conflict,
                }
                text_output.write(json.dumps(snapshot_row, ensure_ascii=False, separators=(",", ":")) + "\n")
                song_count += 1
    return song_count, special_keys_found, special_tag_conflict_count


def build_snapshot(database: Path, special_csv: Path, output: Path, manifest: Path) -> SnapshotResult:
    database = Path(database)
    special_csv = Path(special_csv)
    output = Path(output)
    manifest = Path(manifest)
    if output.exists() or manifest.exists():
        raise FileExistsError("Refusing to replace an existing reference snapshot or manifest")
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest.parent.mkdir(parents=True, exist_ok=True)
    special_index, special_row_count = read_special_songs(special_csv)
    connection = open_source_database(database)
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(prefix="iktv-reference-", suffix=".part", dir=output.parent,
                                         delete=False) as temporary:
            temporary_path = Path(temporary.name)
        song_count, special_keys_found, conflict_count = _write_snapshot(
            connection, special_index, temporary_path)
        duplicate_catalog_number_groups = connection.execute(
            "SELECT COUNT(*) FROM (SELECT number FROM song GROUP BY number HAVING COUNT(*) > 1)"
        ).fetchone()[0]
    except Exception:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
        raise
    finally:
        connection.close()

    source_database_hash = _sha256(database)
    source_special_hash = _sha256(special_csv)
    snapshot_hash = _sha256(temporary_path)
    snapshot_version = hashlib.sha256(
        f"{FORMAT_VERSION}:{source_database_hash}:{source_special_hash}".encode("ascii")
    ).hexdigest()
    manifest_value = {
        "format_version": FORMAT_VERSION,
        "snapshot_version": snapshot_version,
        "source_database_sha256": source_database_hash,
        "source_special_csv_sha256": source_special_hash,
        "snapshot_sha256": snapshot_hash,
        "reference_song_count": song_count,
        "special_csv_row_count": special_row_count,
        "matched_special_key_count": len(special_keys_found),
        "unmatched_special_key_count": len(set(special_index) - special_keys_found),
        "conflicting_special_tag_key_count": sum(len(tag_sets) > 1 for tag_sets in special_index.values()),
        "reference_rows_withheld_special_tags": conflict_count,
        "duplicate_catalog_number_groups": duplicate_catalog_number_groups,
    }
    try:
        os.replace(temporary_path, output)
        manifest.write_text(json.dumps(manifest_value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except Exception:
        output.unlink(missing_ok=True)
        manifest.unlink(missing_ok=True)
        raise
    finally:
        temporary_path.unlink(missing_ok=True)
    return SnapshotResult(song_count, special_row_count, len(special_keys_found), conflict_count,
                          duplicate_catalog_number_groups)


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    references = root / "参考"
    output = root / "backend" / "src" / "main" / "resources" / "iktv" / "iktv-reference-catalog.jsonl.gz"
    manifest = output.with_suffix(".manifest.json")
    result = build_snapshot(
        references / "ktv_extracted_data" / "databases" / "song.db",
        root / "backend" / "src" / "main" / "resources" / "iktv" / "iktv-special-songs.csv",
        output,
        manifest,
    )
    print(
        f"Built {result.song_count} reference rows; matched special keys {result.matched_special_keys}; "
        f"withheld conflicted tags for {result.special_tag_conflict_count} rows; "
        f"duplicate catalog number groups {result.duplicate_catalog_number_groups}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
