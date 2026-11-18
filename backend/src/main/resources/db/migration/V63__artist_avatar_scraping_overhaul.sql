-- Expand artist avatar jobs for fallback chain, song-count priority ordering,
-- and wake historical unresolved/deadlocked profiles for scraping.
ALTER TABLE artist_avatar_jobs
    DROP CONSTRAINT IF EXISTS ck_artist_avatar_job_provider;

ALTER TABLE artist_avatar_jobs
    ADD CONSTRAINT ck_artist_avatar_job_provider
        CHECK (provider IN ('NETEASE', 'QQ', 'KUGOU', 'FALLBACK'));

ALTER TABLE artist_avatar_jobs
    ADD COLUMN IF NOT EXISTS priority INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tier INT NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_artist_avatar_jobs_priority_claim
    ON artist_avatar_jobs (status, next_run_at, priority DESC, tier DESC, id ASC);

UPDATE artist_profiles
SET avatar_status = 'PENDING',
    avatar_error = NULL,
    updated_at = now()
WHERE artist_kind IN ('PERSON', 'GROUP')
  AND (avatar_path IS NULL OR trim(avatar_path) = '')
  AND avatar_status IN ('REVIEW', 'SKIPPED', 'RETRY', 'FAILED');

UPDATE artist_avatar_jobs job
SET status = 'PENDING',
    priority = COALESCE(dir.song_count, 0),
    attempts = 0,
    next_run_at = now(),
    last_error = NULL,
    updated_at = now()
FROM artist_profiles p
LEFT JOIN artist_directory_stats dir ON dir.artist_key = p.artist_key
WHERE job.artist_key = p.artist_key
  AND p.artist_kind IN ('PERSON', 'GROUP')
  AND (p.avatar_path IS NULL OR trim(p.avatar_path) = '')
  AND job.status IN ('FAILED', 'SKIPPED', 'REVIEW');

INSERT INTO artist_avatar_jobs (
    artist_key, provider, priority, tier, status, attempts, next_run_at, created_at, updated_at)
SELECT p.artist_key, 'FALLBACK', COALESCE(dir.song_count, 0), 0, 'PENDING', 0, now(), now(), now()
FROM artist_profiles p
LEFT JOIN artist_directory_stats dir ON dir.artist_key = p.artist_key
WHERE p.artist_kind IN ('PERSON', 'GROUP')
  AND (p.avatar_path IS NULL OR trim(p.avatar_path) = '')
  AND p.avatar_status IN ('PENDING', 'RETRY')
ON CONFLICT (artist_key, provider) DO NOTHING;
