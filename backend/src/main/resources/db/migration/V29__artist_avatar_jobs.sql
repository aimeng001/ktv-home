-- Background artist avatar scraping jobs. Jobs contain only application state;
-- no source-library path is ever written here.
CREATE TABLE artist_avatar_jobs (
    id          BIGSERIAL PRIMARY KEY,
    artist_key  TEXT NOT NULL REFERENCES artist_profiles(artist_key) ON DELETE CASCADE,
    provider    VARCHAR(64) NOT NULL,
    status      VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts    INT NOT NULL DEFAULT 0,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_artist_avatar_job UNIQUE (artist_key, provider),
    CONSTRAINT ck_artist_avatar_job_provider
        CHECK (provider IN ('NETEASE', 'QQ', 'KUGOU'))
);

CREATE INDEX idx_artist_avatar_jobs_due
    ON artist_avatar_jobs (status, next_run_at, id);
