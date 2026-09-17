package com.homektv.repo;

import com.homektv.domain.Song;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

/**
 * 歌曲数据访问层，负责 {@link Song} 实体的数据库操作。
 *
 * Song data access layer, responsible for database operations on the {@link Song} entity.
 */
public interface SongRepository extends JpaRepository<Song, Long> {

    interface LanguageCountProjection {
        String getLanguage();
        Long getSongCount();
    }

    interface ArtistStatsProjection {
        String getArtist();
        String getArtistGender();
        String getArtistInit();
        Long getSongCount();
        String getArtistKey();
        String getArtistKind();
        String getAvatarPath();
    }

    interface ArtistDirectoryProjection {
        String getArtistKey();
        String getName();
        String getGender();
        Long getSongCount();
        Boolean getReviewed();
        String getArtistKind();
        String getAvatarPath();
    }

    interface PublicArtistDirectoryProjection {
        String getArtistKey();
        String getName();
        String getInitial();
        String getGender();
        Long getSongCount();
        String getArtistKind();
        String getAvatarPath();
    }

    interface ArtistInitialProjection {
        String getInitial();
    }

    interface ArtistSongProjection {
        String getArtistKey();
        Long getSongId();
        String getTitle();
        String getArtist();
        String getLanguage();
        String getMediaType();
        String getCoverPath();
    }

    interface TagCountProjection {
        String getName();
        Long getSongCount();
    }

    @Query("""
            SELECT s.language AS language, COUNT(s.id) AS songCount
            FROM Song s
            WHERE s.status = :status AND s.language IS NOT NULL AND s.language <> ''
            GROUP BY s.language
            ORDER BY COUNT(s.id) DESC, s.language ASC
            """)
    List<LanguageCountProjection> aggregateLanguagesByStatus(@Param("status") String status);

    @Query(value = """
            SELECT credits.artist AS "artist",
                   COALESCE(NULLIF(p.gender, '未知'), '未知') AS "artistGender",
                   credits.artist_init AS "artistInit",
                   COUNT(DISTINCT credits.song_id) AS "songCount",
                   credits.artist_key AS "artistKey",
                   p.artist_kind AS "artistKind",
                   p.avatar_path AS "avatarPath"
            FROM (
                SELECT s.id AS song_id, sa.artist_name AS artist, sa.artist_key,
                       COALESCE(NULLIF(sa.artist_init, ''), s.artist_init) AS artist_init
                FROM songs s
                JOIN song_artists sa ON sa.song_id = s.id
                WHERE s.status = :status AND sa.artist_name <> ''
                UNION ALL
                SELECT s.id AS song_id, s.artist,
                       lower(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) AS artist_key,
                       s.artist_init AS artist_init
                FROM songs s
                WHERE s.status = :status
                  AND s.artist IS NOT NULL AND s.artist <> ''
                  AND NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
            ) credits
            LEFT JOIN artist_profiles p ON p.artist_key = credits.artist_key
            GROUP BY credits.artist, credits.artist_key, p.gender, credits.artist_init,
                     p.artist_kind, p.avatar_path
            ORDER BY COUNT(DISTINCT credits.song_id) DESC, credits.artist ASC
            """, nativeQuery = true)
    List<ArtistStatsProjection> aggregateArtistsByStatus(@Param("status") String status);

