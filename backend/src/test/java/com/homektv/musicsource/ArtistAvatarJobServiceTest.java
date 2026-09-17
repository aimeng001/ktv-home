package com.homektv.musicsource;

import com.homektv.library.ArtistAvatarJobService;
import com.homektv.library.ArtistAvatarWorker;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.util.Collection;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class ArtistAvatarJobServiceTest {

    @Test
    void enqueuesOnlyProvidersEnabledByTheCurrentConfiguration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class)))
                .thenReturn(new int[][]{{1}});

        new ArtistAvatarJobService(jdbc, worker, config).enqueueProfiles(Set.of("周杰伦"));

        verify(jdbc).batchUpdate(anyString(), any(Collection.class), eq(500),
                any(ParameterizedPreparedStatementSetter.class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void enqueuesManyArtistProviderPairsWithOneBoundedBatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ, MusicProvider.NETEASE), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class)))
                .thenReturn(new int[][]{{1, 1, 1, 1}});

        new ArtistAvatarJobService(jdbc, worker, config)
                .enqueueProfiles(Set.of("周杰伦", "林俊杰"));

        verify(jdbc).batchUpdate(anyString(), any(Collection.class), eq(500),
                any(ParameterizedPreparedStatementSetter.class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void ordinaryScanEnqueueDoesNotReopenTerminalJobs() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(worker.pendingKeys()).thenReturn(java.util.List.of("zhoujielun"));
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class)))
                .thenReturn(new int[][]{{1}});

        new ArtistAvatarJobService(jdbc, worker, config).enqueuePendingProfiles();

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).batchUpdate(sql.capture(), any(Collection.class), eq(500),
                any(ParameterizedPreparedStatementSetter.class));
        assertThat(sql.getValue()).doesNotContain("status = CASE")
                .doesNotContain("attempts=CASE")
                .contains("DO NOTHING");
    }
}
