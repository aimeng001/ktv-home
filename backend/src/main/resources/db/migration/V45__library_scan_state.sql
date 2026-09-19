CREATE TABLE library_scan_state (
    library_key              VARCHAR(128) PRIMARY KEY,
    configured_root          TEXT NOT NULL,
    library_mode             VARCHAR(32) NOT NULL,
    metadata_policy_version  INTEGER NOT NULL DEFAULT 1,
    scan_id                  UUID,
    owner_id                 UUID,
    generation               BIGINT NOT NULL DEFAULT 0,
    state                    VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    phase                    VARCHAR(32),
    heartbeat_at             TIMESTAMPTZ,
    started_at               TIMESTAMPTZ,
    finished_at              TIMESTAMPTZ,
    last_successful_scan_at  TIMESTAMPTZ,
    discovered_files         BIGINT NOT NULL DEFAULT 0,
    indexed_files            BIGINT NOT NULL DEFAULT 0,
    probe_completed_files    BIGINT NOT NULL DEFAULT 0,
    probe_pending_files      BIGINT NOT NULL DEFAULT 0,
    error_code               VARCHAR(128),
    error_message            VARCHAR(1000),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT library_scan_state_state_ck
        CHECK (state IN ('IDLE', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'INTERRUPTED')),
    CONSTRAINT library_scan_state_counts_ck
        CHECK (discovered_files >= 0 AND indexed_files >= 0
            AND probe_completed_files >= 0 AND probe_pending_files >= 0)
);