    @Query(value = """
            WITH credits AS (
                SELECT s.id AS song_id, sa.artist_key, sa.artist_name
                FROM songs s
                JOIN song_artists sa ON sa.song_id = s.id
                WHERE s.status = :status AND trim(sa.artist_name) <> ''
                UNION ALL
                SELECT s.id AS song_id,
                       lower(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) AS artist_key,
                       trim(s.artist) AS artist_name
                FROM songs s
                WHERE s.status = :status
                  AND s.artist IS NOT NULL AND trim(s.artist) <> ''
                  AND NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
            ), aggregates AS (
                SELECT artist_key,
                       min(artist_name) AS name,
                       COUNT(DISTINCT song_id) AS song_count
                FROM credits
                GROUP BY artist_key
            )
            SELECT a.artist_key AS "artistKey",
                   a.name AS "name",
                   COALESCE(NULLIF(p.gender, '未知'), '未知') AS "gender",
                   a.song_count AS "songCount",
                   COALESCE(p.gender_status = 'MANUAL' AND NULLIF(p.gender, '未知') IS NOT NULL, false) AS "reviewed",
                   COALESCE(p.artist_kind,
                            CASE WHEN a.name IN ('群星', '多人', 'Various Artists') THEN 'VARIOUS'
                                 WHEN lower(a.name) IN ('佚名', '未知', '未知歌手', 'unknown', 'anonymous') THEN 'UNATTRIBUTED'
                                 ELSE 'PERSON' END) AS "artistKind",
                   p.avatar_path AS "avatarPath"
            FROM aggregates a
            LEFT JOIN artist_profiles p ON p.artist_key = a.artist_key
            WHERE (:keyword = '' OR a.name ILIKE '%' || :keyword || '%' OR a.artist_key ILIKE '%' || :keyword || '%')
              AND (:gender = '' OR COALESCE(NULLIF(p.gender, '未知'), '未知') = :gender)
              AND (:reviewed IS NULL OR COALESCE(p.gender_status = 'MANUAL' AND NULLIF(p.gender, '未知') IS NOT NULL, false) = :reviewed)
            ORDER BY a.song_count DESC, a.name ASC, a.artist_key ASC
            """,
            countQuery = """
            WITH credits AS (
                SELECT s.id AS song_id, sa.artist_key, sa.artist_name
                FROM songs s
                JOIN song_artists sa ON sa.song_id = s.id
                WHERE s.status = :status AND trim(sa.artist_name) <> ''
                UNION ALL
                SELECT s.id AS song_id,
                       lower(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) AS artist_key,
                       trim(s.artist) AS artist_name
                FROM songs s
                WHERE s.status = :status
                  AND s.artist IS NOT NULL AND trim(s.artist) <> ''
                  AND NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
            ), aggregates AS (
                SELECT artist_key,
                       min(artist_name) AS name
                FROM credits
                GROUP BY artist_key
            )
            SELECT COUNT(*)
            FROM aggregates a
            LEFT JOIN artist_profiles p ON p.artist_key = a.artist_key
            WHERE (:keyword = '' OR a.name ILIKE '%' || :keyword || '%' OR a.artist_key ILIKE '%' || :keyword || '%')
              AND (:gender = '' OR COALESCE(NULLIF(p.gender, '未知'), '未知') = :gender)
              AND (:reviewed IS NULL OR COALESCE(p.gender_status = 'MANUAL' AND NULLIF(p.gender, '未知') IS NOT NULL, false) = :reviewed)
            """, nativeQuery = true)
    Page<ArtistDirectoryProjection> pageArtistDirectory(@Param("status") String status,
                                                        @Param("keyword") String keyword,
                                                        @Param("gender") String gender,
                                                        @Param("reviewed") Boolean reviewed,
                                                        Pageable pageable);

