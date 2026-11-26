-- Consolidate per-provider avatar jobs into one AUTO job per artist,
-- initialize rate-limit rows for KUWO and ITUNES, and upgrade default music source settings.

ALTER TABLE artist_avatar_jobs
    DROP CONSTRAINT IF EXISTS ck_artist_avatar_job_provider;

ALTER TABLE artist_avatar_jobs
    ADD CONSTRAINT ck_artist_avatar_job_provider
        CHECK (provider IN ('AUTO', 'NETEASE', 'QQ', 'KUGOU', 'KUWO', 'ITUNES', 'FALLBACK'));

ALTER TABLE artist_avatar_jobs
    ADD COLUMN IF NOT EXISTS tried_providers TEXT[] NOT NULL DEFAULT '{}';

INSERT INTO music_provider_rate_state(provider)
VALUES ('NETEASE'), ('QQ'), ('KUGOU'), ('KUWO'), ('ITUNES')
ON CONFLICT (provider) DO NOTHING;

WITH active_artists AS (
    SELECT p.artist_key,
           GREATEST(COALESCE(dir.song_count, 0), COALESCE(MAX(j.priority), 0)) AS effective_priority
    FROM artist_profiles p
    LEFT JOIN artist_directory_stats dir ON dir.artist_key = p.artist_key
    LEFT JOIN artist_avatar_jobs j ON j.artist_key = p.artist_key
    WHERE p.artist_kind IN ('PERSON', 'GROUP')
      AND (p.avatar_path IS NULL OR trim(p.avatar_path) = '')
      AND (
          p.avatar_status IN ('PENDING', 'RETRY')
          OR EXISTS (
              SELECT 1 FROM artist_avatar_jobs aj
              WHERE aj.artist_key = p.artist_key
                AND aj.status IN ('PENDING', 'PROCESSING')
          )
      )
    GROUP BY p.artist_key, dir.song_count
)
INSERT INTO artist_avatar_jobs (
    artist_key, provider, priority, tier, status, attempts, tried_providers,
    next_run_at, lease_until, claim_token, last_error, created_at, updated_at
)
SELECT artist_key, 'AUTO', effective_priority, 1, 'PENDING', 0, '{}',
       now(), NULL, NULL, NULL, now(), now()
FROM active_artists
ON CONFLICT (artist_key, provider) DO UPDATE SET
    priority = GREATEST(artist_avatar_jobs.priority, EXCLUDED.priority),
    tier = 1,
    status = 'PENDING',
    attempts = 0,
    tried_providers = '{}',
    next_run_at = now(),
    lease_until = NULL,
    claim_token = NULL,
    last_error = NULL,
    updated_at = now();

-- Mark legacy per-provider queue rows terminal without destructive SQL inside Flyway;
-- audited application startup code prunes superseded rows safely.
UPDATE artist_avatar_jobs
SET status = 'SKIPPED',
    lease_until = NULL,
    claim_token = NULL,
    last_error = COALESCE(last_error, '已合并为单歌手自动刮削任务'),
    updated_at = now()
WHERE provider <> 'AUTO'
  AND status IN ('PENDING', 'PROCESSING', 'FAILED', 'REVIEW');

UPDATE settings
SET value = (
    value
    || jsonb_build_object(
        'providers',
        CASE
            WHEN jsonb_typeof(value->'providers') = 'array' AND jsonb_array_length(value->'providers') > 0 THEN (
                SELECT jsonb_agg(elem)
                FROM (
                    SELECT DISTINCT elem
                    FROM (
                        SELECT jsonb_array_elements_text(value->'providers') AS elem
                        UNION ALL
                        SELECT unnest(ARRAY['KUWO', 'ITUNES']) AS elem
                    ) merged
                ) distinct_elems
            )
            ELSE '[]'::jsonb
        END,
        'fallbackProviders', '["BAIDU_BAIKE","BING_IMAGES","BAIDU_IMAGES"]'::jsonb,
        'fallbackDailyLimit', 2000,
        'requestIntervalMs', CASE
            WHEN (value->>'requestIntervalMs') ~ '^[0-9]+$' AND (value->>'requestIntervalMs')::int < 1500 THEN 1500
            WHEN (value->>'requestIntervalMs') ~ '^[0-9]+$' AND (value->>'requestIntervalMs')::int <= 2500 THEN (value->>'requestIntervalMs')::int
            ELSE 2500
        END,
        'dailyLimit', CASE
            WHEN (value->>'dailyLimit') ~ '^[0-9]+$' AND (value->>'dailyLimit')::int > 3000 THEN LEAST((value->>'dailyLimit')::int, 10000)
            ELSE 3000
        END
    )
)
WHERE key = 'music_sources.config'
  AND NOT (value ? 'fallbackProviders');
