-- Serialize the active-batch check across multiple application instances.
CREATE TABLE IF NOT EXISTS music_metadata_scrape_lock (
    id SMALLINT PRIMARY KEY CHECK (id = 1)
);

INSERT INTO music_metadata_scrape_lock(id)
VALUES (1)
ON CONFLICT (id) DO NOTHING;
