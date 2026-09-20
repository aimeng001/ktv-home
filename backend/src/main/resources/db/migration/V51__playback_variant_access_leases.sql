ALTER TABLE playback_variants
    ADD COLUMN last_access_at TIMESTAMPTZ,
    ADD COLUMN stream_lease_until TIMESTAMPTZ;

CREATE INDEX idx_playback_variants_access
    ON playback_variants(status, last_access_at, ready_at);
