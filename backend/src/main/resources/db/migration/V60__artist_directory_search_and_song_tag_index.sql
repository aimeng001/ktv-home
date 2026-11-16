-- 1. Support fast artist keyword / pinyin / initials search in artist_directory_stats
ALTER TABLE artist_directory_stats
    ADD COLUMN IF NOT EXISTS artist_init TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS artist_py TEXT NOT NULL DEFAULT '';

-- 2. Normalized tag index table for millisecond tag filtering and tag aggregation
CREATE TABLE IF NOT EXISTS song_tag_index (
    song_id BIGINT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
    tag_lower TEXT NOT NULL,
    display_tag TEXT NOT NULL,
    PRIMARY KEY (song_id, tag_lower)
);

CREATE INDEX IF NOT EXISTS idx_song_tag_index_tag_song
    ON song_tag_index (tag_lower, song_id);

CREATE OR REPLACE FUNCTION sync_song_tag_index_for_song(p_song_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    WITH removed AS (DELETE FROM song_tag_index WHERE song_id = p_song_id)
    INSERT INTO song_tag_index (song_id, tag_lower, display_tag)
    SELECT p_song_id, tag_lower, MIN(display_tag)
    FROM (
        SELECT LOWER(TRIM(val)) AS tag_lower, TRIM(val) AS display_tag
        FROM songs s
        CROSS JOIN LATERAL (
            SELECT unnest(COALESCE(s.tags, ARRAY[]::text[])) AS val
            UNION ALL
            SELECT unnest(COALESCE(s.ai_genres, ARRAY[]::text[])) AS val
            UNION ALL
            SELECT unnest(COALESCE(s.ai_themes, ARRAY[]::text[])) AS val
            UNION ALL
            SELECT rc.category AS val
            FROM iktv_song_categories rc
            JOIN iktv_reference_snapshots rs
              ON rs.snapshot_version = rc.snapshot_version
             AND rs.status = 'ACTIVE'
            WHERE rc.song_id = s.id
        ) raw_tags
        WHERE s.id = p_song_id
          AND s.status = 'ok'
          AND val IS NOT NULL
          AND TRIM(val) <> ''
    ) dedup
    GROUP BY tag_lower
    ON CONFLICT (song_id, tag_lower) DO NOTHING;
END;
$$;

CREATE OR REPLACE FUNCTION trg_songs_sync_tag_index_fn()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM sync_song_tag_index_for_song(NEW.id);
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_songs_sync_tag_index ON songs;
CREATE TRIGGER trg_songs_sync_tag_index
    AFTER INSERT OR UPDATE OF status, tags, ai_genres, ai_themes ON songs
    FOR EACH ROW
    EXECUTE FUNCTION trg_songs_sync_tag_index_fn();

CREATE OR REPLACE FUNCTION trg_iktv_categories_sync_tag_index_fn()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM sync_song_tag_index_for_song(COALESCE(NEW.song_id, OLD.song_id));
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_iktv_categories_sync_tag_index ON iktv_song_categories;
CREATE TRIGGER trg_iktv_categories_sync_tag_index
    AFTER INSERT OR UPDATE OR DELETE ON iktv_song_categories
    FOR EACH ROW
    EXECUTE FUNCTION trg_iktv_categories_sync_tag_index_fn();

-- Initial backfill for existing ok songs
INSERT INTO song_tag_index (song_id, tag_lower, display_tag)
SELECT song_id, tag_lower, MIN(display_tag)
FROM (
    SELECT s.id AS song_id, LOWER(TRIM(raw_tags.val)) AS tag_lower, TRIM(raw_tags.val) AS display_tag
    FROM songs s
    CROSS JOIN LATERAL (
        SELECT unnest(COALESCE(s.tags, ARRAY[]::text[])) AS val
        UNION ALL
        SELECT unnest(COALESCE(s.ai_genres, ARRAY[]::text[])) AS val
        UNION ALL
        SELECT unnest(COALESCE(s.ai_themes, ARRAY[]::text[])) AS val
        UNION ALL
        SELECT rc.category AS val
        FROM iktv_song_categories rc
        JOIN iktv_reference_snapshots rs
          ON rs.snapshot_version = rc.snapshot_version
         AND rs.status = 'ACTIVE'
        WHERE rc.song_id = s.id
    ) raw_tags
    WHERE s.status = 'ok'
      AND raw_tags.val IS NOT NULL
      AND TRIM(raw_tags.val) <> ''
) dedup
GROUP BY song_id, tag_lower
ON CONFLICT (song_id, tag_lower) DO NOTHING;
