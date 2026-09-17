package com.homektv.library;

import com.homektv.musicsource.MusicProvider;
import com.homektv.musicsource.MusicSourceConfig;
import com.homektv.musicsource.MusicSourceConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistAvatarJobServiceRecoveryTest {
    @Test
    void rejectedConfigurationRefreshIsRetriedWithoutLosingTheRequest() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        AtomicBoolean reject = new AtomicBoolean(true);
        Executor executor = command -> {
            if (reject.get()) throw new RejectedExecutionException("queue full");
            command.run();
        };
        when(worker.unresolvedKeys()).thenReturn(List.of("zhoujielun"));
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class)))
                .thenReturn(new int[][]{{1}});

        ArtistAvatarJobService service = new ArtistAvatarJobService(jdbc, worker, config, executor);
        service.enqueueUnresolvedProfilesAsync();
        reject.set(false);
        service.retryRejectedUnresolvedRefresh();

        verify(worker).unresolvedKeys();
        verify(jdbc).batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class));
    }

    @Test
    void scheduledPendingProfileReconciliationKeepsLargeScanQueueMoving() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArtistAvatarWorker worker = mock(ArtistAvatarWorker.class);
        MusicSourceConfigService config = mock(MusicSourceConfigService.class);
        when(worker.pendingKeys()).thenReturn(List.of("zhoujielun"));
        when(config.getConfig()).thenReturn(new MusicSourceConfig(
                true, Set.of(MusicProvider.QQ), 20, 5, 6, 1, 1500, 0.95));
        when(jdbc.batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class)))
                .thenReturn(new int[][]{{1}});

        ArtistAvatarJobService service = new ArtistAvatarJobService(jdbc, worker, config);
        service.retryPendingProfileEnqueue();

        verify(worker).pendingKeys();
        verify(jdbc).batchUpdate(anyString(), any(Collection.class), anyInt(),
                any(ParameterizedPreparedStatementSetter.class));
    }
}
