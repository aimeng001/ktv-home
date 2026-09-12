-- Materialized one/two-character search terms for substring semantics that
-- PostgreSQL pg_trgm cannot optimize. The projection is application-owned;
-- it never touches source music files.
CREATE TABLE song_search_terms (
    song_id     BIGINT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
    kind        VARCHAR(32) NOT NULL,
    value       TEXT NOT NULL,
    value_lower TEXT NOT NULL,
    PRIMARY KEY (song_id, kind, value)
);

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

CREATE OR REPLACE FUNCTION ktv_rebuild_song_search_terms(p_song_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $ktv$
DECLARE
    song_row RECORD;
    tag TEXT;
BEGIN
    EXECUTE 'DELETE FROM song_search_terms
             WHERE song_id = $1
               AND kind IN (
                   ''TITLE'', ''ARTIST'', ''LANGUAGE'', ''TAG'',
                   ''TITLE_PY'', ''TITLE_INIT'', ''ARTIST_PY'', ''ARTIST_INIT''
               )'
        USING p_song_id;

    SELECT title, artist, title_py, title_init, artist_py,
           artist_init, language, tags
    INTO song_row
    FROM songs
    WHERE id = p_song_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    PERFORM ktv_insert_short_search_terms(p_song_id, 'TITLE', song_row.title);
    PERFORM ktv_insert_short_search_terms(p_song_id, 'TITLE_PY', song_row.title_py);
    PERFORM ktv_insert_short_search_terms(p_song_id, 'TITLE_INIT', song_row.title_init);
    PERFORM ktv_insert_short_search_terms(p_song_id, 'ARTIST', song_row.artist);
    PERFORM ktv_insert_short_search_terms(p_song_id, 'ARTIST_PY', song_row.artist_py);
    PERFORM ktv_insert_short_search_terms(p_song_id, 'ARTIST_INIT', song_row.artist_init);
    PERFORM ktv_insert_short_search_terms(p_song_id, 'LANGUAGE', song_row.language);
    FOREACH tag IN ARRAY COALESCE(song_row.tags, ARRAY[]::TEXT[]) LOOP
        PERFORM ktv_insert_short_search_terms(p_song_id, 'TAG', tag);
    END LOOP;
END
$ktv$;

CREATE OR REPLACE FUNCTION ktv_sync_song_search_terms()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $ktv$
BEGIN
    PERFORM ktv_rebuild_song_search_terms(NEW.id);
    RETURN NEW;
END
$ktv$;

CREATE OR REPLACE FUNCTION ktv_sync_song_artist_search_terms()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $ktv$
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'UPDATE' THEN
        EXECUTE 'DELETE FROM song_search_terms
                 WHERE song_id = $1
                   AND kind IN (''ARTIST_CREDIT'', ''ARTIST_CREDIT_PY'', ''ARTIST_CREDIT_INIT'')'
            USING OLD.song_id;
    END IF;

    IF TG_OP <> 'DELETE' THEN
        EXECUTE 'DELETE FROM song_search_terms
                 WHERE song_id = $1
                   AND kind IN (''ARTIST_CREDIT'', ''ARTIST_CREDIT_PY'', ''ARTIST_CREDIT_INIT'')'
            USING NEW.song_id;
    END IF;

    IF TG_OP <> 'DELETE' THEN
        INSERT INTO song_search_terms (song_id, kind, value, value_lower)
        SELECT artists.song_id, sources.kind, grams.value, lower(grams.value)
        FROM song_artists artists
        CROSS JOIN LATERAL (
            SELECT 'ARTIST_CREDIT'::VARCHAR(32) AS kind, artists.artist_name AS source_value
            UNION ALL SELECT 'ARTIST_CREDIT_PY', artists.artist_py
            UNION ALL SELECT 'ARTIST_CREDIT_INIT', artists.artist_init
        ) AS sources
        CROSS JOIN LATERAL generate_series(1, char_length(COALESCE(sources.source_value, ''))) AS positions(position)
        CROSS JOIN (VALUES (1), (2)) AS lengths(length)
        CROSS JOIN LATERAL (
            SELECT substring(sources.source_value FROM positions.position FOR lengths.length) AS value
        ) AS grams
        WHERE artists.song_id = NEW.song_id
          AND grams.value <> ''
          AND positions.position + lengths.length - 1 <= char_length(sources.source_value)
        ON CONFLICT DO NOTHING;
        RETURN NEW;
    END IF;
    RETURN OLD;
END
$ktv$;

DO $ktv$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_songs_search_terms'
          AND tgrelid = 'songs'::regclass
    ) THEN
        CREATE TRIGGER trg_songs_search_terms
        AFTER INSERT OR UPDATE OF title, artist, language, tags ON songs
        FOR EACH ROW EXECUTE FUNCTION ktv_sync_song_search_terms();
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_song_artists_search_terms'
          AND tgrelid = 'song_artists'::regclass
    ) THEN
        CREATE TRIGGER trg_song_artists_search_terms
        AFTER INSERT OR UPDATE OR DELETE ON song_artists
        FOR EACH ROW EXECUTE FUNCTION ktv_sync_song_artist_search_terms();
    END IF;