    @Query(value = """
            WITH credits AS (
                SELECT s.id AS song_id, sa.artist_key, sa.artist_name,
                       COALESCE(NULLIF(sa.artist_init, ''), s.artist_init) AS artist_init
                FROM songs s
                JOIN song_artists sa ON sa.song_id = s.id
                WHERE s.status = :status AND trim(sa.artist_name) <> ''
                UNION ALL
                SELECT s.id AS song_id,
                       lower(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) AS artist_key,
                       trim(s.artist) AS artist_name,
                       s.artist_init AS artist_init
                FROM songs s
                WHERE s.status = :status
                  AND s.artist IS NOT NULL AND trim(s.artist) <> ''
                  AND NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
            ), aggregates AS (
                SELECT artist_key,
                       min(artist_name) AS name,
                       min(NULLIF(artist_init, '')) AS artist_init,
                       COUNT(DISTINCT song_id) AS song_count
                FROM credits
                GROUP BY artist_key
            )
            SELECT a.artist_key AS "artistKey",
                   a.name AS "name",
                   CASE WHEN COALESCE(a.artist_init, '') = '' THEN '#'
                        ELSE upper(left(a.artist_init, 1)) END AS "initial",
                   COALESCE(NULLIF(p.gender, '未知'), '未知') AS "gender",
                   a.song_count AS "songCount",
                   COALESCE(p.artist_kind,
                            CASE WHEN a.name IN ('群星', '多人', 'Various Artists') THEN 'VARIOUS'
                                 WHEN lower(a.name) IN ('佚名', '未知', '未知歌手', 'unknown', 'anonymous') THEN 'UNATTRIBUTED'
                                 ELSE 'PERSON' END) AS "artistKind",
                   p.avatar_path AS "avatarPath"
            FROM aggregates a
            LEFT JOIN artist_profiles p ON p.artist_key = a.artist_key
            WHERE (:gender = '' OR COALESCE(NULLIF(p.gender, '未知'), '未知') = :gender)
              AND (
                    :initial = ''
                    OR (:initial = '#' AND COALESCE(a.artist_init, '') = '')
                    OR (:initial <> '#' AND upper(COALESCE(a.artist_init, '')) LIKE upper(:initial) || '%')
              )
            ORDER BY a.song_count DESC, a.name ASC, a.artist_key ASC
            """,
            countQuery = """
            WITH credits AS (
                SELECT s.id AS song_id, sa.artist_key, sa.artist_name,
                       COALESCE(NULLIF(sa.artist_init, ''), s.artist_init) AS artist_init
                FROM songs s
                JOIN song_artists sa ON sa.song_id = s.id
                WHERE s.status = :status AND trim(sa.artist_name) <> ''
                UNION ALL
                SELECT s.id AS song_id,
                       lower(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) AS artist_key,
                       trim(s.artist) AS artist_name,
                       s.artist_init AS artist_init
                FROM songs s
                WHERE s.status = :status
                  AND s.artist IS NOT NULL AND trim(s.artist) <> ''
                  AND NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
            ), aggregates AS (
                SELECT artist_key,
                       min(artist_name) AS name,
                       min(NULLIF(artist_init, '')) AS artist_init
                FROM credits
                GROUP BY artist_key
            )
            SELECT COUNT(*)
            FROM aggregates a
            LEFT JOIN artist_profiles p ON p.artist_key = a.artist_key
            WHERE (:gender = '' OR COALESCE(NULLIF(p.gender, '未知'), '未知') = :gender)
              AND (
                    :initial = ''
                    OR (:initial = '#' AND COALESCE(a.artist_init, '') = '')
                    OR (:initial <> '#' AND upper(COALESCE(a.artist_init, '')) LIKE upper(:initial) || '%')
              )
            """, nativeQuery = true)
    Page<PublicArtistDirectoryProjection> pagePublicArtistDirectory(@Param("status") String status,
                                                                     @Param("gender") String gender,
                                                                     @Param("initial") String initial,
                                                                     Pageable pageable);

    @Query(value = """
            WITH credits AS (
                SELECT s.id AS song_id, sa.artist_key, sa.artist_name,
                       COALESCE(NULLIF(sa.artist_init, ''), s.artist_init) AS artist_init
                FROM songs s
                JOIN song_artists sa ON sa.song_id = s.id
                WHERE s.status = :status AND trim(sa.artist_name) <> ''
                UNION ALL
                SELECT s.id AS song_id,
                       lower(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) AS artist_key,
                       trim(s.artist) AS artist_name,
                       s.artist_init AS artist_init
                FROM songs s
                WHERE s.status = :status
                  AND s.artist IS NOT NULL AND trim(s.artist) <> ''
                  AND NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
            ), aggregates AS (
                SELECT artist_key,
                       min(NULLIF(artist_init, '')) AS artist_init
                FROM credits
                GROUP BY artist_key
            )
            SELECT DISTINCT CASE WHEN COALESCE(a.artist_init, '') = '' THEN '#'
                                 ELSE upper(left(a.artist_init, 1)) END AS "initial"
            FROM aggregates a
            LEFT JOIN artist_profiles p ON p.artist_key = a.artist_key
            WHERE (:gender = '' OR COALESCE(NULLIF(p.gender, '未知'), '未知') = :gender)
            ORDER BY "initial"
            """, nativeQuery = true)
    List<ArtistInitialProjection> findPublicArtistInitials(@Param("status") String status,
                                                            @Param("gender") String gender);

