-- Reference classifications remain independent from Home's manually edited
-- songs.tags / AI tags. Queries join these rows only for the active snapshot.
CREATE TABLE iktv_song_categories (
    song_id BIGINT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
    snapshot_version VARCHAR(64) NOT NULL,
    reference_song_id BIGINT NOT NULL,
    category TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (song_id, snapshot_version, reference_song_id, category),
    FOREIGN KEY (snapshot_version, reference_song_id)
        REFERENCES iktv_reference_songs(snapshot_version, reference_song_id) ON DELETE CASCADE
);

CREATE INDEX idx_iktv_song_categories_category_song
    ON iktv_song_categories (category, song_id, snapshot_version);
CREATE INDEX idx_iktv_song_categories_song_version
    ON iktv_song_categories (song_id, snapshot_version);

ALTER TABLE iktv_song_match_batches
    ADD COLUMN classification_count BIGINT NOT NULL DEFAULT 0;

ALTER TABLE iktv_song_match_batch_items
    ADD COLUMN classification_changed BOOLEAN NOT NULL DEFAULT false;
