-- V70: Materialize region in artist_directory_stats, isolate placeholder artists,
-- add pending queue order index for artist_avatar_jobs, and optimize song query paths.

-- 1. Materialize region directly in artist_directory_stats
ALTER TABLE artist_directory_stats
    ADD COLUMN IF NOT EXISTS region VARCHAR(16) NOT NULL DEFAULT '';

-- Backfill region from active IKTV artist reference snapshot (excluding placeholders)
UPDATE artist_directory_stats s
SET region = COALESCE(ref.region, '')
FROM active_iktv_artist_regions ref
WHERE ref.artist_key = s.artist_key
  AND s.artist_kind = 'PERSON'
  AND lower(s.name) NOT IN ('佚名', '未知', '未知歌手', 'unknown', 'anonymous');

-- Reset gender and region for UNATTRIBUTED placeholder artists to prevent dominating male artist rankings
UPDATE artist_directory_stats
SET gender = '未知',
    region = ''
WHERE artist_kind = 'UNATTRIBUTED'
   OR lower(name) IN ('佚名', '未知', '未知歌手', 'unknown', 'anonymous');

-- Force VARIOUS (群星) to '组合' and empty region
UPDATE artist_directory_stats
SET gender = '组合',
    region = ''
WHERE artist_kind = 'VARIOUS'
   OR name IN ('群星', '多人', 'Various Artists');

-- Index for public browse directory lookup with region
CREATE INDEX IF NOT EXISTS idx_artist_directory_stats_region_lookup
    ON artist_directory_stats (gender, region, initial, song_count DESC, name, artist_key);

-- 2. Fast pending queue ordering index for artist_avatar_jobs
CREATE INDEX IF NOT EXISTS idx_artist_avatar_jobs_pending_order
    ON artist_avatar_jobs (priority DESC, tier DESC, id ASC)
    WHERE status = 'PENDING';

-- 3. Transition historical unresolved profiles without avatars that have no active jobs to AUTO_INITIAL
UPDATE artist_profiles
SET avatar_status = 'AUTO_INITIAL',
    updated_at = now()
WHERE avatar_status IN ('REVIEW', 'FAILED', 'SKIPPED')
  AND (avatar_path IS NULL OR trim(avatar_path) = '');
