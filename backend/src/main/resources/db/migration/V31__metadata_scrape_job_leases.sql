-- Make metadata scrape items recoverable without allowing a late worker to
-- overwrite a newer claim.
ALTER TABLE music_metadata_scrape_items
    ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(100),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;

UPDATE music_metadata_scrape_items
SET lease_until = COALESCE(lease_until, now())
WHERE status = 'PROCESSING';

CREATE INDEX IF NOT EXISTS idx_metadata_scrape_items_claimable
    ON music_metadata_scrape_items (batch_id, status, lease_until, id);
