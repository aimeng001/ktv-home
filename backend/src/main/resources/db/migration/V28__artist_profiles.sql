-- Persistent artist directory metadata. This migration only adds application
-- tables; it never reads or writes files in the external library.
CREATE TABLE artist_profiles (
    artist_key          TEXT PRIMARY KEY,
    display_name        TEXT NOT NULL,
    artist_kind         VARCHAR(32) NOT NULL DEFAULT 'PERSON',
    pinyin              TEXT NOT NULL DEFAULT '',
    initials            TEXT NOT NULL DEFAULT '',
    gender              VARCHAR(32) NOT NULL DEFAULT '未知',
    gender_status       VARCHAR(32) NOT NULL DEFAULT 'UNREVIEWED',
    avatar_path         TEXT,
    avatar_provider     VARCHAR(64),
    avatar_external_id  TEXT,
    avatar_status       VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    avatar_error        TEXT,
    avatar_attempts     INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_artist_profiles_name_trgm
    ON artist_profiles USING gin (display_name gin_trgm_ops);
CREATE INDEX idx_artist_profiles_avatar_status
    ON artist_profiles (avatar_status, artist_kind);
