package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LibraryScanStateStoreTest {

    @Test
    void partialCompletionPersistsFailureDetailsAndDoesNotAdvanceSuccessfulTimestamp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        LibraryScanStateStore store = new LibraryScanStateStore(jdbc);
        LibraryScanStateStore.Claim claim = new LibraryScanStateStore.Claim(
                UUID.randomUUID(), UUID.randomUUID(), 8L);
        LibraryScanService.ScanProgress progress = new LibraryScanService.ScanProgress(
                false, 0, 0, null, 0, 0, 0, 0, OffsetDateTime.now(), OffsetDateTime.now(),
                "FAILED", 0, 0, 0, 0, 0.0, null,
                LibraryScanService.ScanState.FAILED, 1, "ROOT_NOT_READABLE", "根目录无读取权限");

        assertThat(store.markCompleted(claim, new LibraryScanService.ScanResult(0, 0, 0, 0, 0), progress))
                .isTrue();

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("last_successful_scan_at = CASE");
        assertThat(args.getValue()).contains("ROOT_NOT_READABLE", "根目录无读取权限");
    }
}