END
$ktv$;

-- Backfill the projection set-wise for existing application rows.
INSERT INTO song_search_terms (song_id, kind, value, value_lower)
SELECT songs.id, sources.kind, grams.value, lower(grams.value)
FROM songs
CROSS JOIN LATERAL (
    SELECT 'TITLE'::VARCHAR(32) AS kind, COALESCE(songs.title, '') AS source_value
    UNION ALL SELECT 'TITLE_PY', COALESCE(songs.title_py, '')
    UNION ALL SELECT 'TITLE_INIT', COALESCE(songs.title_init, '')
    UNION ALL SELECT 'ARTIST', COALESCE(songs.artist, '')
    UNION ALL SELECT 'ARTIST_PY', COALESCE(songs.artist_py, '')
    UNION ALL SELECT 'ARTIST_INIT', COALESCE(songs.artist_init, '')
    UNION ALL SELECT 'LANGUAGE', COALESCE(songs.language, '')
    UNION ALL
    SELECT 'TAG', tag
    FROM unnest(COALESCE(songs.tags, ARRAY[]::TEXT[])) AS tags(tag)
) AS sources
CROSS JOIN LATERAL generate_series(1, char_length(sources.source_value)) AS positions(position)
CROSS JOIN (VALUES (1), (2)) AS lengths(length)
CROSS JOIN LATERAL (
    SELECT substring(sources.source_value FROM positions.position FOR lengths.length) AS value
) AS grams
WHERE grams.value <> ''
  AND positions.position + lengths.length - 1 <= char_length(sources.source_value)
ON CONFLICT DO NOTHING;

INSERT INTO song_search_terms (song_id, kind, value, value_lower)
SELECT artists.song_id, sources.kind, grams.value, lower(grams.value)
FROM song_artists artists
CROSS JOIN LATERAL (
    SELECT 'ARTIST_CREDIT'::VARCHAR(32) AS kind, artists.artist_name AS source_value
    UNION ALL SELECT 'ARTIST_CREDIT_PY', artists.artist_py
    UNION ALL SELECT 'ARTIST_CREDIT_INIT', artists.artist_init
) AS sources
CROSS JOIN LATERAL generate_series(1, char_length(COALESCE(sources.source_value, ''))) AS positions(position)
CROSS JOIN (VALUES (1), (2)) AS lengths(length)
CROSS JOIN LATERAL (
    SELECT substring(sources.source_value FROM positions.position FOR lengths.length) AS value
) AS grams
WHERE grams.value <> ''
  AND positions.position + lengths.length - 1 <= char_length(COALESCE(sources.source_value, ''))
ON CONFLICT DO NOTHING;

CREATE INDEX idx_song_search_terms_lower_btree
    ON song_search_terms (value_lower, song_id);

CREATE INDEX idx_song_search_terms_lower_trgm
    ON song_search_terms USING gin (value_lower gin_trgm_ops);
