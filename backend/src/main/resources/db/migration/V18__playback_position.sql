-- Platform-neutral playback position for reconnect/resume and seek commands.
-- Existing rows keep a safe beginning-of-track default.
ALTER TABLE player_state
    ADD COLUMN IF NOT EXISTS position_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS seek_sequence BIGINT NOT NULL DEFAULT 0;
