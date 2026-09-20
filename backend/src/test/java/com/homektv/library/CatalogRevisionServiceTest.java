package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogRevisionServiceTest {
    @Test
    void catalogMutationRefreshesDirectoryAndStats() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LibraryCatalogStatsService stats = mock(LibraryCatalogStatsService.class);
        ArtistDirectoryProjectionService projection = mock(ArtistDirectoryProjectionService.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);

        CatalogRevisionService service = new CatalogRevisionService(jdbc);
        service.setCatalogStats(stats);
        service.setDirectoryProjection(projection);
        service.bumpIfChanged(true);

        verify(stats).requestRefresh();
        verify(projection).requestRefresh();
    }
}