-- Make avatar jobs recoverable after a worker or container crashes.
-- A claim token prevents a late worker from overwriting a job reclaimed by
-- another worker. These columns only belong to application metadata.
ALTER TABLE artist_avatar_jobs
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claim_token UUID;

-- Jobs created by an older version that were left in PROCESSING are eligible
-- for immediate recovery after this migration.
UPDATE artist_avatar_jobs
SET lease_until = COALESCE(lease_until, now())
WHERE status = 'PROCESSING';

CREATE INDEX IF NOT EXISTS idx_artist_avatar_jobs_claimable
    ON artist_avatar_jobs (status, next_run_at, lease_until, id);
