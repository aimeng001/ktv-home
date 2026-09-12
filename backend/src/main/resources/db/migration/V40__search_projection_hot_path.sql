-- Align case-insensitive pinyin predicates with expression indexes and keep
-- long tag substring searches on the maintained projection instead of
-- expanding the source tag array in the songs hot path.

CREATE INDEX IF NOT EXISTS idx_songs_title_py_lower_prefix
    ON songs (lower(title_py) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_songs_title_init_lower_prefix
    ON songs (lower(title_init) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_songs_artist_py_lower_prefix
    ON songs (lower(artist_py) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_songs_artist_init_lower_prefix
    ON songs (lower(artist_init) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_song_artists_artist_py_lower_prefix
    ON song_artists (lower(artist_py) text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_song_artists_artist_init_lower_prefix
    ON song_artists (lower(artist_init) text_pattern_ops);

-- V37 stores one/two-character grams for TAG. Keep the original value too so
-- pg_trgm can serve case-insensitive substring searches of three or more
-- characters without changing the public tag contract.
CREATE OR REPLACE FUNCTION ktv_insert_short_search_terms(
    p_song_id BIGINT,
    p_kind VARCHAR,
    p_value TEXT
) RETURNS VOID
LANGUAGE plpgsql
AS $ktv$
DECLARE
    normalized TEXT := COALESCE(p_value, '');
BEGIN
    IF normalized = '' THEN
        RETURN;
    END IF;

    IF p_kind = 'TAG' THEN
        INSERT INTO song_search_terms (song_id, kind, value, value_lower)
        VALUES (p_song_id, p_kind, normalized, lower(normalized))
        ON CONFLICT DO NOTHING;
    END IF;

    INSERT INTO song_search_terms (song_id, kind, value, value_lower)
    SELECT p_song_id, p_kind, grams.value, lower(grams.value)
    FROM (
        SELECT DISTINCT substring(normalized FROM positions.position FOR lengths.length) AS value
        FROM generate_series(1, char_length(normalized)) AS positions(position)
        CROSS JOIN (VALUES (1), (2)) AS lengths(length)
        WHERE positions.position + lengths.length - 1 <= char_length(normalized)
    ) AS grams
    WHERE grams.value <> ''
    ON CONFLICT DO NOTHING;
END
$ktv$;

-- V38 uses a statement-level INSERT trigger for bulk imports. Add the full
-- tag values with the same statement-level behavior; UPDATE continues to use
-- ktv_rebuild_song_search_terms(), which now inserts full tags through the
-- function above.
CREATE OR REPLACE FUNCTION ktv_sync_full_tag_search_terms_statement()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $ktv$
BEGIN
    INSERT INTO song_search_terms (song_id, kind, value, value_lower)
    SELECT rows.id, 'TAG', tags.value, lower(tags.value)
    FROM new_rows rows
    CROSS JOIN LATERAL unnest(COALESCE(rows.tags, ARRAY[]::TEXT[])) AS tags(value)
    WHERE tags.value <> ''
    ON CONFLICT DO NOTHING;
    RETURN NULL;
END
$ktv$;

DO $ktv$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_songs_full_tag_search_terms_insert'
          AND tgrelid = 'songs'::regclass
    ) THEN
        CREATE TRIGGER trg_songs_full_tag_search_terms_insert
        AFTER INSERT ON songs
        REFERENCING NEW TABLE AS new_rows
        FOR EACH STATEMENT EXECUTE FUNCTION ktv_sync_full_tag_search_terms_statement();
    END IF;
END
$ktv$;

-- Backfill full tag values for rows created before V40. Existing one/two
-- character grams remain in place and are deduplicated by the primary key.
INSERT INTO song_search_terms (song_id, kind, value, value_lower)
SELECT songs.id, 'TAG', tags.value, lower(tags.value)
FROM songs
CROSS JOIN LATERAL unnest(COALESCE(songs.tags, ARRAY[]::TEXT[])) AS tags(value)
WHERE tags.value <> ''
ON CONFLICT DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_song_search_terms_tag_lower_trgm
    ON song_search_terms USING gin (value_lower gin_trgm_ops)
    WHERE kind = 'TAG';
