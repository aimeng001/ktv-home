-- Region data is a separate, exact-name reference. Conflicting source names
-- are withheld during import and never expanded into Home song records.
CREATE TABLE iktv_artist_reference_snapshots (
    snapshot_version VARCHAR(64) PRIMARY KEY,
    status VARCHAR(16) NOT NULL CHECK (status IN ('STAGING', 'ACTIVE', 'RETIRED', 'FAILED')),
    source_row_count BIGINT NOT NULL CHECK (source_row_count >= 0),
    loaded_entry_count BIGINT NOT NULL DEFAULT 0 CHECK (loaded_entry_count >= 0),
    conflicting_key_count BIGINT NOT NULL DEFAULT 0 CHECK (conflicting_key_count >= 0),
    source_sha256 VARCHAR(64) NOT NULL,
    activated_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_iktv_artist_reference_snapshot_active
    ON iktv_artist_reference_snapshots (status) WHERE status = 'ACTIVE';

CREATE TABLE iktv_reference_artists (
    snapshot_version VARCHAR(64) NOT NULL
        REFERENCES iktv_artist_reference_snapshots(snapshot_version) ON DELETE CASCADE,
    artist_key TEXT NOT NULL,
    display_name TEXT NOT NULL,
    region VARCHAR(16) NOT NULL CHECK (region IN ('内地', '港台', '国外')),
    gender VARCHAR(32) NOT NULL CHECK (gender IN ('男歌手', '女歌手', '组合')),
    source_category VARCHAR(32) NOT NULL,
    PRIMARY KEY (snapshot_version, artist_key)
);

CREATE INDEX idx_iktv_reference_artists_region_key
    ON iktv_reference_artists (snapshot_version, region, artist_key);

CREATE VIEW active_iktv_artist_regions AS
SELECT reference_artist.artist_key,
       reference_artist.display_name,
       reference_artist.region,
       reference_artist.gender,
       reference_artist.source_category
  FROM iktv_reference_artists reference_artist
  JOIN iktv_artist_reference_snapshots reference_snapshot
    ON reference_snapshot.snapshot_version = reference_artist.snapshot_version
   AND reference_snapshot.status = 'ACTIVE';
