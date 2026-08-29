CREATE UNLOGGED TABLE library_scan_seen_paths (
    scan_id UUID NOT NULL,
    file_role VARCHAR(32) NOT NULL,
    file_path TEXT NOT NULL,
    pending_at_start BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (scan_id, file_role, file_path)
);

CREATE INDEX idx_library_scan_seen_paths_role_path
    ON library_scan_seen_paths (file_role, file_path);