    @Query(value = """
            WITH credits AS (
                SELECT s.id AS song_id, sa.artist_key
                FROM songs s
                JOIN song_artists sa ON sa.song_id = s.id
                WHERE s.status = :status AND trim(sa.artist_name) <> ''
                UNION ALL
                SELECT s.id AS song_id,
                       lower(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) AS artist_key
                FROM songs s
                WHERE s.status = :status
                  AND s.artist IS NOT NULL AND trim(s.artist) <> ''
                  AND NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
            ), ranked AS (
                SELECT c.artist_key,
                       s.id AS song_id,
                       s.title,
                       s.artist,
                       s.language,
                       s.media_type,
                       s.cover_path,
                       ROW_NUMBER() OVER (
                           PARTITION BY c.artist_key
                           ORDER BY s.play_count DESC, s.title ASC, s.id ASC
                       ) AS row_number
                FROM credits c
                JOIN songs s ON s.id = c.song_id
                WHERE c.artist_key IN (:artistKeys)
            )
            SELECT artist_key AS "artistKey",
                   song_id AS "songId",
                   title AS "title",
                   artist AS "artist",
                   language AS "language",
                   media_type AS "mediaType",
                   cover_path AS "coverPath"
            FROM ranked
            WHERE row_number <= 5
            ORDER BY artist_key, row_number
            """, nativeQuery = true)
    List<ArtistSongProjection> findArtistSamples(@Param("status") String status,
                                                  @Param("artistKeys") List<String> artistKeys);

    @Query(value = """
            SELECT tag AS name, COUNT(DISTINCT song_id) AS songCount FROM (
                SELECT id AS song_id, unnest(tags) AS tag FROM songs WHERE status = 'ok'
                UNION ALL
                SELECT id AS song_id, unnest(ai_genres) AS tag FROM songs WHERE status = 'ok'
                UNION ALL
                SELECT id AS song_id, unnest(ai_themes) AS tag FROM songs WHERE status = 'ok'
            ) sub
            WHERE tag IS NOT NULL AND tag <> ''
            GROUP BY tag
            ORDER BY COUNT(DISTINCT song_id) DESC, tag ASC
            LIMIT 100
            """, nativeQuery = true)
    List<TagCountProjection> aggregateTagsByStatusOk();

