package com.homektv.queue;

import com.homektv.domain.PlayerState;
import com.homektv.repo.AppUserRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotSizeGuardTest {

    @Test
    void an_already_oversized_waiting_queue_fails_closed_before_loading_all_rows() {
        PlayerStateRepository playerRepository = mock(PlayerStateRepository.class);
        QueueItemRepository queueRepository = mock(QueueItemRepository.class);
        SongRepository songRepository = mock(SongRepository.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        WsBroadcaster broadcaster = mock(WsBroadcaster.class);
        when(playerRepository.getSingleton()).thenReturn(new PlayerState());
        when(queueRepository.countByStatus(QueueService.WAITING)).thenReturn(1_001L);

        SnapshotService service = new SnapshotService(playerRepository, queueRepository,
                songRepository, userRepository, broadcaster);

        assertThatThrownBy(service::snapshot)
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "QUEUE_TOO_LARGE");
        verify(queueRepository, never())
                .findByStatusOrderByOrderIndexAsc(QueueService.WAITING);
    }
}
