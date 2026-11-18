-- Support O(1) unrecognized song lookup and fast pending-probe keyset scans.
CREATE INDEX IF NOT EXISTS idx_songs_unrecognized_id
    ON songs (id)
    WHERE status = 'unrecognized';

CREATE INDEX IF NOT EXISTS idx_song_files_role_pending_path
    ON song_files (file_role, file_path)
    WHERE probe_pending = TRUE;
