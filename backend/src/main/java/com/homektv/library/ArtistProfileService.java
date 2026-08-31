package com.homektv.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ArtistProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
    public boolean markAvatarReadyIfClaimed(String artistKey, String provider, String externalId, String path,
                                            long jobId, String claimToken) {
        return jdbc.update("""
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
    }

    @Transactional
    public void setGender(String displayName, String gender) {
        String artistKey = ArtistCreditParser.key(displayName);
        if (artistKey.isBlank() || gender == null || gender.isBlank()) return;
        ensureProfiles(List.of(displayName));
        jdbc.update("""
                UPDATE artist_profiles
                SET gender=?, gender_status='MANUAL', updated_at=now()
                WHERE artist_key=?
                """, gender, artistKey);
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
                    SELECT artist_key FROM artist_profiles
                    WHERE artist_kind IN ('PERSON', 'GROUP')
                      AND avatar_status IN ('PENDING', 'RETRY')
                    ORDER BY updated_at, artist_key
                    LIMIT ?
                    """, (rs, index) -> rs.getString(1), safeLimit);
        } catch (DataAccessException failure) {
            log.debug("pending artist avatar lookup unavailable: {}", failure.getMessage());
            return List.of();
        }
    }

    /** Profiles that may be retried after an explicit provider configuration change. */
    public List<String> unresolvedAvatarKeys(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        try {
            return jdbc.query("""
                    SELECT artist_key FROM artist_profiles
                    WHERE artist_kind IN ('PERSON', 'GROUP')
                      AND avatar_status IN ('PENDING', 'RETRY', 'REVIEW', 'SKIPPED')
                    ORDER BY updated_at, artist_key
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

    private record ProfileSeed(String artistKey, String displayName, String artistKind,
                               String pinyin, String initials) {}
}
