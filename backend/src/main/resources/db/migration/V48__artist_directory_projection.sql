-- Materialized public artist directory. Refreshes are application-owned and
-- never write to source media files.
CREATE TABLE artist_directory_stats (
    artist_key      TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    initial         VARCHAR(8) NOT NULL DEFAULT '#',
    gender          VARCHAR(32) NOT NULL DEFAULT '未知',
    song_count      BIGINT NOT NULL DEFAULT 0,
    reviewed        BOOLEAN NOT NULL DEFAULT FALSE,
    artist_kind     VARCHAR(32) NOT NULL DEFAULT 'PERSON',
    avatar_path     TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_artist_directory_stats_lookup
    ON artist_directory_stats (gender, initial, song_count DESC, name, artist_key);

CREATE TABLE artist_directory_projection_state (
    projection_key  VARCHAR(64) PRIMARY KEY,
    refreshed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
