-- Fence late metadata workers after a lease has been reclaimed by another worker.
ALTER TABLE music_metadata_scrape_items
    ADD COLUMN IF NOT EXISTS claim_token UUID;
