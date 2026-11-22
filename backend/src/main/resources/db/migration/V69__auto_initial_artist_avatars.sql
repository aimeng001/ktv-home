-- Migrate unresolved/failed artist profiles without avatars to AUTO_INITIAL status
-- and clean up their terminal job errors to avoid false alarm alerts.

UPDATE artist_profiles
SET avatar_status = 'AUTO_INITIAL',
    updated_at = now()
WHERE avatar_status IN ('REVIEW', 'FAILED', 'SKIPPED')
  AND (avatar_path IS NULL OR trim(avatar_path) = '');

UPDATE artist_avatar_jobs job
SET status = 'SKIPPED',
    last_error = '已自动转为首字徽章，无需人工复核',
    updated_at = now()
FROM artist_profiles p
WHERE job.artist_key = p.artist_key
  AND p.avatar_status = 'AUTO_INITIAL'
  AND job.status IN ('FAILED', 'REVIEW');
