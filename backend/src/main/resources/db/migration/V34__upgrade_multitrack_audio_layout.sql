-- Automatically promote non-manual multi-track media files to DUAL_TRACK
UPDATE song_files
SET audio_layout = 'DUAL_TRACK',
    audio_layout_source = 'AUTO_DEFAULT',
    accompaniment_track_index = COALESCE(accompaniment_track_index, vocal_track_index, 1),
    original_track_index = COALESCE(original_track_index, CASE WHEN COALESCE(accompaniment_track_index, vocal_track_index, 1) = 0 THEN 1 ELSE 0 END)
WHERE audio_tracks >= 2
  AND (audio_layout_source IS NULL OR audio_layout_source != 'MANUAL');

-- Keep Song.has_vocal_track in sync with valid playable multi-track or dual-channel files
UPDATE songs s
SET has_vocal_track = true
FROM song_files sf
WHERE sf.song_id = s.id
  AND sf.valid = true
  AND (sf.audio_layout = 'DUAL_TRACK' OR sf.audio_layout = 'DUAL_CHANNEL');