    @Query(value = """
            SELECT * FROM songs s
            WHERE s.status = 'ok'
              AND (:artist = '' OR LOWER(s.artist) = LOWER(:artist)
                   OR EXISTS (SELECT 1 FROM song_artists sa
                              WHERE sa.song_id = s.id
                                AND LOWER(sa.artist_name) = LOWER(:artist)))
              AND (
                  :artistGender = ''
                  OR EXISTS (
                      SELECT 1
                      FROM song_artists sa
                      LEFT JOIN artist_profiles p ON p.artist_key = sa.artist_key
                      WHERE sa.song_id = s.id
                        AND (:artist = '' OR LOWER(sa.artist_name) = LOWER(:artist))
                        AND LOWER(COALESCE(NULLIF(p.gender, '未知'), '未知')) = LOWER(:artistGender)
                  )
                  OR (
                      NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                      AND (:artist = '' OR LOWER(s.artist) = LOWER(:artist))
                      AND LOWER(s.artist_gender) = LOWER(:artistGender)
                  )
              )
              AND (:language = '' OR LOWER(s.language) = LOWER(:language))
              AND (:vocalForm = '' OR LOWER(s.ai_vocal_form) = LOWER(:vocalForm))
              AND (:tag = ''
                   OR EXISTS (SELECT 1 FROM unnest(s.tags) value WHERE LOWER(value) = LOWER(:tag))
                   OR EXISTS (SELECT 1 FROM unnest(s.ai_genres) value WHERE LOWER(value) = LOWER(:tag))
                   OR EXISTS (SELECT 1 FROM unnest(s.ai_themes) value WHERE LOWER(value) = LOWER(:tag)))
            """,
            countQuery = """
            SELECT COUNT(*) FROM songs s
            WHERE s.status = 'ok'
              AND (:artist = '' OR LOWER(s.artist) = LOWER(:artist)
                   OR EXISTS (SELECT 1 FROM song_artists sa
                              WHERE sa.song_id = s.id
                                AND LOWER(sa.artist_name) = LOWER(:artist)))
              AND (
                  :artistGender = ''
                  OR EXISTS (
                      SELECT 1
                      FROM song_artists sa
                      LEFT JOIN artist_profiles p ON p.artist_key = sa.artist_key
                      WHERE sa.song_id = s.id
                        AND (:artist = '' OR LOWER(sa.artist_name) = LOWER(:artist))
                        AND LOWER(COALESCE(NULLIF(p.gender, '未知'), '未知')) = LOWER(:artistGender)
                  )
                  OR (
                      NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                      AND (:artist = '' OR LOWER(s.artist) = LOWER(:artist))
                      AND LOWER(s.artist_gender) = LOWER(:artistGender)
                  )
              )
              AND (:language = '' OR LOWER(s.language) = LOWER(:language))
              AND (:vocalForm = '' OR LOWER(s.ai_vocal_form) = LOWER(:vocalForm))
              AND (:tag = ''
                   OR EXISTS (SELECT 1 FROM unnest(s.tags) value WHERE LOWER(value) = LOWER(:tag))
                   OR EXISTS (SELECT 1 FROM unnest(s.ai_genres) value WHERE LOWER(value) = LOWER(:tag))
                   OR EXISTS (SELECT 1 FROM unnest(s.ai_themes) value WHERE LOWER(value) = LOWER(:tag)))
            """,
            nativeQuery = true)
    Page<Song> browseCategorySongs(@Param("artist") String artist,
                                   @Param("artistGender") String artistGender,
                                   @Param("language") String language,
                                   @Param("tag") String tag,
                                   @Param("vocalForm") String vocalForm,
                                   Pageable pageable);

