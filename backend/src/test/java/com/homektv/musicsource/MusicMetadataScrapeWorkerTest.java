package com.homektv.musicsource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MusicMetadataScrapeWorkerTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void applyGateStopsWhenTheBatchWasPaused() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq("batch-1")))
                .thenReturn(List.of("PAUSED"));
        MusicMetadataScrapeWorker worker = worker();

        assertThat(worker.canApply("batch-1", 7L, "claim-1")).isFalse();
    }

    private MusicMetadataScrapeWorker worker() {
        return new MusicMetadataScrapeWorker(
                jdbc,
                mock(MusicSourceSearchService.class),
                mock(MusicMetadataApplyService.class),
                mock(MusicSourceConfigService.class),
                new ObjectMapper());
    }
}
