package com.homektv.queue;

import com.homektv.domain.PlayerState;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueueCapacityTest {

    @Test
    void ordering_at_the_waiting_limit_is_rejected_before_loading_song_data() {
        QueueItemRepository queueRepository = mock(QueueItemRepository.class);
        SongRepository songRepository = mock(SongRepository.class);
        PlayerStateRepository playerRepository = mock(PlayerStateRepository.class);
        SongAvailabilityPolicy availabilityPolicy = mock(SongAvailabilityPolicy.class);
        when(queueRepository.countByStatus(QueueService.WAITING)).thenReturn(1_000L);

        QueueService service = new QueueService(queueRepository, songRepository,
                playerRepository, availabilityPolicy);

        assertThatThrownBy(() -> service.order(42L, 7L, true))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "QUEUE_FULL");
        verify(songRepository, never()).findById(42L);
    }
}
