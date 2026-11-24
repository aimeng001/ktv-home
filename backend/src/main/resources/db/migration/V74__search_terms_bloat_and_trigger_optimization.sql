-- Stage 2 deep performance cleanup for song_search_terms:
-- 1. Drop dead table-wide GIN index idx_song_search_terms_lower_trgm from V37
--    (only kind IN ('TAG', 'ALIAS') runs substring LIKE '%...%', already covered
--    by partial GIN indexes from V40 and V71; 1-2 char search uses V47 covering B-Tree).
-- 2. Upgrade song_artists AFTER INSERT trigger from row-level to statement-level
--    transition-table trigger while keeping UPDATE/DELETE row-scoped.
-- 3. Replace dynamic EXECUTE + N PERFORM calls in ktv_rebuild_song_search_terms()
--    with a single static DELETE and a single set-based INSERT, filtering out
--    whitespace-only and punctuation-only noise grams.

DROP INDEX IF EXISTS idx_song_search_terms_lower_trgm;

-- Statement-level AFTER INSERT trigger on song_artists to avoid per-row trigger
-- amplification during bulk library scans and metadata reconciliation.
CREATE OR REPLACE FUNCTION ktv_sync_song_artist_search_terms_insert_statement()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $ktv$
BEGIN
    INSERT INTO song_search_terms (song_id, kind, value, value_lower)
    SELECT artists.song_id, sources.kind, grams.value, lower(grams.value)
    FROM new_rows artists
    CROSS JOIN LATERAL (
        SELECT 'ARTIST_CREDIT'::VARCHAR(32) AS kind, COALESCE(artists.artist_name, '') AS source_value
        UNION ALL SELECT 'ARTIST_CREDIT_PY', COALESCE(artists.artist_py, '')
        UNION ALL SELECT 'ARTIST_CREDIT_INIT', COALESCE(artists.artist_init, '')
    ) AS sources
    CROSS JOIN LATERAL generate_series(1, char_length(sources.source_value)) AS positions(position)
    CROSS JOIN (VALUES (1), (2)) AS lengths(length)
    CROSS JOIN LATERAL (
        SELECT substring(sources.source_value FROM positions.position FOR lengths.length) AS value
    ) AS grams
    WHERE grams.value <> ''
      AND trim(grams.value) <> ''
      AND positions.position + lengths.length - 1 <= char_length(sources.source_value)
    ON CONFLICT DO NOTHING;

    RETURN NULL;
END
$ktv$;

-- Row-scoped UPDATE/DELETE handler for song_artists with noise-gram filtering.
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
        IF TG_OP = 'UPDATE' AND NEW.song_id IS DISTINCT FROM OLD.song_id THEN
            EXECUTE 'DELETE FROM song_search_terms
                     WHERE song_id = $1
                       AND kind IN (''ARTIST_CREDIT'', ''ARTIST_CREDIT_PY'', ''ARTIST_CREDIT_INIT'')'
                USING NEW.song_id;
        END IF;

        INSERT INTO song_search_terms (song_id, kind, value, value_lower)
        SELECT artists.song_id, sources.kind, grams.value, lower(grams.value)
        FROM song_artists artists
        CROSS JOIN LATERAL (
            SELECT 'ARTIST_CREDIT'::VARCHAR(32) AS kind, COALESCE(artists.artist_name, '') AS source_value
            UNION ALL SELECT 'ARTIST_CREDIT_PY', COALESCE(artists.artist_py, '')
            UNION ALL SELECT 'ARTIST_CREDIT_INIT', COALESCE(artists.artist_init, '')
        ) AS sources
        CROSS JOIN LATERAL generate_series(1, char_length(sources.source_value)) AS positions(position)
        CROSS JOIN (VALUES (1), (2)) AS lengths(length)
        CROSS JOIN LATERAL (
            SELECT substring(sources.source_value FROM positions.position FOR lengths.length) AS value
        ) AS grams
        WHERE artists.song_id = NEW.song_id
          AND grams.value <> ''
          AND trim(grams.value) <> ''
          AND positions.position + lengths.length - 1 <= char_length(sources.source_value)
        ON CONFLICT DO NOTHING;
        RETURN NEW;
    END IF;
    RETURN OLD;
END
$ktv$;

DROP TRIGGER IF EXISTS trg_song_artists_search_terms ON song_artists;
DROP TRIGGER IF EXISTS trg_song_artists_search_terms_insert ON song_artists;
DROP TRIGGER IF EXISTS trg_song_artists_search_terms_mutate ON song_artists;

CREATE TRIGGER trg_song_artists_search_terms_insert
AFTER INSERT ON song_artists
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION ktv_sync_song_artist_search_terms_insert_statement();

CREATE TRIGGER trg_song_artists_search_terms_mutate
AFTER UPDATE OR DELETE ON song_artists
FOR EACH ROW EXECUTE FUNCTION ktv_sync_song_artist_search_terms();

