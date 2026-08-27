-- V20 used SMALLINT while SongFile.lyricSnapshotVersion is an Integer.
-- Widen the column without changing values or removing any user data.
ALTER TABLE song_files
    ALTER COLUMN lyric_snapshot_version TYPE INTEGER
    USING lyric_snapshot_version::INTEGER;
