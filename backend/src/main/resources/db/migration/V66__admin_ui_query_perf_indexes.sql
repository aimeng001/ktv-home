-- Admin UI query performance indexes (KTV songs case-insensitive trigram search and metadata scrape task lookups).

CREATE INDEX IF NOT EXISTS idx_songs_title_lower_trgm
    ON songs USING gin (LOWER(title) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_songs_artist_lower_trgm
    ON songs USING gin (LOWER(artist) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_metadata_scrape_items_song_status
    ON music_metadata_scrape_items (song_id, status);

CREATE INDEX IF NOT EXISTS idx_metadata_scrape_items_batch_id_desc
    ON music_metadata_scrape_items (batch_id, id DESC);
