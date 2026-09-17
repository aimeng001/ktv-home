CREATE TABLE music_provider_rate_state (
    provider          VARCHAR(32) PRIMARY KEY,
    next_request_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cooldown_until    TIMESTAMPTZ NULL,
    window_date_utc   DATE NOT NULL DEFAULT ((CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date),
    request_count     INTEGER NOT NULL DEFAULT 0,
    failure_streak    INTEGER NOT NULL DEFAULT 0,
    last_http_status  INTEGER NULL,
    last_error        VARCHAR(1000) NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT music_provider_rate_state_request_count_ck CHECK (request_count >= 0),
    CONSTRAINT music_provider_rate_state_failure_streak_ck CHECK (failure_streak >= 0)
);

INSERT INTO music_provider_rate_state(provider)
VALUES ('NETEASE'), ('QQ'), ('KUGOU')
ON CONFLICT (provider) DO NOTHING;
