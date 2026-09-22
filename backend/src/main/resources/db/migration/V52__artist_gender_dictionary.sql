-- Database-backed exact artist gender/category dictionary.
-- This table is application-owned and never writes to source media.
CREATE TABLE artist_gender_dictionary (
    alias_key       TEXT PRIMARY KEY,
    canonical_key   TEXT NOT NULL,
    display_name    TEXT NOT NULL,
    gender          VARCHAR(32) NOT NULL
        CHECK (gender IN ('男歌手', '女歌手', '组合', '未知')),
    source          VARCHAR(32) NOT NULL
        CHECK (source IN ('BUILTIN', 'ADMIN')),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_artist_gender_dictionary_gender
    ON artist_gender_dictionary(gender, enabled);

CREATE INDEX idx_artist_gender_dictionary_canonical
    ON artist_gender_dictionary(canonical_key, enabled);