    /** Public browse variant that follows the canonical song_artists identity key. */
    @Query(value = """
            SELECT * FROM songs s
            WHERE s.status = 'ok'
              AND (
                  EXISTS (
                      SELECT 1 FROM song_artists sa
                      WHERE sa.song_id = s.id AND LOWER(sa.artist_key) = LOWER(:artistKey)
                  )
                  OR (
                      NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                      AND LOWER(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) = LOWER(:artistKey)
                  )
              )
              AND (
                  :artistGender = ''
                  OR EXISTS (
                      SELECT 1
                      FROM song_artists sa
                      LEFT JOIN artist_profiles p ON p.artist_key = sa.artist_key
                      WHERE sa.song_id = s.id
                        AND LOWER(sa.artist_key) = LOWER(:artistKey)
                        AND LOWER(COALESCE(NULLIF(p.gender, '未知'), '未知')) = LOWER(:artistGender)
                  )
                  OR (
                      NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                      AND LOWER(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) = LOWER(:artistKey)
                      AND LOWER(s.artist_gender) = LOWER(:artistGender)
                  )
              )
              AND (:language = '' OR LOWER(s.language) = LOWER(:language))
              AND (:vocalForm = '' OR LOWER(s.ai_vocal_form) = LOWER(:vocalForm))
              AND (:tag = ''
                   OR EXISTS (SELECT 1 FROM unnest(s.tags) value WHERE LOWER(value) = LOWER(:tag))
                   OR EXISTS (SELECT 1 FROM unnest(s.ai_genres) value WHERE LOWER(value) = LOWER(:tag))
                   OR EXISTS (SELECT 1 FROM unnest(s.ai_themes) value WHERE LOWER(value) = LOWER(:tag)))
            """,
            countQuery = """
            SELECT COUNT(*) FROM songs s
            WHERE s.status = 'ok'
              AND (
                  EXISTS (
                      SELECT 1 FROM song_artists sa
                      WHERE sa.song_id = s.id AND LOWER(sa.artist_key) = LOWER(:artistKey)
                  )
                  OR (
                      NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                      AND LOWER(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) = LOWER(:artistKey)
                  )
              )
              AND (
                  :artistGender = ''
                  OR EXISTS (
                      SELECT 1
                      FROM song_artists sa
                      LEFT JOIN artist_profiles p ON p.artist_key = sa.artist_key
                      WHERE sa.song_id = s.id
                        AND LOWER(sa.artist_key) = LOWER(:artistKey)
                        AND LOWER(COALESCE(NULLIF(p.gender, '未知'), '未知')) = LOWER(:artistGender)
                  )
                  OR (
                      NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                      AND LOWER(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')) = LOWER(:artistKey)
                      AND LOWER(s.artist_gender) = LOWER(:artistGender)
                  )
              )
              AND (:language = '' OR LOWER(s.language) = LOWER(:language))
              AND (:vocalForm = '' OR LOWER(s.ai_vocal_form) = LOWER(:vocalForm))
              AND (:tag = ''
                   OR EXISTS (SELECT 1 FROM unnest(s.tags) value WHERE LOWER(value) = LOWER(:tag))
                   OR EXISTS (SELECT 1 FROM unnest(s.ai_genres) value WHERE LOWER(value) = LOWER(:tag))
                   OR EXISTS (SELECT 1 FROM unnest(s.ai_themes) value WHERE LOWER(value) = LOWER(:tag)))
            """,
            nativeQuery = true)
    Page<Song> browseCategorySongsByArtistKey(@Param("artistKey") String artistKey,
                                              @Param("artistGender") String artistGender,
                                              @Param("language") String language,
                                              @Param("tag") String tag,
                                              @Param("vocalForm") String vocalForm,
                                              Pageable pageable);

    @Query(value = """
            SELECT sa.artist_name
            FROM songs s
            JOIN song_artists sa ON sa.song_id = s.id
            WHERE s.status = :status AND sa.artist_name <> ''
            UNION
            SELECT s.artist
            FROM songs s
            WHERE s.status = :status
              AND s.artist IS NOT NULL AND s.artist <> ''
              AND NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
            ORDER BY 1
            LIMIT 50000
            """, nativeQuery = true)
    List<String> findDistinctArtistByStatus(@Param("status") String status);

    /**
     * Returns songs credited to one canonical artist key. The second branch
     * keeps rows usable if a legacy record has not received its song_artists
     * backfill yet; it never treats a combined credit as an individual match.
     */
    @Query(value = """
            SELECT s.*
            FROM songs s
            WHERE s.status = :status
              AND (
                  EXISTS (
                      SELECT 1 FROM song_artists sa
                      WHERE sa.song_id = s.id AND sa.artist_key = :artistKey
                  )
                  OR (
                      NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                      AND lower(regexp_replace(trim(coalesce(s.artist, '')), '[[:space:]]+', '', 'g')) = :artistKey
                  )
              )
            ORDER BY s.id
            """, nativeQuery = true)
    List<Song> findByArtistKeyAndStatus(@Param("artistKey") String artistKey,
                                        @Param("status") String status);

