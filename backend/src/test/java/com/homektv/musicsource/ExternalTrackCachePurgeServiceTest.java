package com.homektv.musicsource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalTrackCachePurgeServiceTest {
    private JdbcTemplate jdbc;
    private ExternalTrackCachePurgeService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:cache-purge-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE music_source_search_cache(cache_key VARCHAR(96) PRIMARY KEY, expires_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        jdbc.execute("CREATE TABLE music_source_tracks(provider VARCHAR(24), external_id VARCHAR(160), expires_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY(provider, external_id))");
        jdbc.execute("CREATE TABLE song_external_matches(provider VARCHAR(24), external_id VARCHAR(160))");
        service = new ExternalTrackCachePurgeService(jdbc);
    }

    @Test
    void purgesExpiredSearchAndUnreferencedTrackRowsOnly() {
        jdbc.update("INSERT INTO music_source_search_cache(cache_key,expires_at) VALUES ('expired-search',CURRENT_TIMESTAMP - INTERVAL '1' HOUR), ('fresh-search',CURRENT_TIMESTAMP + INTERVAL '1' HOUR)");
        jdbc.update("INSERT INTO music_source_tracks(provider,external_id,expires_at) VALUES "
                + "('QQ','expired-unreferenced',CURRENT_TIMESTAMP - INTERVAL '1' HOUR), "
                + "('QQ','expired-referenced',CURRENT_TIMESTAMP - INTERVAL '1' HOUR), "
                + "('QQ','fresh-track',CURRENT_TIMESTAMP + INTERVAL '1' HOUR)");
        jdbc.update("INSERT INTO song_external_matches(provider,external_id) VALUES ('QQ','expired-referenced')");

        ExternalTrackCachePurgeService.PurgeResult result = service.purgeExpired();

        assertThat(result.searchCacheRows()).isEqualTo(1);
        assertThat(result.trackRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM music_source_search_cache", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM music_source_tracks", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM music_source_tracks WHERE external_id='expired-referenced'", Integer.class)).isEqualTo(1);
    }

    @Test
    void purgeIsIdempotentAndDoesNotRemoveFreshRows() {
        jdbc.update("INSERT INTO music_source_search_cache(cache_key,expires_at) VALUES ('fresh-search',CURRENT_TIMESTAMP + INTERVAL '1' HOUR)");
        jdbc.update("INSERT INTO music_source_tracks(provider,external_id,expires_at) VALUES ('QQ','fresh-track',CURRENT_TIMESTAMP + INTERVAL '1' HOUR)");

        assertThat(service.purgeExpired()).isEqualTo(new ExternalTrackCachePurgeService.PurgeResult(0, 0));
        assertThat(service.purgeExpired()).isEqualTo(new ExternalTrackCachePurgeService.PurgeResult(0, 0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM music_source_search_cache", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM music_source_tracks", Integer.class)).isEqualTo(1);
    }
}
