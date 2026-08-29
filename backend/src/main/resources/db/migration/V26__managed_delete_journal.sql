CREATE TABLE managed_delete_operations (
    operation_id VARCHAR(36) PRIMARY KEY,
    song_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    manifest TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_managed_delete_operations_status_created
    ON managed_delete_operations(status, created_at);

CREATE OR REPLACE FUNCTION touch_managed_delete_operation_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_managed_delete_operations_updated_at
BEFORE UPDATE ON managed_delete_operations
FOR EACH ROW EXECUTE FUNCTION touch_managed_delete_operation_updated_at();
