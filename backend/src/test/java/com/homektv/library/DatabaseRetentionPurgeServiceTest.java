package com.homektv.library;

import com.homektv.resource.PlaybackPressureMonitor;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseRetentionPurgeServiceTest {

    @Test
    void scheduledPurgeDefersWhenPlaybackIsBusy() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlaybackPressureMonitor pressure = mock(PlaybackPressureMonitor.class);
        when(pressure.isPlaybackBusy()).thenReturn(true);

        DatabaseRetentionPurgeService service = new DatabaseRetentionPurgeService(jdbc, 30);
        service.setPlaybackPressureMonitor(pressure);
        service.scheduledPurge();

        verifyNoInteractions(jdbc);
    }

    @Test
    void toleratesMissingOptionalTablesAndCountsDeletedRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Timestamp.class), eq(5000)))
                .thenReturn(120)
                .thenThrow(new InvalidDataAccessResourceUsageException("missing table"))
                .thenReturn(15);

        DatabaseRetentionPurgeService service = new DatabaseRetentionPurgeService(jdbc, 30);
        int deleted = service.purgeExpiredTerminalItems(Instant.parse("2026-11-24T00:00:00Z"));

        assertThat(deleted).isEqualTo(135);
    }
}
