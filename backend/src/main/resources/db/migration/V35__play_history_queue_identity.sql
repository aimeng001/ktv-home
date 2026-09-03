ALTER TABLE play_history ADD COLUMN queue_id BIGINT;

CREATE UNIQUE INDEX uk_play_history_queue_id
    ON play_history(queue_id)
    WHERE queue_id IS NOT NULL;
