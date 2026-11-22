-- V68: Add audio_codec and video_codec columns to song_files to enable safe browser audio passthrough and stream copy decisions.
ALTER TABLE song_files ADD COLUMN IF NOT EXISTS audio_codec VARCHAR(32);
ALTER TABLE song_files ADD COLUMN IF NOT EXISTS video_codec VARCHAR(32);
