package com.homektv.library;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies exact dictionary aliases to application-owned artist profiles. */
@Service
public class ArtistGenderMatcher {
    private final JdbcTemplate jdbc;
    private final ArtistDirectoryProjectionService directoryProjection;

    public ArtistGenderMatcher(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ArtistGenderMatcher(JdbcTemplate jdbc, ArtistDirectoryProjectionService directoryProjection) {
        this.jdbc = jdbc;
        this.directoryProjection = directoryProjection;
    }

    /**
     * Recomputes only rows previously owned by this matcher, then applies the
     * best enabled dictionary match. MANUAL rows are never changed.
     */
    @Transactional
    public int applyDictionary() {
        jdbc.update("""
                UPDATE artist_profiles
                SET gender = '未知', gender_status = 'UNREVIEWED', updated_at = now()
                WHERE gender_status = 'AUTO_DB'
                """);

        int dictionaryChanged = jdbc.update("""
                WITH matches AS (
                    SELECT DISTINCT ON (p.artist_key)
                           p.artist_key, d.gender
                    FROM artist_profiles p
                    JOIN artist_gender_dictionary d
                      ON d.enabled = TRUE
                     AND (p.artist_key = d.alias_key OR p.artist_key = d.canonical_key)
                    WHERE p.artist_kind IN ('PERSON', 'GROUP')
                      AND p.gender_status <> 'MANUAL'
                    ORDER BY p.artist_key,
                             CASE WHEN d.source = 'ADMIN' THEN 0 ELSE 1 END,
                             CASE WHEN d.alias_key = d.canonical_key THEN 0 ELSE 1 END,
                             d.updated_at DESC,
                             d.alias_key
                )
                UPDATE artist_profiles p
                SET gender = matches.gender,
                    gender_status = 'AUTO_DB',
                    updated_at = now()
                FROM matches
                WHERE p.artist_key = matches.artist_key
                """);

        int songGenderChanged = jdbc.update("""
                WITH credit_rows AS (
                    SELECT credited.artist_key, credited.artist_gender
                    FROM (
                        SELECT sa.artist_key, s.artist_gender,
                               count(*) OVER (PARTITION BY s.id) AS credit_count
                        FROM songs s
                        JOIN song_artists sa ON sa.song_id = s.id
                        WHERE s.status = 'ok'
                          AND s.artist_gender IN ('男歌手', '女歌手', '组合')
                    ) credited
                    WHERE credited.credit_count = 1
                    UNION ALL
                    SELECT lower(regexp_replace(trim(s.artist), '[[:space:]]+', '', 'g')),
                           s.artist_gender
                    FROM songs s
                    WHERE s.status = 'ok'
                      AND trim(coalesce(s.artist, '')) <> ''
                      AND s.artist_gender IN ('男歌手', '女歌手', '组合')
                      AND s.artist !~ '[_、&＆/／,，;；+]'
                      AND NOT EXISTS (SELECT 1 FROM song_artists sa WHERE sa.song_id = s.id)
                ), consistent AS (
                    SELECT artist_key, min(artist_gender) AS gender
                    FROM credit_rows
                    GROUP BY artist_key
                    HAVING count(DISTINCT artist_gender) = 1
                )
                UPDATE artist_profiles p
                SET gender = consistent.gender,
                    gender_status = 'AUTO_DB',
                    updated_at = now()
                FROM consistent
                WHERE p.artist_key = consistent.artist_key
                  AND p.artist_kind IN ('PERSON', 'GROUP')
                  AND p.gender_status <> 'MANUAL'
                  AND p.gender = '未知'
                """);

        int changed = dictionaryChanged + songGenderChanged;
        if (directoryProjection != null && changed > 0) {
            directoryProjection.requestRefreshAfterCommit();
        }
        return changed;
    }
}
