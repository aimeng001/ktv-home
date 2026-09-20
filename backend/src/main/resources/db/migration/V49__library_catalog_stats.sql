-- Cached public library counters. Values are refreshed after catalog changes;
-- source media and the external NAS are never modified.
CREATE TABLE library_catalog_stats (
    library_key         VARCHAR(128) PRIMARY KEY,
    total_songs         BIGINT NOT NULL DEFAULT 0,
    indexed_songs       BIGINT NOT NULL DEFAULT 0,
    ready_songs         BIGINT NOT NULL DEFAULT 0,
    probe_pending_files BIGINT NOT NULL DEFAULT 0,
    refreshed_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
