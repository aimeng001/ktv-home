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
                   credits.artist_gender AS "artistGender",
                   credits.artist_init AS "artistInit",
                   COUNT(*) AS "songCount"
            FROM (
                SELECT s.id, sa.artist_name AS artist, s.artist_gender, sa.artist_init
                FROM songs s
                JOIN song_artists sa ON sa.song_id = s.id
                WHERE s.status = :status AND sa.artist_name <> ''
                UNION ALL
                SELECT s.id, s.artist, s.artist_gender, s.artist_init
                FROM songs s
                WHERE s.status = :status
                  AND s.artist IS NOT NULL AND s.artist <> ''
                  AND NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
            ) credits
            GROUP BY credits.artist, credits.artist_gender, credits.artist_init
            ORDER BY COUNT(*) DESC, credits.artist ASC
            """, nativeQuery = true)
    List<ArtistStatsProjection> aggregateArtistsByStatus(@Param("status") String status);

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
              AND (:artistGender = '' OR LOWER(s.artist_gender) = LOWER(:artistGender))
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
              AND (:artistGender = '' OR LOWER(s.artist_gender) = LOWER(:artistGender))
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
            """, nativeQuery = true)
    List<String> findDistinctArtistByStatus(@Param("status") String status);

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