    @Query(value = """
            SELECT s.*
            FROM songs s
            WHERE s.status = :status
              AND s.id > :afterId
              AND s.id <= :maxId
            ORDER BY s.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Song> findValidSongsAfterId(@Param("status") String status,
                                     @Param("afterId") long afterId,
                                     @Param("maxId") long maxId,
                                     @Param("limit") int limit);

    @Query("SELECT COALESCE(MAX(song.id), 0) FROM Song song WHERE song.status = :status")
    long findMaxIdByStatus(@Param("status") String status);

    @Query(value = """
            SELECT COUNT(DISTINCT s.id)
            FROM songs s
            WHERE s.status = :status
              AND (
                  EXISTS (
                      SELECT 1 FROM song_artists sa
                      WHERE sa.song_id = s.id AND sa.artist_key = :artistKey
                  )
                  OR (
                      NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                      AND lower(regexp_replace(trim(coalesce(s.artist, '')), '[[:space:]]+', '', 'g')) = :artistKey
                  )
              )
            """, nativeQuery = true)
    long countByArtistKeyAndStatus(@Param("artistKey") String artistKey,
                                   @Param("status") String status);

    /** Same association lookup, bounded for AI representative-song samples. */
    @Query(value = """
            SELECT s.*
            FROM songs s
            WHERE s.status = :status
              AND (
                  EXISTS (
                      SELECT 1 FROM song_artists sa
                      WHERE sa.song_id = s.id AND sa.artist_key = :artistKey
                  )
                  OR (
                      NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                      AND lower(regexp_replace(trim(coalesce(s.artist, '')), '[[:space:]]+', '', 'g')) = :artistKey
                  )
              )
            ORDER BY s.play_count DESC, s.title ASC, s.id ASC
            """, nativeQuery = true)
    List<Song> findRepresentativeSongsByArtistKeyAndStatus(@Param("artistKey") String artistKey,
                                                            @Param("status") String status,
                                                            Pageable pageable);

    List<Song> findByArtistIgnoreCaseAndStatus(String artist, String status);

    List<Song> findByArtistInAndStatus(List<String> artists, String status);

    Optional<Song> findByFingerprint(String fingerprint);

    List<Song> findTop10ByTitleIgnoreCase(String title);

    long countByMediaType(String mediaType);

    long countByStatus(String status);

    java.util.List<Song> findTop50ByOrderByCreatedAtDesc();

    org.springframework.data.domain.Page<Song> findByMediaType(String mediaType, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Song> findByStatus(String status, org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT song FROM Song song
            WHERE EXISTS (SELECT file.id FROM SongFile file WHERE file.songId = song.id AND file.valid = true)
              AND (:keyword = ''
                OR LOWER(song.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(song.artist) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(song.titlePy) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(song.titleInit) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(song.artistPy) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(song.artistInit) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:type = ''
                OR (:type = 'unrecognized' AND song.status = 'unrecognized')
                OR (:type <> 'unrecognized' AND song.mediaType = :type))
              AND (:source = ''
                OR (:source = 'EXTERNAL_READ_ONLY' AND EXISTS (
                    SELECT file.id FROM SongFile file
                    WHERE file.songId = song.id AND file.valid = true
                      AND file.fileRole = 'EXTERNAL_READ_ONLY'))
                OR (:source = 'UNKNOWN' AND EXISTS (
                    SELECT file.id FROM SongFile file
                    WHERE file.songId = song.id AND file.valid = true
                      AND file.sourcePath IS NULL AND file.fileRole <> 'EXTERNAL_READ_ONLY'))
                OR (:source = 'COPIED' AND EXISTS (
                    SELECT file.id FROM SongFile file
                    WHERE file.songId = song.id AND file.valid = true
                      AND file.sourcePath IS NOT NULL AND file.transcodeRequired = false))
                OR (:source = 'TRANSCODED' AND EXISTS (
                    SELECT file.id FROM SongFile file
                    WHERE file.songId = song.id AND file.valid = true
                      AND file.sourcePath IS NOT NULL AND file.transcodeRequired = true)))
            """)
    Page<Song> searchAdminSongs(@Param("keyword") String keyword,
                                @Param("type") String type,
                                @Param("source") String source,
                                Pageable pageable);
}
