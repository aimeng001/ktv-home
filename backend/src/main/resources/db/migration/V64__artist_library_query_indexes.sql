-- Support millisecond admin artist directory paging, filtering, and avatar error summary queries.
CREATE INDEX IF NOT EXISTS idx_artist_directory_stats_admin_order
    ON artist_directory_stats (song_count DESC, name, artist_key);

CREATE INDEX IF NOT EXISTS idx_artist_directory_stats_admin_filter
    ON artist_directory_stats (reviewed, gender, song_count DESC, name, artist_key);

CREATE INDEX IF NOT EXISTS idx_artist_directory_stats_gender_order
    ON artist_directory_stats (gender, song_count DESC, name, artist_key);

CREATE INDEX IF NOT EXISTS idx_artist_directory_key_trgm
    ON artist_directory_stats USING gin (LOWER(artist_key) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_artist_avatar_jobs_recent_errors
    ON artist_avatar_jobs (updated_at DESC)
    WHERE last_error IS NOT NULL AND status IN ('FAILED', 'REVIEW', 'PENDING');
