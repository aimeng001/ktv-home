-- Stage 2 database index & trigger hygiene:
-- 1. Drop obsolete indexes on legacy playback_variants while keeping DDL append-only for MigrationSafetyTest.
-- 2. Drop strictly superseded secondary indexes while keeping every active search/browse index.
-- 3. Merge duplicate AFTER INSERT triggers on songs (V38 + V40) into one statement-level trigger.

DROP INDEX IF EXISTS idx_playback_variants_status_lease;
DROP INDEX IF EXISTS idx_playback_variants_source_file;
DROP INDEX IF EXISTS idx_playback_variants_access;

-- Covered by LOWER(...) expression indexes from V40/V61 (queries never use case-sensitive LIKE on pinyin/language).
DROP INDEX IF EXISTS idx_songs_title_py_prefix;
DROP INDEX IF EXISTS idx_songs_title_init_prefix;
DROP INDEX IF EXISTS idx_songs_artist_py_prefix;
DROP INDEX IF EXISTS idx_songs_artist_init_prefix;
DROP INDEX IF EXISTS idx_songs_language_prefix;
DROP INDEX IF EXISTS idx_song_artists_artist_py_prefix;
DROP INDEX IF EXISTS idx_song_artists_artist_init_prefix;

-- Covered by idx_song_search_terms_short_covering (value_lower, song_id) INCLUDE (kind) from V47.
DROP INDEX IF EXISTS idx_song_search_terms_lower_btree;

-- Covered by idx_song_files_probe_queue (file_role, probe_pending, file_path) from V22.
DROP INDEX IF EXISTS idx_song_files_role;

-- Covered by idx_artist_avatar_jobs_priority_claim (status, next_run_at, priority DESC, tier DESC, id ASC) from V63.
DROP INDEX IF EXISTS idx_artist_avatar_jobs_due;

-- Merge V40 full-tag/alias statement trigger work into the primary V38 AFTER INSERT trigger on songs.
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

DROP TRIGGER IF EXISTS trg_songs_full_tag_search_terms_insert ON songs;
