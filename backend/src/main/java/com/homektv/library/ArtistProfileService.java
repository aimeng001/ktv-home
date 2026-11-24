package com.homektv.library;
import com.homektv.common.ArtistCreditParser;
import com.homektv.common.ArtistKindClassifier;
import com.homektv.common.ArtistKind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stores application-owned artist profiles without touching source media. */
@Service
public class ArtistProfileService {
    private static final Logger log = LoggerFactory.getLogger(ArtistProfileService.class);
    private static final int PROFILE_PAGE_SIZE = 500;
    private final JdbcTemplate jdbc;
    private final ArtistDirectoryProjectionService directoryProjection;

    public ArtistProfileService(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public ArtistProfileService(JdbcTemplate jdbc, ArtistDirectoryProjectionService directoryProjection) {
        this.jdbc = jdbc;
        this.directoryProjection = directoryProjection;
    }

    /** Creates missing profiles only; reviewed metadata is never overwritten by a scan. */
    @Transactional
    public void ensureProfiles(Collection<String> names) {
        List<ProfileSeed> seeds = names == null ? List.of() : ArtistCreditParser.normalize(names).stream()
                .map(name -> new ProfileSeed(
                        ArtistCreditParser.key(name),
                        name,
                        ArtistKindClassifier.classify(name).name(),
                        PinyinUtil.fullPinyin(name),
                        PinyinUtil.initials(name)))
                .filter(seed -> !seed.artistKey().isBlank())
                .toList();
        if (seeds.isEmpty()) return;
        try {
            jdbc.batchUpdate("""
                    INSERT INTO artist_profiles(
                        artist_key, display_name, artist_kind, pinyin, initials,
                        gender, gender_status, avatar_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, '未知', 'UNREVIEWED', 'PENDING', now(), now())
                    ON CONFLICT (artist_key) DO NOTHING
                    """, seeds, seeds.size(), (statement, seed) -> {
                statement.setString(1, seed.artistKey());
                statement.setString(2, seed.displayName());
                statement.setString(3, seed.artistKind());
                statement.setString(4, seed.pinyin());
                statement.setString(5, seed.initials());
            });
        } catch (DataAccessException failure) {
            // The song index remains usable during a rolling deployment where
            // the new migration has not reached this process yet.
            log.warn("artist profile upsert skipped: {}", failure.getMessage());
        }
    }

    /** Idempotently backfills profiles from independent credits and legacy song values. */
    public int backfillFromSongs() {
        try {
            int processed = 0;
            String lastName = "";
            while (true) {
                List<String> names = jdbc.query("""
                        SELECT artist_name
                        FROM (
                            SELECT sa.artist_name
                            FROM song_artists sa
                            JOIN songs s ON s.id = sa.song_id
                            WHERE s.status = 'ok'
                              AND trim(sa.artist_name) <> ''
                            UNION
                            SELECT s.artist
                            FROM songs s
                            WHERE s.status = 'ok'
                              AND trim(coalesce(s.artist, '')) <> ''
                              AND NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                        ) names
                        WHERE artist_name > ?
                          AND NOT EXISTS (
                              SELECT 1
                              FROM artist_profiles profile
                              WHERE profile.artist_key = lower(regexp_replace(
                                  trim(names.artist_name), '[[:space:]]+', '', 'g'))
                          )
                        ORDER BY artist_name
                        LIMIT ?
                        """, (rs, index) -> rs.getString(1), lastName, PROFILE_PAGE_SIZE);
                if (names == null || names.isEmpty()) return processed;
                ensureProfiles(names);
                processed += names.size();
                String nextName = names.getLast();
                // The database's ORDER BY/collation is authoritative here. The
                // next query uses the exact last value with a strict `>`
                // boundary, so a Java collation comparison must not terminate
                // a valid page early.
                if (nextName == null || nextName.equals(lastName) || names.size() < PROFILE_PAGE_SIZE) {
                    return processed;
                }
                lastName = nextName;
            }
        } catch (DataAccessException failure) {
            log.warn("artist profile backfill skipped: {}", failure.getMessage());
            return 0;
        }
    }

    /**
     * Removes orphaned artist_profiles rows whose display name parses into
     * multiple independent credits and is no longer referenced by any valid song.
     */
    public int pruneObsoleteCombinedProfiles() {
        try {
            List<ProfileCandidate> orphans = jdbc.query("""
                    SELECT p.artist_key, p.display_name
                    FROM artist_profiles p
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM song_artists sa
                        JOIN songs s ON s.id = sa.song_id
                        WHERE sa.artist_key = p.artist_key
                          AND s.status = 'ok'
                    )
                      AND NOT EXISTS (
                        SELECT 1
                        FROM songs s
                        WHERE s.status = 'ok'
                          AND lower(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) = p.artist_key
                          AND NOT EXISTS (SELECT 1 FROM song_artists sa2 WHERE sa2.song_id = s.id)
                    )
                    """, (rs, index) -> new ProfileCandidate(
                    rs.getString("artist_key"),
                    rs.getString("display_name")));
            if (orphans == null || orphans.isEmpty()) return 0;

            List<String> keysToDelete = orphans.stream()
                    .filter(candidate -> candidate.displayName() != null
                            && ArtistCreditParser.parse(candidate.displayName()).size() > 1)
                    .map(ProfileCandidate::artistKey)
                    .filter(key -> key != null && !key.isBlank())
                    .toList();
            if (keysToDelete.isEmpty()) return 0;

            jdbc.batchUpdate(
                    "DELETE FROM artist_profiles WHERE artist_key = ?",
                    keysToDelete,
                    keysToDelete.size(),
                    (statement, artistKey) -> statement.setString(1, artistKey));
            log.info("pruned {} obsolete combined artist profile(s)", keysToDelete.size());
            return keysToDelete.size();
        } catch (DataAccessException failure) {
            log.warn("obsolete combined profile cleanup skipped: {}", failure.getMessage());
            return 0;
        }
    }

    private record ProfileCandidate(String artistKey, String displayName) {}

    public Optional<Profile> find(String artistKey) {
        if (artistKey == null || artistKey.isBlank()) return Optional.empty();
        List<Profile> rows = jdbc.query("""
                SELECT artist_key, display_name, artist_kind, avatar_path,
                       avatar_provider, avatar_external_id, avatar_status
                FROM artist_profiles WHERE artist_key=?
                """, (rs, index) -> new Profile(
                rs.getString("artist_key"), rs.getString("display_name"),
                rs.getString("artist_kind"), rs.getString("avatar_path"),
                rs.getString("avatar_provider"), rs.getString("avatar_external_id"),
                rs.getString("avatar_status")), artistKey);
        return rows.stream().findFirst();
    }

    @Transactional
    public boolean setAvatarReady(String artistKey, String provider, String path) {
        boolean changed = jdbc.update("""
                UPDATE artist_profiles
                SET avatar_path=?, avatar_provider=?, avatar_status='READY',
                    avatar_error=NULL, updated_at=now()
                WHERE artist_key=?
                """, path, provider, artistKey) > 0;
        if (changed) {
            try {
                jdbc.update("""
                        UPDATE artist_avatar_jobs
                        SET status='SKIPPED', last_error='已获取有效头像', updated_at=now()
                        WHERE artist_key=? AND status='PENDING'
                        """, artistKey);
            } catch (DataAccessException ignored) {}
            if (directoryProjection != null) {
                directoryProjection.updateAvatarPathAfterCommit(artistKey, path);
            }
        }
        return changed;
    }

    public List<Profile> unresolvedProfiles(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50_000));
        try {
            return jdbc.query("""
                    SELECT artist_key, display_name, artist_kind, avatar_path,
                           avatar_provider, avatar_external_id, avatar_status
                    FROM artist_profiles
                    WHERE artist_kind IN ('PERSON', 'GROUP')
                      AND (avatar_path IS NULL OR trim(avatar_path) = '' OR avatar_status <> 'READY')
                    ORDER BY updated_at, artist_key
                    LIMIT ?
                    """, (rs, index) -> new Profile(
                    rs.getString("artist_key"), rs.getString("display_name"),
                    rs.getString("artist_kind"), rs.getString("avatar_path"),
                    rs.getString("avatar_provider"), rs.getString("avatar_external_id"),
                    rs.getString("avatar_status")), safeLimit);
        } catch (DataAccessException failure) {
            log.debug("unresolved artist profiles lookup unavailable: {}", failure.getMessage());
            return List.of();
        }
    }

    @Transactional
    public boolean markAvatarReadyIfClaimed(String artistKey, String provider, String externalId, String path,
                                            long jobId, String claimToken) {
        boolean changed = jdbc.update("""
                UPDATE artist_profiles
                SET avatar_path=?, avatar_provider=?, avatar_external_id=?,
                    avatar_status='READY', avatar_error=NULL, updated_at=now()
                WHERE artist_key=?
                AND EXISTS (
                    SELECT 1 FROM artist_avatar_jobs job
                    WHERE job.id=? AND job.artist_key=artist_profiles.artist_key
                        AND job.status='PROCESSING' AND job.claim_token=?::uuid
                )
                """, path, provider, externalId, artistKey, jobId, claimToken) > 0;
        if (changed && directoryProjection != null) {
            directoryProjection.updateAvatarPathAfterCommit(artistKey, path);
        }
        return changed;
    }

    @Transactional
    public void setGender(String displayName, String gender) {
        String artistKey = ArtistCreditParser.key(displayName);
        if (artistKey.isBlank() || gender == null || gender.isBlank()) return;
        ensureProfiles(List.of(displayName));
        int updated = jdbc.update("""
                UPDATE artist_profiles
                SET gender=?, gender_status='MANUAL', updated_at=now()
                WHERE artist_key=?
                """, gender, artistKey);
        if (updated > 0 && directoryProjection != null) {
            directoryProjection.updateGenderAfterCommit(artistKey, gender, !"未知".equals(gender));
        }
    }

    @Transactional
    public boolean markAvatarStatusIfClaimed(String artistKey, String status, String error,
                                             long jobId, String claimToken) {
        return jdbc.update("""
                UPDATE artist_profiles
                SET avatar_status=?, avatar_error=?, avatar_attempts=avatar_attempts+1, updated_at=now()
                WHERE artist_key=?
                  AND EXISTS (
                      SELECT 1 FROM artist_avatar_jobs job
                      WHERE job.id=? AND job.artist_key=artist_profiles.artist_key
                        AND job.status='PROCESSING' AND job.claim_token=?::uuid
                  )
                """, status, error, artistKey, jobId, claimToken) > 0;
    }

    public List<String> pendingAvatarKeys(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        try {
            return jdbc.query("""
                    SELECT artist_profiles.artist_key FROM artist_profiles
                    LEFT JOIN artist_directory_stats dir ON dir.artist_key = artist_profiles.artist_key
                    WHERE artist_profiles.artist_kind IN ('PERSON', 'GROUP')
                      AND artist_profiles.avatar_status IN ('PENDING', 'RETRY')
                      AND NOT EXISTS (
                          SELECT 1 FROM artist_avatar_jobs job
                          WHERE job.artist_key = artist_profiles.artist_key
                      )
                    ORDER BY COALESCE(dir.song_count, 0) DESC, artist_profiles.updated_at, artist_profiles.artist_key
                    LIMIT ?
                    """, (rs, index) -> rs.getString(1), safeLimit);
        } catch (DataAccessException failure) {
            log.debug("pending artist avatar lookup unavailable: {}", failure.getMessage());
            return List.of();
        }
    }

    /** Returns song-count priority map for scheduling popular artists ahead of rare entries. */
    public Map<String, Integer> artistSongPriorities(Collection<String> artistKeys) {
        if (artistKeys == null || artistKeys.isEmpty()) return Map.of();
        try {
            List<String> keys = artistKeys.stream()
                    .filter(k -> k != null && !k.isBlank())
                    .distinct()
                    .limit(10_000)
                    .toList();
            if (keys.isEmpty()) return Map.of();
            Map<String, Integer> result = new LinkedHashMap<>();
            for (int start = 0; start < keys.size(); start += PROFILE_PAGE_SIZE) {
                List<String> batch = keys.subList(start, Math.min(keys.size(), start + PROFILE_PAGE_SIZE));
                String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
                jdbc.query(
                        "SELECT artist_key, song_count FROM artist_directory_stats WHERE artist_key IN (" + placeholders + ")",
                        rs -> {
                            result.put(rs.getString("artist_key"), Math.max(0, rs.getInt("song_count")));
                        },
                        batch.toArray());
            }
            return result;
        } catch (DataAccessException failure) {
            return Map.of();
        }
    }

    /** Requeues one artist profile for fresh avatar scraping (clearing terminal error state). */
    @Transactional
    public boolean resetSingleArtistForScrape(String artistKey) {
        if (artistKey == null || artistKey.isBlank()) return false;
        int updated = jdbc.update("""
                UPDATE artist_profiles
                SET avatar_path=NULL, avatar_provider=NULL, avatar_external_id=NULL,
                    avatar_status='PENDING', avatar_error=NULL, updated_at=now()
                WHERE artist_key=? AND artist_kind IN ('PERSON', 'GROUP')
                """, artistKey);
        if (updated > 0 && directoryProjection != null) {
            directoryProjection.updateAvatarPathAfterCommit(artistKey, null);
        }
        return updated > 0;
    }

    /**
     * Checks previously cached NetEase avatars and resets flat grey silhouette placeholders
     * so the repaired pipeline can re-scrape real artist portraits.
     */
    public int requeueSuspectNeteasePlaceholders(AssetWriter assets, int limit) {
        if (assets == null) return 0;
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        try {
            List<Profile> candidates = jdbc.query("""
                    SELECT artist_key, display_name, artist_kind, avatar_path,
                           avatar_provider, avatar_external_id, avatar_status
                    FROM artist_profiles
                    WHERE artist_kind IN ('PERSON', 'GROUP')
                      AND avatar_status = 'READY'
                      AND avatar_provider = 'NETEASE'
                      AND avatar_path IS NOT NULL
                    ORDER BY updated_at ASC
                    LIMIT ?
                    """, (rs, index) -> new Profile(
                    rs.getString("artist_key"), rs.getString("display_name"),
                    rs.getString("artist_kind"), rs.getString("avatar_path"),
                    rs.getString("avatar_provider"), rs.getString("avatar_external_id"),
                    rs.getString("avatar_status")), safeLimit);
            int resetCount = 0;
            for (Profile profile : candidates) {
                var path = assets.readableCachePath(profile.avatarPath()).orElse(null);
                if (path == null || !com.homektv.musicsource.AvatarImageQualityValidator.isUsableCachedAvatarFile(path)) {
                    if (resetSingleArtistForScrape(profile.artistKey())) {
                        assets.deleteReadableCache(profile.avatarPath());
                        jdbc.update("DELETE FROM artist_avatar_jobs WHERE artist_key=?", profile.artistKey());
                        resetCount++;
                    }
                }
            }
            if (resetCount > 0) {
                log.info("reset {} suspect NetEase placeholder artist avatar(s) for re-scraping", resetCount);
            }
            return resetCount;
        } catch (DataAccessException failure) {
            log.debug("placeholder avatar verification skipped: {}", failure.getMessage());
            return 0;
        }
    }

    public Map<String, Object> avatarProgressSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            jdbc.query("""
                    SELECT
                        COUNT(*) FILTER (WHERE artist_kind IN ('PERSON', 'GROUP')) AS total_artists,
                        COUNT(*) FILTER (WHERE artist_kind IN ('PERSON', 'GROUP') AND avatar_status = 'READY'
                                         AND avatar_path IS NOT NULL AND trim(avatar_path) <> '') AS ready_count,
                        COUNT(*) FILTER (WHERE artist_kind IN ('PERSON', 'GROUP') AND avatar_status = 'AUTO_INITIAL') AS auto_initial_count,
                        COUNT(*) FILTER (WHERE artist_kind IN ('PERSON', 'GROUP') AND avatar_status IN ('PENDING', 'RETRY')) AS pending_count,
                        COUNT(*) FILTER (WHERE artist_kind IN ('PERSON', 'GROUP') AND avatar_status IN ('REVIEW', 'FAILED')
                                         AND (avatar_path IS NULL OR trim(avatar_path) = '')) AS unresolved_count
                    FROM artist_profiles
                    """, rs -> {
                out.put("totalArtists", rs.getLong("total_artists"));
                out.put("readyCount", rs.getLong("ready_count"));
                out.put("autoInitialCount", rs.getLong("auto_initial_count"));
                out.put("pendingCount", rs.getLong("pending_count"));
                out.put("unresolvedCount", rs.getLong("unresolved_count"));
            });
            Long queuedJobs = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM artist_avatar_jobs WHERE status IN ('PENDING', 'PROCESSING')",
                    Long.class);
            out.put("queuedJobs", queuedJobs == null ? 0L : queuedJobs);

            Long queuedArtists = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT artist_key) FROM artist_avatar_jobs WHERE status IN ('PENDING', 'PROCESSING')",
                    Long.class);
            out.put("queuedArtists", queuedArtists == null ? 0L : queuedArtists);

            List<Map<String, Object>> recentErrors = jdbc.query("""
                    SELECT job.artist_key, job.provider, job.last_error, job.updated_at
                    FROM artist_avatar_jobs job
                    JOIN artist_profiles p ON p.artist_key = job.artist_key
                    WHERE job.last_error IS NOT NULL AND trim(job.last_error) <> ''
                      AND job.status IN ('FAILED', 'REVIEW')
                      AND p.avatar_status NOT IN ('READY', 'AUTO_INITIAL')
                      AND NOT EXISTS (
                          SELECT 1 FROM artist_avatar_jobs active
                          WHERE active.artist_key = job.artist_key
                            AND active.status IN ('PENDING', 'PROCESSING')
                      )
                    ORDER BY job.updated_at DESC
                    LIMIT 5
                    """, (rs, index) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("artistKey", rs.getString("artist_key"));
                row.put("provider", rs.getString("provider"));
                row.put("lastError", rs.getString("last_error"));
                return row;
            });
            out.put("recentErrors", recentErrors);
        } catch (DataAccessException failure) {
            out.putIfAbsent("totalArtists", 0L);
            out.putIfAbsent("readyCount", 0L);
            out.putIfAbsent("autoInitialCount", 0L);
            out.putIfAbsent("pendingCount", 0L);
            out.putIfAbsent("unresolvedCount", 0L);
            out.putIfAbsent("queuedJobs", 0L);
            out.putIfAbsent("queuedArtists", 0L);
            out.putIfAbsent("recentErrors", List.of());
        }
        return out;
    }

    /** Profiles that may be retried after an explicit provider configuration change. */
    public List<String> unresolvedAvatarKeys(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        try {
            return jdbc.query("""
                    SELECT artist_profiles.artist_key FROM artist_profiles
                    LEFT JOIN artist_directory_stats dir ON dir.artist_key = artist_profiles.artist_key
                    WHERE artist_profiles.artist_kind IN ('PERSON', 'GROUP')
                      AND (artist_profiles.avatar_status IN ('PENDING', 'RETRY', 'REVIEW', 'SKIPPED', 'FAILED', 'AUTO_INITIAL')
                           OR artist_profiles.avatar_path IS NULL OR trim(artist_profiles.avatar_path) = '')
                      AND (artist_profiles.avatar_status <> 'READY' OR artist_profiles.avatar_path IS NULL)
                    ORDER BY COALESCE(dir.song_count, 0) DESC, artist_profiles.updated_at, artist_profiles.artist_key
                    LIMIT ?
                    """, (rs, index) -> rs.getString(1), safeLimit);
        } catch (DataAccessException failure) {
            log.debug("unresolved artist avatar lookup unavailable: {}", failure.getMessage());
            return List.of();
        }
    }

    public record Profile(String artistKey, String displayName, String artistKind,
                          String avatarPath, String avatarProvider,
                          String avatarExternalId, String avatarStatus) {}

    public record AvatarDiagnostic(String avatarStatus, String avatarProvider, String avatarError) {}

    public Map<String, AvatarDiagnostic> avatarDiagnostics(Collection<String> artistKeys) {
        if (artistKeys == null || artistKeys.isEmpty()) return Map.of();
        try {
            List<String> keys = artistKeys.stream()
                    .filter(k -> k != null && !k.isBlank())
                    .distinct()
                    .limit(PROFILE_PAGE_SIZE)
                    .toList();
            if (keys.isEmpty()) return Map.of();
            String placeholders = String.join(",", java.util.Collections.nCopies(keys.size(), "?"));
            Map<String, AvatarDiagnostic> out = new LinkedHashMap<>();
            jdbc.query(
                    "SELECT artist_key, avatar_status, avatar_provider, avatar_error FROM artist_profiles WHERE artist_key IN ("
                            + placeholders + ")",
                    rs -> {
                        out.put(rs.getString("artist_key"), new AvatarDiagnostic(
                                rs.getString("avatar_status"),
                                rs.getString("avatar_provider"),
                                rs.getString("avatar_error")));
                    },
                    keys.toArray());
            return out;
        } catch (DataAccessException failure) {
            return Map.of();
        }
    }

    private record ProfileSeed(String artistKey, String displayName, String artistKind,
                               String pinyin, String initials) {}
}