-- Set-based ktv_rebuild_song_search_terms replacing dynamic EXECUTE + 8-15 PERFORM subcalls.
CREATE OR REPLACE FUNCTION ktv_rebuild_song_search_terms(p_song_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $ktv$
BEGIN
    /* set-based cleanup */ DELETE FROM song_search_terms
    WHERE song_id = p_song_id
      AND kind IN (
          'TITLE', 'ARTIST', 'LANGUAGE', 'TAG', 'ALIAS',
          'TITLE_PY', 'TITLE_INIT', 'ARTIST_PY', 'ARTIST_INIT'
      );

    INSERT INTO song_search_terms (song_id, kind, value, value_lower)
    SELECT s.id, sources.kind, grams.value, lower(grams.value)
    FROM songs s
    CROSS JOIN LATERAL (
        SELECT 'TITLE'::VARCHAR(32) AS kind, COALESCE(s.title, '') AS source_value
        UNION ALL SELECT 'TITLE_PY', COALESCE(s.title_py, '')
        UNION ALL SELECT 'TITLE_INIT', COALESCE(s.title_init, '')
        UNION ALL SELECT 'ARTIST', COALESCE(s.artist, '')
        UNION ALL SELECT 'ARTIST_PY', COALESCE(s.artist_py, '')
        UNION ALL SELECT 'ARTIST_INIT', COALESCE(s.artist_init, '')
        UNION ALL SELECT 'LANGUAGE', COALESCE(s.language, '')
        UNION ALL
        SELECT 'TAG', tag
        FROM unnest(COALESCE(s.tags, ARRAY[]::TEXT[])) AS tags(tag)
        UNION ALL
        SELECT 'ALIAS', trim(alias_val)
        FROM unnest(COALESCE(s.aliases, ARRAY[]::TEXT[])) AS aliases(alias_val)
    ) AS sources
    CROSS JOIN LATERAL generate_series(1, char_length(sources.source_value)) AS positions(position)
    CROSS JOIN (VALUES (1), (2)) AS lengths(length)
    CROSS JOIN LATERAL (
        SELECT substring(sources.source_value FROM positions.position FOR lengths.length) AS value
    ) AS grams
    WHERE s.id = p_song_id
      AND grams.value <> ''
      AND trim(grams.value) <> ''
      AND positions.position + lengths.length - 1 <= char_length(sources.source_value)
    ON CONFLICT DO NOTHING;

    INSERT INTO song_search_terms (song_id, kind, value, value_lower)
    SELECT s.id, 'TAG', tags.value, lower(tags.value)
    FROM songs s
    CROSS JOIN LATERAL unnest(COALESCE(s.tags, ARRAY[]::TEXT[])) AS tags(value)
    WHERE s.id = p_song_id
      AND tags.value <> ''
    ON CONFLICT DO NOTHING;

    INSERT INTO song_search_terms (song_id, kind, value, value_lower)
    SELECT s.id, 'ALIAS', trim(aliases.value), lower(trim(aliases.value))
    FROM songs s
    CROSS JOIN LATERAL unnest(COALESCE(s.aliases, ARRAY[]::TEXT[])) AS aliases(value)
    WHERE s.id = p_song_id
      AND trim(aliases.value) <> ''
    ON CONFLICT DO NOTHING;
END
$ktv$;

-- Keep the bulk statement-level songs INSERT trigger aligned with noise-gram filtering.
CREATE OR REPLACE FUNCTION ktv_sync_song_search_terms_statement()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $ktv$
BEGIN
    INSERT INTO song_search_terms (song_id, kind, value, value_lower)
    SELECT rows.id, sources.kind, grams.value, lower(grams.value)
    FROM new_rows rows
    CROSS JOIN LATERAL (
        SELECT 'TITLE'::VARCHAR(32) AS kind, COALESCE(rows.title, '') AS source_value
        UNION ALL SELECT 'TITLE_PY', COALESCE(rows.title_py, '')
        UNION ALL SELECT 'TITLE_INIT', COALESCE(rows.title_init, '')
        UNION ALL SELECT 'ARTIST', COALESCE(rows.artist, '')
        UNION ALL SELECT 'ARTIST_PY', COALESCE(rows.artist_py, '')
        UNION ALL SELECT 'ARTIST_INIT', COALESCE(rows.artist_init, '')
        UNION ALL SELECT 'LANGUAGE', COALESCE(rows.language, '')
        UNION ALL
        SELECT 'TAG', tag
        FROM unnest(COALESCE(rows.tags, ARRAY[]::TEXT[])) AS tags(tag)
        UNION ALL
        SELECT 'ALIAS', trim(alias_val)
        FROM unnest(COALESCE(rows.aliases, ARRAY[]::TEXT[])) AS aliases(alias_val)
    ) AS sources
    CROSS JOIN LATERAL generate_series(1, char_length(sources.source_value)) AS positions(position)
    CROSS JOIN (VALUES (1), (2)) AS lengths(length)
    CROSS JOIN LATERAL (
        SELECT substring(sources.source_value FROM positions.position FOR lengths.length) AS value
    ) AS grams
    WHERE grams.value <> ''
      AND trim(grams.value) <> ''
      AND positions.position + lengths.length - 1 <= char_length(sources.source_value)
    ON CONFLICT DO NOTHING;

    INSERT INTO song_search_terms (song_id, kind, value, value_lower)
    SELECT rows.id, 'TAG', tags.value, lower(tags.value)
    FROM new_rows rows
    CROSS JOIN LATERAL unnest(COALESCE(rows.tags, ARRAY[]::TEXT[])) AS tags(value)
    WHERE tags.value <> ''
    ON CONFLICT DO NOTHING;

    INSERT INTO song_search_terms (song_id, kind, value, value_lower)
    SELECT rows.id, 'ALIAS', trim(aliases.value), lower(trim(aliases.value))
    FROM new_rows rows
    CROSS JOIN LATERAL unnest(COALESCE(rows.aliases, ARRAY[]::TEXT[])) AS aliases(value)
    WHERE trim(aliases.value) <> ''
    ON CONFLICT DO NOTHING;

    RETURN NULL;
END
$ktv$;
