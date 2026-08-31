package com.homektv.queue;

import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueServiceConcurrencyTest {

    @Mock
    private QueueItemRepository queueRepository;
    @Mock
    private SongRepository songRepository;
    @Mock
    private PlayerStateRepository playerRepository;
    @Mock
    private SongAvailabilityPolicy availabilityPolicy;

    private QueueService queueService;
    private Song song;
    private PlayerState playerState;

    @BeforeEach
    void setUp() {
        queueService = new QueueService(queueRepository, songRepository, playerRepository, availabilityPolicy);
        song = new Song();
        song.setId(10L);
        song.setTitle("测试歌曲");
        song.setStatus("ready");
        playerState = new PlayerState();
        playerState.setCurrentQueueId(null);
        playerState.setState("idle");

        lenient().when(songRepository.findById(10L)).thenReturn(Optional.of(song));
        lenient().when(availabilityPolicy.isPlayable(song)).thenReturn(true);
        lenient().when(queueRepository.findFirstByStatusOrderByOrderIndexDesc(QueueService.WAITING))
                .thenReturn(Optional.empty());
        lenient().when(queueRepository.save(any(QueueItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(playerRepository.getSingleton()).thenReturn(playerState);
    }

    @Test
    void orderRejectsSongStillWaitingForMediaProbe() {
        song.setStatus("ok");
        doThrow(new ApiException(SongAvailabilityPolicy.SONG_NOT_READY, "媒体探测未完成"))
                .when(availabilityPolicy).requirePlayable(song);

        assertThatThrownBy(() -> queueService.order(10L, 20L, true))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", SongAvailabilityPolicy.SONG_NOT_READY);
    }

    @Test
    void forceOrderAlsoSerializesTailAllocation() {
        queueService.order(10L, 20L, true);

        verify(queueRepository).lockQueueMutation();
    }

    @Test
    void topSerializesRelativeToOtherQueueMutations() {
        QueueItem item = waitingItem(11L, 10L, 1000.0);
        when(queueRepository.findById(11L)).thenReturn(Optional.of(item));
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(item));

        queueService.top(11L);

        verify(queueRepository).lockQueueMutation();
    }

    @Test
    void cancelSerializesWithOrdering() {
        QueueItem item = waitingItem(11L, 10L, 1000.0);
        when(queueRepository.findById(11L)).thenReturn(Optional.of(item));

        queueService.cancel(11L);

        verify(queueRepository).lockQueueMutation();
    }

    @Test
    void shuffleSerializesWithOrdering() {
        QueueItem first = waitingItem(11L, 10L, 1000.0);
        QueueItem second = waitingItem(12L, 10L, 2000.0);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(first, second));
        when(queueRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        queueService.shuffleWaiting();

        verify(queueRepository).lockQueueMutation();
    }

    @Test
    void repeatedTopOperationsRebalanceBeforeOrderIndexesLosePrecision() {
        Map<Long, QueueItem> items = new HashMap<>();
        List<QueueItem> targets = new ArrayList<>();
        for (long id = 11L; id < 131L; id++) {
            QueueItem target = waitingItem(id, 10L, 1_000.0 + id);
            targets.add(target);
            items.put(id, target);
        }
        when(queueRepository.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(items.get(invocation.getArgument(0))));
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenAnswer(invocation -> items.values().stream()
                        .sorted(Comparator.comparingDouble(QueueItem::getOrderIndex))
                        .toList());
        when(queueRepository.save(any(QueueItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queueRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        for (QueueItem target : targets) {
            queueService.top(target.getId());
        }

        List<QueueItem> ordered = items.values().stream()
                .sorted(Comparator.comparingDouble(QueueItem::getOrderIndex)).toList();
        for (int i = 0; i < ordered.size(); i++) {
            org.assertj.core.api.Assertions.assertThat(ordered.get(i).getOrderIndex()).isFinite().isPositive();
            if (i > 0) {
                double gap = ordered.get(i).getOrderIndex() - ordered.get(i - 1).getOrderIndex();
                org.assertj.core.api.Assertions.assertThat(gap)
                        .isGreaterThanOrEqualTo(QueueService.MIN_ORDER_GAP);
            }
        }
        verify(queueRepository, org.mockito.Mockito.atLeastOnce()).saveAll(any());
    }

    private QueueItem waitingItem(long id, long songId, double orderIndex) {
        QueueItem item = new QueueItem();
        item.setId(id);
        item.setSongId(songId);
        item.setOrderedBy(20L);
        item.setOrderIndex(orderIndex);
        item.setStatus(QueueService.WAITING);
        return item;
    }
}
