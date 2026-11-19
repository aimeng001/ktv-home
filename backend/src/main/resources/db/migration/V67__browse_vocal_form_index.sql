-- Support fast category browse filtering on effective vocal_form (local/manual vocal_form fallback to ai_vocal_form).
CREATE INDEX IF NOT EXISTS idx_songs_ok_effective_vocal_form
    ON songs (LOWER(COALESCE(NULLIF(NULLIF(vocal_form, ''), '未知'), NULLIF(ai_vocal_form, ''), '未知')))
    WHERE status = 'ok';
