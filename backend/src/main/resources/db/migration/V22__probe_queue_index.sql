-- Keep the persistent media-probe queue cheap to page through during large scans.
-- The index follows the queue query: role + pending flag + keyset path order.
CREATE INDEX IF NOT EXISTS idx_song_files_probe_queue
    ON song_files (file_role, probe_pending, file_path);
