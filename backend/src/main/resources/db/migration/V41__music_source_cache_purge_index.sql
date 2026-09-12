-- Keep reference-aware expiration cleanup indexed by the external track key.
CREATE INDEX IF NOT EXISTS idx_song_external_matches_track
    ON song_external_matches(provider, external_id);
