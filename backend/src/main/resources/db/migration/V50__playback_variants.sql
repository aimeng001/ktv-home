-- Server-generated playback sidecars. Source media remains in the configured
-- library and is never overwritten by this table or its cache files.
CREATE TABLE playback_variants (
    id                          BIGSERIAL PRIMARY KEY,
    source_file_id              BIGINT NOT NULL REFERENCES song_files(id) ON DELETE CASCADE,
    source_fingerprint          VARCHAR(128) NOT NULL,
    profile                     VARCHAR(64) NOT NULL,
    status                      VARCHAR(16) NOT NULL,
    cache_path                  TEXT,
    format                      VARCHAR(32),
    audio_tracks                INTEGER NOT NULL DEFAULT 0,
    audio_layout                VARCHAR(32) NOT NULL DEFAULT 'NORMAL_STEREO',
    original_track_index        INTEGER,
    accompaniment_track_index  INTEGER,
    original_channel            VARCHAR(16) NOT NULL DEFAULT 'LEFT',
    accompaniment_channel       VARCHAR(16) NOT NULL DEFAULT 'RIGHT',
    file_size                   BIGINT NOT NULL DEFAULT 0,
    source_size                 BIGINT,
    source_mtime                TIMESTAMPTZ,
    lease_owner                 VARCHAR(128),
    lease_until                 TIMESTAMPTZ,
    error_code                  VARCHAR(64),
    error_message               TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_at                    TIMESTAMPTZ,
    CONSTRAINT uq_playback_variant_source_profile
        UNIQUE (source_file_id, source_fingerprint, profile)
);

CREATE INDEX idx_playback_variants_status_lease
    ON playback_variants(status, lease_until);
CREATE INDEX idx_playback_variants_source_file
    ON playback_variants(source_file_id);
